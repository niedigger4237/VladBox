/* SPDX-License-Identifier: GPL-3.0 */
#define _GNU_SOURCE
#include "nf_process_guard.h"

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

struct proc_snapshot {
    uint64_t ticks;
    uint32_t group, session;
    char state;
};

static int valid_pid(uint32_t pid) { return pid>1 && pid<=INT_MAX; }

int nf_process_current_scope(struct nf_process_scope *s) {
    if(!s) return -1;
    memset(s,0,sizeof(*s));
    struct stat st, proc_init;
    if(nf_wal_current_scope(&s->network)!=0 ||
       stat("/proc/self/ns/pid",&st)!=0 ||
       stat("/proc/1/ns/pid",&proc_init)!=0 ||
       st.st_dev!=proc_init.st_dev || st.st_ino!=proc_init.st_ino) return -1;
    s->pidns_dev=(uint64_t)st.st_dev;s->pidns_ino=(uint64_t)st.st_ino;
    return s->pidns_ino?0:-1;
}

static int scope_equal(const struct nf_process_scope *a,const struct nf_process_scope *b) {
    return a&&b&&a->network.boot_id[36]==0&&b->network.boot_id[36]==0&&
        !strcmp(a->network.boot_id,b->network.boot_id)&&
        a->network.netns_dev==b->network.netns_dev&&a->network.netns_ino==b->network.netns_ino&&
        a->pidns_dev==b->pidns_dev&&a->pidns_ino==b->pidns_ino;
}

/* /proc/<pid>/stat: comm can contain spaces and parentheses.
 * Fields following the final ')' start with state (field 3).
 * Do not parse unsigned kernel counters as signed integers. */
static int read_stat(int dir,uint32_t pid,struct proc_snapshot *out) {
    int fd=openat(dir,"stat",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0) return -1;
    char text[4096];size_t used=0;int ok=0;
    while(used<sizeof(text)-1) {
        ssize_t n=read(fd,text+used,sizeof(text)-1-used);
        if(n<0&&errno==EINTR) continue;
        if(n<0) break;
        if(!n) {ok=1;break;}
        used+=(size_t)n;
    }
    close(fd);
    if(!ok||!used) return -1;
    text[used]=0;
    char prefix[32];int n=snprintf(prefix,sizeof(prefix),"%" PRIu32 " (",pid);
    if(n<=0||(size_t)n>=sizeof(prefix)||strncmp(text,prefix,(size_t)n)) return -1;
    char *p=strrchr(text,')');
    if(!p||p[1]!=' '||!p[2]||p[3]!=' ') return -1;
    memset(out,0,sizeof(*out));out->state=p[2];p+=4;
    for(int field=4;field<=22;field++) {
        char *end=p;
        while(*end&&*end!=' '&&*end!='\n') ++end;
        if(end==p) return -1;
        if(field==5||field==6||field==22) {
            if(*p<'0'||*p>'9') return -1;
            errno=0;char *parsed=NULL;unsigned long long x=strtoull(p,&parsed,10);
            if(errno||parsed!=end||!x) return -1;
            if(field==22) out->ticks=(uint64_t)x;
            else {
                if(x>INT_MAX) return -1;
                if(field==5) out->group=(uint32_t)x;else out->session=(uint32_t)x;
            }
        }
        if(field<22&&*end!=' ') return -1;
        p=end+1;
    }
    return 0;
}

static int open_proc(uint32_t pid) {
    char path[64];snprintf(path,sizeof(path),"/proc/%" PRIu32,pid);
    return open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
}

static int same_stat(const struct proc_snapshot *a,const struct proc_snapshot *b) {
    return a->ticks==b->ticks&&a->group==b->group&&a->session==b->session;
}

static int dead_state(char state) {return state=='Z'||state=='X'||state=='x';}

int nf_child_capture(uint32_t pid,struct nf_child_identity *id) {
    if(!id||!valid_pid(pid)) return -1;
    memset(id,0,sizeof(*id));
    int fd=open_proc(pid);if(fd<0) return -1;
    struct proc_snapshot a,b;struct stat exe;
    int ok=read_stat(fd,pid,&a)==0&&!dead_state(a.state)&&a.group==pid&&a.session==pid&&
        fstatat(fd,"exe",&exe,0)==0&&S_ISREG(exe.st_mode)&&exe.st_ino!=0&&
        read_stat(fd,pid,&b)==0&&!dead_state(b.state)&&same_stat(&a,&b);
    close(fd);
    if(!ok) return -1;
    id->pid=pid;id->start_ticks=a.ticks;id->exe_dev=(uint64_t)exe.st_dev;id->exe_ino=(uint64_t)exe.st_ino;
    return 0;
}

/* Signal 0 is an existence/permission probe; it delivers no signal.
 * A group can survive its leader. Even zombies conservatively block recovery.
 * Never probe -1 or 0, which address unrelated processes. */
static enum nf_child_status group_check(uint32_t pid) {
    if(kill(-(pid_t)pid,0)==0) return NF_CHILD_GROUP_PRESENT;
    if(errno!=ESRCH) return NF_CHILD_UNKNOWN;
    if(kill((pid_t)pid,0)==0) return NF_CHILD_IDENTITY_CHANGED;
    return errno==ESRCH?NF_CHILD_ABSENT:NF_CHILD_UNKNOWN;
}

enum nf_child_status nf_child_check(const struct nf_process_scope *scope,const struct nf_child_identity *id) {
    struct nf_process_scope current;
    if(!id||!valid_pid(id->pid)||!id->start_ticks||!id->exe_ino||
       nf_process_current_scope(&current)!=0||!scope_equal(scope,&current)) return NF_CHILD_UNKNOWN;
    int fd=open_proc(id->pid);
    if(fd<0) return errno==ENOENT?group_check(id->pid):NF_CHILD_UNKNOWN;
    struct proc_snapshot a,b;struct stat exe;
    enum nf_child_status result=NF_CHILD_UNKNOWN;
    if(read_stat(fd,id->pid,&a)==0) {
        if(a.ticks!=id->start_ticks||a.group!=id->pid||a.session!=id->pid) result=NF_CHILD_IDENTITY_CHANGED;
        else if(dead_state(a.state)) result=NF_CHILD_GROUP_PRESENT;
        else if(fstatat(fd,"exe",&exe,0)==0&&read_stat(fd,id->pid,&b)==0&&same_stat(&a,&b)) {
            if(dead_state(b.state)) result=NF_CHILD_GROUP_PRESENT;
            else if((uint64_t)exe.st_dev!=id->exe_dev||(uint64_t)exe.st_ino!=id->exe_ino) result=NF_CHILD_IDENTITY_CHANGED;
            else result=NF_CHILD_LIVE;
        }
    }
    close(fd);
    return result;
}
