// SPDX-License-Identifier: GPL-3.0
// Exact conntrack deletion preparation. No live deletion or mark allocation.
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <string.h>
#include <arpa/inet.h>
#include <linux/netlink.h>
#include <linux/netfilter/nfnetlink.h>
#include <linux/netfilter/nfnetlink_conntrack.h>
#include <linux/netfilter/nf_conntrack_common.h>
#include "rules/marks.h"

struct nf_ct_tuple {
    uint8_t family, proto;
    uint16_t sport, dport;
    uint8_t src[16], dst[16];
};
struct nf_ct_record {
    struct nf_ct_tuple orig, reply;
    uint32_t id, mark, status;
    uint16_t zone;
    bool has_id, has_mark, has_status, has_zone;
};
struct nf_ct_owner {
    uint32_t mark, mask;
    uint16_t port, zone;
    bool verified_exclusive; // mandatory independent live ownership proof
};
static bool nf_ct_tuple_eq(const struct nf_ct_tuple *a,const struct nf_ct_tuple *b) {
    size_t n=a->family==AF_INET?4:16;
    return a->family==b->family && a->proto==b->proto && a->sport==b->sport && a->dport==b->dport &&
        !memcmp(a->src,b->src,n) && !memcmp(a->dst,b->dst,n);
}
static bool nf_ct_valid(const struct nf_ct_owner *o,const struct nf_ct_record *r) {
    if(!o || !r || !o->verified_exclusive || !o->mask || !o->mark || (o->mark & ~o->mask) ||
       (o->mask & ASTERISKD_DNS_MARK_MASK) || o->port<1024 || !r->has_id || !r->id ||
       !r->has_mark || !r->has_status || !r->has_zone || r->zone!=o->zone ||
       (r->mark & o->mask)!=o->mark || !(r->status & IPS_DST_NAT) ||
       !(r->status & IPS_DST_NAT_DONE) || (r->status & IPS_SRC_NAT) ||
       (r->status & IPS_DYING)) return false;
    const struct nf_ct_tuple *a=&r->orig,*b=&r->reply;
    size_t n=a->family==AF_INET?4:16;
    return (a->family==AF_INET || a->family==AF_INET6) && a->family==b->family &&
        (a->proto==IPPROTO_TCP || a->proto==IPPROTO_UDP) && a->proto==b->proto &&
        a->sport && a->dport==53 && b->sport==o->port && b->dport==a->sport &&
        !memcmp(a->src,b->dst,n);
}
// The old epoch inventory must be obtained from a trusted, complete dump.
// Fresh GET must still identify the same tuple pair, mark, zone and kernel ID.
static bool nf_ct_same(const struct nf_ct_record *a,const struct nf_ct_record *b) {
    return a->id==b->id && a->zone==b->zone && a->mark==b->mark &&
        nf_ct_tuple_eq(&a->orig,&b->orig) && nf_ct_tuple_eq(&a->reply,&b->reply);
}
struct nf_ct_message { uint8_t data[256]; size_t len; };
static int nf_ct_attr(struct nf_ct_message *m,uint16_t type,const void *data,size_t len) {
    size_t total=NLA_ALIGN(NLA_HDRLEN+len);
    if(m->len>sizeof(m->data) || total>sizeof(m->data)-m->len) return -1;
    struct nlattr a={.nla_len=(uint16_t)(NLA_HDRLEN+len),.nla_type=type};
    memset(m->data+m->len,0,total);memcpy(m->data+m->len,&a,sizeof(a));
    if(len) memcpy(m->data+m->len+NLA_HDRLEN,data,len);
    m->len+=total;return 0;
}
static int nf_ct_nest(struct nf_ct_message *m,uint16_t type,size_t *start) {
    *start=m->len;return nf_ct_attr(m,type|NLA_F_NESTED,NULL,0);
}
static void nf_ct_end(struct nf_ct_message *m,size_t start) {
    uint16_t n=(uint16_t)(m->len-start);memcpy(m->data+start,&n,sizeof(n));
}
static int nf_ct_encode_tuple(struct nf_ct_message *m,const struct nf_ct_tuple *t) {
    size_t tuple,ip,proto;bool v6=t->family==AF_INET6;size_t n=v6?16:4;
    uint16_t sp=htons(t->sport),dp=htons(t->dport);
    if(nf_ct_nest(m,CTA_TUPLE_ORIG,&tuple) || nf_ct_nest(m,CTA_TUPLE_IP,&ip) ||
       nf_ct_attr(m,v6?CTA_IP_V6_SRC:CTA_IP_V4_SRC,t->src,n) ||
       nf_ct_attr(m,v6?CTA_IP_V6_DST:CTA_IP_V4_DST,t->dst,n)) return -1;
    nf_ct_end(m,ip);
    if(nf_ct_nest(m,CTA_TUPLE_PROTO,&proto) || nf_ct_attr(m,CTA_PROTO_NUM,&t->proto,1) ||
       nf_ct_attr(m,CTA_PROTO_SRC_PORT,&sp,2) || nf_ct_attr(m,CTA_PROTO_DST_PORT,&dp,2)) return -1;
    nf_ct_end(m,proto);nf_ct_end(m,tuple);return 0;
}
// Never emits tuple-less DELETE (kernel interprets that as a table flush).
// The kernel checks ID as well as tuple/zone; mark is a userspace precondition,
// not an atomic delete predicate. Caller must serialize trusted mark writers.
int nf_gateway_ct_delete_request(const struct nf_ct_owner *owner,const struct nf_ct_record *old,
    const struct nf_ct_record *fresh,uint32_t seq,struct nf_ct_message *out) {
    if(!out) return -1;
    memset(out,0,sizeof(*out));
    if(!seq || !nf_ct_valid(owner,old) || !nf_ct_valid(owner,fresh) || !nf_ct_same(old,fresh)) return -1;
    struct nlmsghdr h={.nlmsg_type=(NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_DELETE,
        .nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK,.nlmsg_seq=seq};
    struct nfgenmsg g={.nfgen_family=fresh->orig.family,.version=NFNETLINK_V0};
    out->len=NLMSG_HDRLEN+sizeof(g);memcpy(out->data,&h,sizeof(h));memcpy(out->data+NLMSG_HDRLEN,&g,sizeof(g));
    uint32_t id=htonl(fresh->id);uint16_t zone=htons(fresh->zone);
    if(nf_ct_encode_tuple(out,&fresh->orig) || nf_ct_attr(out,CTA_ZONE,&zone,2) || nf_ct_attr(out,CTA_ID,&id,4)) {
        memset(out,0,sizeof(*out));return -1;
    }
    h.nlmsg_len=(uint32_t)out->len;memcpy(out->data,&h,sizeof(h));return 0;
}
#ifdef NF_CT_HOST_TEST
#include <stdio.h>
#include <stdlib.h>
static unsigned checks;
#define CHECK(x) do {if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
static void check_wire(const struct nf_ct_message *m,uint8_t family,uint32_t id,uint16_t zone) {
    struct nlmsghdr h;memcpy(&h,m->data,sizeof(h));
    CHECK(h.nlmsg_len==m->len && h.nlmsg_type==((NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_DELETE));
    CHECK(h.nlmsg_flags==(NLM_F_REQUEST|NLM_F_ACK) && m->data[NLMSG_HDRLEN]==family);
    size_t off=NLMSG_HDRLEN+sizeof(struct nfgenmsg);unsigned seen=0;
    while(off<m->len) {
        struct nlattr a;memcpy(&a,m->data+off,sizeof(a));
        CHECK(a.nla_len>=NLA_HDRLEN && off+NLA_ALIGN(a.nla_len)<=m->len);
        const uint8_t *p=m->data+off+NLA_HDRLEN;
        if(a.nla_type==(CTA_TUPLE_ORIG|NLA_F_NESTED)) {CHECK(!(seen&1) && a.nla_len>NLA_HDRLEN);seen|=1;}
        else if(a.nla_type==CTA_ZONE) {uint16_t z;memcpy(&z,p,2);CHECK(!(seen&2) && a.nla_len==NLA_HDRLEN+2 && ntohs(z)==zone);seen|=2;}
        else if(a.nla_type==CTA_ID) {uint32_t i;memcpy(&i,p,4);CHECK(!(seen&4) && a.nla_len==NLA_HDRLEN+4 && ntohl(i)==id);seen|=4;}
        else CHECK(false);
        off+=NLA_ALIGN(a.nla_len);
    }
    CHECK(seen==7 && off==m->len);
}
int main(void) {
    // Synthetic mask for host tests ONLY; not an Android mark allocation.
    struct nf_ct_owner o={.mark=0x80,.mask=0xff,.port=15353,.zone=7,.verified_exclusive=true};
    struct nf_ct_record a={.orig={.family=AF_INET,.proto=IPPROTO_UDP,.sport=12345,.dport=53},
        .reply={.family=AF_INET,.proto=IPPROTO_UDP,.sport=15353,.dport=12345},.id=123,.mark=0x80,
        .status=IPS_DST_NAT|IPS_DST_NAT_DONE,.zone=7,.has_id=true,.has_mark=true,.has_status=true,.has_zone=true};
    inet_pton(AF_INET,"192.168.1.2",a.orig.src);inet_pton(AF_INET,"192.168.1.1",a.orig.dst);
    memcpy(a.reply.dst,a.orig.src,4);memcpy(a.reply.src,a.orig.dst,4);
    struct nf_ct_record b=a;struct nf_ct_message m;
    CHECK(!nf_gateway_ct_delete_request(&o,&a,&b,1,&m));check_wire(&m,AF_INET,123,7);
#define REJECT(change) do {b=a;change;CHECK(nf_gateway_ct_delete_request(&o,&a,&b,1,&m)!=0 && !m.len);}while(0)
    REJECT(b.id++);REJECT(b.has_id=false);REJECT(b.mark=0);REJECT(b.mark|=0x100);
    REJECT(b.has_mark=false);REJECT(b.zone++);REJECT(b.has_zone=false);REJECT(b.status=0);
    REJECT(b.status|=IPS_SRC_NAT);REJECT(b.status|=IPS_DYING);REJECT(b.has_status=false);
    REJECT(b.orig.dport=853);REJECT(b.reply.sport=15354);REJECT(b.orig.sport++);
    REJECT(b.reply.dst[0]++);REJECT(b.reply.src[0]++);REJECT(b.orig.proto=IPPROTO_TCP);
    REJECT(b.orig.family=AF_INET6);REJECT(b.orig.dst[0]++);
    b=a;o.verified_exclusive=false;CHECK(nf_gateway_ct_delete_request(&o,&a,&b,1,&m)!=0 && !m.len);
    o.verified_exclusive=true;o.mask|=ASTERISKD_DNS_MARK_MASK;CHECK(nf_gateway_ct_delete_request(&o,&a,&b,1,&m)!=0 && !m.len);
    o.mask=0xff;CHECK(nf_gateway_ct_delete_request(&o,&a,&b,0,&m)!=0 && !m.len);
    a.orig.family=a.reply.family=AF_INET6;a.orig.proto=a.reply.proto=IPPROTO_TCP;
    inet_pton(AF_INET6,"fd00::2",a.orig.src);inet_pton(AF_INET6,"fd00::1",a.orig.dst);
    memcpy(a.reply.dst,a.orig.src,16);memcpy(a.reply.src,a.orig.dst,16);b=a;
    CHECK(!nf_gateway_ct_delete_request(&o,&a,&b,2,&m));check_wire(&m,AF_INET6,123,7);
    printf("PASS %u conntrack request checks\n",checks);return 0;
}
#endif
