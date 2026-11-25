// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0

#include "hev-flow-metadata.h"
#include "hev-logger.h"
#include "hev-config-const.h"
#include <hev-socks5-udp.h>
#include "hev-socks5-session-tcp.h"
#include "hev-socks5-tunnel.h"
#include <hev-task-io-socket.h>
#include <lwip/tcp.h>
#include <lwip/udp.h>
#include <errno.h>

void hev_flow_run(HevSocks5Session *self) {
    HevConfigServer *srv = hev_config_get_socks5_server();
    HevSocks5Client *client = HEV_SOCKS5_CLIENT(self);
    HevSocks5SessionIface *iface;
    /* Bound connect and metadata header transmission in both branches. */
    hev_socks5_set_timeout(HEV_SOCKS5(self), hev_config_get_misc_connect_timeout());
    if (HEV_SOCKS5(self)->type == HEV_SOCKS5_TYPE_TCP) {
        HevSocks5SessionTCP *tcp = HEV_SOCKS5_SESSION_TCP(self);
        uint8_t header[FLOW_METADATA_MAX_HEADER];
        size_t n, sent = 0;
        if (!tcp->pcb) return;
        if (hev_socks5_client_connect(client, srv->addr, srv->port) < 0) {
            LOG_W ("flow metadata TCP: local bridge connect failed");
            return;
        }
        // Connecting yields; a client reset can clear the PCB while we wait.
        if (!tcp->pcb) return;
        n = hev_flow_tcp_header(header, &tcp->pcb->remote_ip, tcp->pcb->remote_port,
                                &tcp->pcb->local_ip, tcp->pcb->local_port);
        if (!n) {
            LOG_W ("flow metadata TCP: cannot encode tuple (src type=%u port=%u, dst type=%u port=%u)",
                (unsigned)IP_GET_TYPE(&tcp->pcb->remote_ip), (unsigned)tcp->pcb->remote_port,
                (unsigned)IP_GET_TYPE(&tcp->pcb->local_ip), (unsigned)tcp->pcb->local_port);
            return;
        }
        while (sent < n) {
            int res = hev_task_io_socket_send(
                HEV_SOCKS5(self)->fd, header + sent, n - sent, 0, hev_socks5_task_io_yielder, self);
            if (res <= 0) {
                LOG_W ("flow metadata TCP: header send failed");
                return;
            }
            sent += res;
        }
    } else {
        struct sockaddr_in6 address = {0};
        HevSocks5Addr dst;
        HevSocks5ClientClass *klass = HEV_OBJECT_GET_CLASS(client);
        int family = HEV_SOCKS5_ADDR_FAMILY_UNSPEC;
        if (HEV_SOCKS5(self)->type != HEV_SOCKS5_TYPE_UDP_IN_UDP ||
            hev_socks5_name_into_sockaddr6(srv->addr, srv->port, &address, &family) < 0) {
            LOG_W ("flow metadata UDP: invalid relay configuration");
            return;
        }
        hev_socks5_set_addr_family(HEV_SOCKS5(self), family);
        hev_socks5_addr_from_sockaddr6(&dst, &address);
        if (klass->set_upstream_addr(client, &dst) < 0) {
            LOG_W ("flow metadata UDP: local bridge connect failed");
            return;
        }
    }
    hev_socks5_set_timeout(HEV_SOCKS5(self),
        HEV_SOCKS5(self)->type == HEV_SOCKS5_TYPE_TCP
            ? hev_config_get_misc_tcp_read_write_timeout()
            : hev_config_get_misc_udp_read_write_timeout());
    iface = HEV_OBJECT_GET_IFACE(self, HEV_SOCKS5_SESSION_TYPE);
    iface->splicer(self);
}

int hev_flow_sendmmsg(HevSocks5SessionUDP *self, HevSocks5UDPMsg *msgv, unsigned int count,
    HevTaskIOYielder yielder) {
    unsigned int i;
    if (!self->pcb || !msgv || count == 0 || count > UDP_SEND_BATCH) {
        errno = EINVAL;
        return -1;
    }
    struct mmsghdr messages[count];
    struct iovec iov[count * 2];
    uint8_t headers[count][FLOW_METADATA_MAX_HEADER];
    memset(messages, 0, sizeof(messages));
    for (i = 0; i < count; i++) {
        if (!msgv[i].addr || !msgv[i].buf) {
            errno = EINVAL;
            return -1;
        }
        size_t n = hev_flow_header(
            headers[i], &self->pcb->remote_ip, self->pcb->remote_port, msgv[i].addr, 1);
        if (!n) {
            LOG_W ("flow metadata UDP: cannot encode tuple (src type=%u port=%u, dst type=%u)",
                (unsigned)IP_GET_TYPE(&self->pcb->remote_ip), (unsigned)self->pcb->remote_port,
                (unsigned)msgv[i].addr->atype);
            return -1;
        }
        iov[2 * i] = (struct iovec){headers[i], n};
        iov[2 * i + 1] = (struct iovec){msgv[i].buf, msgv[i].len};
        messages[i].msg_hdr.msg_iov = &iov[2 * i];
        messages[i].msg_hdr.msg_iovlen = 2;
    }
    int fd = hev_socks5_udp_get_fd(HEV_SOCKS5_UDP(self));
    if (fd < 0) return -1;
    return hev_task_io_socket_sendmmsg(fd, messages, count, MSG_WAITALL, yielder, self);
}

int hev_flow_recvmmsg(HevSocks5SessionUDP *self, HevSocks5UDPMsg *msgv, unsigned int count) {
    int i;
    int res;
    if (!self->pcb || !msgv || count == 0 || count > 16) {
        errno = EINVAL;
        return -1;
    }
    struct mmsghdr messages[count];
    struct iovec iov[count];
    memset(messages, 0, sizeof(messages));
    for (i = 0; i < count; i++) {
        if (!msgv[i].buf || !msgv[i].len) {
            errno = EINVAL;
            return -1;
        }
        iov[i] = (struct iovec){msgv[i].buf, msgv[i].len};
        messages[i].msg_hdr.msg_iov = &iov[i];
        messages[i].msg_hdr.msg_iovlen = 1;
    }
    int fd = hev_socks5_udp_get_fd(HEV_SOCKS5_UDP(self));
    if (fd < 0) return -1;
    // The task-system API takes actual recv flags, unlike the socks5 wrapper's
    // boolean nonblock argument. Passing 1 would request MSG_OOB on Linux.
    res = hev_task_io_socket_recvmmsg(fd, messages, count, MSG_DONTWAIT, hev_socks5_task_io_yielder, self);
    for (i = 0; i < res; i++) {
        struct sockaddr_storage source, destination;
        /* Do not decode a length that exceeds the actual receive buffer.
         * MSG_TRUNC must be checked before any reads from the datagram. */
        if ((messages[i].msg_hdr.msg_flags & MSG_TRUNC) ||
            messages[i].msg_len > iov[i].iov_len) {
            msgv[i].addr = NULL;
            msgv[i].len = 0;
            continue;
        }
        size_t n = flow_metadata_decode(msgv[i].buf, messages[i].msg_len, &source, &destination,
            hev_config_get_socks5_server()->pass);
        if (!n) {
            msgv[i].addr = NULL;
            msgv[i].len = 0;
            continue;
        }
        msgv[i].addr = msgv[i].buf;
        if (source.ss_family == AF_INET) {
            struct sockaddr_in *address = (void *)&source;
            msgv[i].addr->atype = HEV_SOCKS5_ADDR_TYPE_IPV4;
            msgv[i].addr->ipv4.port = address->sin_port;
            memcpy(msgv[i].addr->ipv4.addr, &address->sin_addr, 4);
        } else {
            hev_socks5_addr_from_sockaddr6(msgv[i].addr, (void *)&source);
        }
        msgv[i].buf = (char *)msgv[i].buf + n;
        msgv[i].len = messages[i].msg_len - n;
    }
    return res;
}
