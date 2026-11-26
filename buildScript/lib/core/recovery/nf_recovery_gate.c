/* SPDX-License-Identifier: GPL-3.0 */
#include "nf_recovery_gate.h"
#include <string.h>

static int same_scope(const struct nf_wal *w,const struct nf_process_scope *s) {
    return w->scope.boot_id[36]==0&&s->network.boot_id[36]==0&&!strcmp(w->scope.boot_id,s->network.boot_id)&&
        w->scope.netns_dev==s->network.netns_dev&&w->scope.netns_ino==s->network.netns_ino&&
        w->pidns_dev==s->pidns_dev&&w->pidns_ino==s->pidns_ino;
}
static int same_record(const struct nf_wal_record *a,const struct nf_wal_record *b) {
    return a->kind==b->kind&&a->ifindex==b->ifindex&&!strncmp(a->name,b->name,NF_WAL_NAME);
}
static int same_child(const struct nf_child_identity *a,const struct nf_child_identity *b) {
    return a->pid==b->pid&&a->start_ticks==b->start_ticks&&a->exe_dev==b->exe_dev&&a->exe_ino==b->exe_ino;
}
static void init(struct nf_wal *w,const struct nf_process_scope *s) {
    memset(w,0,sizeof(*w));w->scope=s->network;w->pidns_dev=s->pidns_dev;w->pidns_ino=s->pidns_ino;
}
enum nf_recovery_status nf_recovery_inspect(const struct nf_wal *w,const struct nf_process_scope *s,nf_child_probe child,nf_record_probe record,void *context) {
    if(!w||!s||!child||!record||!same_scope(w,s)||w->count>NF_WAL_MAX||w->child_count>NF_WAL_MAX_CHILDREN||
       (w->count&&!w->child_count)) return NF_RECOVERY_ERROR;
    if(!w->count) return NF_RECOVERY_CLEAR;
    for(size_t i=0;i<w->child_count;i++)
        if(child(s,&w->children[i],context)!=NF_CHILD_ABSENT) return NF_RECOVERY_CHILD_PENDING;
    for(size_t i=0;i<w->count;i++) {
        enum nf_record_state state=record(&w->records[i],context);
        if(state!=NF_RECORD_ORIGINAL&&state!=NF_RECORD_GONE) return NF_RECOVERY_SYSCTL_PENDING;
    }
    return NF_RECOVERY_CLEAR;
}
/* The caller has already admitted this session and owns the exclusive listener.
 * Previous boot intent is obsolete; same-boot unknown scope is not. */
int nf_recovery_record(int dir,const struct nf_process_scope *s,const struct nf_child_identity *ids,size_t n,const struct nf_wal_record *r) {
    if(!s||!ids||!n||n>NF_WAL_MAX_CHILDREN||!r) return -1;
    struct nf_wal w;
    enum nf_wal_status status=nf_wal_load(dir,&s->network,&w);
    if(status==NF_WAL_ABSENT||status==NF_WAL_STALE_BOOT) init(&w,s);
    else if(status!=NF_WAL_OK||!same_scope(&w,s)) return -1;
    for(size_t i=0;i<n;i++) {
        size_t j=0;for(;j<w.child_count;j++) if(w.children[j].pid==ids[i].pid) break;
        if(j<w.child_count) {if(!same_child(&w.children[j],&ids[i])) return -1;}
        else {if(w.child_count==NF_WAL_MAX_CHILDREN) return -1;w.children[w.child_count++]=ids[i];}
    }
    size_t j=0;for(;j<w.count;j++) if(same_record(&w.records[j],r)) break;
    if(j<w.count) {
        if(w.records[j].original!=r->original||w.records[j].desired!=r->desired) return -1;
    } else {if(w.count==NF_WAL_MAX) return -1;w.records[w.count++]=*r;}
    return nf_wal_save(dir,&w);
}
int nf_recovery_forget_verified(int dir,const struct nf_process_scope *s,const struct nf_wal_record *r,nf_record_probe probe,void *context) {
    if(!s||!r||!probe) return -1;
    struct nf_wal w;
    enum nf_wal_status status=nf_wal_load(dir,&s->network,&w);
    if(status==NF_WAL_ABSENT) return 0;
    if(status!=NF_WAL_OK||!same_scope(&w,s)) return -1;
    size_t i=0;for(;i<w.count;i++) if(same_record(&w.records[i],r)) break;
    if(i==w.count) return 0;
    if(w.records[i].original!=r->original||w.records[i].desired!=r->desired) return -1;
    enum nf_record_state observed=probe(&w.records[i],context);
    if(observed!=NF_RECORD_ORIGINAL&&observed!=NF_RECORD_GONE) return -1;
    memmove(&w.records[i],&w.records[i+1],(w.count-i-1)*sizeof(w.records[0]));
    --w.count;
    if(!w.count) w.child_count=0;
    return nf_wal_save(dir,&w);
}
