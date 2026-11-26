// SPDX-License-Identifier: GPL-3.0
// Staged HEV gateway DNS native transactions, not linked into the APK.
#include "supervisor/supervisor.h"
#include <assert.h>
#include <stdlib.h>
#include "rules/marks.h"

/* Existing, unused DNS IDs; table + family distinguish INPUT from NAT.
 * Keep these names separate from the upstream FakeIP ICMP resources. */
static const struct asteriskd_owned_chain nf_chains[] = {
    {ASTERISKD_IP_FAMILY_IPV4, ASTERISKD_IP_TABLE_FILTER, "NF_HEV_DNS4_IN"},
    {ASTERISKD_IP_FAMILY_IPV6, ASTERISKD_IP_TABLE_FILTER, "NF_HEV_DNS6_IN"},
    {ASTERISKD_IP_FAMILY_IPV4, ASTERISKD_IP_TABLE_NAT, "NF_HEV_DNS4_PRE"},
    {ASTERISKD_IP_FAMILY_IPV6, ASTERISKD_IP_TABLE_NAT, "NF_HEV_DNS6_PRE"},
};
static const struct asteriskd_owned_hook nf_hooks[] = {
    {ASTERISKD_IP_FAMILY_IPV4, ASTERISKD_IP_TABLE_FILTER, "INPUT", ASTERISKD_DNS_NONE, "NF_HEV_DNS4_IN"},
    {ASTERISKD_IP_FAMILY_IPV6, ASTERISKD_IP_TABLE_FILTER, "INPUT", ASTERISKD_DNS_NONE, "NF_HEV_DNS6_IN"},
    {ASTERISKD_IP_FAMILY_IPV4, ASTERISKD_IP_TABLE_NAT, "PREROUTING", ASTERISKD_DNS_NONE, "NF_HEV_DNS4_PRE"},
    {ASTERISKD_IP_FAMILY_IPV6, ASTERISKD_IP_TABLE_NAT, "PREROUTING", ASTERISKD_DNS_NONE, "NF_HEV_DNS6_PRE"},
};

/* Caller must supply resolved, live downstream names, not boot selectors.
 * Link identity, upstream exclusion and exclusive port ownership are external
 * preconditions, not inferred from interface names here. */
static bool nf_valid(const struct asteriskd_config *c, unsigned port) {
    if (!c || c->owner != ASTERISKD_OWNER_BOX || c->core_type != ASTERISKD_CORE_SING_BOX ||
        c->mode != ASTERISKD_MODE_TUN2SOCKS || c->enable_local_dns || c->dns_enhancement ||
        port < 1024U || port > 65535U || c->hotspot_interface_count > 16U ||
        c->ignored_interface_count > ASTERISKD_MAX_INTERFACES ||
        c->virtual_interface_count > ASTERISKD_MAX_INTERFACES) return false;
    for (size_t i = 0; i < c->hotspot_interface_count; ++i) {
        const char *n = c->hotspot_interfaces[i];
        if (!asteriskd_interface_selector_valid(n) || strchr(n, '+') ||
            !strcmp(n, "lo") || !strcmp(n, "asterisk0") || !strcmp(n, "hevtun0") ||
            !strcmp(n, ".") || !strcmp(n, "..") || !strcmp(n, "all") || !strcmp(n, "default") ||
            !strcmp(n, c->helper.value.hev.tunnel_name)) return false;
        for (size_t j = 0; j < i; ++j) if (!strcmp(n, c->hotspot_interfaces[j])) return false;
        for (size_t j = 0; j < c->ignored_interface_count; ++j)
            if (asteriskd_interface_matches_selector(n, c->ignored_interfaces[j])) return false;
        for (size_t j = 0; j < c->virtual_interface_count; ++j)
            if (asteriskd_interface_matches_selector(n, c->virtual_interfaces[j])) return false;
    }
    return true;
}

/* Two independent plans: guard survives core lifetime, NAT only active service.
 * Do not combine their commits or remove guard while the listener is alive. */
int nf_gateway_plan(const struct asteriskd_config *c, unsigned port, bool guard,
                    struct asteriskd_rule_transaction_plan *out) {
    if (!out) return -1;
    memset(out, 0, sizeof(*out));
    if (!nf_valid(c, port)) return -1;
    size_t first = guard ? 0U : 2U, end = guard || c->enable_ipv6 ? first + 2U : first + 1U;
    for (size_t i = first; i < end; ++i) {
        const struct asteriskd_owned_chain *owned = &nf_chains[i];
        struct asteriskd_private_chain_group *g = &out->private_groups[out->private_group_count++];
        g->family = owned->family; g->table = owned->table; g->chain_id = ASTERISKD_CHAIN_DNS;
        g->name_count = 1U;
        if (asteriskd_copy_string(g->names[0], sizeof(g->names[0]), owned->name) != 0) return -1;
        g->operation.kind = ASTERISKD_RESOURCE_OPERATION_IPTABLES_CHAIN;
        g->operation.resource.iptables_chain.family = g->family;
        g->operation.resource.iptables_chain.table = g->table;
        g->operation.resource.iptables_chain.chain_id = g->chain_id;
        struct asteriskd_traffic_hook_group *h = &out->hook_groups[out->hook_group_count++];
        h->family = g->family; h->table = g->table; h->chain_id = g->chain_id;
        h->rule_id = ASTERISKD_RULE_DNS_ENTRY;
        h->operation.kind = ASTERISKD_RESOURCE_OPERATION_IPTABLES_RULE;
        h->operation.resource.iptables_rule.family = h->family;
        h->operation.resource.iptables_rule.table = h->table;
        h->operation.resource.iptables_rule.chain_id = h->chain_id;
        h->operation.resource.iptables_rule.rule_id = h->rule_id;
        h->hook_count = 1U;
        h->hooks[0].builtin_chain = guard ? ASTERISKD_BUILTIN_INPUT : ASTERISKD_BUILTIN_PREROUTING;
        h->hooks[0].insert_at_head = true;
        h->hooks[0].verdict = ASTERISKD_HOOK_JUMP;
        if (asteriskd_copy_string(h->hooks[0].jump_target, sizeof(h->hooks[0].jump_target), nf_hooks[i].target) != 0) return -1;
    }
    out->hooks_are_last = true;
    return 0;
}

/* Queue only, or replay through the native verifier. Never commits a table.
 * active=false: closed INPUT guard. NAT is only populated in active state.
 * The guard always covers both families, even if IPv6 proxying is disabled. */
/* Candidate range excludes documented AOSP fwmark/strict bits and the
 * upstream routing mask. It is NOT a reservation on a vendor kernel. */
#define NF_HEV_CTMARK_CANDIDATES UINT32_C(0x00f00000)
static bool nf_gateway_mark_valid(uint32_t mark, uint32_t mask) {
    return mask && !(mask & ~NF_HEV_CTMARK_CANDIDATES) && mark &&
        !(mark & ~mask) && !(mask & ASTERISKD_DNS_MARK_MASK);
}
static int nf_gateway_populate_impl(struct asteriskd_system_supervisor *s,
    const struct asteriskd_private_chain_group *g, unsigned port, bool active,
    uint32_t mark, uint32_t mask) {
    if (!s || !g || (!s->rule_commands.active && !s->verify_private_rules)) return -1;
    const struct asteriskd_config *c = &s->loaded_config.config;
    bool guard = g->table == ASTERISKD_IP_TABLE_FILTER;
    if (port < 1024U || port > 65535U || ((!guard || active) && !nf_valid(c, port)) ||
        g->chain_id != ASTERISKD_CHAIN_DNS || g->name_count != 1U) return -1;
    if (!guard && (g->table != ASTERISKD_IP_TABLE_NAT || !active)) return -1;
    if (g->family != ASTERISKD_IP_FAMILY_IPV4 && g->family != ASTERISKD_IP_FAMILY_IPV6) return -1;
    const bool v6 = g->family == ASTERISKD_IP_FAMILY_IPV6;
    const char *chain = nf_chains[(guard ? 0U : 2U) + (v6 ? 1U : 0U)].name;
    if (strcmp(g->names[0], chain) || (!guard && v6 && !c->enable_ipv6)) return -1;
    char number[8U], tagged[32], empty[32];
    if ((mark || mask) && !nf_gateway_mark_valid(mark, mask)) return -1;
    if (snprintf(tagged, sizeof(tagged), "0x%x/0x%x", mark, mask) <= 0 ||
        snprintf(empty, sizeof(empty), "0x0/0x%x", mask) <= 0) return -1;
    if (snprintf(number, sizeof(number), "%u", port) <= 0) return -1;
    for (size_t i = 0; active && (!v6 || c->enable_ipv6) && i < c->hotspot_interface_count; ++i) {
        const char *n = c->hotspot_interfaces[i];
        for (unsigned p = 0; p < 2U; ++p) {
            const char *proto = p ? "tcp" : "udp";
            const char *redirect[] = {"-i", n, "-p", proto, "-m", proto, "--dport", "53",
                "-m", "addrtype", "--dst-type", "LOCAL", "--limit-iface-in", "-j", "REDIRECT", "--to-ports", number};
            const char *accept[] = {"-i", n, "-p", proto, "-m", proto, "--dport", number,
                "-m", "conntrack", "--ctstate", "DNAT", "--ctorigdstport", "53", "--ctdir", "ORIGINAL", "-j", "ACCEPT"};
            const char *tag[] = {"-i", n, "-p", proto, "-m", proto, "--dport", "53",
                "-m", "addrtype", "--dst-type", "LOCAL", "--limit-iface-in",
                "-m", "connmark", "--mark", empty, "-j", "CONNMARK", "--set-xmark", tagged};
            const char *redirect_tagged[] = {"-i", n, "-p", proto, "-m", proto, "--dport", "53",
                "-m", "addrtype", "--dst-type", "LOCAL", "--limit-iface-in",
                "-m", "connmark", "--mark", tagged, "-j", "REDIRECT", "--to-ports", number};
            const char *accept_tagged[] = {"-i", n, "-p", proto, "-m", proto, "--dport", number,
                "-m", "conntrack", "--ctstate", "DNAT", "--ctorigdstport", "53", "--ctdir", "ORIGINAL",
                "-m", "connmark", "--mark", tagged, "-j", "ACCEPT"};
            if (mask && !guard && asteriskd_supervisor_xtables_zero(s, g->family, g->table, "-A", chain,
                tag, sizeof(tag)/sizeof(*tag)) != 0) return -1;
            const char **args = mask ? (guard ? accept_tagged : redirect_tagged) : (guard ? accept : redirect);
            size_t count = mask ? (guard ? sizeof(accept_tagged)/sizeof(*accept_tagged) : sizeof(redirect_tagged)/sizeof(*redirect_tagged)) :
                (guard ? sizeof(accept)/sizeof(*accept) : sizeof(redirect)/sizeof(*redirect));
            if (asteriskd_supervisor_xtables_zero(s, g->family, g->table, "-A", chain, args, count) != 0) return -1;
        }
    }
    if (guard) for (unsigned p = 0; p < 2U; ++p) {
        const char *proto = p ? "tcp" : "udp";
        const char *drop[] = {"-p", proto, "-m", proto, "--dport", number, "-j", "DROP"};
        if (asteriskd_supervisor_xtables_zero(s, g->family, g->table, "-A", chain, drop, sizeof(drop)/sizeof(*drop)) != 0) return -1;
    }
    return 0;
}

/* Keep existing unmarked WAL format and runtime caller unchanged. */
int nf_gateway_populate(struct asteriskd_system_supervisor *s,
    const struct asteriskd_private_chain_group *g, unsigned port, bool active) {
    return nf_gateway_populate_impl(s, g, port, active, 0, 0);
}
/* Queue-only prototype. Caller must establish exclusive lifetime ownership,
 * verify kernel support and persist the mask before publishing any rule. */
int nf_gateway_populate_marked(struct asteriskd_system_supervisor *s,
    const struct asteriskd_private_chain_group *g, unsigned port, bool active,
    uint32_t mark, uint32_t mask, bool exclusive) {
    if (!exclusive || !nf_gateway_mark_valid(mark, mask)) return -1;
    return nf_gateway_populate_impl(s, g, port, active, mark, mask);
}

/* Check a normalized native -S listing against the queued population lines.
 * The upstream verifier counts exact matches but does not enforce their
 * order. This extra check rejects a DROP preceding its intended ACCEPT. */
bool nf_gateway_verify_order(const struct asteriskd_private_chain_group *g,
    const struct asteriskd_byte_buffer *expected, const char *actual, size_t length) {
    if (!g || !expected || !actual || !length || actual[length - 1] != '\n' ||
        g->chain_id != ASTERISKD_CHAIN_DNS || g->name_count != 1U ||
        (expected->length && (!expected->bytes || expected->bytes[expected->length - 1] != '\n'))) return false;
    char prefix[ASTERISKD_MAX_CHAIN_NAME + 5U];
    int n = snprintf(prefix, sizeof(prefix), "-A %s ", g->names[0]);
    if (n <= 0 || (size_t)n >= sizeof(prefix)) return false;
    size_t declarations = 0, rules = 0;
    if (asteriskd_xtables_private_chain_counts(actual, length, g->names[0], &declarations, &rules) != 0 || declarations != 1U) return false;
    size_t cursor = 0, found = 0;
    for (size_t offset = 0; offset < length;) {
        const char *line = actual + offset;
        const char *newline = memchr(line, '\n', length - offset);
        if (!newline) return false;
        size_t size = (size_t)(newline - line) + 1U;
        offset += size;
        if (size <= (size_t)n || memcmp(line, prefix, (size_t)n)) continue;
        if (cursor >= expected->length) return false;
        const unsigned char *want = expected->bytes + cursor;
        const unsigned char *end = memchr(want, '\n', expected->length - cursor);
        if (!end || (size_t)(end - want) + 1U != size || memcmp(line, want, size)) return false;
        cursor += size; ++found;
    }
    return cursor == expected->length && found == rules;
}

#ifdef NF_GATEWAY_HOST_TEST
static unsigned checks;
#define CHECK(expr) do { if (!(expr)) { fprintf(stderr, "FAIL line %d\n", __LINE__); exit(1); } ++checks; } while (0)
static bool queued(struct asteriskd_system_supervisor *s, enum asteriskd_ip_family f,
                   enum asteriskd_ip_table t, const char *needle) {
    const struct asteriskd_byte_buffer *b = &s->rule_commands.xtables[0][f][t];
    return b->length && memmem(b->bytes, b->length, needle, strlen(needle)) != NULL;
}
int main(void) {
    struct asteriskd_system_supervisor *s = calloc(1, sizeof(*s)); CHECK(s != NULL);
    struct asteriskd_config *c = &s->loaded_config.config;
    c->owner = ASTERISKD_OWNER_BOX; c->core_type = ASTERISKD_CORE_SING_BOX;
    c->mode = ASTERISKD_MODE_TUN2SOCKS; c->enable_ipv6 = true;
    strcpy(c->helper.value.hev.tunnel_name, "hevtun0");
    strcpy(c->hotspot_interfaces[0], "ap0"); c->hotspot_interface_count = 1;
    struct asteriskd_rule_transaction_plan guard, nat;
    CHECK(nf_gateway_plan(c, 15353, true, &guard) == 0 && guard.private_group_count == 2);
    CHECK(nf_gateway_plan(c, 15353, false, &nat) == 0 && nat.hook_group_count == 2);
    CHECK(guard.hook_groups[0].hooks[0].builtin_chain == ASTERISKD_BUILTIN_INPUT);
    CHECK(nat.hook_groups[0].hooks[0].builtin_chain == ASTERISKD_BUILTIN_PREROUTING);
    CHECK(guard.route_count == 0 && nat.route_count == 0);
    CHECK(asteriskd_supervisor_rule_batch_begin(&s->rule_commands) == 0);
    CHECK(nf_gateway_populate(s, &guard.private_groups[0], 15353, false) == 0);
    CHECK(queued(s, ASTERISKD_IP_FAMILY_IPV4, ASTERISKD_IP_TABLE_FILTER, "--dport 15353 -j DROP"));
    CHECK(!queued(s, ASTERISKD_IP_FAMILY_IPV4, ASTERISKD_IP_TABLE_FILTER, "ACCEPT"));
    CHECK(nf_gateway_populate(s, &nat.private_groups[0], 15353, false) == -1);
    asteriskd_supervisor_rule_batch_destroy(&s->rule_commands);
    CHECK(asteriskd_supervisor_rule_batch_begin(&s->rule_commands) == 0);
    for (size_t i = 0; i < 2; ++i) {
        CHECK(nf_gateway_populate(s, &guard.private_groups[i], 15353, true) == 0);
        CHECK(nf_gateway_populate(s, &nat.private_groups[i], 15353, true) == 0);
        CHECK(queued(s, i, ASTERISKD_IP_TABLE_FILTER, "--ctstate DNAT --ctorigdstport 53 --ctdir ORIGINAL -j ACCEPT"));
        CHECK(queued(s, i, ASTERISKD_IP_TABLE_NAT, "--dst-type LOCAL --limit-iface-in -j REDIRECT --to-ports 15353"));
        CHECK(!queued(s, i, ASTERISKD_IP_TABLE_NAT, "-j MARK"));
    }
    const struct asteriskd_byte_buffer *expected = &s->rule_commands.xtables[0][ASTERISKD_IP_FAMILY_IPV4][ASTERISKD_IP_TABLE_FILTER];
    const char *decl = "-N NF_HEV_DNS4_IN\n";
    struct asteriskd_byte_buffer listing = {0};
    CHECK(asteriskd_byte_buffer_append(&listing, decl, strlen(decl)) == 0);
    CHECK(asteriskd_byte_buffer_append(&listing, expected->bytes, expected->length) == 0);
    CHECK(nf_gateway_verify_order(&guard.private_groups[0], expected, (const char *)listing.bytes, listing.length));
    const unsigned char *newline = memchr(expected->bytes, '\n', expected->length);
    CHECK(newline != NULL);
    size_t first = (size_t)(newline - expected->bytes) + 1U;
    asteriskd_byte_buffer_destroy(&listing);
    CHECK(asteriskd_byte_buffer_append(&listing, decl, strlen(decl)) == 0);
    CHECK(asteriskd_byte_buffer_append(&listing, expected->bytes + first, expected->length - first) == 0);
    CHECK(asteriskd_byte_buffer_append(&listing, expected->bytes, first) == 0);
    CHECK(!nf_gateway_verify_order(&guard.private_groups[0], expected, (const char *)listing.bytes, listing.length));
    asteriskd_byte_buffer_destroy(&listing);
    CHECK(!nf_gateway_verify_order(&guard.private_groups[0], expected, (const char *)expected->bytes, expected->length));
    CHECK(asteriskd_byte_buffer_append(&listing, decl, strlen(decl)) == 0);
    CHECK(asteriskd_byte_buffer_append(&listing, expected->bytes, expected->length) == 0);
    CHECK(asteriskd_byte_buffer_append(&listing, expected->bytes, first) == 0);
    CHECK(!nf_gateway_verify_order(&guard.private_groups[0], expected, (const char *)listing.bytes, listing.length));
    asteriskd_byte_buffer_destroy(&listing);
    asteriskd_supervisor_rule_batch_destroy(&s->rule_commands);
    CHECK(!nf_gateway_mark_valid(1, 1));
    CHECK(!nf_gateway_mark_valid(0x01000000, 0x01000000));
    CHECK(!nf_gateway_mark_valid(0x02000000, 0x02000000));
    CHECK(!nf_gateway_mark_valid(0x20000000, 0x20000000));
    CHECK(!nf_gateway_mark_valid(0, 0x00300000));
    CHECK(!nf_gateway_mark_valid(0x00400000, 0x00300000));
    CHECK(nf_gateway_mark_valid(0x00100000, 0x00300000));
    CHECK(asteriskd_supervisor_rule_batch_begin(&s->rule_commands) == 0);
    CHECK(nf_gateway_populate_marked(s, &nat.private_groups[0], 15353, true, 0x00100000, 0x00300000, false) != 0);
    CHECK(!s->rule_commands.xtables[0][0][ASTERISKD_IP_TABLE_NAT].length);
    for (size_t i = 0; i < 2; ++i) {
        CHECK(nf_gateway_populate_marked(s, &nat.private_groups[i], 15353, true, 0x00100000, 0x00300000, true) == 0);
        CHECK(nf_gateway_populate_marked(s, &guard.private_groups[i], 15353, true, 0x00100000, 0x00300000, true) == 0);
        CHECK(queued(s, i, ASTERISKD_IP_TABLE_NAT, "--mark 0x0/0x300000 -j CONNMARK --set-xmark 0x100000/0x300000"));
        CHECK(queued(s, i, ASTERISKD_IP_TABLE_NAT, "--mark 0x100000/0x300000 -j REDIRECT --to-ports 15353"));
        CHECK(queued(s, i, ASTERISKD_IP_TABLE_FILTER, "--mark 0x100000/0x300000 -j ACCEPT"));
        CHECK(!queued(s, i, ASTERISKD_IP_TABLE_NAT, "-j MARK"));
    }
    asteriskd_supervisor_rule_batch_destroy(&s->rule_commands);
    CHECK(asteriskd_supervisor_rule_batch_begin(&s->rule_commands) == 0);
    CHECK(nf_gateway_populate_marked(s, &guard.private_groups[0], 15353, false, 0x00100000, 0x00300000, true) == 0);
    CHECK(!queued(s, 0, ASTERISKD_IP_TABLE_FILTER, "CONNMARK"));
    CHECK(!queued(s, 0, ASTERISKD_IP_TABLE_FILTER, "ACCEPT"));
    CHECK(queued(s, 0, ASTERISKD_IP_TABLE_FILTER, "--dport 15353 -j DROP"));
    asteriskd_supervisor_rule_batch_destroy(&s->rule_commands);
    c->enable_ipv6 = false;
    CHECK(nf_gateway_plan(c, 15353, true, &guard) == 0 && guard.private_group_count == 2);
    CHECK(nf_gateway_plan(c, 15353, false, &nat) == 0 && nat.private_group_count == 1);
    CHECK(asteriskd_supervisor_rule_batch_begin(&s->rule_commands) == 0);
    CHECK(nf_gateway_populate(s, &guard.private_groups[1], 15353, true) == 0);
    CHECK(!queued(s, ASTERISKD_IP_FAMILY_IPV6, ASTERISKD_IP_TABLE_FILTER, "ACCEPT"));
    CHECK(queued(s, ASTERISKD_IP_FAMILY_IPV6, ASTERISKD_IP_TABLE_FILTER, "DROP"));
    asteriskd_supervisor_rule_batch_destroy(&s->rule_commands);
    CHECK(nf_gateway_plan(c, 53, true, &guard) == -1);
    strcpy(c->hotspot_interfaces[0], "ap+"); CHECK(nf_gateway_plan(c, 15353, true, &guard) == -1);
    strcpy(c->hotspot_interfaces[0], "hevtun0"); CHECK(nf_gateway_plan(c, 15353, true, &guard) == -1);
    strcpy(c->hotspot_interfaces[0], "ap0"); strcpy(c->ignored_interfaces[0], "ap+"); c->ignored_interface_count = 1;
    CHECK(nf_gateway_plan(c, 15353, true, &guard) == -1);
    c->ignored_interface_count = 0; c->hotspot_interface_count = 0;
    CHECK(nf_gateway_plan(c, 15353, true, &guard) == 0);
    CHECK(asteriskd_supervisor_rule_batch_begin(&s->rule_commands) == 0);
    CHECK(nf_gateway_populate(s, &guard.private_groups[0], 15353, true) == 0);
    CHECK(!queued(s, ASTERISKD_IP_FAMILY_IPV4, ASTERISKD_IP_TABLE_FILTER, "ACCEPT"));
    asteriskd_supervisor_rule_batch_destroy(&s->rule_commands);
    free(s); printf("PASS %u checks\n", checks); return 0;
}
#endif
