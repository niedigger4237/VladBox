/* A full legacy save is an observation, never an exclusive mark lease.
 * Recognize connmark reads as well as writes. Packet MARK is independent.
 * 0 = no observed overlap, 1 = overlap, -1 = unknown/malformed. */
static int nf_audit_number(const char *s,uint32_t *out) {
    if(!s || !*s || *s=='-' || *s=='+')return -1;
    char *end;errno=0;unsigned long long v=strtoull(s,&end,0);
    if(errno || *end || v>UINT32_MAX)return -1;
    *out=(uint32_t)v;return 0;
}
static int nf_audit_markarg(char *s,uint32_t *value,uint32_t *mask) {
    char *slash=strchr(s,'/');*mask=UINT32_MAX;
    if(slash){*slash++=0;if(nf_audit_number(slash,mask))return -1;}
    return nf_audit_number(s,value);
}
/* Tokenize iptables-save rule lines, including quoted comments and escapes.
 * Unknown CONNMARK operations are ambiguous, not a free mask. */
static int nf_audit_rule(char *line,uint32_t candidate) {
    char *tokens[256];size_t count=0;char *r=line,*w=line;
    while(*r) {
        while(*r==' ' || *r=='\t')r++;
        if(!*r)break;
        if(count==256)return -1;
        tokens[count++]=w;char quote=0;
        while(*r) {
            char c=*r++;
            if(c=='\\'){if(!*r)return -1;*w++=*r++;continue;}
            if(quote){if(c==quote)quote=0;else *w++=c;continue;}
            if(c=='\'' || c=='"'){quote=c;continue;}
            if(c==' ' || c=='\t')break;
            *w++=c;
        }
        if(quote)return -1;
        *w++=0;
    }
    if(count<2 || strcmp(tokens[0],"-A"))return -1;
    bool cm=false,match=false;size_t target=0;uint32_t used=0;
    for(size_t i=2;i<count;i++) {
        if(!strcmp(tokens[i],"--comment")){if(++i>=count)return -1;continue;}
        if(!strcmp(tokens[i],"-m") || !strcmp(tokens[i],"--match")) {
            if(++i>=count)return -1;
            if(!strcmp(tokens[i],"connmark"))match=true;
        } else if(!strcmp(tokens[i],"-j") || !strcmp(tokens[i],"--jump") || !strcmp(tokens[i],"-g") || !strcmp(tokens[i],"--goto")) {
            if(++i>=count || target)return -1;
            target=i;cm=!strcmp(tokens[i],"CONNMARK");
        } else if(match && !strcmp(tokens[i],"--mark")) {
            uint32_t v,m;if(++i>=count || nf_audit_markarg(tokens[i],&v,&m))return -1;
            used|=m;match=false;
        } else if(!strcmp(tokens[i],"--ctmark")) {
            uint32_t v,m;if(++i>=count || nf_audit_markarg(tokens[i],&v,&m))return -1;
            used|=m;
        }
    }
    if(match)return -1;
    if(cm) {
        if(target+1>=count)return -1;
        const char *op=tokens[target+1];size_t i=target+2;uint32_t v,m;
        if(!strcmp(op,"--set-xmark") || !strcmp(op,"--set-mark")) {
            if(i+1!=count || nf_audit_markarg(tokens[i],&v,&m))return -1;
            used|=v|m;
        } else if(!strcmp(op,"--save-mark") || !strcmp(op,"--restore-mark")) {
            uint32_t nf=UINT32_MAX,ct=UINT32_MAX;bool hn=false,hc=false;
            for(;i<count;i+=2) {
                if(i+1>=count)return -1;
                if(!strcmp(tokens[i],"--nfmask")&&!hn){hn=true;if(nf_audit_number(tokens[i+1],&nf))return -1;}
                else if(!strcmp(tokens[i],"--ctmask")&&!hc){hc=true;if(nf_audit_number(tokens[i+1],&ct))return -1;}
                else if(!strcmp(tokens[i],"--mask")&&!hc&&!hn){hc=hn=true;if(nf_audit_number(tokens[i+1],&ct))return -1;nf=ct;}
                else return -1;
            }
            used|=ct;if(!strcmp(op,"--save-mark"))used|=nf;
        } else if(!strcmp(op,"--and-mark") || !strcmp(op,"--or-mark") || !strcmp(op,"--xor-mark")) {
            if(i+1!=count || nf_audit_number(tokens[i],&v))return -1;
            used|=!strcmp(op,"--and-mark")?~v:v;
        } else return -1;
    }
    return (used&candidate)?1:0;
}
static int nf_audit_save(const char *bytes,size_t n,uint32_t mask,bool v6) {
    if(!bytes || !n || n>8U*1024U*1024U || bytes[n-1]!='\n' || memchr(bytes,0,n) || !mask)return -1;
    unsigned seen=0,table=0;int result=0;size_t pos=0;
    while(pos<n) {
        const char *end=memchr(bytes+pos,'\n',n-pos);if(!end)return -1;
        size_t len=(size_t)(end-(bytes+pos));if(len>16384U)return -1;
        char line[16385];memcpy(line,bytes+pos,len);line[len]=0;pos+=len+1;
        if(!len || line[0]=='#')continue;
        if(line[0]=='*') {
            if(table)return -1;
            const char *names[]={"filter","nat","mangle","raw","security"};
            for(unsigned j=0;j<5;j++)if(!strcmp(line+1,names[j]))table=1U<<j;
            if(!table || (seen&table))return -1;
        } else if(!strcmp(line,"COMMIT")) {if(!table)return -1;seen|=table;table=0;}
        else if(table && line[0]==':')continue;
        else if(table && !strncmp(line,"-A ",3)) {
            int rc=nf_audit_rule(line,mask);if(rc<0)return -1;
        if(rc)result=1;
        } else return -1;
    }
    unsigned required=v6?13U:15U; // filter,mangle,raw; IPv4 also NAT
    if(table || (seen&required)!=required)return -1;
    return result;
}
/* Report observed conflicts in both legacy families. No successful scan
 * can set mark_authorized or replace the independent live verifier. */
int nf_gateway_native_audit_mark(struct asteriskd_system_supervisor *s,uint32_t mask) {
    if(!nf_native_available(s) || !mask || (mask&~NF_HEV_CTMARK_CANDIDATES))return -1;
    int result=0;
    for(unsigned f=0;f<2;f++) {
        const char *argv[]={f?"ip6tables-save":"iptables-save",NULL};int status=-1;
        if(asteriskd_supervisor_action_run_argv(s,argv,true,&status) || status || s->action_stdout_overflow || s->action_stderr_length)return -1;
        int rc=nf_audit_save(s->action_stdout,s->action_stdout_length,mask,f!=0);
        if(rc<0)return -1;
        if(rc)result=1;
    }
    return result;
}
