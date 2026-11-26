// SPDX-License-Identifier: GPL-3.0
// Strict gateway DNS socket ownership observation; not a port reservation.
#include "supervisor/supervisor.h"
#include <dirent.h>
#include <inttypes.h>

#define NF_SOCKET_LIMIT 64U
#define NF_TCP4 1U
#define NF_UDP4 2U
#define NF_TCP6 4U
#define NF_UDP6 8U
struct nf_socket_observation {
    unsigned families;
    uint64_t inode[NF_SOCKET_LIMIT];
    size_t count;
};

static int nf_number(const char *s, unsigned base, uint64_t *value) {
    if (!s || !*s || *s=='-' || *s=='+') return -1;
    errno=0; char *end=NULL;
    unsigned long long v=strtoull(s,&end,base);
    if(errno || *end) return -1;
    *value=(uint64_t)v; return 0;
}
static int nf_table(FILE *f,unsigned port,unsigned bit,struct nf_socket_observation *o) {
    char *line=NULL; size_t cap=0; int rc=-1;
    if(getline(&line,&cap,f)<0 || !strstr(line,"local_address") || !strstr(line,"inode")) goto done;
    while(getline(&line,&cap,f)>=0) {
        char *tok[10],*save=NULL; size_t n=0;
        for(char *p=strtok_r(line," \t\r\n",&save);p && n<10;p=strtok_r(NULL," \t\r\n",&save)) tok[n++]=p;
        if(n!=10) goto done;
        char *colon=strchr(tok[1],':'); uint64_t p,state,inode;
        if(!colon || nf_number(colon+1,16,&p) || p>65535 || nf_number(tok[3],16,&state)) goto done;
        if(p!=port) continue;
        bool tcp=bit==NF_TCP4 || bit==NF_TCP6;
        if(tcp && state!=0x0a) continue; // accepted connections/TIME_WAIT are not listeners
        size_t addrlen=(bit==NF_TCP6 || bit==NF_UDP6)?32U:8U;
        if((size_t)(colon-tok[1])!=addrlen || strspn(tok[1],"0")!=addrlen ||
           (!tcp && state!=7) || nf_number(tok[9],10,&inode) || !inode || o->count>=NF_SOCKET_LIMIT) goto done;
        // Only wildcard listener(s), never a specific local address or connected UDP socket.
        for(size_t i=0;i<o->count;i++) if(o->inode[i]==inode) goto done;
        o->inode[o->count++]=inode; o->families|=bit;
    }
    if(feof(f) && !ferror(f)) rc=0;
done:
    free(line); return rc;
}
static int nf_scan_tables(int pid,unsigned port,struct nf_socket_observation *o) {
    const char *files[]={"tcp","udp","tcp6","udp6"};
    memset(o,0,sizeof(*o));
    for(size_t i=0;i<4;i++) {
        char path[80]; snprintf(path,sizeof(path),"/proc/%d/net/%s",pid,files[i]);
        FILE *f=fopen(path,"r"); if(!f) return -1;
        int rc=nf_table(f,port,1U<<i,o); fclose(f); if(rc) return -1;
    }
    return 0;
}
static int nf_owned_fds(int pid,const struct nf_socket_observation *o) {
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/fd",pid);
    DIR *d=opendir(path); if(!d) return -1;
    bool found[NF_SOCKET_LIMIT]={0}; int rc=0;
    for(;;) {
        errno=0; struct dirent *e=readdir(d); if(!e) {if(errno) rc=-1;break;}
        if(e->d_name[0]=='.') continue;
        char link[128]; ssize_t n=readlinkat(dirfd(d),e->d_name,link,sizeof(link)-1);
        if(n<0) {if(errno==ENOENT) continue;rc=-1;break;}
        link[n]='\0'; if(strncmp(link,"socket:[",8)) continue;
        char *end=strchr(link+8,']'); uint64_t inode;
        if(!end || end[1]) {rc=-1;break;}
        *end='\0'; if(nf_number(link+8,10,&inode)) {rc=-1;break;}
        for(size_t i=0;i<o->count;i++) if(o->inode[i]==inode) found[i]=true;
    }
    closedir(d); for(size_t i=0;i<o->count;i++) if(!found[i]) rc=-1;
    return rc;
}
static bool nf_same_sockets(const struct nf_socket_observation *a,const struct nf_socket_observation *b) {
    if(a->families!=b->families || a->count!=b->count) return false;
    for(size_t i=0;i<a->count;i++) {bool found=false;for(size_t j=0;j<b->count;j++) if(a->inode[i]==b->inode[j]) found=true;if(!found) return false;}
    return true;
}
static bool nf_core_identity(struct asteriskd_system_supervisor *s) {
    if(!s || !s->core_spawned || s->core_reaped || !s->core_identity_ready || !s->core_spec_ready) return false;
    const struct asteriskd_child_identity *old=&s->core_identity;
    struct asteriskd_child_identity now; char err[128];
    if(old->pid!=s->core_process.pid || old->type!=ASTERISKD_CHILD_TYPE_SING_BOX || old->role!=ASTERISKD_CHILD_CORE ||
       asteriskd_process_identity_read(asteriskd_system_process_identity_backend(),old->pid,old->role,old->type,&s->core_spec,&now,err,sizeof(err))) return false;
    return now.start_time_ticks==old->start_time_ticks && now.exe_device==old->exe_device && now.exe_inode==old->exe_inode && now.process_group_id==old->process_group_id;
}
// Observes actual listener families; IPv6 wildcard is NOT proof of IPv4 dual-stack.
// Fail closed if /proc access is restricted or the socket set changes during observation.
int nf_gateway_socket_observe(struct asteriskd_system_supervisor *s,unsigned port,unsigned *families) {
    if(!families) return -1;
    *families=0;
    if(port<1024 || port>65535 || !nf_core_identity(s)) return -1;
    int pid=s->core_identity.pid;
    struct nf_socket_observation a,b;
    if(nf_scan_tables(pid,port,&a) || !a.count || nf_owned_fds(pid,&a) ||
       nf_scan_tables(pid,port,&b) || !nf_same_sockets(&a,&b) || nf_owned_fds(pid,&b) || !nf_core_identity(s)) return -1;
    *families=b.families; return 0;
}
/* Used only during recovery of an exited process. No port reservation. */
int nf_gateway_port_absent(unsigned port) {
    struct nf_socket_observation a,b;
    if(port<1024 || port>65535 || nf_scan_tables(getpid(),port,&a) || a.count ||
       nf_scan_tables(getpid(),port,&b) || b.count)return -1;
    return 0;
}
#ifdef NF_SOCKET_HOST_TEST
#include <sys/wait.h>
#include <sys/socket.h>
#include <netinet/in.h>
static unsigned checks;
#define CHECK(x) do {if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);exit(1);}checks++;}while(0)
int main(void) {
    int t=socket(AF_INET,SOCK_STREAM,0),u=socket(AF_INET,SOCK_DGRAM,0);
    CHECK(t>=0 && u>=0);
    struct sockaddr_in a={.sin_family=AF_INET}; socklen_t len=sizeof(a);
    CHECK(!bind(t,(void*)&a,sizeof(a)) && !listen(t,1) && !getsockname(t,(void*)&a,&len));
    CHECK(!bind(u,(void*)&a,sizeof(a)));
    unsigned port=ntohs(a.sin_port); struct nf_socket_observation o,b;
    CHECK(!nf_scan_tables(getpid(),port,&o)); CHECK(o.families==(NF_TCP4|NF_UDP4) && o.count==2);
    CHECK(!nf_owned_fds(getpid(),&o)); CHECK(nf_gateway_port_absent(port)!=0); CHECK(!nf_scan_tables(getpid(),port,&b) && nf_same_sockets(&o,&b));
    b.inode[0]=UINT64_MAX; CHECK(!nf_same_sockets(&o,&b));
    CHECK(nf_owned_fds(getpid(),&b)!=0);
    close(u); CHECK(!nf_scan_tables(getpid(),port,&b) && b.families==NF_TCP4 && !nf_same_sockets(&o,&b));
    close(t); CHECK(!nf_scan_tables(getpid(),port,&b) && !b.count); CHECK(!nf_gateway_port_absent(port));
    int v=socket(AF_INET6,SOCK_STREAM,0); CHECK(v>=0);
    int only=1; CHECK(!setsockopt(v,IPPROTO_IPV6,IPV6_V6ONLY,&only,sizeof(only)));
    struct sockaddr_in6 a6={.sin6_family=AF_INET6};len=sizeof(a6);
    CHECK(!bind(v,(void*)&a6,sizeof(a6)) && !listen(v,1) && !getsockname(v,(void*)&a6,&len));
    CHECK(!nf_scan_tables(getpid(),ntohs(a6.sin6_port),&b) && b.families==NF_TCP6 && !nf_owned_fds(getpid(),&b));close(v);
    t=socket(AF_INET,SOCK_STREAM,0);CHECK(t>=0);a=(struct sockaddr_in){.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};len=sizeof(a);
    CHECK(!bind(t,(void*)&a,sizeof(a)) && !listen(t,1) && !getsockname(t,(void*)&a,&len));
    CHECK(nf_scan_tables(getpid(),ntohs(a.sin_port),&b)!=0);close(t);
    int pipes[2],done[2]; CHECK(!pipe(pipes) && !pipe(done));
    pid_t child=fork(); CHECK(child>=0);
    if(!child) {
        close(pipes[0]);close(done[1]);
        int fd=socket(AF_INET,SOCK_DGRAM,0);
        struct sockaddr_in addr={.sin_family=AF_INET};socklen_t n=sizeof(addr);
        if(fd<0 || bind(fd,(void*)&addr,sizeof(addr)) || getsockname(fd,(void*)&addr,&n) ||
           write(pipes[1],&addr.sin_port,sizeof(addr.sin_port))!=sizeof(addr.sin_port)) _exit(2);
        char byte; if(read(done[0],&byte,1)!=1) _exit(3);close(fd);_exit(0);
    }
    close(pipes[1]);close(done[0]);uint16_t foreign;
    CHECK(read(pipes[0],&foreign,sizeof(foreign))==sizeof(foreign));
    CHECK(!nf_scan_tables(getpid(),ntohs(foreign),&b) && b.families==NF_UDP4);
    CHECK(nf_owned_fds(getpid(),&b)!=0);CHECK(!nf_owned_fds(child,&b));
    CHECK(write(done[1],"x",1)==1);int status;CHECK(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(pipes[0]);close(done[1]);
    unsigned mask=99;CHECK(nf_gateway_socket_observe(NULL,15353,&mask)!=0 && !mask);
    printf("PASS %u socket checks\n",checks);return 0;
}
#endif
