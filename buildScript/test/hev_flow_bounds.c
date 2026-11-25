#include "flow-metadata.h"
#include <assert.h>
#include <stdio.h>

static void check(unsigned width) {
    FlowMetadataEndpoint src = { .address = {10, 0, 0, 2}, .port = htons(12345), .width = width };
    FlowMetadataEndpoint dst = { .address = {192, 0, 2, 1}, .port = htons(53), .width = width };
    static const char token[] = "0123456789abcdef0123456789abcdef";
    unsigned char bytes[FLOW_METADATA_MAX_HEADER];
    struct sockaddr_storage a, b;
    size_t n = flow_metadata_encode_endpoints(bytes, sizeof(bytes), src, dst, 1, 10001, token);
    assert(n == (width == 4 ? 68 : 92));
    for (size_t i = 0; i < n; ++i)
        assert(flow_metadata_decode(bytes, i, &a, &b, token) == 0);
    assert(flow_metadata_decode(bytes, n, &a, &b, token) == n);
    assert(flow_metadata_decode(NULL, n, &a, &b, token) == 0);
    assert(flow_metadata_decode(bytes, n, NULL, &b, token) == 0);
    assert(flow_metadata_decode(bytes, n, &a, NULL, token) == 0);
    bytes[16 + 2 * width] = bytes[17 + 2 * width] = 0;
    assert(flow_metadata_decode(bytes, n, &a, &b, token) == 0);
    n = flow_metadata_encode_endpoints(bytes, sizeof(bytes), src, dst, 1, 10001, token);
    bytes[18 + 2 * width] = bytes[19 + 2 * width] = 0;
    assert(flow_metadata_decode(bytes, n, &a, &b, token) == 0);
}
int main(void) {
    check(4);
    check(16);
    puts("HEV flow metadata bounds fixtures passed");
    return 0;
}
