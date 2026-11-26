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
static bool nf_recovery_image(const uint8_t *p,size_t len,const char *chain,bool guard,unsigned port,uint32_t mark,uint32_t mask) {
    if((mark || mask) && !nf_gateway_mark_valid(mark,mask))return false;
    unsigned pairs=0, drops=0;char prev[16]={0},names[16][16]={{0}};bool tcp=false,tag_pending=false;
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
            if(!tcp && !tag_pending) {for(unsigned i=0;i<pairs;i++)if(!strcmp(names[i],name))return false;strcpy(prev,name);}
            else if(strcmp(prev,name))return false;
            if(mask && !guard && !tag_pending) {
                size=snprintf(want,sizeof(want),"-A %s -i %s -p %s -m %s --dport 53 -m addrtype --dst-type LOCAL --limit-iface-in -m connmark --mark 0x0/0x%x -j CONNMARK --set-xmark 0x%x/0x%x\n",chain,name,proto,proto,mask,mark,mask);
                if(size<=0 || (size_t)size>=sizeof(want) || strcmp(line,want))return false;
                tag_pending=true;p+=n;len-=n;continue;
            }
            if(mask && guard)size=snprintf(want,sizeof(want),"-A %s -i %s -p %s -m %s --dport %u -m conntrack --ctstate DNAT --ctorigdstport 53 --ctdir ORIGINAL -m connmark --mark 0x%x/0x%x -j ACCEPT\n",chain,name,proto,proto,port,mark,mask);
            else if(mask)size=snprintf(want,sizeof(want),"-A %s -i %s -p %s -m %s --dport 53 -m addrtype --dst-type LOCAL --limit-iface-in -m connmark --mark 0x%x/0x%x -j REDIRECT --to-ports %u\n",chain,name,proto,proto,mark,mask,port);
            else if(guard)size=snprintf(want,sizeof(want),"-A %s -i %s -p %s -m %s --dport %u -m conntrack --ctstate DNAT --ctorigdstport 53 --ctdir ORIGINAL -j ACCEPT\n",chain,name,proto,proto,port);
            else size=snprintf(want,sizeof(want),"-A %s -i %s -p %s -m %s --dport 53 -m addrtype --dst-type LOCAL --limit-iface-in -j REDIRECT --to-ports %u\n",chain,name,proto,proto,port);
            if(size<=0 || (size_t)size>=sizeof(want) || strcmp(line,want))return false;
            if(tcp)strcpy(names[pairs++],name);
            tag_pending=false;tcp=!tcp;
        }
        p+=n;len-=n;
    }
    return !tcp && !tag_pending && (guard?drops==2:drops==0);
}
static int nf_recovery_slot(const struct nf_wal_record *r,size_t *pos,struct nf_native_slot *s,bool guard,uint32_t mark,uint32_t mask) {
    if(*pos>r->length || r->length-*pos<2)return -1;
    unsigned count=r->payload[(*pos)++],flags=r->payload[(*pos)++];
    if(flags&~7U || ((flags&6U) && !(flags&1U)) || ((flags&1U) && (!(flags&6U)||!count)) || nf_recovery_plan(s,guard,count,r->port))return -1;
    s->owned=flags&1U;s->expected_valid=flags&2U;s->pending_valid=flags&4U;
    for(size_t i=0;i<2;i++)for(size_t v=0;v<2;v++) {
        if(*pos>r->length || r->length-*pos<4)return -1;
        size_t n=(size_t)nf_get(r->payload+*pos,4);*pos+=4;
        bool present=i<count && (v?s->pending_valid:s->expected_valid);
        if(n>r->length-*pos || (!present && n))return -1;
        if(present && !nf_recovery_image(r->payload+*pos,n,s->plan.private_groups[i].names[0],guard,r->port,mark,mask))return -1;
        struct asteriskd_byte_buffer *dst=v?&s->pending[i]:&s->expected[i];
        if(n && asteriskd_byte_buffer_append(dst,r->payload+*pos,n))return -1;
        *pos+=n;
    }
    return 0;
}
/* Only accepts the same boot. Across reboot, separate proof of absence of
 * old processes and resources is needed, not replay of old ownership. */
int nf_gateway_recovery_load(struct nf_native_backend *b,const uint8_t boot[16]) {
    if(!b || !boot || b->wal_ready || b->session_mark || b->session_mask || b->mark_authorized || b->guard.owned || b->nat.owned || b->guard.plan.private_group_count || b->nat.plan.private_group_count)return -1;
    uint8_t *payload=malloc(NF_WAL_MAX);if(!payload)return -1;
    struct nf_wal_record r;int rc=nf_gateway_wal_load(b->wal_fd,&r,payload,NF_WAL_MAX);
    if(rc) {free(payload);return rc;}
    rc=-1;
    if(memcmp(boot,r.boot,16) || r.length<8 || memcmp(payload,"NFFW01",6) || (payload[7]&~7U) || (payload[7]&3U)==3U ||
       (payload[6]!=NF_GUARD_CLOSED && payload[6]!=NF_GUARD_OPEN && payload[6]!=NF_NAT_INSTALL && payload[6]!=NF_NAT_REMOVE && payload[6]!=NF_GUARD_REMOVE))goto done;
    size_t pos=8;
    if(payload[7]&4U) {
        if(r.length-pos<8)goto done;
        b->session_mark=(uint32_t)nf_get(payload+pos,4);b->session_mask=(uint32_t)nf_get(payload+pos+4,4);pos+=8;
        if(!nf_gateway_mark_valid(b->session_mark,b->session_mask))goto done;
    }
    if(nf_recovery_slot(&r,&pos,&b->guard,true,b->session_mark,b->session_mask) || nf_recovery_slot(&r,&pos,&b->nat,false,b->session_mark,b->session_mask) ||
       (b->guard.owned && !(r.debts&2U)) || (b->nat.owned && (r.debts&24U)!=24U))goto done;
    if((payload[7]&3U)==1U && (r.debts!=3U || r.epoch || !b->guard.owned || b->nat.owned || b->nat.plan.private_group_count ||
       (payload[6]!=NF_GUARD_CLOSED && payload[6]!=NF_GUARD_REMOVE)))goto done;
    if((payload[7]&3U)==2U) {
        if(!(r.debts&4U) || r.length-pos!=32)goto done;
        b->core_evidence=(struct nf_core_evidence){.pid=(uint32_t)nf_get(payload+pos,4),.group=(uint32_t)nf_get(payload+pos+4,4),
            .start=nf_get(payload+pos+8,8),.device=nf_get(payload+pos+16,8),.inode=nf_get(payload+pos+24,8)};
        if(!nf_core_evidence_valid(&b->core_evidence))goto done;
        b->core_evidence_valid=true;pos+=32;
    }
    if(pos!=r.length)goto done;
    b->recovered_op=(enum nf_step)payload[6];
    b->precore_certified=(payload[7]&3U)==1U;
    b->mark_authorized=false; // persisted identity is not a live mark lease
    b->wal=r;b->wal.payload=NULL;b->wal.length=0;b->wal_ready=true;rc=0;
done:
    if(rc){nf_native_clear(&b->guard);nf_native_clear(&b->nat);b->core_evidence_valid=false;memset(&b->core_evidence,0,sizeof(b->core_evidence));b->session_mark=b->session_mask=0;b->mark_authorized=false;}
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
/* Startup fence: called with the global control listener held, before the
 * old telemetry state is overwritten or any service cycle starts. No
 * journal is normal for existing installations. Pending resources require
 * real recovery evidence; never erase the journal to make startup succeed. */
int nf_gateway_admission_check(int directory) {
    struct stat st;
    int exists=fstatat(directory,NF_WAL_NAME,&st,AT_SYMLINK_NOFOLLOW);
    if(exists<0 && errno!=ENOENT)return -1;
    if(exists<0) {
        if(fstatat(directory,NF_WAL_TEMP,&st,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT)return -1;
        return 0;
    }
    int lock=nf_gateway_wal_lock(directory);
    if(lock<0)return -1;
    struct nf_wal_record r;
    uint8_t *payload=malloc(NF_WAL_MAX);
    int rc=-1;
    if(payload && !nf_gateway_wal_load(lock,&r,payload,NF_WAL_MAX) &&
       !r.debts && !r.length && !r.epoch &&
       fstatat(lock,NF_WAL_TEMP,&st,AT_SYMLINK_NOFOLLOW)<0 && errno==ENOENT)rc=0;
    free(payload);close(lock);return rc;
}

/* Boot identity is kernel supplied, not the wall clock. Require a complete
 * canonical UUID and refuse malformed/unavailable data. */
static int nf_boot_parse(const char *s,size_t n,uint8_t out[16]) {
    if(n!=37 || s[36]!='\n')return -1;
    uint8_t tmp[16]={0};size_t digits=0;
    for(size_t i=0;i<36;i++) {
        if(i==8 || i==13 || i==18 || i==23) {if(s[i]!='-')return -1;continue;}
        unsigned v;
        if(s[i]>='0' && s[i]<='9')v=(unsigned)(s[i]-'0');
        else if(s[i]>='a' && s[i]<='f')v=(unsigned)(s[i]-'a'+10);
        else return -1;
        tmp[digits/2]=(uint8_t)((tmp[digits/2]<<4)|v);digits++;
    }
    if(digits!=32 || !nf_nonzero(tmp,16))return -1;
    memcpy(out,tmp,16);return 0;
}
static int nf_boot_read(uint8_t out[16]) {
    int fd=open("/proc/sys/kernel/random/boot_id",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0)return -1;
    char buf[38];size_t n=0;int rc=-1;
    while(n<sizeof(buf)) {ssize_t got=read(fd,buf+n,sizeof(buf)-n);if(got<0&&errno==EINTR)continue;if(got<0)goto done;if(!got)break;n+=(size_t)got;}
    rc=nf_boot_parse(buf,n,out);
 done: close(fd);return rc;
}
static bool nf_names_view_absent(const struct system_rule_view *v,bool nat_only) {
    if(!v || !v->present || !v->bytes ||  v->length<3 || v->bytes[v->length-1]!='\n' || memchr(v->bytes,0,v->length))return false;
    // A complete table listing must contain at least one builtin policy.
    if(strncmp(v->bytes,"-P ",3))return false;
    for(size_t i=nat_only?2U:0U;i<sizeof(nf_chains)/sizeof(nf_chains[0]);i++)
        if(memmem(v->bytes,v->length,nf_chains[i].name,strlen(nf_chains[i].name)))return false;
    return true;
}
static int nf_live_names_absence(void *ctx,bool nat_only) {
    struct asteriskd_system_supervisor *s=ctx;
    if(!nf_native_available(s))return -1;
    for(unsigned f=0;f<ASTERISKD_IP_FAMILY_COUNT;f++)for(unsigned t=0;t<ASTERISKD_IP_TABLE_COUNT;t++) {
        struct system_rule_view v={0};
        const bool nat6=f==ASTERISKD_IP_FAMILY_IPV6 && t==ASTERISKD_IP_TABLE_NAT;
        int rc=nat6?nf_nat6_view(s,&v):asteriskd_supervisor_rule_view_capture_table(s,f,t,&v);
        // Only the explicitly recognized legacy NAT6 absence is optional.
        // All other tables and ambiguous failures remain mandatory.
        bool absent=(nat6 && rc==1) || (!rc && nf_names_view_absent(&v,nat_only));
        asteriskd_supervisor_rule_view_destroy(&v);if(!absent)return -1;
    }
    return 0;
}
static int nf_reboot_live_absence(void *ctx) {return nf_live_names_absence(ctx,false);}
/* A marked live session may drain only while the exact closed guard is
 * still installed and all NAT names are absent. Do not infer a live lease
 * from recovered WAL metadata. Recheck before each mutation. */
extern int nf_gateway_ct_drain(uint32_t mark,uint32_t mask,uint16_t port,uint16_t zone,
                               int (*check)(void *),void *ctx);
static bool nf_drain_guard_closed(const struct nf_native_backend *b) {
    if(!b || !b->wal_ready || !b->guard.owned || !b->guard.expected_valid || b->guard.pending_valid ||
       b->guard.plan.private_group_count!=2 || b->nat.owned || b->nat.plan.private_group_count ||
       (b->wal.debts&31U)!=31U || !b->wal.epoch)return false;
    for(size_t i=0;i<2;i++) {
        const struct asteriskd_byte_buffer *v=&b->guard.expected[i];
        if(!v->bytes || !v->length || memmem(v->bytes,v->length,"-j ACCEPT",9) ||
           !nf_recovery_image(v->bytes,v->length,b->guard.plan.private_groups[i].names[0],true,b->wal.port,b->session_mark,b->session_mask))return false;
    }
    return true;
}
static int nf_native_drain_check(void *ctx) {
    struct nf_native_backend *b=ctx;
    if(!nf_drain_guard_closed(b) || !b->session_mask || nf_native_mark_recheck(b) ||
       nf_recovery_preflight(b,&b->guard) || nf_native_verify(b->system,&b->guard,false) ||
       nf_live_names_absence(b->system,true))return -1;
    return nf_native_mark_recheck(b);
}
int nf_gateway_native_drain_marked(struct nf_native_backend *b) {
    if(nf_native_drain_check(b))return -1;
    // Default zone only; inventory rejects collisions in any other zone.
    return nf_gateway_ct_drain(b->session_mark,b->session_mask,b->wal.port,0,nf_native_drain_check,b)==0?0:-1;
}

#ifdef NF_REBOOT_HOST_TEST
static bool nf_reboot_view_absent(const struct system_rule_view *v) {return nf_names_view_absent(v,false);}
#endif
/* Different kernel boot means old processes and in-memory conntrack cannot
 * survive. Still require the old journal to decode and all fixed resources
 * to be absent; never delete current kernel objects by stale ownership. */
static int nf_reboot_reconcile(int directory,const uint8_t boot[16],int (*absence)(void *),void *ctx) {
    if(!boot || !nf_nonzero(boot,16) || !absence)return -1;
    int lock=nf_gateway_wal_lock(directory);if(lock<0)return -1;
    struct stat st;int rc=-1;
    struct nf_native_backend *b=calloc(1,sizeof(*b));uint8_t *payload=malloc(NF_WAL_MAX);
    struct nf_wal_record r;
    if(!b || !payload || fstatat(lock,NF_WAL_TEMP,&st,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT ||
       nf_gateway_wal_load(lock,&r,payload,NF_WAL_MAX) || !r.debts || !memcmp(boot,r.boot,16))goto done;
    b->wal_fd=lock;
    if(nf_gateway_recovery_load(b,r.boot) || absence(ctx))goto done;
    if(r.generation==UINT64_MAX)goto done;
    // Retain old boot/session for the compare-and-swap; a subsequent clean
    // record permits a new session identity. No kernel mutation is performed.
    r.generation++;r.epoch=0;r.debts=0;r.payload=NULL;r.length=0;
    rc=nf_gateway_wal_save(lock,&r,r.generation-1);
 done:
    if(b){nf_native_clear(&b->guard);nf_native_clear(&b->nat);free(b);}
    free(payload);close(lock);return rc;
}
/* Only an explicitly certified pre-spawn checkpoint can be recovered in
 * the same boot without durable child/flow identities. Old NFFW01 records
 * (byte 7 = 0) never qualify. No NAT was published in this state. */
static bool nf_closed_guard_only(const struct nf_native_backend *b) {
    if(!b->guard.owned ||
       b->nat.owned || b->nat.plan.private_group_count)return false;
    for(size_t i=0;i<b->guard.plan.private_group_count;i++) {
        const struct asteriskd_byte_buffer *v[]={&b->guard.expected[i],&b->guard.pending[i]};
        for(size_t j=0;j<2;j++)if(v[j]->length && memmem(v[j]->bytes,v[j]->length,"-j ACCEPT",9))return false;
    }
    return true;
}
static bool nf_precore_safe(const struct nf_native_backend *b) {
    return b->precore_certified && b->wal.debts==3U && !b->wal.epoch && nf_closed_guard_only(b);
}
/* No signalling of a live or reused PID. The entire old process group and
 * DNS port must be absent; a same-boot record with any NAT/flow debt is
 * explicitly excluded, even if the old process has gone. */
extern int nf_gateway_port_absent(unsigned port);
static bool nf_core_gone(const struct nf_core_evidence *e) {
    if(!nf_core_evidence_valid(e))return false;
    if(kill((pid_t)e->pid,0)==0 || errno!=ESRCH)return false;
    if(kill(-(pid_t)e->group,0)==0 || errno!=ESRCH)return false;
    return true;
}
static bool nf_postcore_safe(const struct nf_native_backend *b) {
    return !b->precore_certified && b->core_evidence_valid && b->wal.debts==7U && !b->wal.epoch && nf_closed_guard_only(b);
}
static int nf_postcore_absent(void *ctx,const struct nf_native_backend *b) {
    (void)ctx;
    return nf_core_gone(&b->core_evidence) && !nf_gateway_port_absent(b->wal.port) && nf_core_gone(&b->core_evidence)?0:-1;
}
static int nf_precore_remove(void *ctx,struct nf_native_backend *b) {
    b->system=ctx;
    if(nf_recovery_preflight(b,&b->guard) || nf_native_remove(b,&b->guard))return -1;
    return nf_reboot_live_absence(ctx);
}
static int nf_guard_only_reconcile(int directory,const uint8_t boot[16],
        int (*remove_guard)(void *,struct nf_native_backend *),
        int (*postcore_absent)(void *,const struct nf_native_backend *),void *ctx) {
    if(!boot || !remove_guard)return -1;
    int lock=nf_gateway_wal_lock(directory);if(lock<0)return -1;
    struct stat st;int rc=-1;
    struct nf_native_backend *b=calloc(1,sizeof(*b));if(!b)goto done;
    b->wal_fd=lock;
    if(fstatat(lock,NF_WAL_TEMP,&st,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT ||
       nf_gateway_recovery_load(b,boot) || b->wal.generation==UINT64_MAX)goto done;
    bool postcore=nf_postcore_safe(b);
    if(!nf_precore_safe(b) && (!postcore || !postcore_absent || postcore_absent(ctx,b)))goto done;
    if(remove_guard(ctx,b) || b->guard.owned || b->wal.generation==UINT64_MAX ||
       (postcore && postcore_absent(ctx,b)))goto done;
    struct nf_wal_record clean=b->wal;clean.generation++;clean.debts=0;clean.epoch=0;clean.payload=NULL;clean.length=0;
    rc=nf_gateway_wal_save(lock,&clean,clean.generation-1);
 done:
    if(b){nf_native_clear(&b->guard);nf_native_clear(&b->nat);free(b);}
    close(lock);return rc;
}
#ifdef NF_PRECORE_HOST_TEST
static int nf_precore_reconcile(int directory,const uint8_t boot[16],
        int (*remove_guard)(void *,struct nf_native_backend *),void *ctx) {
    return nf_guard_only_reconcile(directory,boot,remove_guard,NULL,ctx);
}
#endif
/* No conntrack DELETE or session mark is needed to reconcile an exited
 * session whose old mappings have drained. Keep the closed guard and WAL
 * while any matching port is busy or the kernel cannot be inspected. */
extern int nf_gateway_ct_port_absent(unsigned port);
/* Eligibility for an interrupted activation or removal. An epoch is
 * checkpointed before NAT is installed, so debt 7 with an epoch can be
 * legitimate. Debt 31 survives NAT withdrawal until final release.
 * Guard removal may be replayed only with closed images and no NAT plan. */
static bool nf_active_recoverable(const struct nf_native_backend *b) {
    if(!b->wal_ready || !b->core_evidence_valid || b->precore_certified ||
       !b->wal.epoch || !b->guard.owned || b->wal.generation==UINT64_MAX)return false;
    if(b->wal.debts==7U) {
        if(b->nat.owned || b->nat.plan.private_group_count ||
           (b->recovered_op!=NF_GUARD_CLOSED && b->recovered_op!=NF_GUARD_OPEN &&
            b->recovered_op!=NF_GUARD_REMOVE))return false;
    } else if(b->wal.debts!=31U)return false;
    return b->recovered_op!=NF_GUARD_REMOVE || nf_closed_guard_only(b);
}
static int nf_active_cleanup(void *ctx,struct nf_native_backend *b) {
    b->system=ctx;
    if(!nf_active_recoverable(b) || nf_postcore_absent(NULL,b) || nf_recovery_preflight(b,&b->guard) ||
       nf_recovery_preflight(b,&b->nat))return -1;
    // A crash during final guard removal may leave only one family or no
    // guard at all. Do not reopen/recreate it. Repeat all absence checks
    // before idempotent removal of the remaining exact owned images.
    if(b->recovered_op!=NF_GUARD_REMOVE &&
       (nf_native_write(b,&b->guard,b->wal.port,true,false) || nf_native_remove(b,&b->nat)))return -1;
    if(nf_live_names_absence(ctx,true) || nf_gateway_ct_port_absent(b->wal.port) ||
       nf_postcore_absent(NULL,b) || nf_gateway_ct_port_absent(b->wal.port) ||
       nf_recovery_preflight(b,&b->guard) || nf_native_remove(b,&b->guard))return -1;
    return nf_reboot_live_absence(ctx);
}
static int nf_active_reconcile(int directory,const uint8_t boot[16],
        int (*cleanup)(void *,struct nf_native_backend *),void *ctx) {
    if(!boot || !cleanup)return -1;
    int lock=nf_gateway_wal_lock(directory);if(lock<0)return -1;
    struct stat st;int rc=-1;
    struct nf_native_backend *b=calloc(1,sizeof(*b));if(!b)goto done;
    b->wal_fd=lock;
    if(fstatat(lock,NF_WAL_TEMP,&st,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT ||
       nf_gateway_recovery_load(b,boot) || !nf_active_recoverable(b))goto done;
    if(cleanup(ctx,b) || b->guard.owned || b->nat.owned || b->wal.generation==UINT64_MAX)goto done;
    struct nf_wal_record clean=b->wal;clean.generation++;clean.debts=0;clean.epoch=0;clean.payload=NULL;clean.length=0;
    rc=nf_gateway_wal_save(lock,&clean,clean.generation-1);
 done:
    if(b){nf_native_clear(&b->guard);nf_native_clear(&b->nat);free(b);}
    close(lock);return rc;
}

int nf_gateway_startup_reconcile(struct asteriskd_system_supervisor *s) {
    if(!s)return -1;
    int d=s->store.directory_fd;
    if(!nf_gateway_admission_check(d))return 0;
    uint8_t boot[16];
    if(nf_boot_read(boot))return -1;
    if(nf_reboot_reconcile(d,boot,nf_reboot_live_absence,s) && nf_guard_only_reconcile(d,boot,nf_precore_remove,nf_postcore_absent,s) &&
       nf_active_reconcile(d,boot,nf_active_cleanup,s))return -1;
    return nf_gateway_admission_check(d);
}

/* Adapter for the existing upstream core lifecycle, not a second process
 * manager. The opt-in supervisor field remains zero in app-generated configs
 * until the device-tested marked activation is ready. Native-only opt-in
 * permits a conservative unmarked NAT experiment, not targeted DELETE. */
extern int nf_gateway_socket_observe(struct asteriskd_system_supervisor *,unsigned,unsigned *);
static int nf_runtime_session_id(uint8_t id[16]) {
    int fd=open("/dev/urandom",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0)return -1;
    int rc=nf_io(fd,id,16,false);close(fd);
    return rc || !nf_nonzero(id,16)?-1:0;
}
static void nf_runtime_free(struct nf_native_backend *b) {
    if(!b)return;
    nf_native_clear(&b->guard);nf_native_clear(&b->nat);
    if(b->wal_fd>=0)close(b->wal_fd);
    free(b);
}
/* No new clean WAL is written until ownership is checkpointed. Retain the
 * previous generation for CAS; an unresolved record is never replaced. */
static int nf_runtime_init_wal(struct nf_native_backend *b,int directory,unsigned port) {
    b->wal_fd=nf_gateway_wal_lock(directory);if(b->wal_fd<0)return -1;
    struct stat st;
    if(fstatat(b->wal_fd,NF_WAL_TEMP,&st,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT)return -1;
    uint8_t *payload=malloc(NF_WAL_MAX);if(!payload)return -1;
    struct nf_wal_record old;int rc=nf_gateway_wal_load(b->wal_fd,&old,payload,NF_WAL_MAX);free(payload);
    if(rc<0 || (!rc && (old.debts || old.epoch || old.length || old.generation==UINT64_MAX)))return -1;
    b->wal=(struct nf_wal_record){.generation=rc==1?0:old.generation,.port=(uint16_t)port,.schema=1};
    if(nf_boot_read(b->wal.boot) || nf_runtime_session_id(b->wal.session))return -1;
    b->wal_ready=true;b->precore_certified=true;return 0;
}
int nf_gateway_runtime_before_core(struct asteriskd_system_supervisor *s) {
    unsigned port=s->loaded_config.config.nf_hev_gateway_dns_port;
    if(!port)return s->nf_gateway_session?-1:0;
    if(s->nf_gateway_session || !nf_valid(&s->loaded_config.config,port) ||
       !s->loaded_config.config.nf_tether_dns_hijack || !nf_native_available(s) ||
       nf_gateway_port_absent(port))return -1;
    struct nf_native_backend *b=calloc(1,sizeof(*b));if(!b)return -1;
    b->system=s;b->wal_fd=-1;
    b->ipv6_nat_disabled=true;
    if(s->loaded_config.config.enable_ipv6) {
        int capability=nf_nat6_probe(s);
        if(capability<0){nf_runtime_free(b);return -1;}
        b->ipv6_nat_disabled=capability==1;
    }
    if(nf_runtime_init_wal(b,s->store.directory_fd,port)) {nf_runtime_free(b);return -1;}
    // The real stop/release path must retain the handle even if a write fails.
    s->nf_gateway_session=b;
    if(nf_native_write(b,&b->guard,port,true,false))return -1;
    b->precore_certified=false;b->wal.debts|=4U;
    return nf_native_checkpoint(b,NF_GUARD_CLOSED);
}
int nf_gateway_runtime_core_identity(struct asteriskd_system_supervisor *s) {
    struct nf_native_backend *b=s->nf_gateway_session;
    if(!b)return s->loaded_config.config.nf_hev_gateway_dns_port?-1:0;
    return nf_native_record_core(b);
}
int nf_gateway_runtime_core_ready(struct asteriskd_system_supervisor *s) {
    struct nf_native_backend *b=s->nf_gateway_session;
    if(!b)return s->loaded_config.config.nf_hev_gateway_dns_port?-1:0;
    unsigned families=0;
    // This proves TCP and UDP listener ownership in at least one family.
    // Dual-stack reachability is deliberately not inferred from procfs.
    if(!nf_closed_guard_only(b) || nf_native_verify(s,&b->guard,false) ||
       nf_native_record_core(b) || nf_gateway_socket_observe(s,b->wal.port,&families))return -1;
    return (families&3U)==3U || (families&12U)==12U?0:-1;
}
/* Capture the actual address set, not just a reused interface name.
 * The Android caller still owns the classification of trusted downstreams.
 * Canonical ordering makes getifaddrs enumeration order irrelevant. */
static int nf_link_address_compare(const void *a,const void *b) {
    return memcmp(a,b,sizeof(struct nf_link_address));
}
static bool nf_runtime_links_equal(const struct nf_link_snapshot *a,const struct nf_link_snapshot *b) {
    if(a->count!=b->count || a->count>16U)return false;
    for(size_t i=0;i<a->count;i++) {
        const struct nf_link_identity *x=&a->links[i],*y=&b->links[i];
        if(strcmp(x->name,y->name) || x->index!=y->index || x->count!=y->count ||
           x->count>NF_LINK_ADDRESS_LIMIT ||
           memcmp(x->addresses,y->addresses,x->count*sizeof(x->addresses[0])))return false;
    }
    return true;
}
static int nf_runtime_links(struct asteriskd_system_supervisor *s,struct nf_link_snapshot *out) {
    const struct asteriskd_config *c=&s->loaded_config.config;
    if(!out || !nf_valid(c,c->nf_hev_gateway_dns_port))return -1;
    memset(out,0,sizeof(*out));
    struct ifaddrs *list=NULL;if(getifaddrs(&list))return -1;
    int rc=-1;
    for(size_t i=0;i<c->hotspot_interface_count;i++) {
        struct nf_link_identity *link=&out->links[i];
        const char *name=c->hotspot_interfaces[i];
        if(strlen(name)>=sizeof(link->name))goto done;
        strcpy(link->name,name);link->index=if_nametoindex(name);
        if(!link->index)goto done;
        bool ipv4=false;
        for(const struct ifaddrs *p=list;p;p=p->ifa_next) {
            if(!p->ifa_name || strcmp(p->ifa_name,name) || !p->ifa_addr ||
               !(p->ifa_flags&IFF_UP) || (p->ifa_flags&IFF_LOOPBACK))continue;
            int family=p->ifa_addr->sa_family;
            if(family!=AF_INET && family!=AF_INET6)continue;
            if(!p->ifa_netmask || p->ifa_netmask->sa_family!=family)goto done;
            struct nf_link_address item={0};
            item.family=family==AF_INET?4:6;
            if(family==AF_INET) {
                const struct sockaddr_in *a=(const struct sockaddr_in *)p->ifa_addr;
                const struct sockaddr_in *m=(const struct sockaddr_in *)p->ifa_netmask;
                if(a->sin_addr.s_addr==INADDR_ANY)continue;
                memcpy(item.address,&a->sin_addr,4);memcpy(item.mask,&m->sin_addr,4);
                ipv4=true;
            } else {
                const struct sockaddr_in6 *a=(const struct sockaddr_in6 *)p->ifa_addr;
                const struct sockaddr_in6 *m=(const struct sockaddr_in6 *)p->ifa_netmask;
                if(IN6_IS_ADDR_UNSPECIFIED(&a->sin6_addr))continue;
                memcpy(item.address,&a->sin6_addr,16);memcpy(item.mask,&m->sin6_addr,16);
            }
            bool duplicate=false;
            for(unsigned j=0;j<link->count;j++)
                if(!nf_link_address_compare(&item,&link->addresses[j]))duplicate=true;
            if(duplicate)continue;
            if(link->count==NF_LINK_ADDRESS_LIMIT)goto done;
            link->addresses[link->count++]=item;
        }
        if(!ipv4 || if_nametoindex(name)!=link->index)goto done;
        qsort(link->addresses,link->count,sizeof(link->addresses[0]),nf_link_address_compare);
        out->count++;
    }
    rc=0;
done:
    freeifaddrs(list);
    if(rc)memset(out,0,sizeof(*out));
    return rc;
}
/* Service Control retains the core over pause. It does not reclassify
 * downstreams while paused, so require the last trusted identity on resume.
 * Initial publication (including an empty selection) freezes that identity.
 * Never turn an unverified name reuse into a new downstream lease. */
static bool nf_service_links_match(const struct nf_native_backend *b,const struct nf_link_snapshot *current) {
    return !b->service_links_valid || nf_runtime_links_equal(current,&b->published_links);
}
/* Invoked before upstream apply_rules, including resume. Fail before
 * installing traffic hooks when an old gateway epoch cannot be drained.
 * A failed resume follows the existing lifecycle teardown, not a new core. */
int nf_gateway_runtime_prepare_rules(struct asteriskd_system_supervisor *s) {
    struct nf_native_backend *b=s->nf_gateway_session;
    if(!b)return s->loaded_config.config.nf_hev_gateway_dns_port?-1:0;
    if(!b->service_links_valid)return 0;
    struct nf_link_snapshot current;unsigned families=0;
    unsigned required=b->ipv6_nat_disabled?3U:15U;
    if(!b->wal_ready || b->links_published || b->session_mask || b->session_mark ||
       !nf_closed_guard_only(b) || nf_runtime_links(s,&current) ||
       !nf_service_links_match(b,&current) || nf_native_record_core(b) ||
       nf_native_verify(s,&b->guard,false) || nf_live_names_absence(s,true) ||
       nf_gateway_ct_port_absent(b->wal.port) ||
       nf_gateway_socket_observe(s,b->wal.port,&families) ||
       (families&required)!=required)return -1;
    return 0;
}

/* The core's regular apply_rules callback has already installed and verified
 * its routing rules. No publish unless both socket transports are observed
 * in every requested family. Never turn this observation into a mark lease. */
int nf_gateway_runtime_publish(struct asteriskd_system_supervisor *s) {
    struct nf_native_backend *b=s->nf_gateway_session;
    if(!b)return s->loaded_config.config.nf_hev_gateway_dns_port?-1:0;
    if(!b->wal_ready || b->session_mask || b->session_mark || !s->rules_verified ||
       !nf_closed_guard_only(b) || nf_native_verify(s,&b->guard,false) ||
       nf_native_record_core(b))return -1;
    struct nf_link_snapshot before,after;unsigned families=0;
    if(nf_runtime_links(s,&before) || !nf_service_links_match(b,&before))return -1;
    if(!before.count) {
        if(s->loaded_config.config.service_control.enabled) {
            b->published_links=before;b->service_links_valid=true;
        }
        return 0;
    }
    unsigned required=b->ipv6_nat_disabled?3U:15U;
    if(nf_gateway_socket_observe(s,b->wal.port,&families) ||
       (families&required)!=required || nf_live_names_absence(s,true) ||
       nf_gateway_ct_port_absent(b->wal.port) || b->wal.generation==UINT64_MAX ||
       nf_runtime_links(s,&after) || !nf_runtime_links_equal(&before,&after))return -1;
    b->wal.epoch=b->wal.generation+1;
    if(nf_native_write(b,&b->guard,b->wal.port,true,true) ||
       nf_native_write(b,&b->nat,b->wal.port,false,true) ||
       nf_native_verify(s,&b->guard,false) || nf_native_verify(s,&b->nat,false) ||
       nf_runtime_links(s,&after) || !nf_runtime_links_equal(&before,&after) ||
       nf_gateway_socket_observe(s,b->wal.port,&families) || (families&required)!=required)return -1;
    b->published_links=after;b->links_published=true;
    b->service_links_valid=s->loaded_config.config.service_control.enabled;
    return 0;
}
/* Close INPUT before withdrawing REDIRECT. Old mappings may persist:
 * retain the WAL and guard until a complete read-only drain is observed.
 * No conntrack DELETE is authorized by this experimental path. */
int nf_gateway_runtime_withdraw(struct asteriskd_system_supervisor *s) {
    struct nf_native_backend *b=s->nf_gateway_session;
    if(!b)return 0;
    if(!b->wal_ready)return -1;
    if(!b->guard.owned)return b->nat.owned?-1:0;
    if(!nf_closed_guard_only(b) &&
       nf_native_write(b,&b->guard,b->wal.port,true,false))return -1;
    if(nf_recovery_preflight(b,&b->nat) || nf_native_remove(b,&b->nat) ||
       nf_native_verify(s,&b->guard,false) || nf_live_names_absence(s,true))return -1;
    b->links_published=false;
    if(!b->service_links_valid)memset(&b->published_links,0,sizeof(b->published_links));
    return 0;
}

/* Called by the existing hot tether transaction before selector mutation.
 * A busy or unreadable conntrack table is a lifecycle failure, not an
 * excuse to open INPUT for a new epoch. No polling loop or global flush. */
int nf_gateway_runtime_refresh_begin(struct asteriskd_system_supervisor *s) {
    struct nf_native_backend *b=s->nf_gateway_session;
    if(!b)return s->loaded_config.config.nf_hev_gateway_dns_port?-1:0;
    if(nf_gateway_runtime_withdraw(s))return -1;
    return nf_gateway_ct_port_absent(b->wal.port)==0?0:-1;
}
/* Called only after the upstream selector transaction and verification.
 * A partial publication is withdrawn before reporting failure. */
int nf_gateway_runtime_refresh_finish(struct asteriskd_system_supervisor *s) {
    if(!nf_gateway_runtime_publish(s))return 0;
    (void)nf_gateway_runtime_withdraw(s);
    return -1;
}
/* The ordinary reconciliation callback checks for unnoticed link/address
 * drift as well as exact rules and owned listener identity. Never silently
 * adopt an unclassified interface after ifindex reuse. */
int nf_gateway_runtime_reconcile(struct asteriskd_system_supervisor *s) {
    struct nf_native_backend *b=s->nf_gateway_session;
    if(!b)return s->loaded_config.config.nf_hev_gateway_dns_port?-1:0;
    if(!b->wal_ready || !s->rules_verified)return -1;
    struct nf_link_snapshot current;
    unsigned families=0,required=b->ipv6_nat_disabled?3U:15U;
    bool live=b->links_published && b->nat.owned;
    if(live && !nf_runtime_links(s,&current) &&
       nf_runtime_links_equal(&current,&b->published_links) &&
       !nf_native_record_core(b) && !nf_native_verify(s,&b->guard,false) &&
       !nf_native_verify(s,&b->nat,false) &&
       !nf_gateway_socket_observe(s,b->wal.port,&families) &&
       (families&required)==required)return 0;
    if(!b->links_published && !b->nat.owned &&
       !s->loaded_config.config.hotspot_interface_count &&
       nf_closed_guard_only(b) && !nf_native_verify(s,&b->guard,false) &&
       !nf_native_record_core(b))return 0;
    (void)nf_gateway_runtime_withdraw(s);
    return -1;
}

int nf_gateway_runtime_after_core_stop(struct asteriskd_system_supervisor *s) {
    struct nf_native_backend *b=s->nf_gateway_session;if(!b)return 0;
    if(!b->wal_ready || b->nat.owned || b->nat.plan.private_group_count ||
       (s->core_spawned && !s->core_reaped) ||
       (s->core_spawned && !b->core_evidence_valid) ||
       (b->core_evidence_valid && !nf_core_gone(&b->core_evidence)))return -1;
    if(!b->wal.debts && !b->guard.owned) {
        // No owned mutation was committed. Never clear another owner's rules.
        nf_runtime_free(b);s->nf_gateway_session=NULL;return 0;
    }
    if((b->guard.owned && !nf_closed_guard_only(b)) ||
       nf_gateway_port_absent(b->wal.port) || nf_live_names_absence(s,true) ||
       nf_gateway_ct_port_absent(b->wal.port) || nf_recovery_preflight(b,&b->guard) ||
       nf_native_remove(b,&b->guard) || nf_reboot_live_absence(s) ||
       nf_gateway_port_absent(b->wal.port) || b->wal.generation==UINT64_MAX)return -1;
    struct nf_wal_record clean=b->wal;clean.generation++;clean.debts=0;clean.epoch=0;clean.length=0;clean.payload=NULL;
    if(nf_gateway_wal_save(b->wal_fd,&clean,b->wal.generation)) {b->wal_ready=false;return -1;}
    nf_runtime_free(b);s->nf_gateway_session=NULL;return 0;
}
/* Destruction never claims cleanup success. An unresolved WAL survives. */
void nf_gateway_runtime_dispose(struct asteriskd_system_supervisor *s) {
    nf_runtime_free(s->nf_gateway_session);s->nf_gateway_session=NULL;
}

#ifdef NF_RECOVERY_HOST_TEST
static unsigned checks;
#define CHECK(x) do{if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
int main(void) {
    const char *closed="-A NF_HEV_DNS4_IN -p udp -m udp --dport 15353 -j DROP\n-A NF_HEV_DNS4_IN -p tcp -m tcp --dport 15353 -j DROP\n";
    CHECK(nf_recovery_image((const uint8_t*)closed,strlen(closed),"NF_HEV_DNS4_IN",true,15353,0,0));
    CHECK(!nf_recovery_image((const uint8_t*)closed,strlen(closed),"NF_HEV_DNS6_IN",true,15353,0,0));
    CHECK(!nf_recovery_image((const uint8_t*)closed,strlen(closed),"NF_HEV_DNS4_IN",true,15354,0,0));
    CHECK(!nf_recovery_image((const uint8_t*)closed,strlen(closed)-1,"NF_HEV_DNS4_IN",true,15353,0,0));
    CHECK(!nf_recovery_image((const uint8_t*)closed,0,"NF_HEV_DNS4_IN",true,15353,0,0));
    char path[]="/tmp/nf-recovery-XXXXXX";CHECK(mkdtemp(path)!=NULL);int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));struct nf_native_backend *a=calloc(1,sizeof(*a)),*b=calloc(1,sizeof(*b));CHECK(s&&a&&b);
    struct asteriskd_config *c=&s->loaded_config.config;c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;c->enable_ipv6=true;
    strcpy(c->hotspot_interfaces[0],"ap0");c->hotspot_interface_count=1;
    a->system=s;a->wal_fd=nf_gateway_wal_lock(d);CHECK(a->wal_fd>=0);a->wal_ready=true;a->wal=(struct nf_wal_record){.boot={1},.session={2},.port=15353,.epoch=5};
    CHECK(!nf_gateway_plan(c,15353,true,&a->guard.plan));CHECK(!nf_native_render(s,&a->guard,15353,false,0,0,false));a->guard.pending_valid=a->guard.owned=true;
    CHECK(!nf_gateway_plan(c,15353,false,&a->nat.plan));CHECK(!nf_native_render(s,&a->nat,15353,true,0,0,false));a->nat.pending_valid=a->nat.owned=true;
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
#ifdef NF_ADMISSION_HOST_TEST
#include <stdio.h>
static unsigned checks;
#define CHECK(x) do { if(!(x)) { fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); exit(1); } ++checks; } while(0)
int main(void) {
    char path[]="/tmp/nf-admission-XXXXXX";CHECK(mkdtemp(path)!=NULL);
    int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);
    CHECK(nf_gateway_admission_check(-1)<0);
    CHECK(!nf_gateway_admission_check(d));
    int temp=openat(d,NF_WAL_TEMP,O_CREAT|O_EXCL|O_WRONLY,0600);CHECK(temp>=0);close(temp);
    CHECK(nf_gateway_admission_check(d)<0);CHECK(!unlinkat(d,NF_WAL_TEMP,0));
    CHECK(!symlinkat("missing",d,NF_WAL_NAME));CHECK(nf_gateway_admission_check(d)<0);CHECK(!unlinkat(d,NF_WAL_NAME,0));
    int lock=nf_gateway_wal_lock(d);CHECK(lock>=0);
    uint8_t raw[]={1};struct nf_wal_record r={.generation=1,.epoch=1,.boot={1},.session={2},.port=15353,.debts=31,.schema=1,.payload=raw,.length=1};
    CHECK(!nf_gateway_wal_save(lock,&r,0));CHECK(nf_gateway_admission_check(d)<0);close(lock);
    CHECK(nf_gateway_admission_check(d)<0);
    lock=nf_gateway_wal_lock(d);CHECK(lock>=0);r.generation++;r.debts=0;r.epoch=0;r.payload=NULL;r.length=0;
    CHECK(!nf_gateway_wal_save(lock,&r,1));CHECK(nf_gateway_admission_check(d)<0);close(lock);
    CHECK(!nf_gateway_admission_check(d));
    temp=openat(d,NF_WAL_TEMP,O_CREAT|O_EXCL|O_WRONLY,0600);CHECK(temp>=0);close(temp);
    CHECK(nf_gateway_admission_check(d)<0);CHECK(!unlinkat(d,NF_WAL_TEMP,0));
    int f=openat(d,NF_WAL_NAME,O_RDWR);CHECK(f>=0);CHECK(pwrite(f,"X",1,0)==1);close(f);
    CHECK(nf_gateway_admission_check(d)<0);CHECK(!unlinkat(d,NF_WAL_NAME,0));
    CHECK(!nf_gateway_admission_check(d));close(d);CHECK(!rmdir(path));
    printf("PASS %u admission checks\n",checks);return 0;
}
#endif
#ifdef NF_REBOOT_HOST_TEST
static unsigned checks;
#define CHECK(x) do {if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
struct absence_fake {int rc;unsigned calls;};
static int fake_absence(void *p) {struct absence_fake *f=p;f->calls++;return f->rc;}
int main(void) {
    uint8_t boot[16],actual[16];const char *id="12345678-90ab-cdef-1234-567890abcdef\n";
    CHECK(!nf_boot_parse(id,strlen(id),boot) && boot[0]==0x12 && boot[15]==0xef);
    CHECK(nf_boot_parse(id,36,actual)<0);CHECK(nf_boot_parse("00000000-0000-0000-0000-000000000000\n",37,actual)<0);
    CHECK(nf_boot_parse("12345678-90ab-cdef-1234-567890abcdeg\n",37,actual)<0);CHECK(!nf_boot_read(actual));
    const char *clean="-P INPUT ACCEPT\n-P FORWARD DROP\n";
    struct system_rule_view view={.bytes=(char*)clean,.length=strlen(clean),.present=true};CHECK(nf_reboot_view_absent(&view));
    view.bytes="-P INPUT ACCEPT\n-N NF_HEV_DNS4_IN\n";view.length=strlen(view.bytes);CHECK(!nf_reboot_view_absent(&view));
    view.bytes="-P INPUT ACCEPT\n-A INPUT -g NF_HEV_DNS6_PRE\n";view.length=strlen(view.bytes);CHECK(!nf_reboot_view_absent(&view));
    view.bytes="-P INPUT ACCEPT";view.length=strlen(view.bytes);CHECK(!nf_reboot_view_absent(&view));
    view.bytes="x\n";view.length=2;CHECK(!nf_reboot_view_absent(&view));
    char path[]="/tmp/nf-reboot-XXXXXX";CHECK(mkdtemp(path));int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));struct nf_native_backend *b=calloc(1,sizeof(*b));CHECK(s&&b);
    struct asteriskd_config *c=&s->loaded_config.config;c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;
    b->system=s;b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);b->wal_ready=true;b->wal=(struct nf_wal_record){.boot={1},.session={2},.port=15353};
    CHECK(!nf_gateway_plan(c,15353,true,&b->guard.plan));CHECK(!nf_native_render(s,&b->guard,15353,false,0,0,false));b->guard.pending_valid=b->guard.owned=true;
    CHECK(!nf_native_checkpoint(b,NF_GUARD_CLOSED));close(b->wal_fd);
    struct absence_fake f={.rc=-1};uint8_t same[16]={1};
    CHECK(nf_reboot_reconcile(d,same,fake_absence,&f)<0 && f.calls==0);
    CHECK(nf_reboot_reconcile(d,boot,fake_absence,&f)<0 && f.calls==1);CHECK(nf_gateway_admission_check(d)<0);
    f.rc=0;int lock=nf_gateway_wal_lock(d);CHECK(lock>=0);CHECK(nf_reboot_reconcile(d,boot,fake_absence,&f)<0 && f.calls==1);close(lock);
    int t=openat(d,NF_WAL_TEMP,O_WRONLY|O_CREAT|O_EXCL,0600);CHECK(t>=0);close(t);CHECK(nf_reboot_reconcile(d,boot,fake_absence,&f)<0 && f.calls==1);CHECK(!unlinkat(d,NF_WAL_TEMP,0));
    CHECK(!nf_reboot_reconcile(d,boot,fake_absence,&f) && f.calls==2);CHECK(!nf_gateway_admission_check(d));
    uint8_t raw[256];struct nf_wal_record r;CHECK(!nf_gateway_wal_load(d,&r,raw,sizeof(raw)) && r.generation==2 && !r.debts && !r.epoch && !r.length);
    CHECK(nf_reboot_reconcile(d,boot,fake_absence,&f)<0 && f.calls==2);
    CHECK(!unlinkat(d,NF_WAL_NAME,0));close(d);CHECK(!rmdir(path));nf_native_clear(&b->guard);free(b);free(s);
    printf("PASS %u reboot recovery checks\n",checks);return 0;
}
#endif
#ifdef NF_PRECORE_HOST_TEST
static unsigned checks;
#define CHECK(x) do {if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
struct pre_fake {struct nf_native_backend *b;unsigned starts,removes;bool fail;};
static int pre_step(void *ctx,enum nf_step op,unsigned port,uint64_t epoch) {
    struct pre_fake *f=ctx;(void)port;(void)epoch;
    if(op==NF_RESERVE)return 0;
    if(op==NF_START_CORE) {
        uint8_t raw[4096];struct nf_wal_record r;
        if(nf_gateway_wal_load(f->b->wal_fd,&r,raw,sizeof(raw)) || !(r.debts&4U) || raw[7])return -1;
        f->starts++;return 0;
    }
    return -1;
}
static int pre_remove_fake(void *ctx,struct nf_native_backend *b) {
    struct pre_fake *f=ctx;f->removes++;
    if(f->fail)return -1;
    nf_native_clear(&b->guard);return 0;
}
int main(void) {
    char path[]="/tmp/nf-precore-XXXXXX";CHECK(mkdtemp(path));int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));struct nf_native_backend *b=calloc(1,sizeof(*b));CHECK(s&&b);
    struct asteriskd_config *c=&s->loaded_config.config;c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;
    b->system=s;b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);b->wal_ready=true;b->wal=(struct nf_wal_record){.boot={1},.session={2},.port=15353};
    struct pre_fake f={.b=b};b->lifecycle_operations=(struct nf_backend){&f,pre_step};
    CHECK(!nf_gateway_native_step(b,NF_RESERVE,15353,0) && b->precore_certified);
    CHECK(!nf_gateway_plan(c,15353,true,&b->guard.plan));CHECK(!nf_native_render(s,&b->guard,15353,false,0,0,false));b->guard.pending_valid=b->guard.owned=true;
    CHECK(!nf_native_checkpoint(b,NF_GUARD_CLOSED));CHECK(nf_precore_safe(b));
    CHECK(nf_gateway_native_step(b,NF_GUARD_OPEN,15353,0)<0);CHECK(nf_gateway_native_step(b,NF_NAT_INSTALL,15353,0)<0);
    uint8_t boot[16]={1},wrong[16]={3};close(b->wal_fd);
    CHECK(nf_precore_reconcile(d,wrong,pre_remove_fake,&f)<0 && !f.removes);
    f.fail=true;CHECK(nf_precore_reconcile(d,boot,pre_remove_fake,&f)<0 && f.removes==1);CHECK(nf_gateway_admission_check(d)<0);
    b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);
    CHECK(!nf_gateway_native_step(b,NF_START_CORE,15353,0) && f.starts==1 && !b->precore_certified && (b->wal.debts&4U));close(b->wal_fd);
    CHECK(nf_precore_reconcile(d,boot,pre_remove_fake,&f)<0 && f.removes==1);
    CHECK(!unlinkat(d,NF_WAL_NAME,0));b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);b->wal.generation=0;b->wal.debts=0;
    CHECK(!nf_native_checkpoint(b,NF_GUARD_CLOSED));close(b->wal_fd);
    CHECK(nf_precore_reconcile(d,boot,pre_remove_fake,&f)<0 && f.removes==1); // old format
    CHECK(!unlinkat(d,NF_WAL_NAME,0));b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);b->wal.generation=0;b->wal.debts=0;b->precore_certified=true;
    CHECK(!nf_native_checkpoint(b,NF_GUARD_CLOSED));close(b->wal_fd);f.fail=false;
    CHECK(!nf_precore_reconcile(d,boot,pre_remove_fake,&f) && f.removes==2);CHECK(!nf_gateway_admission_check(d));
    CHECK(!unlinkat(d,NF_WAL_NAME,0));close(d);CHECK(!rmdir(path));nf_native_clear(&b->guard);free(b);free(s);
    printf("PASS %u pre-core recovery checks\n",checks);return 0;
}
#endif
#ifdef NF_POSTCORE_HOST_TEST
#include <sys/wait.h>
#include <sys/socket.h>
#include <netinet/in.h>
static unsigned checks;
#define CHECK(x) do{if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
static int remove_fake(void *ctx,struct nf_native_backend *b) {
    unsigned *n=ctx;(*n)++;nf_native_clear(&b->guard);return 0;
}
int main(void) {
    char path[]="/tmp/nf-postcore-XXXXXX";CHECK(mkdtemp(path));int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));struct nf_native_backend *b=calloc(1,sizeof(*b));CHECK(s&&b);
    struct asteriskd_config *c=&s->loaded_config.config;c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;
    int fd=socket(AF_INET,SOCK_STREAM,0);CHECK(fd>=0);struct sockaddr_in addr={.sin_family=AF_INET};socklen_t len=sizeof(addr);
    CHECK(!bind(fd,(void*)&addr,sizeof(addr)) && !listen(fd,1) && !getsockname(fd,(void*)&addr,&len));unsigned port=ntohs(addr.sin_port);CHECK(port>=1024);
    b->system=s;b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);b->wal_ready=true;b->wal=(struct nf_wal_record){.boot={1},.session={2},.port=(uint16_t)port,.debts=4|1};
    CHECK(!nf_gateway_plan(c,port,true,&b->guard.plan));CHECK(!nf_native_render(s,&b->guard,port,false,0,0,false));b->guard.pending_valid=b->guard.owned=true;
    CHECK(nf_native_record_core(b)<0);CHECK(!nf_native_checkpoint(b,NF_GUARD_CLOSED));
    pid_t pid=fork();CHECK(pid>=0);
    if(!pid){close(fd);if(setsid()<0)_exit(2);execl("/bin/sleep","/bin/sleep","60",(char*)NULL);_exit(3);}
    struct asteriskd_process_spec *spec=&s->core_spec;strcpy(spec->argv[0],"/bin/sleep");strcpy(spec->argv[1],"60");spec->argc=2;
    int found=-1;char error[128];for(unsigned i=0;i<200;i++) {found=asteriskd_process_identity_read(asteriskd_system_process_identity_backend(),pid,ASTERISKD_CHILD_CORE,ASTERISKD_CHILD_TYPE_SING_BOX,spec,&s->core_identity,error,sizeof(error));if(!found)break;usleep(10000);}
    CHECK(!found);s->core_spawned=s->core_identity_ready=s->core_spec_ready=true;s->core_process.pid=pid;
    CHECK(!nf_native_record_core(b) && b->core_evidence_valid && b->wal.generation==2);CHECK(!nf_native_record_core(b) && b->wal.generation==2);
    CHECK(nf_postcore_safe(b) && !nf_core_gone(&b->core_evidence));
    uint8_t boot[16]={1};unsigned removes=0;close(b->wal_fd);
    CHECK(nf_guard_only_reconcile(d,boot,remove_fake,nf_postcore_absent,&removes)<0 && !removes);
    CHECK(!kill(pid,SIGTERM));int status;CHECK(waitpid(pid,&status,0)==pid);CHECK(nf_core_gone(&b->core_evidence));
    CHECK(nf_guard_only_reconcile(d,boot,remove_fake,nf_postcore_absent,&removes)<0 && !removes); // port occupied
    close(fd);CHECK(!nf_gateway_port_absent(port));
    b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);b->wal.debts|=24U;b->wal.epoch=3;CHECK(!nf_native_checkpoint(b,NF_GUARD_CLOSED));close(b->wal_fd);
    CHECK(nf_guard_only_reconcile(d,boot,remove_fake,nf_postcore_absent,&removes)<0 && !removes); // flow debt
    CHECK(!unlinkat(d,NF_WAL_NAME,0));b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);b->wal.generation=0;b->wal.debts=7;b->wal.epoch=0;CHECK(!nf_native_checkpoint(b,NF_GUARD_CLOSED));close(b->wal_fd);
    CHECK(!nf_guard_only_reconcile(d,boot,remove_fake,nf_postcore_absent,&removes) && removes==1);CHECK(!nf_gateway_admission_check(d));
    CHECK(!unlinkat(d,NF_WAL_NAME,0));close(d);CHECK(!rmdir(path));nf_native_clear(&b->guard);free(b);free(s);
    printf("PASS %u post-core recovery checks\n",checks);return 0;
}
#endif
#ifdef NF_ACTIVE_HOST_TEST
static unsigned checks;
#define CHECK(x) do{if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
struct active_fake {unsigned calls;bool busy;};
static int active_fake_cleanup(void *ctx,struct nf_native_backend *b) {
    struct active_fake *f=ctx;f->calls++;
    if(!b->guard.owned || !b->nat.owned)return -1;
    if(f->busy)return -1;
    nf_native_clear(&b->nat);nf_native_clear(&b->guard);return 0;
}
int main(void) {
    char path[]="/tmp/nf-active-XXXXXX";CHECK(mkdtemp(path));int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));struct nf_native_backend *b=calloc(1,sizeof(*b));CHECK(s&&b);
    struct asteriskd_config *c=&s->loaded_config.config;c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;
    c->enable_ipv6=true;strcpy(c->hotspot_interfaces[0],"ap0");c->hotspot_interface_count=1;
    b->system=s;b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);b->wal_ready=true;
    b->wal=(struct nf_wal_record){.boot={1},.session={2},.port=15353,.epoch=5,.debts=5};
    b->core_evidence_valid=true;b->core_evidence=(struct nf_core_evidence){.pid=100,.group=100,.start=10,.device=1,.inode=2};
    CHECK(!nf_gateway_plan(c,15353,true,&b->guard.plan));CHECK(!nf_native_render(s,&b->guard,15353,true,0,0,false));b->guard.pending_valid=b->guard.owned=true;
    CHECK(!nf_gateway_plan(c,15353,false,&b->nat.plan));CHECK(!nf_native_render(s,&b->nat,15353,true,0,0,false));b->nat.pending_valid=b->nat.owned=true;
    CHECK(!nf_native_checkpoint(b,NF_NAT_INSTALL));close(b->wal_fd);
    // Exercise the interrupted activation and final removal eligibility.
    b->recovered_op=NF_NAT_INSTALL;CHECK(nf_active_recoverable(b));
    b->wal.debts=7;CHECK(!nf_active_recoverable(b));b->wal.debts=31;
    b->recovered_op=NF_GUARD_REMOVE;CHECK(!nf_active_recoverable(b));
    struct nf_native_backend *edge=calloc(1,sizeof(*edge));CHECK(edge);
    edge->system=s;edge->wal_ready=true;edge->wal=b->wal;edge->wal.debts=7;
    edge->core_evidence_valid=true;edge->recovered_op=NF_GUARD_OPEN;
    CHECK(!nf_gateway_plan(c,15353,true,&edge->guard.plan));
    CHECK(!nf_native_render(s,&edge->guard,15353,true,0,0,false));edge->guard.owned=edge->guard.pending_valid=true;
    CHECK(nf_active_recoverable(edge));
    edge->recovered_op=NF_GUARD_REMOVE;CHECK(!nf_active_recoverable(edge));
    nf_native_clear(&edge->guard);CHECK(!nf_gateway_plan(c,15353,true,&edge->guard.plan));
    CHECK(!nf_native_render(s,&edge->guard,15353,false,0,0,false));edge->guard.owned=edge->guard.pending_valid=true;
    CHECK(nf_active_recoverable(edge));edge->wal.debts=31;CHECK(nf_active_recoverable(edge));
    edge->wal.epoch=0;CHECK(!nf_active_recoverable(edge));
    nf_native_clear(&edge->guard);free(edge);
    struct active_fake f={.busy=true};uint8_t boot[16]={1},wrong[16]={2};
    CHECK(nf_active_reconcile(d,wrong,active_fake_cleanup,&f)<0 && !f.calls);
    CHECK(nf_active_reconcile(d,boot,active_fake_cleanup,&f)<0 && f.calls==1);CHECK(nf_gateway_admission_check(d)<0);
    uint8_t raw[4096];struct nf_wal_record rec;CHECK(!nf_gateway_wal_load(d,&rec,raw,sizeof(raw)) && rec.debts==31 && rec.generation==1);
    // Closing the recovered guard is independent of the next selected mode.
    struct nf_native_slot closed={0};closed.plan=b->guard.plan;memset(c,0,sizeof(*c));
    CHECK(!nf_native_render(s,&closed,15353,false,0,0,false));CHECK(closed.pending[0].length && !memmem(closed.pending[0].bytes,closed.pending[0].length,"ACCEPT",6));nf_native_clear(&closed);
    f.busy=false;CHECK(!nf_active_reconcile(d,boot,active_fake_cleanup,&f) && f.calls==2);CHECK(!nf_gateway_admission_check(d));
    CHECK(!nf_gateway_wal_load(d,&rec,raw,sizeof(raw)) && rec.generation==2 && !rec.debts);
    CHECK(!unlinkat(d,NF_WAL_NAME,0));close(d);CHECK(!rmdir(path));nf_native_clear(&b->guard);nf_native_clear(&b->nat);free(b);free(s);
    printf("PASS %u active recovery checks\n",checks);return 0;
}
#endif
#ifdef NF_MARK_WAL_HOST_TEST
static unsigned checks;
#define CHECK(x) do{if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
int main(void) {
    char path[]="/tmp/nf-mark-wal-XXXXXX";CHECK(mkdtemp(path));int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));struct nf_native_backend *a=calloc(1,sizeof(*a)),*b=calloc(1,sizeof(*b));CHECK(s&&a&&b);
    struct asteriskd_config *c=&s->loaded_config.config;c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;c->enable_ipv6=true;
    strcpy(c->hotspot_interfaces[0],"ap0");c->hotspot_interface_count=1;
    a->system=s;a->wal_fd=nf_gateway_wal_lock(d);CHECK(a->wal_fd>=0);a->wal_ready=true;a->wal=(struct nf_wal_record){.boot={1},.session={2},.port=15353};
    uint32_t mark=0x100000,mask=0x300000;
    CHECK(nf_gateway_native_bind_mark(a,mark,mask,false)<0);CHECK(nf_gateway_native_bind_mark(a,1,1,true)<0);
    CHECK(!nf_gateway_native_bind_mark(a,mark,mask,true));CHECK(nf_gateway_native_bind_mark(a,0x200000,mask,true)<0);
    CHECK(!nf_gateway_plan(c,15353,true,&a->guard.plan));CHECK(!nf_gateway_plan(c,15353,false,&a->nat.plan));
    CHECK(nf_native_render(s,&a->guard,15353,true,mark,mask,false)<0 && !a->guard.pending[0].length);
    CHECK(!nf_native_render(s,&a->guard,15353,true,mark,mask,true));a->guard.pending_valid=a->guard.owned=true;
    CHECK(!nf_native_render(s,&a->nat,15353,true,mark,mask,true));a->nat.pending_valid=a->nat.owned=true;
    const struct asteriskd_byte_buffer *nat=&a->nat.pending[0];const char *name=a->nat.plan.private_groups[0].names[0];
    CHECK(nf_recovery_image(nat->bytes,nat->length,name,false,15353,mark,mask));
    CHECK(!nf_recovery_image(nat->bytes,nat->length,name,false,15353,0,0));
    CHECK(!nf_recovery_image(nat->bytes,nat->length,name,false,15353,0x200000,mask));
    const uint8_t *newline=memchr(nat->bytes,'\n',nat->length);CHECK(newline!=NULL);size_t first=(size_t)(newline-nat->bytes)+1;
    CHECK(!nf_recovery_image(nat->bytes,first,name,false,15353,mark,mask));
    CHECK(!nf_recovery_image(nat->bytes+first,nat->length-first,name,false,15353,mark,mask));
    a->wal.epoch=5;a->wal.debts=5;a->core_evidence_valid=true;a->core_evidence=(struct nf_core_evidence){.pid=100,.group=100,.start=10,.device=1,.inode=2};
    CHECK(!nf_native_checkpoint(a,NF_NAT_INSTALL));close(a->wal_fd);
    b->system=s;b->wal_fd=nf_gateway_wal_lock(d);CHECK(b->wal_fd>=0);uint8_t boot[16]={1};
    CHECK(!nf_gateway_recovery_load(b,boot) && b->session_mark==mark && b->session_mask==mask && !b->mark_authorized && b->core_evidence_valid);
    CHECK(b->guard.owned && b->nat.owned && b->wal.debts==31);
    struct nf_native_slot closed={.plan=b->guard.plan};memset(c,0,sizeof(*c));
    CHECK(!nf_native_render(s,&closed,15353,false,mark,mask,false));CHECK(!memmem(closed.pending[0].bytes,closed.pending[0].length,"ACCEPT",6));nf_native_clear(&closed);
    uint8_t *raw=malloc(NF_WAL_MAX);struct nf_wal_record rec;CHECK(raw!=NULL);CHECK(!nf_gateway_wal_load(b->wal_fd,&rec,raw,NF_WAL_MAX));
    CHECK(raw[7]==6 && nf_get(raw+8,4)==mark && nf_get(raw+12,4)==mask);
    nf_put(raw+8,4,0x200000);rec.generation++;CHECK(!nf_gateway_wal_save(b->wal_fd,&rec,rec.generation-1));
    nf_native_clear(&b->guard);nf_native_clear(&b->nat);b->session_mark=b->session_mask=0;b->wal_ready=false;b->core_evidence_valid=false;
    CHECK(nf_gateway_recovery_load(b,boot)<0 && !b->wal_ready && !b->session_mask);
    CHECK(!unlinkat(b->wal_fd,NF_WAL_NAME,0));close(b->wal_fd);close(d);CHECK(!rmdir(path));nf_native_clear(&a->guard);nf_native_clear(&a->nat);free(raw);free(a);free(b);free(s);
    printf("PASS %u marked WAL checks\n",checks);return 0;
}
#endif
#ifdef NF_DRAIN_GUARD_HOST_TEST
static unsigned checks;
#define CHECK(x) do{if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
static int check_mark(void *ctx,uint32_t mark,uint32_t mask) {
    return *(bool *)ctx && mark==0x100000 && mask==0x300000?0:-1;
}
int main(void) {
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));struct nf_native_backend *b=calloc(1,sizeof(*b));CHECK(s&&b);
    struct asteriskd_config *c=&s->loaded_config.config;c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;c->enable_ipv6=true;
    strcpy(c->hotspot_interfaces[0],"ap0");c->hotspot_interface_count=1;
    b->system=s;b->wal_ready=true;b->wal.port=15353;bool authorized=true;
    CHECK(!nf_gateway_native_bind_mark(b,0x100000,0x300000,true));CHECK(nf_native_mark_recheck(b)<0);
    CHECK(!nf_gateway_native_set_mark_recheck(b,check_mark,&authorized));CHECK(!nf_native_mark_recheck(b));
    CHECK(nf_gateway_native_set_mark_recheck(b,check_mark,&authorized)<0);authorized=false;CHECK(nf_native_mark_recheck(b)<0);authorized=true;
    CHECK(!nf_gateway_plan(c,15353,true,&b->guard.plan));CHECK(!nf_native_render(s,&b->guard,15353,false,b->session_mark,b->session_mask,true));
    b->guard.owned=b->guard.expected_valid=true;b->wal.debts=31;b->wal.epoch=1;
    for(size_t i=0;i<2;i++){b->guard.expected[i]=b->guard.pending[i];memset(&b->guard.pending[i],0,sizeof(b->guard.pending[i]));}
    CHECK(nf_drain_guard_closed(b));b->nat.owned=true;CHECK(!nf_drain_guard_closed(b));b->nat.owned=false;
    b->guard.pending_valid=true;CHECK(!nf_drain_guard_closed(b));b->guard.pending_valid=false;
    b->wal.epoch=0;CHECK(!nf_drain_guard_closed(b));b->wal.epoch=1;
    b->wal.debts=7;CHECK(!nf_drain_guard_closed(b));b->wal.debts=31;
    authorized=false;CHECK(nf_native_drain_check(b)<0);authorized=true;
    b->mark_authorized=false;CHECK(nf_gateway_native_drain_marked(b)<0);b->mark_authorized=true;
    CHECK(!nf_native_render(s,&b->guard,15353,true,b->session_mark,b->session_mask,true));
    for(size_t i=0;i<2;i++){asteriskd_byte_buffer_destroy(&b->guard.expected[i]);b->guard.expected[i]=b->guard.pending[i];memset(&b->guard.pending[i],0,sizeof(b->guard.pending[i]));}
    CHECK(!nf_drain_guard_closed(b));nf_native_clear(&b->guard);free(b);free(s);
    printf("PASS %u drain guard checks (no live firewall)\n",checks);return 0;
}
#endif

#ifdef NF_RUNTIME_SESSION_HOST_TEST
static unsigned checks;
#define CHECK(x) do {if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
int main(void) {
    struct asteriskd_system_supervisor *s=calloc(1,sizeof(*s));CHECK(s);
    CHECK(!nf_gateway_runtime_before_core(s));CHECK(!nf_gateway_runtime_core_identity(s));
    CHECK(!nf_gateway_runtime_core_ready(s));CHECK(!nf_gateway_runtime_after_core_stop(s));
    CHECK(!nf_gateway_runtime_publish(s));CHECK(!nf_gateway_runtime_withdraw(s));
    CHECK(!nf_gateway_runtime_prepare_rules(s));
    CHECK(!nf_gateway_runtime_refresh_begin(s));CHECK(!nf_gateway_runtime_refresh_finish(s));
    CHECK(!nf_gateway_runtime_reconcile(s));
    nf_gateway_runtime_dispose(s);CHECK(!s->nf_gateway_session);
    s->loaded_config.config.nf_hev_gateway_dns_port=15353;
    CHECK(nf_gateway_runtime_before_core(s)<0);CHECK(nf_gateway_runtime_core_ready(s)<0);
    CHECK(nf_gateway_runtime_publish(s)<0);CHECK(nf_gateway_runtime_prepare_rules(s)<0);
    CHECK(nf_gateway_runtime_core_identity(s)<0);CHECK(!s->nf_gateway_session);
    struct asteriskd_config *c=&s->loaded_config.config;
    c->owner=ASTERISKD_OWNER_BOX;c->core_type=ASTERISKD_CORE_SING_BOX;c->mode=ASTERISKD_MODE_TUN2SOCKS;
    struct nf_link_snapshot links,other;CHECK(!nf_runtime_links(s,&links));
    CHECK(!links.count);other=links;CHECK(nf_runtime_links_equal(&links,&other));
    other.count=1;CHECK(!nf_runtime_links_equal(&links,&other));
    links=(struct nf_link_snapshot){.count=1};
    strcpy(links.links[0].name,"test0");links.links[0].index=5;links.links[0].count=2;
    links.links[0].addresses[0]=(struct nf_link_address){.family=4,.address={192,0,2,1},.mask={255,255,255,0}};
    links.links[0].addresses[1]=(struct nf_link_address){.family=6,.address={0xfe,0x80,0,1},.mask={255,255,255,255,255,255,255,255}};
    other=links;CHECK(nf_runtime_links_equal(&links,&other));
    other.links[0].index++;CHECK(!nf_runtime_links_equal(&links,&other));
    other=links;other.links[0].addresses[0].address[3]++;CHECK(!nf_runtime_links_equal(&links,&other));
    other=links;other.links[0].addresses[0].mask[3]=128;CHECK(!nf_runtime_links_equal(&links,&other));
    other=links;other.links[0].addresses[1].address[3]++;CHECK(!nf_runtime_links_equal(&links,&other));
    other=links;other.links[0].count--;CHECK(!nf_runtime_links_equal(&links,&other));
    other=links;struct nf_link_address tmp=other.links[0].addresses[0];
    other.links[0].addresses[0]=other.links[0].addresses[1];other.links[0].addresses[1]=tmp;
    CHECK(!nf_runtime_links_equal(&links,&other));
    qsort(other.links[0].addresses,2,sizeof(tmp),nf_link_address_compare);
    CHECK(nf_runtime_links_equal(&links,&other));
    other=links;strcpy(other.links[0].name,"test1");CHECK(!nf_runtime_links_equal(&links,&other));
    struct nf_native_backend *paused=calloc(1,sizeof(*paused));CHECK(paused);
    CHECK(nf_service_links_match(paused,&other));paused->service_links_valid=true;
    paused->published_links=links;CHECK(nf_service_links_match(paused,&links));
    CHECK(!nf_service_links_match(paused,&other));other=links;
    other.links[0].addresses[0].address[3]++;CHECK(!nf_service_links_match(paused,&other));
    other=links;other.links[0].index++;CHECK(!nf_service_links_match(paused,&other));
    memset(&other,0,sizeof(other));CHECK(!nf_service_links_match(paused,&other));
    paused->published_links=other;CHECK(nf_service_links_match(paused,&other));
    CHECK(!nf_service_links_match(paused,&links));free(paused);
    c->hotspot_interface_count=1;strcpy(c->hotspot_interfaces[0],"lo");
    CHECK(nf_runtime_links(s,&links)<0);
    strcpy(c->hotspot_interfaces[0],"nfmissing0");CHECK(nf_runtime_links(s,&links)<0);
    c->hotspot_interface_count=0;
    char path[]="/tmp/nf-runtime-session-XXXXXX";CHECK(mkdtemp(path));
    int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);
    struct nf_native_backend *a=calloc(1,sizeof(*a)),*b=calloc(1,sizeof(*b));CHECK(a&&b);
    a->wal_fd=b->wal_fd=-1;
    CHECK(!nf_runtime_init_wal(a,d,15353) && a->wal_ready && a->precore_certified && !a->wal.generation);
    CHECK(nf_nonzero(a->wal.boot,16) && nf_nonzero(a->wal.session,16));
    CHECK(nf_runtime_init_wal(b,d,15354)<0);nf_runtime_free(b);
    struct nf_wal_record clean=a->wal;clean.generation=1;CHECK(!nf_gateway_wal_save(a->wal_fd,&clean,0));
    nf_runtime_free(a);
    a=calloc(1,sizeof(*a));CHECK(a);a->wal_fd=-1;
    CHECK(!nf_runtime_init_wal(a,d,15354) && a->wal.generation==1 && a->wal.port==15354);
    CHECK(memcmp(a->wal.session,clean.session,16)!=0);
    uint8_t data[]={1};struct nf_wal_record dirty=a->wal;dirty.generation=2;dirty.debts=1;dirty.payload=data;dirty.length=1;
    CHECK(!nf_gateway_wal_save(a->wal_fd,&dirty,1));nf_runtime_free(a);
    a=calloc(1,sizeof(*a));CHECK(a);a->wal_fd=-1;
    CHECK(nf_runtime_init_wal(a,d,15355)<0);nf_runtime_free(a);
    CHECK(!unlinkat(d,NF_WAL_NAME,0));
    int fd=openat(d,NF_WAL_TEMP,O_CREAT|O_WRONLY|O_EXCL,0600);CHECK(fd>=0);close(fd);
    a=calloc(1,sizeof(*a));CHECK(a);a->wal_fd=-1;
    CHECK(nf_runtime_init_wal(a,d,15355)<0);nf_runtime_free(a);
    CHECK(!unlinkat(d,NF_WAL_TEMP,0));close(d);CHECK(!rmdir(path));free(s);
    printf("PASS %u runtime session checks (no firewall mutation)\n",checks);return 0;
}
#endif
