// SPDX-License-Identifier: GPL-3.0
// Native transaction adapter; compiled but not activated by supervisor lifecycle.
#include "native_gateway_dns.c"
#include "gateway_lifecycle.c"
#include "gateway_journal.c"

struct nf_native_slot {
    struct asteriskd_rule_transaction_plan plan;
    struct asteriskd_byte_buffer expected[2];
    struct asteriskd_byte_buffer pending[2];
    bool owned, expected_valid, pending_valid;
    bool block_ipv6; // guard stays closed for IPv6 when gateway NAT is unavailable
};
/* Stable same-boot process identity, never a pointer or raw native struct. */
struct nf_core_evidence {
    uint32_t pid, group;
    uint64_t start, device, inode;
};
static bool nf_core_evidence_valid(const struct nf_core_evidence *e) {
    return e->pid>1 && e->pid<=INT32_MAX && e->group==e->pid && e->start && e->device && e->inode;
}
static bool nf_core_evidence_equal(const struct nf_core_evidence *a,const struct nf_core_evidence *b) {
    return a->pid==b->pid && a->group==b->group && a->start==b->start && a->device==b->device && a->inode==b->inode;
}
/* Live-only downstream identities. Do not serialize these as ownership
 * evidence; crash recovery uses only the closed-guard drain protocol. */
#define NF_LINK_ADDRESS_LIMIT 32U
struct nf_link_address {
    uint8_t family, address[16], mask[16];
};
struct nf_link_identity {
    char name[ASTERISKD_MAX_INTERFACE_NAME];
    unsigned index, count;
    struct nf_link_address addresses[NF_LINK_ADDRESS_LIMIT];
};
struct nf_link_snapshot {
    size_t count;
    struct nf_link_identity links[16];
};
struct nf_native_backend {
    struct asteriskd_system_supervisor *system;
    struct nf_native_slot guard, nat;
    // Non-firewall operations must be provided by the actual supervisor.
    struct nf_backend lifecycle_operations;
    // Caller must hold the WAL lock and establish boot/session identity first.
    int wal_fd;
    bool wal_ready;
    bool precore_certified;
    enum nf_step recovered_op; // validated last WAL operation; recovery only
    bool ipv6_nat_disabled; // session capability, does not change core routing
    bool links_published;
    struct nf_link_snapshot published_links;
    uint32_t session_mark, session_mask;
    bool mark_authorized; // live assertion; never restored from disk
    int (*mark_recheck)(void *,uint32_t,uint32_t);
    void *mark_ctx;
    bool core_evidence_valid;
    struct nf_core_evidence core_evidence;
    struct nf_wal_record wal;
};

/* Bind a caller-supplied, independently verified mark before reservation.
 * This API does not allocate bits or establish exclusive ownership itself. */
int nf_gateway_native_bind_mark(struct nf_native_backend *b,uint32_t mark,uint32_t mask,bool exclusive) {
    if(!b || !exclusive || !b->wal_ready || b->wal.debts || b->wal.epoch ||
       b->precore_certified || b->core_evidence_valid || b->guard.owned || b->nat.owned ||
       b->session_mark || b->session_mask || !nf_gateway_mark_valid(mark,mask))return -1;
    b->session_mark=mark;b->session_mask=mask;b->mark_authorized=true;return 0;
}
/* Register the independent live ownership verifier before reservation.
 * Not a mask allocator; the verifier must hold the lifetime contract. */
int nf_gateway_native_set_mark_recheck(struct nf_native_backend *b,
        int (*check)(void *,uint32_t,uint32_t),void *ctx) {
    if(!b || !check || !b->mark_authorized || !b->wal_ready || b->wal.debts || b->wal.epoch ||
       b->precore_certified || b->guard.owned || b->nat.owned || b->mark_recheck)return -1;
    b->mark_recheck=check;b->mark_ctx=ctx;return 0;
}
static int nf_native_mark_recheck(struct nf_native_backend *b) {
    if(!b->session_mask && !b->session_mark)return 0;
    return !b->mark_authorized || !nf_gateway_mark_valid(b->session_mark,b->session_mask) || !b->mark_recheck ||
        b->mark_recheck(b->mark_ctx,b->session_mark,b->session_mask)?-1:0;
}
/* Implemented in the recovery unit, sharing its kernel absence checks. */
int nf_gateway_native_drain_marked(struct nf_native_backend *b);
/* Persist exact old/pending chain images before any kernel mutation.
 * The schema stores guard then NAT, with family order fixed by the planner.
 * No raw struct/pointer serialization. Caller must separately journal core,
 * link and conntrack identity; firewall checkpoint never clears cleanup debt. */
static int nf_native_checkpoint(struct nf_native_backend *b,enum nf_step op) {
    if(!b->wal_ready || b->wal.generation==UINT64_MAX) return -1;
    uint8_t *data=calloc(1,NF_WAL_MAX);if(!data)return -1;
    memcpy(data,"NFFW01",6);data[6]=(uint8_t)op;data[7]=(b->precore_certified?1U:0U)|(b->core_evidence_valid?2U:0U)|(b->session_mask?4U:0U);size_t pos=8;
    if(b->session_mark || b->session_mask) {
        if(!nf_gateway_mark_valid(b->session_mark,b->session_mask))goto fail;
        nf_put(data+pos,4,b->session_mark);nf_put(data+pos+4,4,b->session_mask);pos+=8;
    }
    struct nf_native_slot *slots[]={&b->guard,&b->nat};
    for(size_t j=0;j<2;j++) {
        struct nf_native_slot *s=slots[j];
        if(s->plan.private_group_count>2 || (s->owned && !s->plan.private_group_count)) goto fail;
        data[pos++]=(uint8_t)s->plan.private_group_count;
        data[pos++]=(s->owned?1U:0U)|(s->expected_valid?2U:0U)|(s->pending_valid?4U:0U);
        for(size_t i=0;i<2;i++) for(size_t p=0;p<2;p++) {
            const struct asteriskd_byte_buffer *v=p?&s->pending[i]:&s->expected[i];
            if(pos>NF_WAL_MAX-4 || v->length>NF_WAL_MAX-pos-4 || (v->length&&!v->bytes))goto fail;
            nf_put(data+pos,4,v->length);pos+=4;
            if(v->length)memcpy(data+pos,v->bytes,v->length);
            pos+=v->length;
        }
        if(pos>NF_WAL_MAX-20)goto fail;
    }
    if(b->core_evidence_valid) {
        const struct nf_core_evidence *e=&b->core_evidence;
        if(b->precore_certified || !(b->wal.debts&4U) || !nf_core_evidence_valid(e) || pos>NF_WAL_MAX-32)goto fail;
        nf_put(data+pos,4,e->pid);nf_put(data+pos+4,4,e->group);
        nf_put(data+pos+8,8,e->start);nf_put(data+pos+16,8,e->device);nf_put(data+pos+24,8,e->inode);pos+=32;
    }
    struct nf_wal_record r=b->wal;r.generation++;r.schema=1;r.payload=data;r.length=pos;
    r.debts|=1U|(b->guard.owned?2U:0U)|(b->nat.owned?24U:0U);
    if(nf_gateway_wal_save(b->wal_fd,&r,b->wal.generation)) {
        // Rename may already have succeeded; refuse further mutations until reload.
        b->wal_ready=false;goto fail;
    }
    b->wal.generation=r.generation;b->wal.debts=r.debts;
    free(data);return 0;
fail:
    free(data);return -1;
}

/* Recognize only the exact legacy ENOENT diagnostic; a command error,
 * permission failure, truncated output or nft backend is not absence. */
static bool nf_nat6_missing(int run,int status,bool overflow,size_t stdout_len,const char *err,size_t n) {
    static const char prefix[]="ip6tables v";
    static const char suffix[]=" (legacy): can't initialize ip6tables table `nat': Table does not exist (do you need to insmod?)\nPerhaps ip6tables or your kernel needs to be upgraded.\n";
    if(run || status!=3 || overflow || stdout_len || !err || n>=512U || n<sizeof(prefix)+sizeof(suffix)-1U ||
       memcmp(err,prefix,sizeof(prefix)-1U))return false;
    size_t p=sizeof(prefix)-1U;unsigned dots=0;bool digit=false;
    while(p<n && ((err[p]>='0' && err[p]<='9') || err[p]=='.')) {
        if(err[p]=='.'){if(!digit || ++dots>2)return false;digit=false;}else digit=true;
        p++;
    }
    return dots==2 && digit && n-p==sizeof(suffix)-1U && !memcmp(err+p,suffix,sizeof(suffix)-1U);
}
/* 0=readable legacy/nft listing, 1=legacy NAT table reported missing,
 * -1=unknown. The caller must still verify all other tables normally. */
static int nf_nat6_view(struct asteriskd_system_supervisor *s,struct system_rule_view *v) {
    const char *argv[]={"ip6tables","-w","5","-t","nat","-S",NULL};
    int status=-1;
    int run=asteriskd_supervisor_action_run_argv(s,argv,true,&status);
    if(nf_nat6_missing(run,status,s->action_stdout_overflow,s->action_stdout_length,s->action_stderr,s->action_stderr_length))return 1;
    size_t n=s->action_stdout_length;
    if(run || status || s->action_stdout_overflow || s->action_stderr_length || !s->action_stdout || n<3 ||
       strncmp(s->action_stdout,"-P ",3) || s->action_stdout[n-1]!='\n' || memchr(s->action_stdout,0,n))return -1;
    v->bytes=malloc(n);if(!v->bytes)return -1;
    memcpy(v->bytes,s->action_stdout,n);v->length=n;v->present=true;return 0;
}
static int nf_nat6_probe(struct asteriskd_system_supervisor *s) {
    struct system_rule_view v={0};int rc=nf_nat6_view(s,&v);
    asteriskd_supervisor_rule_view_destroy(&v);return rc;
}

static bool nf_native_available(const struct asteriskd_system_supervisor *s) {
    return s && !s->rule_commands.active && !s->verify_private_rules &&
        s->rule_snapshot.phase == SYSTEM_RULE_SNAPSHOT_NONE && !s->action_spawned;
}
#include <errno.h>
#include "gateway_mark_audit.c"
extern int nf_gateway_ct_candidate_available(uint32_t mark,uint32_t mask,uint16_t port);
static void nf_native_clear(struct nf_native_slot *slot) {
    for (size_t i=0;i<2;i++) {
        asteriskd_byte_buffer_destroy(&slot->expected[i]);
        asteriskd_byte_buffer_destroy(&slot->pending[i]);
    }
    memset(slot,0,sizeof(*slot));
}
static int nf_native_snapshot(struct asteriskd_system_supervisor *s,
        const struct nf_native_slot *slot) {
    return asteriskd_supervisor_rule_snapshot_capture(s,&slot->plan,SYSTEM_RULE_SNAPSHOT_BEFORE_APPLY);
}
// 0: absent, 1: exact owned, -1: foreign or unreadable. A chain may be
// present without its hook after a partial commit. Never flush unknown rules.
static int nf_native_classify(struct asteriskd_system_supervisor *s,
        const struct nf_native_slot *slot,size_t i,bool require_hook,bool pending_only) {
    const struct asteriskd_private_chain_group *g=&slot->plan.private_groups[i];
    const struct asteriskd_traffic_hook_group *h=&slot->plan.hook_groups[i];
    const struct system_rule_view *v=&s->rule_snapshot.xtables[g->family][g->table];
    size_t declarations=0,rules=0,matches=0,position=0;
    const char *args[]={"-j",g->names[0]};
    const char *builtin=g->table==ASTERISKD_IP_TABLE_FILTER?"INPUT":"PREROUTING";
    if (!v->present || h->hook_count!=1 ||
        asteriskd_xtables_private_chain_counts(v->bytes,v->length,g->names[0],&declarations,&rules)!=0 ||
        asteriskd_xtables_rule_output_locate(v->bytes,v->length,builtin,args,2,&matches,&position)!=0 || matches>1) return -1;
    if (!declarations && !rules && !matches) return 0;
    if (declarations!=1 || (require_hook && (matches!=1 || position!=1))) return -1;
    bool exact=(!pending_only && slot->expected_valid &&
        nf_gateway_verify_order(g,&slot->expected[i],v->bytes,v->length)) ||
        (slot->pending_valid && nf_gateway_verify_order(g,&slot->pending[i],v->bytes,v->length));
    return exact?1:-1;
}
static int nf_native_verify(struct asteriskd_system_supervisor *s,
        const struct nf_native_slot *slot,bool pending_only) {
    if (!nf_native_available(s) || !slot->owned) return -1;
    int rc=nf_native_snapshot(s,slot);
    if (!rc) for(size_t i=0;i<slot->plan.private_group_count;i++)
        if(nf_native_classify(s,slot,i,true,pending_only)!=1) rc=-1;
    asteriskd_supervisor_rule_snapshot_destroy(&s->rule_snapshot);
    return rc;
}
/* Keep the dual-stack INPUT guard; trim only the gateway NAT plan. */
static int nf_native_plan(struct nf_native_backend *b,struct nf_native_slot *slot,unsigned port,bool guard) {
    if(nf_gateway_plan(&b->system->loaded_config.config,port,guard,&slot->plan))return -1;
    slot->block_ipv6=b->ipv6_nat_disabled;
    if(!guard && b->ipv6_nat_disabled && slot->plan.private_group_count==2) {
        slot->plan.private_group_count=slot->plan.hook_group_count=1;
        memset(&slot->plan.private_groups[1],0,sizeof(slot->plan.private_groups[1]));
        memset(&slot->plan.hook_groups[1],0,sizeof(slot->plan.hook_groups[1]));
    }
    return 0;
}
static bool nf_family_active(const struct nf_native_slot *slot,const struct asteriskd_private_chain_group *g,bool active) {
    return active && !(slot->block_ipv6 && g->family==ASTERISKD_IP_FAMILY_IPV6);
}
static int nf_native_populate(struct asteriskd_system_supervisor *s,
        const struct asteriskd_private_chain_group *g,unsigned port,bool active,
        uint32_t mark,uint32_t mask,bool authorized) {
    if(mark || mask) {
        if(!nf_gateway_mark_valid(mark,mask))return -1;
        if(active)return nf_gateway_populate_marked(s,g,port,true,mark,mask,authorized);
    }
    // Recovery can close an owned guard without authorizing new mark writes.
    return nf_gateway_populate(s,g,port,active);
}
static int nf_native_render(struct asteriskd_system_supervisor *s,
        struct nf_native_slot *slot,unsigned port,bool active,
        uint32_t mark,uint32_t mask,bool authorized) {
    if (asteriskd_supervisor_rule_batch_begin(&s->rule_commands)!=0) return -1;
    int rc=0;
    for(size_t i=0;i<slot->plan.private_group_count;i++) {
        const struct asteriskd_private_chain_group *g=&slot->plan.private_groups[i];
        if(nf_native_populate(s,g,port,nf_family_active(slot,g,active),mark,mask,authorized)!=0) {rc=-1;break;}
        const struct asteriskd_byte_buffer *b=&s->rule_commands.xtables[0][g->family][g->table];
        if(b->length && asteriskd_byte_buffer_append(&slot->pending[i],b->bytes,b->length)!=0) {rc=-1;break;}
    }
    asteriskd_supervisor_rule_batch_destroy(&s->rule_commands);
    return rc;
}
static int nf_native_write(struct nf_native_backend *b,
        struct nf_native_slot *slot,unsigned port,bool guard,bool active) {
    struct asteriskd_system_supervisor *s=b->system;
    if (!b->wal_ready || !nf_native_available(s)) return -1;
    bool fresh=!slot->owned;
    if (!fresh && !guard) return -1;
    if (!fresh && nf_native_verify(s,slot,false)!=0) return -1;
    if (!fresh && slot->pending_valid) {
        // Recover a partial cross-family update using the exact observed version.
        if(nf_native_snapshot(s,slot)!=0) return -1;
        for(size_t i=0;i<slot->plan.private_group_count;i++) {
            if(nf_native_classify(s,slot,i,true,true)==1) {
                asteriskd_byte_buffer_destroy(&slot->expected[i]);
                slot->expected[i]=slot->pending[i];
                memset(&slot->pending[i],0,sizeof(slot->pending[i]));
            }
            asteriskd_byte_buffer_destroy(&slot->pending[i]);
        }
        asteriskd_supervisor_rule_snapshot_destroy(&s->rule_snapshot);
        slot->expected_valid=true;slot->pending_valid=false;
    }
    if (fresh && nf_native_plan(b,slot,port,guard)!=0) return -1;
    int rc=nf_native_snapshot(s,slot);
    if(!rc) for(size_t i=0;i<slot->plan.private_group_count;i++)
        if(nf_native_classify(s,slot,i,!fresh,false)!=(fresh?0:1)) rc=-1;
    asteriskd_supervisor_rule_snapshot_destroy(&s->rule_snapshot);
    if(rc) return -1;
    if(nf_native_render(s,slot,port,active,b->session_mark,b->session_mask,b->mark_authorized)!=0) goto render_failed;
    if(asteriskd_supervisor_rule_batch_begin(&s->rule_commands)!=0) goto render_failed;
    for(size_t i=0;i<slot->plan.private_group_count;i++) {
        const struct asteriskd_private_chain_group *g=&slot->plan.private_groups[i];
        if(asteriskd_supervisor_xtables_zero(s,g->family,g->table,fresh?"-N":"-F",g->names[0],NULL,0)!=0 ||
           nf_native_populate(s,g,port,nf_family_active(slot,g,active),b->session_mark,b->session_mask,b->mark_authorized)!=0 ||
           (fresh && asteriskd_supervisor_apply_hook_group(s,&slot->plan.hook_groups[i])!=0)) {rc=-1;break;}
    }
    if(!rc) {
        slot->owned=true;slot->pending_valid=true;
        rc=nf_native_checkpoint(b,guard?(active?NF_GUARD_OPEN:NF_GUARD_CLOSED):NF_NAT_INSTALL);
        if(!rc)rc=asteriskd_supervisor_rule_batch_flush(s);
    }
    asteriskd_supervisor_rule_batch_destroy(&s->rule_commands);
    if(!slot->pending_valid) goto render_failed;
    if(rc || nf_native_verify(s,slot,true)!=0) return -1;
    for(size_t i=0;i<2;i++) {
        asteriskd_byte_buffer_destroy(&slot->expected[i]);slot->expected[i]=slot->pending[i];
        memset(&slot->pending[i],0,sizeof(slot->pending[i]));
    }
    slot->expected_valid=true;slot->pending_valid=false;
    return 0;
render_failed:
    for(size_t i=0;i<2;i++) asteriskd_byte_buffer_destroy(&slot->pending[i]);
    slot->pending_valid=false;
    return -1;
}
static int nf_native_remove(struct nf_native_backend *b,struct nf_native_slot *slot) {
    struct asteriskd_system_supervisor *s=b->system;
    if(!slot->owned) return 0;
    if(!b->wal_ready || !nf_native_available(s)) return -1;
    int rc=nf_native_snapshot(s,slot);
    if(!rc) for(size_t i=0;i<slot->plan.private_group_count;i++)
        if(nf_native_classify(s,slot,i,false,false)<0) rc=-1;
    if(!rc && asteriskd_supervisor_rule_batch_begin(&s->rule_commands)==0) {
        for(size_t i=0;i<slot->plan.hook_group_count;i++)
            if(asteriskd_supervisor_remove_hook_group(s,&slot->plan.hook_groups[i])!=0) rc=-1;
        for(size_t i=0;!rc && i<slot->plan.private_group_count;i++)
            if(asteriskd_supervisor_remove_private_group(s,&slot->plan.private_groups[i])!=0) rc=-1;
        if(!rc) rc=nf_native_checkpoint(b,slot==&b->guard?NF_GUARD_REMOVE:NF_NAT_REMOVE);
        if(!rc) rc=asteriskd_supervisor_rule_batch_flush(s);
        asteriskd_supervisor_rule_batch_destroy(&s->rule_commands);
    } else rc=-1;
    asteriskd_supervisor_rule_snapshot_destroy(&s->rule_snapshot);
    if(rc || nf_native_snapshot(s,slot)!=0) return -1;
    for(size_t i=0;i<slot->plan.private_group_count;i++)
        if(nf_native_classify(s,slot,i,false,false)!=0) rc=-1;
    asteriskd_supervisor_rule_snapshot_destroy(&s->rule_snapshot);
    if(!rc) nf_native_clear(slot);
    return rc;
}
/* Record only the already verified child from the upstream supervisor. A
 * crash between spawn and readiness remains ambiguous and blocks recovery. */
static int nf_native_record_core(struct nf_native_backend *b) {
    struct asteriskd_system_supervisor *s=b->system;
    if(!b->wal_ready || b->precore_certified || !(b->wal.debts&4U) || !s->core_spawned ||
       s->core_reaped || !s->core_identity_ready || !s->core_spec_ready)return -1;
    const struct asteriskd_child_identity *old=&s->core_identity;
    struct asteriskd_child_identity now;char error[128];
    if(old->pid!=s->core_process.pid || old->type!=ASTERISKD_CHILD_TYPE_SING_BOX || old->role!=ASTERISKD_CHILD_CORE ||
       asteriskd_process_identity_read(asteriskd_system_process_identity_backend(),old->pid,old->role,old->type,&s->core_spec,&now,error,sizeof(error)) ||
       now.pid!=old->pid || now.process_group_id!=old->process_group_id || now.start_time_ticks!=old->start_time_ticks ||
       now.exe_device!=old->exe_device || now.exe_inode!=old->exe_inode)return -1;
    struct nf_core_evidence e={.pid=(uint32_t)now.pid,.group=(uint32_t)now.process_group_id,.start=now.start_time_ticks,.device=now.exe_device,.inode=now.exe_inode};
    if(!nf_core_evidence_valid(&e))return -1;
    if(b->core_evidence_valid)return nf_core_evidence_equal(&b->core_evidence,&e)?0:-1;
    b->core_evidence=e;b->core_evidence_valid=true;
    if(nf_native_checkpoint(b,NF_GUARD_CLOSED)) {b->core_evidence_valid=false;memset(&b->core_evidence,0,sizeof(b->core_evidence));return -1;}
    return 0;
}
// Connect the real firewall operations to the coordinator. Deliberately
// require the process/port/link/conntrack callbacks; no successful stub.
int nf_gateway_native_step(void *opaque,enum nf_step op,unsigned port,uint64_t epoch) {
    struct nf_native_backend *b=opaque;
    if(!b || !b->system || !b->lifecycle_operations.step) return -1;
    // Firewall mutation is never allowed without a durable session journal.
    if(b->wal_ready && b->wal.port!=port)return -1;
    if(b->wal_ready && epoch)b->wal.epoch=epoch;
    switch(op) {
    case NF_RESERVE: {
        if(nf_native_mark_recheck(b))return -1;
        // Probe only when IPv6 gateway capture was requested, before core/guard.
        b->ipv6_nat_disabled=true;
        if(b->system->loaded_config.config.enable_ipv6) {
            if(!nf_native_available(b->system))return -1;
            int capability=nf_nat6_probe(b->system);
            if(capability<0)return -1;
            b->ipv6_nat_disabled=capability==1;
        }
        if(b->session_mask && (nf_gateway_native_audit_mark(b->system,b->session_mask) ||
            nf_gateway_ct_candidate_available(b->session_mark,b->session_mask,port) ||
            nf_native_mark_recheck(b)))return -1;
        int rc=b->lifecycle_operations.step(b->lifecycle_operations.ctx,op,port,epoch);
        if(!rc && b->wal_ready && !b->wal.debts && !b->wal.epoch &&
           b->wal.port==port && !b->guard.owned && !b->nat.owned) b->precore_certified=true;
        return rc;
    }
    case NF_START_CORE:
        // Record possible process creation before invoking any spawn callback.
        if(!b->wal_ready || !b->guard.owned || b->core_evidence_valid)return -1;
        b->precore_certified=false;b->wal.debts|=4U;
        if(nf_native_checkpoint(b,NF_GUARD_CLOSED))return -1;
        return b->lifecycle_operations.step(b->lifecycle_operations.ctx,op,port,epoch);
    case NF_CORE_READY:
        if(b->lifecycle_operations.step(b->lifecycle_operations.ctx,op,port,epoch))return -1;
        return nf_native_record_core(b);
    case NF_GUARD_CLOSED: return nf_native_write(b,&b->guard,port,true,false);
    case NF_GUARD_OPEN: if(nf_native_mark_recheck(b) || b->precore_certified || !(b->wal.debts&4U) || !b->core_evidence_valid || nf_native_record_core(b))return -1; return nf_native_write(b,&b->guard,port,true,true);
    case NF_GUARD_VERIFY: return b->guard.pending_valid?-1:nf_native_verify(b->system,&b->guard,false);
    case NF_NAT_INSTALL: if(nf_native_mark_recheck(b) || b->precore_certified || !(b->wal.debts&4U) || !b->core_evidence_valid || nf_native_record_core(b))return -1; return nf_native_write(b,&b->nat,port,false,true);
    case NF_NAT_VERIFY: return b->nat.pending_valid?-1:nf_native_verify(b->system,&b->nat,false);
    case NF_NAT_REMOVE: return nf_native_remove(b,&b->nat);
    case NF_GUARD_REMOVE: return nf_native_remove(b,&b->guard);
    case NF_CONNTRACK_DRAIN: if(b->session_mask || b->session_mark)return nf_gateway_native_drain_marked(b);
        return b->lifecycle_operations.step(b->lifecycle_operations.ctx,op,port,epoch);
    default: return b->lifecycle_operations.step(b->lifecycle_operations.ctx,op,port,epoch);
    }
}

#ifdef NF_BACKEND_HOST_TEST
static unsigned backend_checks;
#define BTEST(x) do {if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++backend_checks;}while(0)
static int classify_text(struct asteriskd_system_supervisor *s, struct nf_native_slot *slot,
                         const char *text, bool require_hook, bool pending_only) {
    struct system_rule_view *v=&s->rule_snapshot.xtables[0][ASTERISKD_IP_TABLE_FILTER];
    v->bytes=(char *)text;v->length=strlen(text);v->present=true;
    return nf_native_classify(s,slot,0,require_hook,pending_only);
}
int main(void) {
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));
    struct nf_native_slot *slot=calloc(1,sizeof(*slot));BTEST(s && slot);
    struct asteriskd_config *c=&s->loaded_config.config;
    c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;
    strcpy(c->helper.value.hev.tunnel_name,"hevtun0");
    strcpy(c->hotspot_interfaces[0],"ap0");c->hotspot_interface_count=1;
    BTEST(!nf_gateway_plan(c,15353,true,&slot->plan));
    BTEST(!nf_native_render(s,slot,15353,false,0,0,false));slot->pending_valid=true;
    const char *closed="-A NF_HEV_DNS4_IN -p udp -m udp --dport 15353 -j DROP\n-A NF_HEV_DNS4_IN -p tcp -m tcp --dport 15353 -j DROP\n";
    char listing[4096];
    BTEST(classify_text(s,slot,"-P INPUT ACCEPT\n",false,false)==0);
    snprintf(listing,sizeof(listing),"-N NF_HEV_DNS4_IN\n-A INPUT -j NF_HEV_DNS4_IN\n%s",closed);
    BTEST(classify_text(s,slot,listing,true,true)==1);
    slot->pending_valid=false;BTEST(classify_text(s,slot,listing,true,true)==-1);
    slot->pending_valid=true;
    snprintf(listing,sizeof(listing),"-N NF_HEV_DNS4_IN\n-A INPUT -j ACCEPT\n-A INPUT -j NF_HEV_DNS4_IN\n%s",closed);
    BTEST(classify_text(s,slot,listing,true,true)==-1);
    BTEST(classify_text(s,slot,listing,false,false)==1);
    snprintf(listing,sizeof(listing),"-N NF_HEV_DNS4_IN\n%s",closed);
    BTEST(classify_text(s,slot,listing,false,false)==1);
    BTEST(classify_text(s,slot,listing,true,true)==-1);
    snprintf(listing,sizeof(listing),"-N NF_HEV_DNS4_IN\n-A INPUT -j NF_HEV_DNS4_IN\n-A INPUT -j NF_HEV_DNS4_IN\n%s",closed);
    BTEST(classify_text(s,slot,listing,false,false)==-1);
    snprintf(listing,sizeof(listing),"-N NF_HEV_DNS4_IN\n-A INPUT -j NF_HEV_DNS4_IN\n%s-A NF_HEV_DNS4_IN -j ACCEPT\n",closed);
    BTEST(classify_text(s,slot,listing,false,false)==-1);
    BTEST(classify_text(s,slot,"-N NF_HEV_DNS4_IN\n-A NF_HEV_DNS4_IN -j ACCEPT\n",false,false)==-1);
    // Real disk checkpoint of pending guard listing; no kernel operations.
    char path[]="/tmp/nf-backend-wal-XXXXXX";BTEST(mkdtemp(path)!=NULL);
    int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);BTEST(d>=0);
    struct nf_native_backend *backend=calloc(1,sizeof(*backend));BTEST(backend!=NULL);
    backend->system=s;backend->wal_fd=nf_gateway_wal_lock(d);BTEST(backend->wal_fd>=0);
    backend->wal_ready=true;backend->wal=(struct nf_wal_record){.boot={1},.session={2},.port=15353};
    backend->guard=*slot;memset(slot,0,sizeof(*slot));backend->guard.owned=true;
    BTEST(!nf_native_checkpoint(backend,NF_GUARD_CLOSED));
    uint8_t *payload=malloc(NF_WAL_MAX);struct nf_wal_record rec;BTEST(payload!=NULL);
    BTEST(!nf_gateway_wal_load(backend->wal_fd,&rec,payload,NF_WAL_MAX) && rec.generation==1 && rec.debts==3);
    BTEST(rec.length>12 && !memcmp(payload,"NFFW01",6) && payload[6]==NF_GUARD_CLOSED && payload[8]==2 && payload[9]==5);
    BTEST(nf_get(payload+14,4)==strlen(closed) && !memcmp(payload+18,closed,strlen(closed)));
    backend->wal.generation=0;BTEST(nf_native_checkpoint(backend,NF_GUARD_CLOSED)<0 && !backend->wal_ready);
    BTEST(!nf_gateway_wal_load(backend->wal_fd,&rec,payload,NF_WAL_MAX) && rec.generation==1);
    BTEST(!unlinkat(backend->wal_fd,NF_WAL_NAME,0));close(backend->wal_fd);close(d);BTEST(!rmdir(path));
    nf_native_clear(&backend->guard);free(payload);free(backend);
    nf_native_clear(slot);
    struct nf_native_backend *cap=calloc(1,sizeof(*cap));BTEST(cap!=NULL);cap->system=s;
    c->enable_ipv6=true;cap->ipv6_nat_disabled=true;
    BTEST(!nf_native_plan(cap,&cap->nat,15353,false) && cap->nat.plan.private_group_count==1 && cap->nat.plan.hook_group_count==1);
    BTEST(!nf_native_plan(cap,&cap->guard,15353,true) && cap->guard.plan.private_group_count==2);
    BTEST(!nf_native_render(s,&cap->guard,15353,true,0,0,false));
    BTEST(memmem(cap->guard.pending[0].bytes,cap->guard.pending[0].length,"-j ACCEPT",9)!=NULL);
    BTEST(memmem(cap->guard.pending[1].bytes,cap->guard.pending[1].length,"-j ACCEPT",9)==NULL);
    BTEST(memmem(cap->guard.pending[1].bytes,cap->guard.pending[1].length,"-j DROP",7)!=NULL);
    BTEST(c->enable_ipv6);nf_native_clear(&cap->guard);nf_native_clear(&cap->nat);
    cap->ipv6_nat_disabled=false;BTEST(!nf_native_plan(cap,&cap->nat,15353,false) && cap->nat.plan.private_group_count==2);
    nf_native_clear(&cap->nat);free(cap);
    const char *missing="ip6tables v1.8.11 (legacy): can't initialize ip6tables table `nat': Table does not exist (do you need to insmod?)\nPerhaps ip6tables or your kernel needs to be upgraded.\n";
    BTEST(nf_nat6_missing(0,3,false,0,missing,strlen(missing)));
    BTEST(!nf_nat6_missing(-1,3,false,0,missing,strlen(missing)));
    BTEST(!nf_nat6_missing(0,4,false,0,missing,strlen(missing)));
    BTEST(!nf_nat6_missing(0,3,true,0,missing,strlen(missing)));
    BTEST(!nf_nat6_missing(0,3,false,1,missing,strlen(missing)));
    BTEST(!nf_nat6_missing(0,3,false,0,missing,strlen(missing)-1));
    char audit1[]="-A X -j CONNMARK --save-mark --nfmask 0xfffff --ctmask 0xfffff";
    char audit2[]="-A X -j CONNMARK --save-mark --nfmask 0x800000 --ctmask 0xfffff";
    char audit3[]="-A X -j MARK --set-xmark 0x800000/0x800000";
    char audit4[]="-A X -j CONNMARK --unknown";
    BTEST(nf_audit_rule(audit1,0x800000)==0);
    BTEST(nf_audit_rule(audit2,0x800000)==1);
    BTEST(nf_audit_rule(audit3,0x800000)==0);
    BTEST(nf_audit_rule(audit4,0x800000)<0);
    const char *save="*filter\n:INPUT ACCEPT [0:0]\nCOMMIT\n*mangle\nCOMMIT\n*raw\nCOMMIT\n";
    BTEST(nf_audit_save(save,strlen(save),0x800000,true)==0);
    BTEST(nf_audit_save(save,strlen(save),0x800000,false)<0);
    BTEST(nf_audit_save(save,strlen(save)-1,0x800000,true)<0);
    free(slot);free(s);
    printf("PASS %u backend checks\n",backend_checks);return 0;
}
#endif
