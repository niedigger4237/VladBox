// SPDX-License-Identifier: GPL-3.0
// Staged same-boot recovery; compile instead of gateway_backend.c.
#include "gateway_backend.c"

/* Recovery must not reinterpret the old plan using today's settings. The
 * only plan inputs persisted by NFFW01 are fixed chain IDs and family count. */
static int nf_recovery_plan(struct nf_native_slot *slot,bool guard,unsigned count,unsigned port) {
    if(!count)return 0;
    if(count>2 || (guard && count!=2))return -1;
    struct asteriskd_config *c=calloc(1,sizeof(*c));if(!c)return -1;
    c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;
    c->mode=ASTERISKD_MODE_TUN2SOCKS;c->enable_ipv6=count==2;
    int rc=nf_gateway_plan(c,port,guard,&slot->plan);free(c);
    return rc || slot->plan.private_group_count!=count?-1:0;
}
/* Validate exact generated grammar, not arbitrary saved iptables commands.
 * A saved image is evidence for comparisons only; it is never executed. */
static bool nf_recovery_image(const uint8_t *p,size_t len,const char *chain,bool guard,unsigned port) {
    unsigned pairs=0, drops=0;char prev[16]={0},names[16][16]={{0}};bool tcp=false;
    while(len) {
        const uint8_t *end=memchr(p,'\n',len);if(!end)return false;
        size_t n=(size_t)(end-p)+1;if(n>512 || memchr(p,0,n))return false;
        char line[513],want[513],name[16]={0};memcpy(line,p,n);line[n]=0;
        const char *proto=tcp?"tcp":"udp";
        int size=snprintf(want,sizeof(want),"-A %s -p %s -m %s --dport %u -j DROP\n",chain,drops?"tcp":"udp",drops?"tcp":"udp",port);
        if(guard && !tcp && drops<2 && size>0 && !strcmp(line,want)) {drops++;}
        else {
            if(drops || pairs>=16)return false;
            char prefix[64];size=snprintf(prefix,sizeof(prefix),"-A %s -i ",chain);
            if(size<=0 || (size_t)size>=sizeof(prefix) || strncmp(line,prefix,(size_t)size))return false;
            const char *start=line+size,*space=strchr(start,' ');if(!space || space==start || space-start>15)return false;
            memcpy(name,start,(size_t)(space-start));
            if(!asteriskd_interface_selector_valid(name) || strchr(name,'+') || !strcmp(name,"lo") || !strcmp(name,"hevtun0") || !strcmp(name,"asterisk0") || !strcmp(name,".") || !strcmp(name,"..") || !strcmp(name,"all") || !strcmp(name,"default"))return false;
            if(!tcp) {for(unsigned i=0;i<pairs;i++)if(!strcmp(names[i],name))return false;strcpy(prev,name);}
            else if(strcmp(prev,name))return false;
            if(guard)size=snprintf(want,sizeof(want),"-A %s -i %s -p %s -m %s --dport %u -m conntrack --ctstate DNAT --ctorigdstport 53 --ctdir ORIGINAL -j ACCEPT\n",chain,name,proto,proto,port);
            else size=snprintf(want,sizeof(want),"-A %s -i %s -p %s -m %s --dport 53 -m addrtype --dst-type LOCAL --limit-iface-in -j REDIRECT --to-ports %u\n",chain,name,proto,proto,port);
            if(size<=0 || (size_t)size>=sizeof(want) || strcmp(line,want))return false;
            if(tcp)strcpy(names[pairs++],name);
            tcp=!tcp;
        }
        p+=n;len-=n;
    }
    return !tcp && (guard?drops==2:drops==0);
}
static int nf_recovery_slot(const struct nf_wal_record *r,size_t *pos,struct nf_native_slot *s,bool guard) {
    if(*pos>r->length || r->length-*pos<2)return -1;
    unsigned count=r->payload[(*pos)++],flags=r->payload[(*pos)++];
    if(flags&~7U || ((flags&6U) && !(flags&1U)) || ((flags&1U) && (!(flags&6U)||!count)) || nf_recovery_plan(s,guard,count,r->port))return -1;
    s->owned=flags&1U;s->expected_valid=flags&2U;s->pending_valid=flags&4U;
    for(size_t i=0;i<2;i++)for(size_t v=0;v<2;v++) {
        if(*pos>r->length || r->length-*pos<4)return -1;
        size_t n=(size_t)nf_get(r->payload+*pos,4);*pos+=4;
        bool present=i<count && (v?s->pending_valid:s->expected_valid);
        if(n>r->length-*pos || (!present && n))return -1;
        if(present && !nf_recovery_image(r->payload+*pos,n,s->plan.private_groups[i].names[0],guard,r->port))return -1;
        struct asteriskd_byte_buffer *dst=v?&s->pending[i]:&s->expected[i];
        if(n && asteriskd_byte_buffer_append(dst,r->payload+*pos,n))return -1;
        *pos+=n;
    }
    return 0;
}
/* Only accepts the same boot. Across reboot, separate proof of absence of
 * old processes and resources is needed, not replay of old ownership. */
int nf_gateway_recovery_load(struct nf_native_backend *b,const uint8_t boot[16]) {
    if(!b || !boot || b->wal_ready || b->guard.owned || b->nat.owned || b->guard.plan.private_group_count || b->nat.plan.private_group_count)return -1;
    uint8_t *payload=malloc(NF_WAL_MAX);if(!payload)return -1;
    struct nf_wal_record r;int rc=nf_gateway_wal_load(b->wal_fd,&r,payload,NF_WAL_MAX);
    if(rc) {free(payload);return rc;}
    rc=-1;
    if(memcmp(boot,r.boot,16) || r.length<8 || memcmp(payload,"NFFW01",6) || payload[7] ||
       (payload[6]!=NF_GUARD_CLOSED && payload[6]!=NF_GUARD_OPEN && payload[6]!=NF_NAT_INSTALL && payload[6]!=NF_NAT_REMOVE && payload[6]!=NF_GUARD_REMOVE))goto done;
    size_t pos=8;
    if(nf_recovery_slot(&r,&pos,&b->guard,true) || nf_recovery_slot(&r,&pos,&b->nat,false) || pos!=r.length ||
       (b->guard.owned && !(r.debts&2U)) || (b->nat.owned && (r.debts&24U)!=24U))goto done;
    b->wal=r;b->wal.payload=NULL;b->wal.length=0;b->wal_ready=true;rc=0;
done:
    if(rc){nf_native_clear(&b->guard);nf_native_clear(&b->nat);}
    free(payload);return rc;
}
/* Reject extra references even if the private chain itself is exact.
 * Conservative substring check on unrelated listing lines: comments that
 * mention our chain may also block recovery. Never remove such references. */
static bool nf_recovery_refs(const struct system_rule_view *v,const char *chain,const char *builtin) {
    if(!v->present || !v->bytes)return false;
    char decl[64],rule[64],hook[96];
    snprintf(decl,sizeof(decl),"-N %s\n",chain);
    snprintf(rule,sizeof(rule),"-A %s ",chain);
    snprintf(hook,sizeof(hook),"-A %s -j %s\n",builtin,chain);
    for(size_t i=0;i<v->length;) {
        const char *p=v->bytes+i,*end=memchr(p,'\n',v->length-i);if(!end)return false;
        size_t n=(size_t)(end-p)+1;i+=n;
        if(!memmem(p,n,chain,strlen(chain)))continue;
        if((n==strlen(decl)&&!memcmp(p,decl,n)) || (n==strlen(hook)&&!memcmp(p,hook,n)) ||
           (n>strlen(rule)&&!memcmp(p,rule,strlen(rule))))continue;
        return false;
    }
    return true;
}
static int nf_recovery_preflight(struct nf_native_backend *b,struct nf_native_slot *slot) {
    if(!slot->owned)return 0;
    struct asteriskd_system_supervisor *s=b->system;
    if(!nf_native_available(s))return -1;
    int rc=nf_native_snapshot(s,slot);
    if(!rc)for(size_t i=0;i<slot->plan.private_group_count;i++) {
        const struct asteriskd_private_chain_group *g=&slot->plan.private_groups[i];
        if(nf_native_classify(s,slot,i,false,false)<0 || !nf_recovery_refs(&s->rule_snapshot.xtables[g->family][g->table],g->names[0],g->table==ASTERISKD_IP_TABLE_FILTER?"INPUT":"PREROUTING"))rc=-1;
    }
    asteriskd_supervisor_rule_snapshot_destroy(&s->rule_snapshot);return rc;
}
/* Mandatory non-firewall evidence: no old listener or process can still
 * serve the port, and exact old epoch flows are drained. The latter must
 * run after NAT removal. Neither callback can be a success stub. The WAL
 * remains outstanding until the full process/port recovery commits clean. */
struct nf_recovery_ops {
    void *ctx;
    int (*listener_stopped)(void *,const struct nf_wal_record *);
    int (*flows_drained)(void *,const struct nf_wal_record *);
};
int nf_gateway_recovery_cleanup(struct nf_native_backend *b,const struct nf_recovery_ops *ops) {
    if(!b || !b->wal_ready || !b->system || !ops || !ops->listener_stopped || !ops->flows_drained)return -1;
    if(ops->listener_stopped(ops->ctx,&b->wal))return -1;
    if(nf_recovery_preflight(b,&b->nat) || nf_native_remove(b,&b->nat))return -1;
    if(ops->flows_drained(ops->ctx,&b->wal) || ops->listener_stopped(ops->ctx,&b->wal))return -1;
    if(nf_recovery_preflight(b,&b->guard) || nf_native_remove(b,&b->guard))return -1;
    return 0;
}
#ifdef NF_RECOVERY_HOST_TEST
static unsigned checks;
#define CHECK(x) do{if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
int main(void) {
    const char *closed="-A NF_HEV_DNS4_IN -p udp -m udp --dport 15353 -j DROP\n-A NF_HEV_DNS4_IN -p tcp -m tcp --dport 15353 -j DROP\n";
    CHECK(nf_recovery_image((const uint8_t*)closed,strlen(closed),"NF_HEV_DNS4_IN",true,15353));
    CHECK(!nf_recovery_image((const uint8_t*)closed,strlen(closed),"NF_HEV_DNS6_IN",true,15353));
    CHECK(!nf_recovery_image((const uint8_t*)closed,strlen(closed),"NF_HEV_DNS4_IN",true,15354));
    CHECK(!nf_recovery_image((const uint8_t*)closed,strlen(closed)-1,"NF_HEV_DNS4_IN",true,15353));
    CHECK(!nf_recovery_image((const uint8_t*)closed,0,"NF_HEV_DNS4_IN",true,15353));
    char path[]="/tmp/nf-recovery-XXXXXX";CHECK(mkdtemp(path)!=NULL);int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));struct nf_native_backend *a=calloc(1,sizeof(*a)),*b=calloc(1,sizeof(*b));CHECK(s&&a&&b);
    struct asteriskd_config *c=&s->loaded_config.config;c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;c->enable_ipv6=true;
    strcpy(c->hotspot_interfaces[0],"ap0");c->hotspot_interface_count=1;
    a->system=s;a->wal_fd=nf_gateway_wal_lock(d);CHECK(a->wal_fd>=0);a->wal_ready=true;a->wal=(struct nf_wal_record){.boot={1},.session={2},.port=15353,.epoch=5};
    CHECK(!nf_gateway_plan(c,15353,true,&a->guard.plan));CHECK(!nf_native_render(s,&a->guard,15353,false));a->guard.pending_valid=a->guard.owned=true;
    CHECK(!nf_gateway_plan(c,15353,false,&a->nat.plan));CHECK(!nf_native_render(s,&a->nat,15353,true));a->nat.pending_valid=a->nat.owned=true;
    CHECK(!nf_native_checkpoint(a,NF_NAT_INSTALL));close(a->wal_fd);nf_native_clear(&a->guard);nf_native_clear(&a->nat);
    b->system=s;b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);uint8_t wrong[16]={3},boot[16]={1};
    CHECK(nf_gateway_recovery_load(b,wrong)<0 && !b->wal_ready);memset(c,0,sizeof(*c)); // unrelated current settings
    CHECK(!nf_gateway_recovery_load(b,boot) && b->wal_ready && b->guard.owned && b->nat.owned);
    CHECK(b->wal.debts==27 && b->wal.epoch==5 && b->guard.plan.private_group_count==2 && b->nat.plan.private_group_count==2);
    CHECK(b->guard.pending[0].length==strlen(closed) && !memcmp(b->guard.pending[0].bytes,closed,strlen(closed)));
    CHECK(nf_gateway_recovery_load(b,boot)<0);CHECK(nf_gateway_recovery_cleanup(b,NULL)<0);
    struct system_rule_view *v=&s->rule_snapshot.xtables[0][ASTERISKD_IP_TABLE_FILTER];char listing[512];
    snprintf(listing,sizeof(listing),"-N NF_HEV_DNS4_IN\n-A INPUT -j NF_HEV_DNS4_IN\n%s",closed);v->bytes=listing;v->length=strlen(listing);v->present=true;
    CHECK(nf_native_classify(s,&b->guard,0,true,false)==1);strcat(listing,"-A NF_HEV_DNS4_IN -j ACCEPT\n");v->length=strlen(listing);CHECK(nf_native_classify(s,&b->guard,0,false,false)<0);
    CHECK(nf_recovery_refs(v,"NF_HEV_DNS4_IN","INPUT"));
    strcpy(listing,"-A FORWARD -j NF_HEV_DNS4_IN\n");v->length=strlen(listing);CHECK(!nf_recovery_refs(v,"NF_HEV_DNS4_IN","INPUT"));
    strcpy(listing,"-A INPUT -p tcp -g NF_HEV_DNS4_IN\n");v->length=strlen(listing);CHECK(!nf_recovery_refs(v,"NF_HEV_DNS4_IN","INPUT"));
    uint8_t *raw=malloc(NF_WAL_MAX);struct nf_wal_record rec;CHECK(raw!=NULL);
    CHECK(!nf_gateway_wal_load(b->wal_fd,&rec,raw,NF_WAL_MAX));
    raw[9]=255;rec.generation++;CHECK(!nf_gateway_wal_save(b->wal_fd,&rec,rec.generation-1));
    nf_native_clear(&b->guard);nf_native_clear(&b->nat);b->wal_ready=false;
    CHECK(nf_gateway_recovery_load(b,boot)<0 && !b->wal_ready && !b->guard.owned && !b->nat.owned);free(raw);
    CHECK(!unlinkat(b->wal_fd,NF_WAL_NAME,0));close(b->wal_fd);close(d);CHECK(!rmdir(path));nf_native_clear(&b->guard);nf_native_clear(&b->nat);free(a);free(b);free(s);
    printf("PASS %u recovery checks\n",checks);return 0;
}
#endif
