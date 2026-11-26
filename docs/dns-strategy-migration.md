# DNS rule strategy migration (sing-box extended 1.14.3)
Pinned core: `d9442d14b19ddd02605bf3ee893a171947038e19`.

- Keep supported global `dns.strategy`, outbound `domain_resolver.strategy` and route resolve action strategy.
- Omit generated DNS-rule overrides equal to the global default.
- Generated FakeIP remains IPv4-only: with dual-stack or IPv4-only global defaults, omit its IPv6 range and legacy rule strategy. The pinned FakeIP transport returns NOERROR with empty answers for an unconfigured address family. The router skips FakeIP during internal lookup. With IPv6-only global defaults retain the legacy IPv4 exception; the DNS client would otherwise reject A before calling the transport.
- Preserve distinct Direct and Remote strategies, including explicit Remote rules when Direct is the global default. A different preferred family for internal DNS lookups cannot be represented faithfully by query_type matching: new-mode lookup ordering uses the global or caller lookup strategy.
- Do not generate query_type rules alongside retained legacy strategies: this combination fails the pinned router's startup validation.
- Raw imported configurations and user JSON overrides are not automatically rewritten. They may independently require migration.
- The FakeIP change targets A/AAAA responses, not arbitrary DNS question types; runtime behavior for HTTPS/SVCB and caller-supplied lookup strategies must be checked on the pinned core. Do not promote this branch before testing.
- This affects generated configs across service modes, not firewall, BPF, tethering, or route rule generation.

## Opt-in modern mode
`dnsEngineMode` defaults to legacy; both saved per-resolver preferences remain intact. Modern uses a separate global preference (`dnsGlobalStrategy`, default auto = service IPv6 mode). The DNS main server and routing order stay unchanged. Managed generated rules omit legacy strategy. FakeIP rules match A/AAAA only; other queries continue through remaining DNS rules. With IPv6-only, IPv4 FakeDNS is disabled in modern mode (legacy still uses the prior exception). ROOT TUN continues to exclude FakeDNS. Imported raw JSON and custom overrides are not rewritten. A custom rule-set or override requiring legacy DNS can still conflict with modern query_type. No core or firewall changes.

## Advanced DNS response rules in Modern mode

The existing global/per-profile Custom Config merge supports `dns.rules+` (append)
and `dns.+rules` (prepend). Prefer append to preserve generated bootstrap,
domain, block and FakeIP precedence. Appended rules apply only if earlier
rules do not terminate evaluation. Do not use prepend unless intentionally
overriding the generated DNS policy.

Example (only unmatched example.org requests, no change in DNS transport):
```json
{
  "dns": {
    "rules+": [
      {
        "domain_suffix": ["example.org"],
        "action": "evaluate",
        "server": "dns-remote",
        "tag": "example-answer"
      },
      {
        "domain_suffix": ["example.org"],
        "match_response": "example-answer",
        "response_rcode": "NOERROR",
        "action": "respond"
      },
      {
        "domain_suffix": ["example.org"],
        "action": "route",
        "server": "dns-remote"
      }
    ]
  }
}
```

This illustrates explicit response-based selection; the fallback can cause a
second DNS lookup. It is not automatically enabled and is not a recommended
performance optimization. If a matching generated rule already terminates,
this example is not reached. Internal lookups also obey the global strategy.

Managed Modern configurations now receive structural validation after both
custom JSON merges, before core startup. Legacy rule strategies, missing or
forward evaluate references, duplicate tags, direct address response filters
without match_response, nested evaluate/respond and evaluate on FakeIP are
rejected with a rule path. Raw full-core configs and Legacy mode are untouched.
The core still validates SRS metadata, available servers, full option schemas
and conditional runtime response availability; the application check is not
a replacement for sing-box check or device testing.

## Optimistic DNS Cache
Optional Modern setting: `dnsOptimisticWindow`. Default off. The explicit
Default (3 days) selection emits `dns.optimistic: true` and inherits the
core's default stale window (3 days in the pinned version). Manual windows
30s, 5m, 1h, 24h, 72h emit `dns.optimistic: {"enabled":true,"timeout":"5m"}`
with the selected duration. Existing 72h selections remain explicit. The window is measured from original TTL expiration, not query
timeout. Pinned core returns stale entries with TTL 1 and triggers its own
refresh. No application polling, extra worker, persistent cache enablement,
or firewall changes. Legacy, URL-test generation and raw imports do not
receive the managed setting. Saved preference survives mode switching.
Per-rule disable_cache remains valid (FakeIP bypasses DNS cache in the core).
Custom JSON can override the generated option; after merging, Modern mode
rejects enabled optimistic cache combined with global disable_cache or
disable_expire. The core remains authoritative for option schema and timing.
Imported/older ROOT cores must support sing-box 1.14 before enabling.

## DNS query timeout and memory cache capacity

Both the original v1.14.3 and pinned extended core use 10s by default
(`constant/timeout.go`) and clamp the in-memory LRU capacity to at least
1024 (`dns/client.go`, NewClient). Modern managed settings expose
`dnsQueryTimeout` and `dnsCacheCapacity` separately from the optimistic
stale window. Default omits each field independently; manual settings emit
`dns.timeout` as a duration and `dns.cache_capacity` as an integer.
Allowed durations: 3s, 5s, 10s, 15s, 30s. Allowed capacities:
1024, 2048, 4096, 8192, 16384. Invalid saved values inherit defaults.
These are bounded application choices, not upstream recommendations.
Capacity counts cached entries, not bytes; larger capacity may consume
more memory. DNS timeout applies to an individual query and background
refresh, not the entire proxy connection; rule/explicit resolver overrides
can take precedence. No changes to FakeIP cache, disk caching, resolver
routing, legacy managed settings, URL-test settings or raw full imports.
User JSON overrides retain their existing precedence.

## Server domain resolver and bootstrap audit

Original v1.14.3 `common/dialer/dialer.go` selects an explicit
domain_resolver before route.default_domain_resolver. With an explicit
resolver it can resolve the server locally even with a detour; the detour
still carries the outbound connection. WireGuard endpoint peer resolution
also uses these query options (`protocol/wireguard/endpoint.go`).
Do not remove explicit resolvers from chained outbounds or change the
selected DNS transport automatically.

Managed bootstrap stays dns-local -> dns-direct -> dns-remote
(dependency order; dns-remote has proxy detour). The default outbound
resolver remains dns-direct. Explicit resolver values provided by protocol
builders (WireGuard advanced dialer) and custom outbound JSON are preserved
instead of being overwritten by the generated default. URL tests retain
their prior direct resolver behavior; customOutboundJson still merges
after generated fields, as before. Raw full config imports are unchanged.
An explicitly configured resolver using a proxy that requires that same
resolver to start can still cause a cycle; the core validates dependencies,
and user overrides are not silently redirected. No change to system local
DNS selection or firewall/tethering.

A shared first hop can already have tag "proxy" or a selector name rather
than the proposed g-ID. Resolve its actual existing tag before linking
the previous outbound's detour or external mapping route. No change to
the order or intended members of chains. Unit tests exercise tag selection
and resolver policy; full chain startup and Android network tests remain
required.
