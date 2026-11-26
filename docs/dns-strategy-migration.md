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
