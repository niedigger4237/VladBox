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
Optional Modern setting: `dnsOptimisticWindow`. Default off; allowed windows
30s, 5m, 1h, 24h, 72h. Emits `dns.optimistic: {"enabled":true,"timeout":"5m"}`
when selected. The window is measured from original TTL expiration, not query
timeout. Pinned core returns stale entries with TTL 1 and triggers its own
refresh. No application polling, extra worker, persistent cache enablement,
or firewall changes. Legacy, URL-test generation and raw imports do not
receive the managed setting. Saved preference survives mode switching.
Per-rule disable_cache remains valid (FakeIP bypasses DNS cache in the core).
Custom JSON can override the generated option; after merging, Modern mode
rejects enabled optimistic cache combined with global disable_cache or
disable_expire. The core remains authoritative for option schema and timing.
Imported/older ROOT cores must support sing-box 1.14 before enabling.
