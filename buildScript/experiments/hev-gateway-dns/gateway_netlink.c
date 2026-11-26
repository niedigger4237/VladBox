// SPDX-License-Identifier: GPL-3.0
// Read-only CTNETLINK GET transport. Compile instead of gateway_conntrack.c.
#include "gateway_conntrack.c"
#include <errno.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

struct nf_attr_view { const uint8_t *p; size_t n; };
static int nf_attrs(const uint8_t *p,size_t n,struct nf_attr_view *v,size_t count) {
    while(n) {
        struct nlattr a;
        if(n<sizeof(a))return -1;
        memcpy(&a,p,sizeof(a));size_t len=a.nla_len,step=NLA_ALIGN(len);
        if(len<NLA_HDRLEN || step>n)return -1;
        unsigned type=a.nla_type & NLA_TYPE_MASK;
        if(type<count) {
            if(v[type].p)return -1;
            v[type]=(struct nf_attr_view){p+NLA_HDRLEN,len-NLA_HDRLEN};
        }
        p+=step;n-=step;
    }
    return 0;
}
static int nf_scalar(const struct nf_attr_view *v,void *out,size_t n) {
    if(!v->p || v->n!=n)return -1;
    memcpy(out,v->p,n);return 0;
}
static int nf_tuple_read(const struct nf_attr_view *v,uint8_t family,struct nf_ct_tuple *out) {
    struct nf_attr_view t[CTA_TUPLE_MAX+1]={0},ip[CTA_IP_MAX+1]={0},p[CTA_PROTO_MAX+1]={0};
    if(!v->p || (family!=AF_INET && family!=AF_INET6) || nf_attrs(v->p,v->n,t,CTA_TUPLE_MAX+1) ||
       t[CTA_TUPLE_ZONE].p || !t[CTA_TUPLE_IP].p || !t[CTA_TUPLE_PROTO].p ||
       nf_attrs(t[CTA_TUPLE_IP].p,t[CTA_TUPLE_IP].n,ip,CTA_IP_MAX+1) ||
       nf_attrs(t[CTA_TUPLE_PROTO].p,t[CTA_TUPLE_PROTO].n,p,CTA_PROTO_MAX+1))return -1;
    bool v6=family==AF_INET6;size_t n=v6?16:4;uint16_t sp,dp;
    memset(out,0,sizeof(*out));out->family=family;
    if(ip[v6?CTA_IP_V4_SRC:CTA_IP_V6_SRC].p || ip[v6?CTA_IP_V4_DST:CTA_IP_V6_DST].p ||
       nf_scalar(&ip[v6?CTA_IP_V6_SRC:CTA_IP_V4_SRC],out->src,n) ||
       nf_scalar(&ip[v6?CTA_IP_V6_DST:CTA_IP_V4_DST],out->dst,n) ||
       nf_scalar(&p[CTA_PROTO_NUM],&out->proto,1) || (out->proto!=IPPROTO_TCP && out->proto!=IPPROTO_UDP) ||
       nf_scalar(&p[CTA_PROTO_SRC_PORT],&sp,2) || nf_scalar(&p[CTA_PROTO_DST_PORT],&dp,2))return -1;
    out->sport=ntohs(sp);out->dport=ntohs(dp);return 0;
}
static int nf_get_request(const struct nf_ct_tuple *tuple,uint16_t zone,uint32_t seq,struct nf_ct_message *m) {
    memset(m,0,sizeof(*m));
    if(!tuple || !seq || (tuple->family!=AF_INET && tuple->family!=AF_INET6) ||
       (tuple->proto!=IPPROTO_TCP && tuple->proto!=IPPROTO_UDP) || !tuple->sport || !tuple->dport)return -1;
    struct nlmsghdr h={.nlmsg_type=(NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_GET,.nlmsg_flags=NLM_F_REQUEST,.nlmsg_seq=seq};
    struct nfgenmsg g={.nfgen_family=tuple->family,.version=NFNETLINK_V0};
    m->len=NLMSG_HDRLEN+sizeof(g);memcpy(m->data,&h,sizeof(h));memcpy(m->data+NLMSG_HDRLEN,&g,sizeof(g));
    uint16_t z=htons(zone);
    if(nf_ct_encode_tuple(m,tuple) || nf_ct_attr(m,CTA_ZONE,&z,2)){m->len=0;return -1;}
    h.nlmsg_len=(uint32_t)m->len;memcpy(m->data,&h,sizeof(h));return 0;
}
/* 0 = exact record, 1 = kernel ENOENT for this GET, -1 = error/ambiguous.
 * Sender sockaddr is checked by the transport; this checks message framing. */
static int nf_get_reply(const uint8_t *buf,size_t n,uint32_t local,const struct nf_ct_message *request,
                        const struct nf_ct_tuple *tuple,uint16_t zone,struct nf_ct_record *out) {
    memset(out,0,sizeof(*out));struct nlmsghdr h,q;
    if(n<sizeof(h))return -1;
    memcpy(&h,buf,sizeof(h));memcpy(&q,request->data,sizeof(q));
    if(h.nlmsg_len<NLMSG_HDRLEN || NLMSG_ALIGN((size_t)h.nlmsg_len)!=n || h.nlmsg_seq!=q.nlmsg_seq ||
       h.nlmsg_pid!=local || (h.nlmsg_flags&(NLM_F_MULTI|NLM_F_DUMP_INTR)))return -1;
    const uint8_t *p=buf+NLMSG_HDRLEN;size_t len=h.nlmsg_len-NLMSG_HDRLEN;
    if(h.nlmsg_type==NLMSG_ERROR) {
        struct nlmsgerr e;if(len<sizeof(e))return -1;memcpy(&e,p,sizeof(e));
        if(e.msg.nlmsg_seq!=q.nlmsg_seq || e.msg.nlmsg_type!=q.nlmsg_type || e.msg.nlmsg_len!=q.nlmsg_len ||
           e.msg.nlmsg_flags!=q.nlmsg_flags || e.msg.nlmsg_pid!=q.nlmsg_pid)return -1;
        return e.error==-ENOENT?1:-1;
    }
    struct nfgenmsg g;if(h.nlmsg_type!=((NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_NEW) || len<sizeof(g))return -1;
    memcpy(&g,p,sizeof(g));if(g.version!=NFNETLINK_V0 || g.nfgen_family!=tuple->family)return -1;
    struct nf_attr_view v[CTA_MAX+1]={0};struct nf_ct_record r={0};uint32_t x;uint16_t z;
    if(nf_attrs(p+sizeof(g),len-sizeof(g),v,CTA_MAX+1) ||
       nf_tuple_read(&v[CTA_TUPLE_ORIG],g.nfgen_family,&r.orig) || nf_tuple_read(&v[CTA_TUPLE_REPLY],g.nfgen_family,&r.reply) ||
       !nf_ct_tuple_eq(&r.orig,tuple) || r.orig.proto!=r.reply.proto || nf_scalar(&v[CTA_ID],&x,4))return -1;
    r.id=ntohl(x);r.has_id=true;
    if(!r.id || nf_scalar(&v[CTA_STATUS],&x,4))return -1;
    r.status=ntohl(x);r.has_status=true;
    if(v[CTA_MARK].p){if(nf_scalar(&v[CTA_MARK],&x,4))return -1;r.mark=ntohl(x);r.has_mark=true;}
    if(v[CTA_ZONE].p){if(nf_scalar(&v[CTA_ZONE],&z,2))return -1;r.zone=ntohs(z);}
    // The kernel omits CTA_ZONE for the default zone. Direction-specific
    // tuple zones were rejected above, rather than collapsed into zone 0.
    r.has_zone=true;
    if(r.zone!=zone)return -1;
    *out=r;return 0;
}
/* Fresh socket, one request, bounded receive. No multicast, dump, DELETE,
 * mark allocation, or privilege escalation. EPERM is an error, not absence. */
int nf_gateway_ct_get(const struct nf_ct_tuple *tuple,uint16_t zone,struct nf_ct_record *out) {
    if(!out)return -1;
    memset(out,0,sizeof(*out));struct nf_ct_message request;
    if(nf_get_request(tuple,zone,1,&request))return -1;
    int fd=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC|SOCK_NONBLOCK,NETLINK_NETFILTER);if(fd<0)return -1;
    struct sockaddr_nl local={.nl_family=AF_NETLINK},kernel={.nl_family=AF_NETLINK};socklen_t sl=sizeof(local);int rc=-1;
    if(bind(fd,(struct sockaddr*)&local,sizeof(local)) || getsockname(fd,(struct sockaddr*)&local,&sl) ||
       sl!=sizeof(local) || local.nl_family!=AF_NETLINK || !local.nl_pid || local.nl_groups)goto done;
    // Set explicit sender port in the request so the embedded error header
    // can also be checked; kernel sender address is validated independently.
    struct nlmsghdr h;memcpy(&h,request.data,sizeof(h));h.nlmsg_pid=local.nl_pid;memcpy(request.data,&h,sizeof(h));
    if(sendto(fd,request.data,request.len,0,(struct sockaddr*)&kernel,sizeof(kernel))!=(ssize_t)request.len)goto done;
    struct pollfd p={.fd=fd,.events=POLLIN};if(poll(&p,1,1000)!=1 || p.revents!=POLLIN)goto done;
    uint8_t buf[16384];struct sockaddr_nl sender={0};struct iovec iov={buf,sizeof(buf)};
    struct msghdr msg={.msg_name=&sender,.msg_namelen=sizeof(sender),.msg_iov=&iov,.msg_iovlen=1};
    ssize_t n=recvmsg(fd,&msg,0);
    if(n<=0 || (msg.msg_flags&(MSG_TRUNC|MSG_CTRUNC)) || msg.msg_namelen!=sizeof(sender) ||
       sender.nl_family!=AF_NETLINK || sender.nl_pid || sender.nl_groups)goto done;
    rc=nf_get_reply(buf,(size_t)n,local.nl_pid,&request,tuple,zone,out);
 done: close(fd);return rc;
}
/* Conservative drain check: never delete shared conntrack entries. Scan both
 * families and all zones; a reply TCP/UDP source port matching the private
 * listener is sufficient to retain the guard, regardless of mark or DNAT.
 * No absence claim until a complete uninterrupted multipart dump ends. */
#include <time.h>
static int nf_dump_proto(const struct nf_attr_view *v,uint8_t *proto) {
    struct nf_attr_view t[CTA_TUPLE_MAX+1]={0},p[CTA_PROTO_MAX+1]={0};
    if(!v->p || nf_attrs(v->p,v->n,t,CTA_TUPLE_MAX+1) || !t[CTA_TUPLE_PROTO].p ||
       nf_attrs(t[CTA_TUPLE_PROTO].p,t[CTA_TUPLE_PROTO].n,p,CTA_PROTO_MAX+1))return -1;
    return nf_scalar(&p[CTA_PROTO_NUM],proto,1);
}
static int nf_dump_part(const uint8_t *buf,size_t n,uint32_t local,uint8_t family,unsigned port,bool *done) {
    if(!n || *done)return -1;
    while(n) {
        struct nlmsghdr h;
        if(n<sizeof(h))return -1;
        memcpy(&h,buf,sizeof(h));size_t step=NLMSG_ALIGN((size_t)h.nlmsg_len);
        if(h.nlmsg_len<NLMSG_HDRLEN || step>n || h.nlmsg_seq!=1 || h.nlmsg_pid!=local ||
           !(h.nlmsg_flags&NLM_F_MULTI) || (h.nlmsg_flags&NLM_F_DUMP_INTR))return -1;
        const uint8_t *p=buf+NLMSG_HDRLEN;size_t len=h.nlmsg_len-NLMSG_HDRLEN;
        if(h.nlmsg_type==NLMSG_DONE) {
            int error=0;if(len==sizeof(error))memcpy(&error,p,sizeof(error));else if(len)return -1;
            if(error || step!=n)return -1;
            *done=true;return 0;
        }
        struct nfgenmsg g;
        if(h.nlmsg_type!=((NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_NEW) || len<sizeof(g))return -1;
        memcpy(&g,p,sizeof(g));if(g.version!=NFNETLINK_V0 || g.nfgen_family!=family)return -1;
        struct nf_attr_view v[CTA_MAX+1]={0};uint8_t a,b;
        if(nf_attrs(p+sizeof(g),len-sizeof(g),v,CTA_MAX+1) ||
           nf_dump_proto(&v[CTA_TUPLE_ORIG],&a) || nf_dump_proto(&v[CTA_TUPLE_REPLY],&b) || a!=b)return -1;
        if(a==IPPROTO_TCP || a==IPPROTO_UDP) {
            struct nf_ct_tuple orig,reply;
            if(nf_tuple_read(&v[CTA_TUPLE_ORIG],family,&orig) || nf_tuple_read(&v[CTA_TUPLE_REPLY],family,&reply))return -1;
            if(reply.sport==port)return 1;
        }
        buf+=step;n-=step;
    }
    return 0;
}
static int64_t nf_monotonic_ms(void) {
    struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t))return -1;
    return (int64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static int nf_dump_family(uint8_t family,unsigned port) {
    int fd=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC|SOCK_NONBLOCK,NETLINK_NETFILTER);if(fd<0)return -1;
    struct sockaddr_nl local={.nl_family=AF_NETLINK},kernel={.nl_family=AF_NETLINK};socklen_t sl=sizeof(local);int rc=-1;
    if(bind(fd,(struct sockaddr*)&local,sizeof(local)) || getsockname(fd,(struct sockaddr*)&local,&sl) ||
       sl!=sizeof(local) || local.nl_family!=AF_NETLINK || !local.nl_pid || local.nl_groups)goto done;
    struct {struct nlmsghdr h;struct nfgenmsg g;} req={.h={.nlmsg_len=NLMSG_LENGTH(sizeof(struct nfgenmsg)),
        .nlmsg_type=(NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_GET,.nlmsg_flags=NLM_F_REQUEST|NLM_F_DUMP,
        .nlmsg_seq=1,.nlmsg_pid=local.nl_pid},.g={.nfgen_family=family,.version=NFNETLINK_V0}};
    int64_t start=nf_monotonic_ms();if(start<0)goto done;
    if(sendto(fd,&req,req.h.nlmsg_len,0,(struct sockaddr*)&kernel,sizeof(kernel))!=(ssize_t)req.h.nlmsg_len)goto done;
    bool complete=false;size_t total=0;
    while(!complete) {
        int64_t now=nf_monotonic_ms();if(now<start || now-start>=2000)goto done;
        struct pollfd p={.fd=fd,.events=POLLIN};if(poll(&p,1,(int)(2000-(now-start)))!=1 || p.revents!=POLLIN)goto done;
        uint8_t buf[32768];struct sockaddr_nl sender={0};struct iovec iov={buf,sizeof(buf)};
        struct msghdr msg={.msg_name=&sender,.msg_namelen=sizeof(sender),.msg_iov=&iov,.msg_iovlen=1};
        ssize_t n=recvmsg(fd,&msg,0);
        if(n<=0 || (msg.msg_flags&(MSG_TRUNC|MSG_CTRUNC)) || msg.msg_namelen!=sizeof(sender) ||
           sender.nl_family!=AF_NETLINK || sender.nl_pid || sender.nl_groups || (size_t)n>16U*1024U*1024U-total)goto done;
        total+=(size_t)n;
        int part=nf_dump_part(buf,(size_t)n,local.nl_pid,family,port,&complete);
        if(part){rc=part;goto done;}
    }
    rc=0;
 done: close(fd);return rc;
}
/* Caller must first withdraw and verify NAT and keep the port protected.
 * 0=observed absent, 1=possibly busy, -1=inspection failed. A dump is not
 * atomic against new kernel flows: recheck before releasing the guard. */
int nf_gateway_ct_port_absent(unsigned port) {
    if(port<1024 || port>65535)return -1;
    int rc=nf_dump_family(AF_INET,port);if(rc)return rc;
    return nf_dump_family(AF_INET6,port);
}

#ifdef NF_NETLINK_HOST_TEST
#include <stdio.h>
#include <stdlib.h>
static unsigned checks;
#define CHECK(x) do {if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
static void response(struct nf_ct_message *m,const struct nf_ct_tuple *a,struct nf_ct_tuple b,uint16_t zone) {
    struct nlmsghdr h={.nlmsg_type=(NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_NEW,.nlmsg_seq=1,.nlmsg_pid=42};
    struct nfgenmsg g={.nfgen_family=a->family,.version=NFNETLINK_V0};
    memset(m,0,sizeof(*m));m->len=NLMSG_HDRLEN+sizeof(g);memcpy(m->data,&h,sizeof(h));memcpy(m->data+NLMSG_HDRLEN,&g,sizeof(g));
    CHECK(!nf_ct_encode_tuple(m,a));size_t pos=m->len;CHECK(!nf_ct_encode_tuple(m,&b));uint16_t type=CTA_TUPLE_REPLY|NLA_F_NESTED;memcpy(m->data+pos+2,&type,2);
    uint32_t x=htonl(123);CHECK(!nf_ct_attr(m,CTA_ID,&x,4));x=htonl(IPS_DST_NAT|IPS_DST_NAT_DONE);CHECK(!nf_ct_attr(m,CTA_STATUS,&x,4));x=htonl(128);CHECK(!nf_ct_attr(m,CTA_MARK,&x,4));
    if(zone){uint16_t z=htons(zone);CHECK(!nf_ct_attr(m,CTA_ZONE,&z,2));}
    h.nlmsg_len=(uint32_t)m->len;memcpy(m->data,&h,sizeof(h));
}
int main(void) {
    struct nf_ct_tuple a={.family=AF_INET,.proto=IPPROTO_UDP,.sport=12345,.dport=53},b=a;
    inet_pton(AF_INET,"192.168.1.2",a.src);inet_pton(AF_INET,"192.168.1.1",a.dst);b.sport=15353;b.dport=a.sport;memcpy(b.src,a.dst,4);memcpy(b.dst,a.src,4);
    struct nf_ct_message req,reply;struct nf_ct_record r;CHECK(!nf_get_request(&a,0,1,&req));response(&reply,&a,b,0);
    CHECK(!nf_get_reply(reply.data,reply.len,42,&req,&a,0,&r) && r.id==123 && r.mark==128 && r.has_zone && !r.zone && r.reply.sport==15353);
    CHECK(nf_get_reply(reply.data,reply.len,43,&req,&a,0,&r)<0 && !r.has_id);
    CHECK(nf_get_reply(reply.data,reply.len-1,42,&req,&a,0,&r)<0);
    CHECK(nf_get_reply(reply.data,reply.len,42,&req,&a,1,&r)<0);
    struct nf_ct_tuple wrong=a;wrong.sport++;CHECK(nf_get_reply(reply.data,reply.len,42,&req,&wrong,0,&r)<0);
    struct nlmsghdr h;memcpy(&h,reply.data,sizeof(h));h.nlmsg_seq++;memcpy(reply.data,&h,sizeof(h));CHECK(nf_get_reply(reply.data,reply.len,42,&req,&a,0,&r)<0);
    response(&reply,&a,b,7);CHECK(!nf_get_request(&a,7,1,&req));CHECK(!nf_get_reply(reply.data,reply.len,42,&req,&a,7,&r) && r.zone==7);
    // Duplicate known attributes cannot replace the first identity.
    uint32_t id=htonl(456);CHECK(!nf_ct_attr(&reply,CTA_ID,&id,4));memcpy(&h,reply.data,sizeof(h));h.nlmsg_len=reply.len;memcpy(reply.data,&h,sizeof(h));CHECK(nf_get_reply(reply.data,reply.len,42,&req,&a,7,&r)<0);
    struct nlmsgerr e={.error=-ENOENT};memcpy(&e.msg,req.data,sizeof(e.msg));h=(struct nlmsghdr){.nlmsg_len=NLMSG_LENGTH(sizeof(e)),.nlmsg_type=NLMSG_ERROR,.nlmsg_seq=1,.nlmsg_pid=42};
    memcpy(reply.data,&h,sizeof(h));memcpy(reply.data+NLMSG_HDRLEN,&e,sizeof(e));reply.len=NLMSG_ALIGN(h.nlmsg_len);
    CHECK(nf_get_reply(reply.data,reply.len,42,&req,&a,7,&r)==1 && !r.has_id);e.error=-EPERM;memcpy(reply.data+NLMSG_HDRLEN,&e,sizeof(e));CHECK(nf_get_reply(reply.data,reply.len,42,&req,&a,7,&r)<0);
    e.error=-ENOENT;e.msg.nlmsg_seq++;memcpy(reply.data+NLMSG_HDRLEN,&e,sizeof(e));CHECK(nf_get_reply(reply.data,reply.len,42,&req,&a,7,&r)<0);
    a.family=b.family=AF_INET6;a.proto=b.proto=IPPROTO_TCP;inet_pton(AF_INET6,"fd00::2",a.src);inet_pton(AF_INET6,"fd00::1",a.dst);memcpy(b.src,a.dst,16);memcpy(b.dst,a.src,16);
    CHECK(!nf_get_request(&a,0,1,&req));response(&reply,&a,b,0);CHECK(!nf_get_reply(reply.data,reply.len,42,&req,&a,0,&r) && r.orig.family==AF_INET6 && r.orig.proto==IPPROTO_TCP);
    // Multipart parser: busy, clean, interrupted, malformed and foreign responses.
    bool done=false;memcpy(&h,reply.data,sizeof(h));h.nlmsg_flags=NLM_F_MULTI;memcpy(reply.data,&h,sizeof(h));
    CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,15353,&done)==1 && !done);
    CHECK(!nf_dump_part(reply.data,reply.len,42,AF_INET6,15354,&done) && !done);
    CHECK(nf_dump_part(reply.data,reply.len,43,AF_INET6,15354,&done)<0);
    CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET,15354,&done)<0);
    CHECK(nf_dump_part(reply.data,reply.len-1,42,AF_INET6,15354,&done)<0);
    h.nlmsg_flags|=NLM_F_DUMP_INTR;memcpy(reply.data,&h,sizeof(h));CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,15354,&done)<0);
    struct {struct nlmsghdr h;int error;} end={.h={.nlmsg_len=NLMSG_LENGTH(sizeof(int)),.nlmsg_type=NLMSG_DONE,.nlmsg_flags=NLM_F_MULTI,.nlmsg_seq=1,.nlmsg_pid=42}};
    CHECK(!nf_dump_part((const uint8_t*)&end,sizeof(end),42,AF_INET6,15354,&done) && done);
    CHECK(nf_dump_part((const uint8_t*)&end,sizeof(end),42,AF_INET6,15354,&done)<0);
    done=false;end.error=-EINTR;CHECK(nf_dump_part((const uint8_t*)&end,sizeof(end),42,AF_INET6,15354,&done)<0 && !done);
    CHECK(nf_gateway_ct_port_absent(53)<0);
    printf("Live read-only DUMP result: %d\n",nf_gateway_ct_port_absent(15353));
    int live=nf_gateway_ct_get(&a,0,&r);printf("Live read-only GET result: %d (0=record, 1=absent, -1=error)\n",live);
    printf("PASS %u netlink checks\n",checks);return 0;
}
#endif
