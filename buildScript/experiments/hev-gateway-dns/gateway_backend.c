// SPDX-License-Identifier: GPL-3.0
// Native transaction adapter; compiled but not activated by supervisor lifecycle.
#include "native_gateway_dns.c"
#include "gateway_lifecycle.c"

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
};

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
static int nf_native_write(struct asteriskd_system_supervisor *s,
        struct nf_native_slot *slot,unsigned port,bool guard,bool active) {
    if (!nf_native_available(s)) return -1;
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
    if(!rc) {slot->owned=true;slot->pending_valid=true;rc=asteriskd_supervisor_rule_batch_flush(s);}
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
static int nf_native_remove(struct asteriskd_system_supervisor *s,struct nf_native_slot *slot) {
    if(!slot->owned) return 0;
    if(!nf_native_available(s)) return -1;
    int rc=nf_native_snapshot(s,slot);
    if(!rc) for(size_t i=0;i<slot->plan.private_group_count;i++)
        if(nf_native_classify(s,slot,i,false,false)<0) rc=-1;
    if(!rc && asteriskd_supervisor_rule_batch_begin(&s->rule_commands)==0) {
        for(size_t i=0;i<slot->plan.hook_group_count;i++)
            if(asteriskd_supervisor_remove_hook_group(s,&slot->plan.hook_groups[i])!=0) rc=-1;
        for(size_t i=0;!rc && i<slot->plan.private_group_count;i++)
            if(asteriskd_supervisor_remove_private_group(s,&slot->plan.private_groups[i])!=0) rc=-1;
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
    switch(op) {
    case NF_GUARD_CLOSED: return nf_native_write(b->system,&b->guard,port,true,false);
    case NF_GUARD_OPEN: return nf_native_write(b->system,&b->guard,port,true,true);
    case NF_GUARD_VERIFY: return b->guard.pending_valid?-1:nf_native_verify(b->system,&b->guard,false);
    case NF_NAT_INSTALL: return nf_native_write(b->system,&b->nat,port,false,true);
    case NF_NAT_VERIFY: return b->nat.pending_valid?-1:nf_native_verify(b->system,&b->nat,false);
    case NF_NAT_REMOVE: return nf_native_remove(b->system,&b->nat);
    case NF_GUARD_REMOVE: return nf_native_remove(b->system,&b->guard);
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
    nf_native_clear(slot);free(slot);free(s);
    printf("PASS %u backend checks\n",backend_checks);return 0;
}
#endif
