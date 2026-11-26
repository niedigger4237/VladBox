/* SPDX-License-Identifier: GPL-3.0 */
#include "nf_native_recovery.h"
#include "nf_recovery_gate.h"

static int scope_for(struct asteriskd_system_supervisor *s,struct nf_process_scope *scope) {
    if(!s||!s->store.initialized||s->store.directory_fd<0||
       s->loaded_config.config.owner!=ASTERISKD_OWNER_BOX||geteuid()!=0) return -1;
    return nf_process_current_scope(scope);
}

static int record_from(const struct asteriskd_effect *e,struct nf_wal_record *r) {
    if(!e||e->kind!=ASTERISKD_EFFECT_SYSCTL) return -1;
    const struct asteriskd_sysctl_resource *v=&e->resource.sysctl;
    memset(r,0,sizeof(*r));
    if(v->sysctl_id==ASTERISKD_SYSCTL_ROUTE_LOCALNET) r->kind=NF_ROUTE_LOCALNET;
    else if(v->sysctl_id==ASTERISKD_SYSCTL_DISABLE_IPV6) r->kind=NF_DISABLE_IPV6;
    else return -1;
    size_t n=strnlen(v->interface_name,sizeof(v->interface_name));
    if(!n||n>=sizeof(r->name)||!v->interface_index||v->original_value>1||v->desired_value>1) return -1;
    memcpy(r->name,v->interface_name,n);
    r->ifindex=v->interface_index;r->original=v->original_value;r->desired=v->desired_value;
    return 0;
}

static enum nf_child_status child_probe(const struct nf_process_scope *scope,const struct nf_child_identity *id,void *opaque) {
    (void)opaque;
    return nf_child_check(scope,id);
}
static enum nf_record_state record_probe(const struct nf_wal_record *r,void *opaque) {
    (void)opaque;
    struct asteriskd_sysctl_resource v;
    memset(&v,0,sizeof(v));
    if(r->kind==NF_ROUTE_LOCALNET) v.sysctl_id=ASTERISKD_SYSCTL_ROUTE_LOCALNET;
    else if(r->kind==NF_DISABLE_IPV6) v.sysctl_id=ASTERISKD_SYSCTL_DISABLE_IPV6;
    else return NF_RECORD_UNKNOWN;
    if(!r->ifindex||strnlen(r->name,sizeof(r->name))>=sizeof(r->name)) return NF_RECORD_UNKNOWN;
    memcpy(v.interface_name,r->name,strlen(r->name)+1);
    v.interface_index=r->ifindex;
    uint8_t value=0;
    int result=asteriskd_supervisor_sysctl_read(&v,&value);
    if(result==1) return NF_RECORD_GONE;
    if(result!=0) return NF_RECORD_UNKNOWN;
    return value==r->original?NF_RECORD_ORIGINAL:NF_RECORD_CHANGED;
}

static int inspect_and_clear(struct asteriskd_system_supervisor *s) {
    struct nf_process_scope scope;
    if(scope_for(s,&scope)!=0) return -1;
    struct nf_wal w;
    enum nf_wal_status loaded=nf_wal_load(s->store.directory_fd,&scope.network,&w);
    if(loaded==NF_WAL_ABSENT) return 0;
    if(loaded==NF_WAL_STALE_BOOT) {
        memset(&w,0,sizeof(w));w.scope=scope.network;
        w.pidns_dev=scope.pidns_dev;w.pidns_ino=scope.pidns_ino;
        return nf_wal_save(s->store.directory_fd,&w);
    }
    if(loaded!=NF_WAL_OK) return -1;
    if(nf_recovery_inspect(&w,&scope,child_probe,record_probe,NULL)!=NF_RECOVERY_CLEAR) return -1;
    if(w.count==0&&w.child_count==0) return 0;
    w.count=0;w.child_count=0;
    return nf_wal_save(s->store.directory_fd,&w);
}

/* The caller owns the listener. No restoration of changed values on crash:
 * a name/ifindex pair alone cannot prove interface continuity across a crash. */
int nf_native_admit(struct asteriskd_system_supervisor *s) {
    return inspect_and_clear(s);
}
int nf_native_finish(struct asteriskd_system_supervisor *s) {
    return inspect_and_clear(s);
}

static int verified_child(const struct asteriskd_child_identity *expected,struct nf_child_identity *observed) {
    if(!expected||expected->pid<=1||expected->process_group_id!=expected->pid||
       nf_child_capture((uint32_t)expected->pid,observed)!=0) return -1;
    return observed->start_ticks==expected->start_time_ticks&&
        observed->exe_dev==expected->exe_device&&observed->exe_ino==expected->exe_inode?0:-1;
}
int nf_native_before_write(struct asteriskd_system_supervisor *s,const struct asteriskd_effect *e) {
    struct nf_process_scope scope;struct nf_wal_record r;
    if(scope_for(s,&scope)!=0||record_from(e,&r)!=0) return -1;
    struct nf_child_identity ids[2];size_t n=1;
    if(!s->core_identity_ready||s->core_reaped||
       verified_child(&s->core_identity,&ids[0])!=0) return -1;
    if(s->loaded_config.config.helper.type!=ASTERISKD_HELPER_NONE) {
        if(!s->helper_identity_ready||s->helper_reaped||
           verified_child(&s->helper_identity,&ids[1])!=0) return -1;
        n=2;
    }
    return nf_recovery_record(s->store.directory_fd,&scope,ids,n,&r);
}

int nf_native_after_restore(struct asteriskd_system_supervisor *s,const struct asteriskd_effect *e) {
    struct nf_process_scope scope;struct nf_wal_record r;
    if(scope_for(s,&scope)!=0||record_from(e,&r)!=0) return -1;
    return nf_recovery_forget_verified(s->store.directory_fd,&scope,&r,record_probe,NULL);
}
