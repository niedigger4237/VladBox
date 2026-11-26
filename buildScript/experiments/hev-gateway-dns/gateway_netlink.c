// SPDX-License-Identifier: GPL-3.0
// CTNETLINK observation and gated exact deletion. Compile instead of gateway_conntrack.c.
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
/* A dump is an occupancy observation, not a mark lease. Inventory requires
 * an independent exclusive-writer proof and a quiesced old session. */
struct nf_ct_scan {
    const struct nf_ct_owner *owner;
    struct nf_ct_record *records;
    size_t capacity, count;
    bool preflight;
};
static bool nf_scan_owner_valid(const struct nf_ct_owner *o) {
    return o && o->mask && o->mark && !(o->mark & ~o->mask) &&
        !(o->mask & ASTERISKD_DNS_MARK_MASK) && o->port>=1024;
}
static int nf_scan_record(struct nf_ct_scan *s,const struct nf_attr_view *v,
                          const struct nf_ct_tuple *orig,const struct nf_ct_tuple *reply) {
    const struct nf_ct_owner *o=s->owner;
    uint32_t x=0,mark=0;
    if(v[CTA_MARK].p) {
        if(nf_scalar(&v[CTA_MARK],&x,4))return -1;
        mark=ntohl(x);
    }
    bool port_match=reply && reply->sport==o->port;
    if(s->preflight)return ((mark&o->mask) || port_match)?1:0;
    if(!(mark&o->mask) && !port_match)return 0;
    // Partial mask, foreign port, another protocol, or another zone is a
    // collision, not a deletable entry. Never return a partial inventory.
    if(!orig || !reply || !port_match || (mark&o->mask)!=o->mark)return 1;
    struct nf_ct_record r={.orig=*orig,.reply=*reply,.mark=mark,.has_mark=v[CTA_MARK].p!=NULL};
    uint16_t z=0;
    if(nf_scalar(&v[CTA_ID],&x,4))return -1;
    r.id=ntohl(x);r.has_id=true;
    if(nf_scalar(&v[CTA_STATUS],&x,4))return -1;
    r.status=ntohl(x);r.has_status=true;
    if(v[CTA_ZONE].p && nf_scalar(&v[CTA_ZONE],&z,2))return -1;
    r.zone=ntohs(z);r.has_zone=true;
    if(!nf_ct_valid(o,&r))return 1;
    for(size_t i=0;i<s->count;i++) {
        const struct nf_ct_record *old=&s->records[i];
        if(old->zone==r.zone && nf_ct_tuple_eq(&old->orig,&r.orig))return -1;
    }
    if(s->count>=s->capacity)return -1;
    s->records[s->count++]=r;return 0;
}

static int nf_dump_part(const uint8_t *buf,size_t n,uint32_t local,uint8_t family,unsigned port,bool *done,struct nf_ct_scan *scan) {
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
            if(scan) {int rc=nf_scan_record(scan,v,&orig,&reply);if(rc)return rc;}
            else if(reply.sport==port)return 1;
        } else if(scan) {
            int rc=nf_scan_record(scan,v,NULL,NULL);if(rc)return rc;
        }
        buf+=step;n-=step;
    }
    return 0;
}
static int64_t nf_monotonic_ms(void) {
    struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t))return -1;
    return (int64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static int nf_dump_family(uint8_t family,unsigned port,struct nf_ct_scan *scan) {
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
        int part=nf_dump_part(buf,(size_t)n,local.nl_pid,family,port,&complete,scan);
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
    int rc=nf_dump_family(AF_INET,port,NULL);if(rc)return rc;
    return nf_dump_family(AF_INET6,port,NULL);
}

/* 0 = no collision observed, 1 = occupied, -1 = incomplete inspection.
 * A successful scan cannot grant exclusive ownership of future writes. */
int nf_gateway_ct_mask_available(const struct nf_ct_owner *o) {
    if(!nf_scan_owner_valid(o))return -1;
    struct nf_ct_scan scan={.owner=o,.preflight=true};
    int rc=nf_dump_family(AF_INET,o->port,&scan);if(rc)return rc;
    return nf_dump_family(AF_INET6,o->port,&scan);
}
/* Caller must withdraw verified NAT, retain INPUT guard and prove that the
 * old session exclusively owned the mask throughout its lifetime. Success
 * is a complete observed inventory, not permission to delete without GET. */
int nf_gateway_ct_inventory(const struct nf_ct_owner *o,struct nf_ct_record *records,
                            size_t capacity,size_t *count) {
    if(!count)return -1;
    *count=0;
    if(!nf_scan_owner_valid(o) || !o->verified_exclusive || !records || !capacity || capacity>4096U)return -1;
    struct nf_ct_scan scan={.owner=o,.records=records,.capacity=capacity};
    int rc=nf_dump_family(AF_INET,o->port,&scan);
    if(!rc)rc=nf_dump_family(AF_INET6,o->port,&scan);
    if(rc) {memset(records,0,scan.count*sizeof(*records));return rc;}
    *count=scan.count;return 0;
}

/* Only an exact, acknowledged DELETE is accepted. No dump/flush path. */
static int nf_delete_ack(const uint8_t *buf,size_t n,uint32_t local,const struct nf_ct_message *request) {
    struct nlmsghdr h,q;struct nlmsgerr e;
    if(n<NLMSG_LENGTH(sizeof(e)) || request->len<NLMSG_HDRLEN)return -1;
    memcpy(&h,buf,sizeof(h));memcpy(&q,request->data,sizeof(q));
    if(q.nlmsg_type!=((NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_DELETE) ||
       q.nlmsg_flags!=(NLM_F_REQUEST|NLM_F_ACK) || q.nlmsg_len!=request->len ||
       h.nlmsg_type!=NLMSG_ERROR || h.nlmsg_len!=n || h.nlmsg_seq!=q.nlmsg_seq ||
       h.nlmsg_pid!=local || (h.nlmsg_flags&~NLM_F_CAPPED))return -1;
    memcpy(&e,buf+NLMSG_HDRLEN,sizeof(e));
    if(memcmp(&e.msg,&q,sizeof(q)))return -1;
    size_t base=NLMSG_LENGTH(sizeof(e)),body=request->len-NLMSG_HDRLEN;
    if(h.nlmsg_flags&NLM_F_CAPPED) {if(n!=base)return -1;}
    else if(e.error) {if(n!=base+body || memcmp(buf+base,request->data+NLMSG_HDRLEN,body))return -1;}
    else if(n!=base)return -1;
    return !e.error?0:e.error==-ENOENT?1:-1;
}
static int nf_delete_exchange(struct nf_ct_message *request) {
    int fd=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC|SOCK_NONBLOCK,NETLINK_NETFILTER);if(fd<0)return -1;
    struct sockaddr_nl local={.nl_family=AF_NETLINK},kernel={.nl_family=AF_NETLINK};socklen_t sl=sizeof(local);int rc=-1;
    if(bind(fd,(struct sockaddr*)&local,sizeof(local)) || getsockname(fd,(struct sockaddr*)&local,&sl) ||
       sl!=sizeof(local) || local.nl_family!=AF_NETLINK || !local.nl_pid || local.nl_groups)goto done;
    struct nlmsghdr h;memcpy(&h,request->data,sizeof(h));h.nlmsg_pid=local.nl_pid;memcpy(request->data,&h,sizeof(h));
    if(sendto(fd,request->data,request->len,0,(struct sockaddr*)&kernel,sizeof(kernel))!=(ssize_t)request->len)goto done;
    struct pollfd p={.fd=fd,.events=POLLIN};if(poll(&p,1,1000)!=1 || p.revents!=POLLIN)goto done;
    uint8_t buf[16384];struct sockaddr_nl sender={0};struct iovec iov={buf,sizeof(buf)};
    struct msghdr msg={.msg_name=&sender,.msg_namelen=sizeof(sender),.msg_iov=&iov,.msg_iovlen=1};
    ssize_t n=recvmsg(fd,&msg,0);
    if(n<=0 || (msg.msg_flags&(MSG_TRUNC|MSG_CTRUNC)) || msg.msg_namelen!=sizeof(sender) ||
       sender.nl_family!=AF_NETLINK || sender.nl_pid || sender.nl_groups)goto done;
    rc=nf_delete_ack(buf,(size_t)n,local.nl_pid,request);
 done: close(fd);return rc;
}
/* Recheck must validate the live exclusive mark contract, withdrawn NAT
 * and closed guard under the caller's session lock. A callback is required;
 * a successful occupancy scan alone does not satisfy this contract. */
typedef int (*nf_ct_recheck_fn)(void *,const struct nf_ct_owner *);
static int nf_delete_checked(const struct nf_ct_owner *owner,const struct nf_ct_record *old,
        nf_ct_recheck_fn recheck,void *ctx,
        int (*get)(const struct nf_ct_tuple *,uint16_t,struct nf_ct_record *),
        int (*send_delete)(struct nf_ct_message *)) {
    if(!recheck || !get || !send_delete || !nf_ct_valid(owner,old) || recheck(ctx,owner))return -1;
    struct nf_ct_record fresh={0};struct nf_ct_message request;
    int rc=get(&old->orig,old->zone,&fresh);
    if(rc==1)return recheck(ctx,owner)?-1:0;
    if(rc || nf_gateway_ct_delete_request(owner,old,&fresh,1,&request) || recheck(ctx,owner))return -1;
    // Kernel DELETE compares tuple and ID, not the mark atomically.
    rc=send_delete(&request);
    if(rc!=0 && rc!=1)return -1; // ambiguous ACK: retain debt, no blind retry
    if(get(&old->orig,old->zone,&fresh)!=1 || recheck(ctx,owner))return -1;
    return 0;
}
/* One old inventory entry per call. Only success or verified absence can
 * discharge that entry. No replacement flow is deleted on mismatch. Not
 * invoked by the supervisor until real device ownership is established. */
int nf_gateway_ct_delete_owned(const struct nf_ct_owner *owner,const struct nf_ct_record *old,
        nf_ct_recheck_fn recheck,void *ctx) {
    return nf_delete_checked(owner,old,recheck,ctx,nf_gateway_ct_get,nf_delete_exchange);
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
    CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,15353,&done,NULL)==1 && !done);
    CHECK(!nf_dump_part(reply.data,reply.len,42,AF_INET6,15354,&done,NULL) && !done);
    CHECK(nf_dump_part(reply.data,reply.len,43,AF_INET6,15354,&done,NULL)<0);
    CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET,15354,&done,NULL)<0);
    CHECK(nf_dump_part(reply.data,reply.len-1,42,AF_INET6,15354,&done,NULL)<0);
    h.nlmsg_flags|=NLM_F_DUMP_INTR;memcpy(reply.data,&h,sizeof(h));CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,15354,&done,NULL)<0);
    struct {struct nlmsghdr h;int error;} end={.h={.nlmsg_len=NLMSG_LENGTH(sizeof(int)),.nlmsg_type=NLMSG_DONE,.nlmsg_flags=NLM_F_MULTI,.nlmsg_seq=1,.nlmsg_pid=42}};
    CHECK(!nf_dump_part((const uint8_t*)&end,sizeof(end),42,AF_INET6,15354,&done,NULL) && done);
    CHECK(nf_dump_part((const uint8_t*)&end,sizeof(end),42,AF_INET6,15354,&done,NULL)<0);
    done=false;end.error=-EINTR;CHECK(nf_dump_part((const uint8_t*)&end,sizeof(end),42,AF_INET6,15354,&done,NULL)<0 && !done);
    CHECK(nf_gateway_ct_port_absent(53)<0);
    struct nf_ct_owner owner={.mark=128,.mask=255,.port=15353,.zone=0,.verified_exclusive=true};
    struct nf_ct_record entries[2];size_t count=99;
    struct nf_ct_scan scan={.owner=&owner,.preflight=true};
    response(&reply,&a,b,0);memcpy(&h,reply.data,sizeof(h));h.nlmsg_flags=NLM_F_MULTI;memcpy(reply.data,&h,sizeof(h));done=false;
    CHECK(nf_scan_owner_valid(&owner));CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan)==1);
    owner.port=15354;CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan)==1); // mask occupied elsewhere
    owner.port=15353;owner.mask=64;owner.mark=64;CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan)==1); // port occupied without mark
    owner.port=15354;CHECK(!nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan));
    owner.port=15353;owner.mask=255;owner.mark=128;scan.preflight=false;scan.records=entries;scan.capacity=2;
    CHECK(!nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan) && scan.count==1 && entries[0].id==123 && entries[0].has_zone);
    CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan)<0 && scan.count==1); // duplicate tuple
    scan.count=0;scan.capacity=0;CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan)<0 && !scan.count);
    scan.capacity=2;owner.zone=7;CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan)==1 && !scan.count);
    owner.zone=0;owner.mark=64;CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan)==1 && !scan.count);
    owner.mark=128;owner.port=15354;CHECK(nf_dump_part(reply.data,reply.len,42,AF_INET6,owner.port,&done,&scan)==1 && !scan.count);
    owner.port=15353;owner.verified_exclusive=false;CHECK(nf_gateway_ct_inventory(&owner,entries,2,&count)<0 && !count);
    owner.verified_exclusive=true;owner.mask|=ASTERISKD_DNS_MARK_MASK;CHECK(nf_gateway_ct_mask_available(&owner)<0);CHECK(nf_gateway_ct_inventory(&owner,entries,2,&count)<0 && !count);
    owner.mask=255;CHECK(nf_gateway_ct_inventory(&owner,entries,4097,&count)<0 && !count);
    struct nf_attr_view attrs[CTA_MAX+1]={0};uint32_t mark=htonl(128);attrs[CTA_MARK]=(struct nf_attr_view){(uint8_t*)&mark,4};
    CHECK(nf_scan_record(&scan,attrs,NULL,NULL)==1 && !scan.count); // marked non-TCP/UDP
    attrs[CTA_MARK].n=3;CHECK(nf_scan_record(&scan,attrs,NULL,NULL)<0);
    printf("Live read-only DUMP result: %d\n",nf_gateway_ct_port_absent(15353));
    int live=nf_gateway_ct_get(&a,0,&r);printf("Live read-only GET result: %d (0=record, 1=absent, -1=error)\n",live);
    printf("PASS %u netlink checks\n",checks);return 0;
}
#endif
#ifdef NF_DELETE_HOST_TEST
#include <stdio.h>
#include <stdlib.h>
static unsigned checks;
#define CHECK(x) do {if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
static struct nf_ct_record sample;
static struct {unsigned gets,sends,verifies,fail_verify;int before,after,ack;bool changed;} mock;
static int fake_get(const struct nf_ct_tuple *t,uint16_t z,struct nf_ct_record *out) {
    if(!nf_ct_tuple_eq(t,&sample.orig) || z!=sample.zone)return -1;
    *out=sample;if(mock.changed)out->id++;
    return mock.gets++?mock.after:mock.before;
}
static int fake_delete(struct nf_ct_message *m) {
    struct nlmsghdr h;memcpy(&h,m->data,sizeof(h));mock.sends++;
    return h.nlmsg_type==((NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_DELETE) && m->len>NLMSG_HDRLEN+sizeof(struct nfgenmsg)?mock.ack:-1;
}
static int fake_recheck(void *ctx,const struct nf_ct_owner *o) {
    (void)ctx;(void)o;return ++mock.verifies==mock.fail_verify?-1:0;
}
static void reset(void){memset(&mock,0,sizeof(mock));mock.after=1;}
static size_t ack(uint8_t *buf,const struct nf_ct_message *req,int err,bool capped) {
    struct nlmsgerr e={.error=err};memcpy(&e.msg,req->data,sizeof(e.msg));
    size_t n=NLMSG_LENGTH(sizeof(e));
    if(err&&!capped){memcpy(buf+n,req->data+NLMSG_HDRLEN,req->len-NLMSG_HDRLEN);n+=req->len-NLMSG_HDRLEN;}
    struct nlmsghdr h={.nlmsg_type=NLMSG_ERROR,.nlmsg_len=(uint32_t)n,.nlmsg_seq=e.msg.nlmsg_seq,.nlmsg_pid=42,.nlmsg_flags=capped?NLM_F_CAPPED:0};
    memcpy(buf,&h,sizeof(h));memcpy(buf+NLMSG_HDRLEN,&e,sizeof(e));return n;
}
int main(void) {
    struct nf_ct_owner owner={.mark=0x100000,.mask=0x300000,.port=15353,.zone=0,.verified_exclusive=true};
    sample=(struct nf_ct_record){.orig={.family=AF_INET,.proto=IPPROTO_UDP,.sport=12345,.dport=53},.reply={.family=AF_INET,.proto=IPPROTO_UDP,.sport=15353,.dport=12345},.id=123,.mark=owner.mark,.status=IPS_DST_NAT|IPS_DST_NAT_DONE,.has_id=true,.has_mark=true,.has_status=true,.has_zone=true};
    inet_pton(AF_INET,"192.168.1.2",sample.orig.src);inet_pton(AF_INET,"192.168.1.1",sample.orig.dst);memcpy(sample.reply.src,sample.orig.dst,4);memcpy(sample.reply.dst,sample.orig.src,4);
    struct nf_ct_message req;uint8_t buf[512];size_t n;
    CHECK(!nf_gateway_ct_delete_request(&owner,&sample,&sample,1,&req));
    n=ack(buf,&req,0,true);CHECK(!nf_delete_ack(buf,n,42,&req));CHECK(nf_delete_ack(buf,n,43,&req)<0);CHECK(nf_delete_ack(buf,n-1,42,&req)<0);
    n=ack(buf,&req,-ENOENT,false);CHECK(nf_delete_ack(buf,n,42,&req)==1);buf[n-1]^=1;CHECK(nf_delete_ack(buf,n,42,&req)<0);
    n=ack(buf,&req,-EPERM,true);CHECK(nf_delete_ack(buf,n,42,&req)<0);
    n=ack(buf,&req,-ENOENT,true);CHECK(nf_delete_ack(buf,n,42,&req)==1);buf[NLMSG_HDRLEN+sizeof(int)+8]^=1;CHECK(nf_delete_ack(buf,n,42,&req)<0);
#define RUN() nf_delete_checked(&owner,&sample,fake_recheck,NULL,fake_get,fake_delete)
    reset();CHECK(!RUN() && mock.gets==2 && mock.sends==1 && mock.verifies==3);
    reset();mock.before=1;CHECK(!RUN() && !mock.sends && mock.gets==1);
    reset();mock.before=-1;CHECK(RUN()<0 && !mock.sends);
    reset();mock.changed=true;CHECK(RUN()<0 && !mock.sends);
    reset();mock.fail_verify=1;CHECK(RUN()<0 && !mock.gets && !mock.sends);
    reset();mock.fail_verify=2;CHECK(RUN()<0 && mock.gets==1 && !mock.sends);
    reset();mock.fail_verify=3;CHECK(RUN()<0 && mock.gets==2 && mock.sends==1);
    reset();mock.ack=-1;CHECK(RUN()<0 && mock.gets==1 && mock.sends==1);
    reset();mock.ack=1;CHECK(!RUN() && mock.gets==2 && mock.sends==1);
    reset();mock.after=0;CHECK(RUN()<0 && mock.gets==2 && mock.sends==1);
    reset();mock.after=-1;CHECK(RUN()<0 && mock.gets==2 && mock.sends==1);
    reset();owner.verified_exclusive=false;CHECK(RUN()<0 && !mock.gets && !mock.sends);
    CHECK(nf_gateway_ct_delete_owned(&owner,&sample,NULL,NULL)<0);
    printf("PASS %u delete transport checks (simulated; no live DELETE)\n",checks);return 0;
}
#endif
