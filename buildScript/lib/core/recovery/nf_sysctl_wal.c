/* SPDX-License-Identifier: GPL-3.0 */
#define _GNU_SOURCE
#include "nf_sysctl_wal.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define NF_WAL_TMP ".nf-sysctl-journal.v1.new"
#define NF_WAL_LIMIT (128 + NF_WAL_MAX * 64)

static int valid_boot(const char *s) {
    if (strnlen(s,37)!=36) return 0;
    for (size_t i=0;i<36;i++) {
        char c=s[i];
        if (i==8||i==13||i==18||i==23) { if(c!='-') return 0; }
        else if (!((c>='0'&&c<='9')||(c>='a'&&c<='f'))) return 0;
    }
    return 1;
}
static int valid_scope(const struct nf_wal_scope *s) {
    return s && valid_boot(s->boot_id) && s->netns_ino!=0;
}
static int valid_name(const char *s) {
    size_t n=strnlen(s,NF_WAL_NAME);
    if (!n||n>=NF_WAL_NAME||!strcmp(s,".")||!strcmp(s,"..")||!strcmp(s,"all")||!strcmp(s,"default")||!strcmp(s,"lo")) return 0;
    for (size_t i=0;i<n;i++) {
        char c=s[i];
        if (!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'||c=='-'||c=='.')) return 0;
    }
    return 1;
}
static int valid_wal(const struct nf_wal *w) {
    if(!w||!valid_scope(&w->scope)||w->count>NF_WAL_MAX) return 0;
    for(size_t i=0;i<w->count;i++) {
        const struct nf_wal_record *r=&w->records[i];
        if(!r->ifindex||!valid_name(r->name)||(r->kind!=NF_ROUTE_LOCALNET&&r->kind!=NF_DISABLE_IPV6)||r->original>1||r->desired>1) return 0;
        for(size_t j=0;j<i;j++) {
            const struct nf_wal_record *p=&w->records[j];
            if(r->kind==p->kind && r->ifindex==p->ifindex && !strcmp(r->name,p->name)) return 0;
        }
    }
    return 1;
}
static int safe_dir(int fd) {
    struct stat st;
    return fstat(fd,&st)==0 && S_ISDIR(st.st_mode) && st.st_uid==geteuid() && !(st.st_mode&0022);
}
static int safe_file(const struct stat *st) {
    return S_ISREG(st->st_mode)&&st->st_uid==geteuid()&&st->st_nlink==1&&(st->st_mode&0777)==0600;
}
static int safe_path_or_absent(int dir,const char *name) {
    struct stat st;
    if(fstatat(dir,name,&st,AT_SYMLINK_NOFOLLOW)==0) return safe_file(&st)?0:-1;
    return errno==ENOENT?1:-1;
}
static int read_bounded(int fd,char *out,size_t cap,size_t *len) {
    size_t n=0;
    while(n<cap) {
        ssize_t r=read(fd,out+n,cap-n);
        if(r<0&&errno==EINTR) continue;
        if(r<0) return -1;
        if(!r) { *len=n; return 0; }
        n+=(size_t)r;
    }
    return -1;
}
static int write_all(int fd,const char *p,size_t n) {
    while(n) {
        ssize_t x=write(fd,p,n);
        if(x<0&&errno==EINTR) continue;
        if(x<=0) return -1;
        p+=x;n-=(size_t)x;
    }
    return 0;
}
int nf_wal_current_scope(struct nf_wal_scope *s) {
    if(!s) return -1;
    memset(s,0,sizeof(*s));
    int fd=open("/proc/sys/kernel/random/boot_id",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0) return -1;
    char b[38]; size_t n=0;
    int rc=read_bounded(fd,b,sizeof(b),&n);
    close(fd);
    if(rc||n!=37||b[36]!='\n') return -1;
    memcpy(s->boot_id,b,36);
    struct stat st;
    if(stat("/proc/self/ns/net",&st)!=0) return -1;
    s->netns_dev=(uint64_t)st.st_dev;s->netns_ino=(uint64_t)st.st_ino;
    return valid_scope(s)?0:-1;
}
static int encode(const struct nf_wal *w,char *out,size_t cap,size_t *len) {
    if(!valid_wal(w)) return -1;
    int n=snprintf(out,cap,"NF_SYSCTL_V1 %s %" PRIu64 " %" PRIu64 "\n",w->scope.boot_id,w->scope.netns_dev,w->scope.netns_ino);
    if(n<0||(size_t)n>=cap) return -1;
    size_t off=(size_t)n;
    for(size_t i=0;i<w->count;i++) {
        const struct nf_wal_record *r=&w->records[i];
        n=snprintf(out+off,cap-off,"%" PRIu32 " %u %u %u %s\n",r->ifindex,(unsigned)r->kind,(unsigned)r->original,(unsigned)r->desired,r->name);
        if(n<0||(size_t)n>=cap-off) return -1;
        off+=(size_t)n;
    }
    *len=off;return 0;
}
int nf_wal_save(int dir,const struct nf_wal *w) {
    char bytes[NF_WAL_LIMIT];size_t n=0;
    if(!safe_dir(dir)||encode(w,bytes,sizeof(bytes),&n)||safe_path_or_absent(dir,NF_WAL_FILE)<0) return -1;
    int tmp=safe_path_or_absent(dir,NF_WAL_TMP);
    if(tmp<0||(tmp==0&&unlinkat(dir,NF_WAL_TMP,0))) return -1;
    int fd=openat(dir,NF_WAL_TMP,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0) return -1;
    int ok=fchmod(fd,0600)==0&&write_all(fd,bytes,n)==0&&fsync(fd)==0;
    if(close(fd)!=0) ok=0;
    if(!ok||renameat(dir,NF_WAL_TMP,dir,NF_WAL_FILE)!=0) { unlinkat(dir,NF_WAL_TMP,0);return -1; }
    return fsync(dir)==0?0:-1;
}
static int parse_uint(const char **p,uint64_t *out) {
    const char *s=*p;
    if(*s<'0'||*s>'9') return -1;
    uint64_t n=0;
    do {
        unsigned digit=(unsigned)(*s-'0');
        if(n>(UINT64_MAX-digit)/10) return -1;
        n=n*10+digit;++s;
    } while(*s>='0'&&*s<='9');
    if(*s!=' ') return -1;
    *p=s+1;*out=n;return 0;
}
static int decode(char *bytes,size_t n,struct nf_wal *w) {
    const char tag[]="NF_SYSCTL_V1 ";
    if(!n||bytes[n-1]!='\n'||memchr(bytes,0,n)||n<sizeof(tag)-1+37||memcmp(bytes,tag,sizeof(tag)-1)) return -1;
    memset(w,0,sizeof(*w));
    bytes[n]=0;
    const char *p=bytes+sizeof(tag)-1;
    memcpy(w->scope.boot_id,p,36);p+=36;
    if(*p++!=' ') return -1;
    if(parse_uint(&p,&w->scope.netns_dev)) return -1;
    char *line=strchr(p,'\n');
    if(!line) return -1;
    *line=' ';
    int rc=parse_uint(&p,&w->scope.netns_ino);
    *line='\n';
    if(rc||p!=line+1) return -1;
    while(*p) {
        if(w->count==NF_WAL_MAX) return -1;
        uint64_t idx,kind,original,desired;
        if(parse_uint(&p,&idx)||parse_uint(&p,&kind)||parse_uint(&p,&original)||parse_uint(&p,&desired)||idx>UINT32_MAX||kind>UINT8_MAX||original>1||desired>1) return -1;
        line=strchr(p,'\n');
        if(!line||line-p<=0||line-p>=NF_WAL_NAME) return -1;
        struct nf_wal_record *r=&w->records[w->count++];
        r->ifindex=(uint32_t)idx;r->kind=(uint8_t)kind;r->original=(uint8_t)original;r->desired=(uint8_t)desired;
        memcpy(r->name,p,(size_t)(line-p));p=line+1;
    }
    return valid_wal(w)?0:-1;
}
enum nf_wal_status nf_wal_load(int dir,const struct nf_wal_scope *scope,struct nf_wal *w) {
    if(!w||!valid_scope(scope)||!safe_dir(dir)) return NF_WAL_ERROR;
    memset(w,0,sizeof(*w));
    int fd=openat(dir,NF_WAL_FILE,O_RDONLY|O_NOFOLLOW|O_NONBLOCK|O_CLOEXEC);
    if(fd<0) return errno==ENOENT?NF_WAL_ABSENT:NF_WAL_ERROR;
    struct stat st;char bytes[NF_WAL_LIMIT+1];size_t n=0;
    int ok=fstat(fd,&st)==0&&safe_file(&st)&&read_bounded(fd,bytes,NF_WAL_LIMIT,&n)==0;
    if(close(fd)!=0) ok=0;
    if(!ok||decode(bytes,n,w)) {memset(w,0,sizeof(*w));return NF_WAL_ERROR;}
    if(strcmp(scope->boot_id,w->scope.boot_id)) {memset(w,0,sizeof(*w));return NF_WAL_STALE_BOOT;}
    if(scope->netns_dev!=w->scope.netns_dev||scope->netns_ino!=w->scope.netns_ino) {memset(w,0,sizeof(*w));return NF_WAL_ERROR;}
    return NF_WAL_OK;
}
