// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0

#ifndef ASTERISK_FLOW_METADATA_H
#define ASTERISK_FLOW_METADATA_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <netinet/in.h>

#define FLOW_METADATA_USERNAME "__asterisk_flow_v1"
#define FLOW_METADATA_MAX_HEADER 92
#define FLOW_METADATA_UNKNOWN_UID UINT32_MAX

/* Explicit endpoint representation avoids aliasing sockaddr_storage through
 * unrelated structure types under optimized Android clang builds. */
typedef struct {
    uint8_t address[16];
    uint16_t port; /* network byte order */
    uint8_t width; /* 4 or 16 */
} FlowMetadataEndpoint;

static inline void flow_metadata_normalize(FlowMetadataEndpoint *a) {
    static const uint8_t mapped[12] = {0,0,0,0,0,0,0,0,0,0,0xff,0xff};
    if (a->width == 16 && !memcmp(a->address, mapped, sizeof(mapped))) {
        memmove(a->address, a->address + 12, 4);
        a->width = 4;
    }
}

/* PROXY v2, custom TLV 0xe0: version 1, 32-byte credential, uint32 UID. */
static inline size_t flow_metadata_encode_endpoints(uint8_t *out, size_t cap,
    FlowMetadataEndpoint source, FlowMetadataEndpoint destination, int udp, uint32_t uid, const char *token) {
    static const uint8_t signature[12] = {
        '\r', '\n', '\r', '\n', 0, '\r', '\n', 'Q', 'U', 'I', 'T', '\n'};
    flow_metadata_normalize(&source);
    flow_metadata_normalize(&destination);
    size_t width = source.width, n;
    if (!out || !token || strlen(token) != 32 || !source.port || !destination.port ||
        (width != 4 && width != 16) || width != destination.width)
        return 0;
    const void *src = source.address, *dst = destination.address;
    const void *sp = &source.port, *dp = &destination.port;
    n = 2 * width + 4;
    if (cap < 16 + n + 40) return 0;
    memcpy(out, signature, 12);
    out[12] = 0x21;
    out[13] = (width == 4 ? 0x10 : 0x20) | (udp ? 2 : 1);
    out[14] = 0;
    out[15] = (uint8_t)(n + 40);
    memcpy(out + 16, src, width);
    memcpy(out + 16 + width, dst, width);
    memcpy(out + 16 + 2 * width, sp, 2);
    memcpy(out + 18 + 2 * width, dp, 2);
    out[16 + n] = 0xe0;
    out[17 + n] = 0;
    out[18 + n] = 37;
    out[19 + n] = 1;
    memcpy(out + 20 + n, token, 32);
    out[52 + n] = (uint8_t)(uid >> 24);
    out[53 + n] = (uint8_t)(uid >> 16);
    out[54 + n] = (uint8_t)(uid >> 8);
    out[55 + n] = (uint8_t)uid;
    return 16 + n + 40;
}

/* UDP replies carry the actual remote peer as source, even for full-cone flows. */
static inline size_t flow_metadata_decode(const uint8_t *data, size_t len,
    struct sockaddr_storage *source, struct sockaddr_storage *destination, const char *token) {
    static const uint8_t signature[12] = {
        '\r', '\n', '\r', '\n', 0, '\r', '\n', 'Q', 'U', 'I', 'T', '\n'};
    size_t width, n;
    if (len < 16 || memcmp(data, signature, 12) || data[12] != 0x21 || data[14] != 0 || !token ||
        strlen(token) != 32)
        return 0;
    if (data[13] == 0x12)
        width = 4;
    else if (data[13] == 0x22)
        width = 16;
    else
        return 0;
    n = width * 2 + 4;
    if (data[15] != n + 40 || len < 16 + n + 40 || data[16 + n] != 0xe0 || data[17 + n] ||
        data[18 + n] != 37 || data[19 + n] != 1 || memcmp(data + 20 + n, token, 32))
        return 0;
    memset(source, 0, sizeof(*source));
    memset(destination, 0, sizeof(*destination));
    if (width == 4) {
        struct sockaddr_in *s = (void *)source, *d = (void *)destination;
        s->sin_family = d->sin_family = AF_INET;
        memcpy(&s->sin_addr, data + 16, 4);
        memcpy(&d->sin_addr, data + 20, 4);
        memcpy(&s->sin_port, data + 24, 2);
        memcpy(&d->sin_port, data + 26, 2);
    } else {
        struct sockaddr_in6 *s = (void *)source, *d = (void *)destination;
        s->sin6_family = d->sin6_family = AF_INET6;
        memcpy(&s->sin6_addr, data + 16, 16);
        memcpy(&d->sin6_addr, data + 32, 16);
        memcpy(&s->sin6_port, data + 48, 2);
        memcpy(&d->sin6_port, data + 50, 2);
    }
    return 16 + n + 40;
}
#endif
