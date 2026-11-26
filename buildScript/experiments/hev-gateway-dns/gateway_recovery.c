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
    if(memcmp(boot,r.boot,16) || r.length<8 || memcmp(payload,"NFFW01",6) || payload[7]>2 ||
       (payload[6]!=NF_GUARD_CLOSED && payload[6]!=NF_GUARD_OPEN && payload[6]!=NF_NAT_INSTALL && payload[6]!=NF_NAT_REMOVE && payload[6]!=NF_GUARD_REMOVE))goto done;
    size_t pos=8;
    if(nf_recovery_slot(&r,&pos,&b->guard,true) || nf_recovery_slot(&r,&pos,&b->nat,false) ||
       (b->guard.owned && !(r.debts&2U)) || (b->nat.owned && (r.debts&24U)!=24U))goto done;
    if(payload[7]==1U && (r.debts!=3U || r.epoch || !b->guard.owned || b->nat.owned || b->nat.plan.private_group_count ||
       (payload[6]!=NF_GUARD_CLOSED && payload[6]!=NF_GUARD_REMOVE)))goto done;
    if(payload[7]==2U) {
        if(!(r.debts&4U) || r.length-pos!=32)goto done;
        b->core_evidence=(struct nf_core_evidence){.pid=(uint32_t)nf_get(payload+pos,4),.group=(uint32_t)nf_get(payload+pos+4,4),
            .start=nf_get(payload+pos+8,8),.device=nf_get(payload+pos+16,8),.inode=nf_get(payload+pos+24,8)};
        if(!nf_core_evidence_valid(&b->core_evidence))goto done;
        b->core_evidence_valid=true;pos+=32;
    }
    if(pos!=r.length)goto done;
    b->precore_certified=payload[7]==1U;
    b->wal=r;b->wal.payload=NULL;b->wal.length=0;b->wal_ready=true;rc=0;
done:
    if(rc){nf_native_clear(&b->guard);nf_native_clear(&b->nat);b->core_evidence_valid=false;memset(&b->core_evidence,0,sizeof(b->core_evidence));}
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
        int rc=asteriskd_supervisor_rule_view_capture_table(s,f,t,&v);
        bool absent=!rc && nf_names_view_absent(&v,nat_only);
        asteriskd_supervisor_rule_view_destroy(&v);if(!absent)return -1;
    }
    return 0;
}
static int nf_reboot_live_absence(void *ctx) {return nf_live_names_absence(ctx,false);}
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
static int nf_active_cleanup(void *ctx,struct nf_native_backend *b) {
    b->system=ctx;
    if(nf_postcore_absent(NULL,b) || nf_recovery_preflight(b,&b->guard) ||
       nf_recovery_preflight(b,&b->nat))return -1;
    if(nf_native_write(b,&b->guard,b->wal.port,true,false) || nf_native_remove(b,&b->nat))return -1;
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
       nf_gateway_recovery_load(b,boot) || !b->core_evidence_valid || b->precore_certified ||
       b->wal.debts!=31U || !b->wal.epoch || !b->guard.owned || b->wal.generation==UINT64_MAX)goto done;
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
    CHECK(!nf_gateway_plan(c,15353,true,&b->guard.plan));CHECK(!nf_native_render(s,&b->guard,15353,false));b->guard.pending_valid=b->guard.owned=true;
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
    CHECK(!nf_gateway_plan(c,15353,true,&b->guard.plan));CHECK(!nf_native_render(s,&b->guard,15353,false));b->guard.pending_valid=b->guard.owned=true;
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
    CHECK(!nf_gateway_plan(c,port,true,&b->guard.plan));CHECK(!nf_native_render(s,&b->guard,port,false));b->guard.pending_valid=b->guard.owned=true;
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
    CHECK(!nf_gateway_plan(c,15353,true,&b->guard.plan));CHECK(!nf_native_render(s,&b->guard,15353,true));b->guard.pending_valid=b->guard.owned=true;
    CHECK(!nf_gateway_plan(c,15353,false,&b->nat.plan));CHECK(!nf_native_render(s,&b->nat,15353,true));b->nat.pending_valid=b->nat.owned=true;
    CHECK(!nf_native_checkpoint(b,NF_NAT_INSTALL));close(b->wal_fd);
    struct active_fake f={.busy=true};uint8_t boot[16]={1},wrong[16]={2};
    CHECK(nf_active_reconcile(d,wrong,active_fake_cleanup,&f)<0 && !f.calls);
    CHECK(nf_active_reconcile(d,boot,active_fake_cleanup,&f)<0 && f.calls==1);CHECK(nf_gateway_admission_check(d)<0);
    uint8_t raw[4096];struct nf_wal_record rec;CHECK(!nf_gateway_wal_load(d,&rec,raw,sizeof(raw)) && rec.debts==31 && rec.generation==1);
    // Closing the recovered guard is independent of the next selected mode.
    struct nf_native_slot closed={0};closed.plan=b->guard.plan;memset(c,0,sizeof(*c));
    CHECK(!nf_native_render(s,&closed,15353,false));CHECK(closed.pending[0].length && !memmem(closed.pending[0].bytes,closed.pending[0].length,"ACCEPT",6));nf_native_clear(&closed);
    f.busy=false;CHECK(!nf_active_reconcile(d,boot,active_fake_cleanup,&f) && f.calls==2);CHECK(!nf_gateway_admission_check(d));
    CHECK(!nf_gateway_wal_load(d,&rec,raw,sizeof(raw)) && rec.generation==2 && !rec.debts);
    CHECK(!unlinkat(d,NF_WAL_NAME,0));close(d);CHECK(!rmdir(path));nf_native_clear(&b->guard);nf_native_clear(&b->nat);free(b);free(s);
    printf("PASS %u active recovery checks\n",checks);return 0;
}
#endif
