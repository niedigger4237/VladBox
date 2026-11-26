// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0

#ifndef HEV_FLOW_METADATA_H
#define HEV_FLOW_METADATA_H

#include "flow-metadata.h"
#include "hev-config.h"
#include "hev-utils.h"
#include <hev-task-mutex.h>
#include "hev-socks5-session-udp.h"
#include <hev-task-io.h>
#include <hev-socks5-misc.h>

static inline int hev_flow_enabled(void) {
    HevConfigServer *s = hev_config_get_socks5_server();
    return s->user && !strcmp(s->user, FLOW_METADATA_USERNAME) && s->pass && strlen(s->pass) == 32;
}

static inline int hev_flow_endpoint(const HevSocks5Addr *a, FlowMetadataEndpoint *out) {
    memset(out, 0, sizeof(*out));
    if (a->atype == HEV_SOCKS5_ADDR_TYPE_IPV4) {
        out->width = 4;
        memcpy(out->address, a->ipv4.addr, 4);
        memcpy(&out->port, &a->ipv4.port, 2);
    } else if (a->atype == HEV_SOCKS5_ADDR_TYPE_IPV6) {
        out->width = 16;
        memcpy(out->address, a->ipv6.addr, 16);
        memcpy(&out->port, &a->ipv6.port, 2);
    } else return -1;
    return 0;
}

static inline int hev_flow_lwip_endpoint(const ip_addr_t *ip, uint16_t port, FlowMetadataEndpoint *out) {
    memset(out, 0, sizeof(*out));
    if (IP_IS_V4(ip)) {
        out->width = 4;
        memcpy(out->address, &ip_2_ip4(ip)->addr, 4);
    } else if (IP_IS_V6(ip)) {
        out->width = 16;
        memcpy(out->address, ip_2_ip6(ip)->addr, 16);
    } else return -1;
    out->port = htons(port);
    return 0;
}

static inline size_t hev_flow_header(
    uint8_t *out, const ip_addr_t *ip, uint16_t port, const HevSocks5Addr *dst, int udp) {
    FlowMetadataEndpoint s, d;
    if (hev_flow_lwip_endpoint(ip, port, &s) || hev_flow_endpoint(dst, &d)) return 0;
    return flow_metadata_encode_endpoints(out, FLOW_METADATA_MAX_HEADER, s, d, udp,
        FLOW_METADATA_UNKNOWN_UID, hev_config_get_socks5_server()->pass);
}

static inline size_t hev_flow_tcp_header(uint8_t *out, const ip_addr_t *src, uint16_t sport,
    const ip_addr_t *dst, uint16_t dport) {
    FlowMetadataEndpoint s, d;
    if (hev_flow_lwip_endpoint(src, sport, &s) || hev_flow_lwip_endpoint(dst, dport, &d)) return 0;
    return flow_metadata_encode_endpoints(out, FLOW_METADATA_MAX_HEADER, s, d, 0,
        FLOW_METADATA_UNKNOWN_UID, hev_config_get_socks5_server()->pass);
}

void hev_flow_run(HevSocks5Session *self);
int hev_flow_sendmmsg(
    HevSocks5SessionUDP *self, HevSocks5UDPMsg *msgv, unsigned int count, HevTaskIOYielder yielder);
int hev_flow_recvmmsg(HevSocks5SessionUDP *self, HevSocks5UDPMsg *msgv, unsigned int count);

#endif
