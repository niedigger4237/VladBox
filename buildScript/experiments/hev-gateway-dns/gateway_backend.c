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
};
struct nf_native_backend {
    struct asteriskd_system_supervisor *system;
    struct nf_native_slot guard, nat;
    // Non-firewall operations must be provided by the actual supervisor.
    struct nf_backend lifecycle_operations;
    // Caller must hold the WAL lock and establish boot/session identity first.
    int wal_fd;
    bool wal_ready;
    struct nf_wal_record wal;
};

/* Persist exact old/pending chain images before any kernel mutation.
 * The schema stores guard then NAT, with family order fixed by the planner.
 * No raw struct/pointer serialization. Caller must separately journal core,
 * link and conntrack identity; firewall checkpoint never clears cleanup debt. */
static int nf_native_checkpoint(struct nf_native_backend *b,enum nf_step op) {
    if(!b->wal_ready || b->wal.generation==UINT64_MAX) return -1;
    uint8_t *data=calloc(1,NF_WAL_MAX);if(!data)return -1;
    memcpy(data,"NFFW01",6);data[6]=(uint8_t)op;size_t pos=8;
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

static bool nf_native_available(const struct asteriskd_system_supervisor *s) {
    return s && !s->rule_commands.active && !s->verify_private_rules &&
        s->rule_snapshot.phase == SYSTEM_RULE_SNAPSHOT_NONE && !s->action_spawned;
}
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
static int nf_native_render(struct asteriskd_system_supervisor *s,
        struct nf_native_slot *slot,unsigned port,bool active) {
    if (asteriskd_supervisor_rule_batch_begin(&s->rule_commands)!=0) return -1;
    int rc=0;
    for(size_t i=0;i<slot->plan.private_group_count;i++) {
        const struct asteriskd_private_chain_group *g=&slot->plan.private_groups[i];
        if(nf_gateway_populate(s,g,port,active)!=0) {rc=-1;break;}
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
    if (fresh && nf_gateway_plan(&s->loaded_config.config,port,guard,&slot->plan)!=0) return -1;
    int rc=nf_native_snapshot(s,slot);
    if(!rc) for(size_t i=0;i<slot->plan.private_group_count;i++)
        if(nf_native_classify(s,slot,i,!fresh,false)!=(fresh?0:1)) rc=-1;
    asteriskd_supervisor_rule_snapshot_destroy(&s->rule_snapshot);
    if(rc) return -1;
    if(nf_native_render(s,slot,port,active)!=0) goto render_failed;
    if(asteriskd_supervisor_rule_batch_begin(&s->rule_commands)!=0) goto render_failed;
    for(size_t i=0;i<slot->plan.private_group_count;i++) {
        const struct asteriskd_private_chain_group *g=&slot->plan.private_groups[i];
        if(asteriskd_supervisor_xtables_zero(s,g->family,g->table,fresh?"-N":"-F",g->names[0],NULL,0)!=0 ||
           nf_gateway_populate(s,g,port,active)!=0 ||
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
// Connect the real firewall operations to the coordinator. Deliberately
// require the process/port/link/conntrack callbacks; no successful stub.
int nf_gateway_native_step(void *opaque,enum nf_step op,unsigned port,uint64_t epoch) {
    struct nf_native_backend *b=opaque;
    if(!b || !b->system || !b->lifecycle_operations.step) return -1;
    // Firewall mutation is never allowed without a durable session journal.
    if(b->wal_ready && b->wal.port!=port)return -1;
    if(b->wal_ready && epoch)b->wal.epoch=epoch;
    switch(op) {
    case NF_GUARD_CLOSED: return nf_native_write(b,&b->guard,port,true,false);
    case NF_GUARD_OPEN: return nf_native_write(b,&b->guard,port,true,true);
    case NF_GUARD_VERIFY: return b->guard.pending_valid?-1:nf_native_verify(b->system,&b->guard,false);
    case NF_NAT_INSTALL: return nf_native_write(b,&b->nat,port,false,true);
    case NF_NAT_VERIFY: return b->nat.pending_valid?-1:nf_native_verify(b->system,&b->nat,false);
    case NF_NAT_REMOVE: return nf_native_remove(b,&b->nat);
    case NF_GUARD_REMOVE: return nf_native_remove(b,&b->guard);
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
    BTEST(!nf_native_render(s,slot,15353,false));slot->pending_valid=true;
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
    nf_native_clear(slot);free(slot);free(s);
    printf("PASS %u backend checks\n",backend_checks);return 0;
}
#endif
