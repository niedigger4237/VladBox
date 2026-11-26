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

## Optional Bootstrap DNS

Available in both Legacy and Modern managed modes (including URL tests).
System DNS preserves the previous `dns-local` bootstrap. A selected
IP-literal resolver adds `dns-bootstrap`, with no detour or domain_resolver;
only domain-based `dns-direct` server addresses reference it. An IP-based
Direct DNS or a local/DHCP Direct DNS does not need this bootstrap.
The Remote DNS resolver remains dns-direct; server resolvers, FakeIP,
SRS rules, TUN and firewall/tethering are unchanged. Presets provide
Cloudflare, Quad9 and Google UDP, DoH and DoT; encrypted presets use literal
IP plus provider TLS server_name. Custom permits UDP/TCP/DoT/DoH, IPv4 and
IPv6, optional port and DoH path, optional TLS hostname. No insecure TLS
or automatic fallback to system DNS. With no TLS name, certificate
verification uses the IP. A configured IPv6 bootstrap requires IPv6
connectivity. User JSON overrides keep existing precedence. Raw full
config imports bypass managed settings. Actual reachability and TLS
verification depend on the network and chosen provider.

## DNS routing mode

The existing dnsMain preference is now labeled DNS Routing Mode.
Existing direct (Split DNS) and remote (Remote Preferred) values keep their
previous behavior. Split defaults to dns-direct, Remote Preferred defaults
to dns-remote. Both retain DNS Routing rule mappings (Bypass -> Direct,
Proxy -> Remote, Block -> predefined NOERROR) when DNS Routing is enabled.
Direct Only (direct_only) does not generate dns-remote, remote server
bootstrap exceptions, or generated DNS rules referring to dns-remote.
Proxy domain rules resolve using dns-direct and its legacy strategy.
The DNS Routing switch still governs user-derived DNS rules; FakeIP,
forced bootstrap/server exceptions, and traffic routing remain independent.
FakeIP may still synthesize A/AAAA when separately enabled; Direct Only
means all ordinary managed DNS resolution is direct, not that FakeIP or
blocking is disabled. URL-test generation always uses Direct DNS.
Remote settings remain saved but hidden in Direct Only. Raw config imports
and custom JSON overrides retain precedence; user overrides explicitly
referencing dns-remote must be adapted before choosing Direct Only.
A Remote preset using platform local/DHCP has platform-specific semantics;
use a network DNS server (e.g. DoH) for DNS through the proxy.

## Persistent DNS cache audit (not enabled)

Verified against original SagerNet/sing-box v1.14.3 and pinned extended
d9442d14b19ddd02605bf3ee893a171947038e19:
- option/experimental.go supports experimental.cache_file.enabled,
  path, cache_id, store_dns and separate store_fakeip. Managed ConfigBuilder
  does not currently enable a cache file; raw/custom configs may do so.
- dns/client.go Start initializes the in-memory LRU only if there is no
  persistent DNS store. storeCache/loadResponse use the disk store instead
  of LRU when active. dns.cache_capacity does not bound that disk store.
- DNS cache keys include the transport tag, client subnet and environment
  hash where available. A reused dns-direct/dns-remote tag alone does not
  identify the DNS provider or proxy configuration.
- Persistent responses retain their expiration timestamps and obey the
  optimistic stale window. FakeIP mappings are controlled independently by
  store_fakeip; do not enable it as a side effect of store_dns.
- experimental/cachefile/dns_cache.go uses asynchronous writes; queued
  responses are not guaranteed to survive an abrupt termination. The core
  cleans expired entries at startup and periodically (optimistic timeout / 2,
  otherwise one hour). Cleanup walks only the active cache_id namespace;
  repeated new IDs can retain inactive namespaces. Bolt file size need not
  shrink after deleting entries. There is no disk capacity setting analogous
  to the memory LRU.
- experimental/cachefile/cache.go opens a Bolt file with a lock; separate
  simultaneously running cores must not share a writable database.
  The upstream default creation mode is 0666 before umask/platform
  restrictions, so directory ownership/permissions matter.

Deployment constraints:
- Embedded Android core chdirs into app no_backup (libcore/nb4a.go).
  Use a dedicated private cache directory, not external storage.
- All four ROOT modes use RootDaemon and asteriskd's workingDirectory
  /data/adb/nekobox/run; root boot configurations can survive app restarts.
  Use an explicitly protected root-owned location, not the embedded
  database and not a generic relative cache.db.
- DNS cache contains queried names and answers. Disabling persistence must
  stop writes; an explicit clear operation must remove retained data only
  after the corresponding core has released the file. Do not unlink a live
  Bolt database. Keep user-supplied cache files untouched.
- Managed persistence needs stable isolation by effective resolver/outbound
  context, including routing profile and proxy selection. A selector can
  change its remote egress while the core stays alive; a static profile ID
  is insufficient. Scope churn needs bounded cleanup or a current-scope-only
  store with a verified shutdown/handoff. Avoid automatic persistence in
  selector/external-core cases until these are addressed.

First implementation should be opt-in, use a private current-scope store
with safe invalidation after verified shutdown, exclude URL tests and
exports, respect custom cache_file settings, and expose clear-cache
behavior. Explain the memory-vs-disk distinction in UI, preserve
Optimistic DNS Cache independently, and test restart/expiry, profile and
resolver switching, selector switching, embedded VPN and each ROOT mode
(including boot start and tethering). No runtime settings or native
lifecycle changes are introduced by this audit.

## Experimental embedded VPN implementation

Modern + ordinary VPN only, default memory. Runtime injection (not exports,
URL tests, temporary selector checks, ROOT, or raw/custom selected ConfigBean
profiles). Selector groups, external plugins and unrecognized outbound types
fall back to memory. Explicit experimental.cache_file is never modified or
cleared by managed startup. Disabling persistence clears the reserved file
on the next ordinary managed VPN start; it does not clear user cache files.
The storage choice remains saved when temporarily ineligible.

One private no_backup/nf-dns-cache-v1/cache.db (0600, directory 0700).
No per-profile file or cache_id accumulation. A SHA-256 scope identifies
the effective static DNS/route/egress config, selected proxy/routing profile
and clear generation; scope changes reset the database rather than reuse
answers. Scope metadata contains no raw configuration. Core lease + actual
DB flock checked before unlink; fallback to memory on unavailable lock or
preparation failure. Raw external process/core is not modified. A 32 MiB
startup threshold resets an oversized file after acquiring locks; not a
hard live size cap. No automatic FakeIP persistence. The existing memory
capacity remains a memory-only option. Optimistic TTL is unchanged.
Clear Saved DNS Cache attempts immediate deletion only when both locks
permit it; otherwise increments the clear generation and requests reload.
An unavailable file is retried at next eligible VPN startup. Active RAM
cache is not cleared by this action. Android build/device validation and
ROOT implementation (boot/handoff, native cache locks) remain outstanding.

## Experimental ROOT persistence

Separate ROOT storage preference (Memory by default). Applies to the
bundled core in Modern DNS for TUN, TPROXY, TUN2SOCKS and BPF2SOCKS with
static managed configurations; selector groups, external plugins,
external cores, ConfigBean and explicit cache_file are excluded.
Scope includes proxy/profile, effective DNS and static egress, ROOT mode,
bundled binary fingerprint and clear generation. A single root-private
/data/adb/nekobox/nf-dns-cache-v1/cache.db is independent of VPN storage.
After boot-monitor handoff and verified STOPPED/NO_STATE, the app invokes
the preparation asset with root-only directory/file checks, nonblocking
lease and bbolt-compatible DB flock, no-follow checks, 32 MiB startup
threshold and filesystem sync. Unavailable preparation keeps memory
cache. No live DB unlink. A missing socket with incomplete cleanup
evidence does not permit preparation. Explicit user cache_file options
are left untouched. ROOT clear/storage changes are applied at the next
verified interactive start, not by direct root deletion from the UI.
The existing verified boot publication and TUN templates carry the
same cache path, and Service Control uses that same static config; no
native supervisor/firewall/TC/tether script changes. Autonomous restarts
do not run the size preflight or consume new app preferences until a
verified interactive start. Native/device validation remains outstanding.

## DNS interception audit (five service modes)

Managed ConfigBuilder prepends sniff, protocol DNS hijack and port 53 hijack
before user traffic rules. Fixed a dependency: Protocol/Both must request
DNS-only sniffing when general Traffic Sniffing is disabled. Port/Off do
not enable additional sniffing. General sniffing retains default sniffers.
Confirmed the sniffer list is supported in option/rule_action.go in both
SagerNet/sing-box v1.14.3 and pinned extended d9442d14b19ddd02605bf3ee893a171947038e19.
URL tests and full raw configs are unaffected; custom JSON can override rules.

- Ordinary VPN: Android VpnService.Builder advertises plan.dns4 and routes
  it even with bypassLan. TetherVpn and TetherIpv6 install TCP and UDP 53
  DNAT to the session's virtual DNS address, independent of the hijack
  setting. LeakGuard drops forwarding and local INPUT port 53 until the
  owned tunnel route is ready. Turning hijack Off does not necessarily
  disable the core's intrinsic TUN DNS handling. Do not remove DNAT or
  change the address plan as part of the sniff fix. Current watchdog checks
  hooks/routes, not exact DNS rules inside the owned NAT chain; inspect
  that separately with tests for guard ordering and netd resets.
- ROOT TUN: core auto_route/auto_redirect and configured UID/interface
  scopes; strict_route=false, downstream local subnet route exclusions.
  DNS to local gateway/private resolver can remain outside core capture.
  No blanket local exclusion removal: this would risk DHCP and hotspot.
- ROOT TPROXY/TUN2SOCKS: asteriskd pinned v2.3.2
  a31fd6dfdbfb515b8c18cc91bc3637b523f0b255 has enable_local_dns branches
  in supervisor/supervisor_xtables.c that act before local bypass and
  some app policy handling; app explicitly leaves enableLocalDns=false.
  A public DNS server reached through captured traffic uses core rules,
  but local gateway/private destinations can bypass those rules.
  Do not turn on global enableLocalDns: early PREROUTING capture is not
  limited to selected downstreams; app selection must be preserved.
- ROOT BPF2SOCKS: helper-fed SOCKS traffic reaching sing-box can be hijacked;
  traffic excluded by native local/app/interface selection cannot.
  Audit exact TC/cgroup policies and IPv6 before changing native behavior.
- DNS Enhancement is a different asteriskd feature requiring a compatible
  Android resolver companion (network/dns.c, supervisor/supervisor_dns.c).
  App publishes dnsEnhancement=null; enabling it blindly is not a fix.

DNS hijack only affects traffic actually captured by the core. DoT (TCP
853), DoQ (UDP 853 or other ports) and DoH (HTTPS/QUIC) are encrypted;
the DNS sniffer does not decrypt them. Direct/Bootstrap resolver queries
outside the VPN are intentional, as are selected app exclusions. No
unconditional DNS leak protection or kill switch claim is warranted.
Future device matrix: phone and Wi-Fi/USB clients, gateway/private and
public DNS, UDP/TCP 53, IPv4/IPv6, all four hijack modes, per-app policy,
Private DNS, DNS over HTTPS, network switching, service-control/boot,
and tethering hot-refresh. Verify using packet capture at physical
uplink and core logs without logging user queries by default.
No changes to native firewall, tethering, Service Control or boot.

## Scoped tether DNS interception (stage 1)

Ordinary VPN's existing DNS DNAT is unchanged. Its watchdog now reads
the owned NAT chain and checks exactly one TCP and UDP port-53 redirect
per downstream, with the session DNS destination. A missing/altered rule
uses existing guarded reconciliation. No new chains, hooks or journal
formats.

Experimental opt-in ROOT TPROXY only, default off. Requires bundled
managed core, exact discovered downstreams, boot disabled and an
unqualified port-53 hijack at the head of the effective core route.
Patched native config accepts an optional nfTetherDnsHijack boolean
without breaking existing 11-field network objects. The native chain
builder inserts only selected-interface TCP/UDP 53 TPROXY rules before
local bypass, after IPsec exclusions, never OUTPUT. Ignores excluded
interfaces, preserves IPv6 enable policy and existing TCP6 socket
handling. Native hot refresh rebuilds the same owned chains, with no
additional hooks/cleanup. No global enableLocalDns or DNS Enhancement.
Boot prefix selectors, raw/custom overridden capture, external cores and
other ROOT modes are not enabled. TUN2SOCKS local routing, ROOT TUN
nftables and BPF2SOCKS TC/cgroup handling require separate work.
Native build/Android device testing outstanding; no leak-free claim.

## Scoped tether DNS interception (stage 2: partial HEV)

ROOT TUN2SOCKS gets its own opt-in preference, default off, independent
of TPROXY. Requires the same managed bundled core, exact downstreams,
boot off and effective unconditional port-53 hijack. No change to the
global enableLocalDns flag or native route effects. For IPv4 non-local
UNICAST only, the owned HEV PREROUTING chain marks UDP/TCP 53 and returns
before private-CIDR bypass, after the native IPsec and local-address
exclusions. This reuses existing routing table 168 and the existing
hot refresh/rollback machinery. The addrtype match is required; native
rule creation must succeed. IPv6 is deliberately unchanged.

This does not intercept the handset's own gateway DNS: Linux's priority-0
local routing table precedes asteriskd's mark rule at priority 14599.
Do not move the local routing rule or enable route_localnet globally.
A separate local DNS input path (with owned, downstream-only delivery,
listener isolation, IPv4/IPv6, lifecycle cleanup and boot/hot-refresh
verification) is still needed. Do not call this a full DNS leak fix.
NDK compilation and Android integration remain unverified.
