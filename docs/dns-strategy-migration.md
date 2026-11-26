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

## HEV gateway DNS: staged rule planner, not enabled

The existing mark-based IPv4 non-local interception is unchanged. The
local destination is delivered by Linux's priority-0 local table. An
extra transparent listener is not sufficient: sing-box TProxy UDP
writeback opens a socket at the original destination (port 53), which
can conflict with Android's existing DNS listener (original v1.14.3
protocol/redirect/tproxy.go). Prefer a distinct high-port direct inbound
with a first, inbound-specific hijack-dns action and reverse NAT. The
direct inbound supports both transports (protocol/direct/inbound.go,
option/direct.go). Do not globally change the local table or enable
route_localnet.

buildScript/experiments/hev-gateway-dns contains an isolated Kotlin
rule planner with 18 host checks. It does not execute commands or enter
the APK. Exact verified active downstream names only, never upstream,
ignored, reserved or wildcard names. For each enabled family it plans
LOCAL destination TCP/UDP 53 REDIRECT to a reserved high port, and
INPUT acceptance only for original-direction DNAT with original port
53 on those downstreams. Direct connections to the high port are
dropped. The caller must expand ignored selectors, verify link identity,
reserve the port and check real kernel support. The IPv6 rules are
candidates, not proven support; test link-local gateway and response
source/conntrack separately.

Required before activation:
1. Register distinct NAT and INPUT chain/hook identities in the native
   transaction plan and owned resource catalog, with exact cleanup and
   crash reconciliation. Do not reuse FakeIP ICMP resources.
2. Install and verify input isolation before opening the listener.
   Bind TCP/UDP to the selected port, validate exclusive ownership, and
   prepend a tag-specific hijack-dns rule. The listener must be ready
   before NAT hooks are published. Reject collisions/unsupported
   core configs and fail safely with no interception.
3. Extend hot-refresh transaction to NAT and filter groups, preserve
   guard order during cross-table commits, and verify every family.
   Keep input guard until redirects/owned conntrack are removed and
   listener stopped; retain cleanup obligations on failure. Handle
   established flows and reused interfaces by precise conntrack
   cleanup rather than flushing shared state. Verify Service Control,
   boot config publication and failure rollback before enabling.
4. Test IPv4/IPv6 UDP/TCP on a real Android device (SELinux, addrtype,
   REDIRECT, conntrack DNAT state, INPUT policy, reverse NAT, DHCP,
   local access, switching Wi-Fi/USB, shutdown, core crash).
   Host here has no CAP_NET_ADMIN, so no live netfilter validation.

Do not present the planner as runtime gateway interception. Existing
TUN2SOCKS behavior and experimental opt-in are unchanged.

## HEV non-local IPv6 DNS (opt-in extension)

The existing HEV IPv4 mark/return rule pair remains unchanged. When IPv6 is
already enabled, additional pairs match TCP/UDP 53 only on exact eligible
downstreams and destinations in 2000::/3 or fc00::/7, explicitly excluding
addrtype LOCAL (IPv6 UNICAST is not sufficient to exclude local addresses).
Existing local-address and IPsec exemptions stay first. No link-local,
multicast, gateway DNS, additional route, NAT hook, or OUTPUT rule is added.
The same owned MANGLE chains, dynamic refresh and cleanup path are used.
The existing opt-in, bundled-core, port-hijack and no-boot constraints remain.

Reviewed the supplied asteriskd a31fd6df source. Baseline and isolated
modified native rule builder compiled with host GCC; actual queued command
checks passed for IPv4, IPv6, disabled/empty, ignored and invalid selectors.
Full patched Android NDK build and on-device packet tests remain outstanding.
Service Control pause leaves the core alive, so a future gateway listener
requires a guard spanning core lifetime (not just traffic-rule lifetime).
The existing gateway config/planner remains unconnected.

## HEV gateway native transaction prototype

The supplied pinned asteriskd source was inspected. The service pause removes
traffic rules without stopping the core. Native operation lookup currently
uses only system->rules_runtime.plan, and the stock private-chain verifier
checks exact rule presence and count but not order. Do not attach a wildcard
DNS listener to that single traffic lifecycle.

buildScript/experiments/hev-gateway-dns/native_gateway_dns.c now builds
separate native transaction descriptors for INPUT guard and PREROUTING NAT,
using the unused native DNS IDs differentiated by table/family. The separate
staged ownership arrays are not yet registered in the production catalog.
The guard generates unconditional TCP/UDP high-port drops in both families
when inactive; only active eligible downstreams and original-direction DNAT
from port 53 receive exceptions. NAT is generated only when active and for
enabled address families; LOCAL and limit-iface-in constrain redirects to the
incoming interface. The native queue API is used, never shell strings or live
commits. An additional exact-order verifier rejects reordered, duplicate or
missing rules in a normalized native listing. Actual kernel save formatting,
IPv6 REDIRECT/link-local handling, reverse NAT, and hook priority still need
Android verification.

Host test, from extracted pinned source root (EXPERIMENT points at the C file):

    gcc -D_GNU_SOURCE -DNF_GATEWAY_HOST_TEST -std=c17 -O0 -I. "$EXPERIMENT" $(find . -name '*.c' ! -name asteriskd.c) -ldl -lz -o /tmp/nf-gateway-test
    /tmp/nf-gateway-test

49 assertions passed with the supplied native sources and the non-local IPv6
helper patch; standalone experiment also compiled with -Wall -Wextra -Werror.
No CAP_NET_ADMIN or iptables executable was available to test live rules.

Before runtime activation, register the independent runtimes and catalog;
verify INPUT hook precedence, guard readiness before spawn, listener ownership,
redirect publication only after readiness, closed guard during service pause,
precise conntrack cleanup and retained obligations after failure. The boot
monitor must resolve exact downstreams before enabling NAT. Do not treat the
prototype as a working gateway DNS redirect. No APK build inputs changed.

## HEV gateway lifecycle coordinator (staged)

The existing upstream lifecycle pause retains core and, for HEV, the helper.
Full stop returns early if quiescing or removal cannot be verified, before
stopping the core. A gateway listener must not rely solely on that traffic
lifetime. The isolated gateway_lifecycle.c coordinates two independent
rule lifetimes via a synchronous, verified, idempotent backend contract:

- Prepare: reserve exclusive ownership, install and verify closed INPUT
  guard for both families, start the owned core, verify listener readiness
  and guard again. No NAT or open guard during startup or waiting.
- Resume: verify the exact downstream snapshot, open only the guarded DNAT
  exceptions, publish NAT, verify NAT and INPUT and recheck link identities.
- Pause: close INPUT first, withdraw NAT, drain only the old snapshot's
  owned conntrack tuples. Keep core and closed guard alive.
- Refresh: pause and drain the old immutable snapshot before using the new
  one. A reused interface name is not proof of unchanged ifindex/address.
- Failure/stop: attempt to stop owned core even if NAT withdrawal or
  conntrack cleanup fails; preserve the guard, old snapshot and outstanding
  obligations until verified cleanup succeeds. Never release the port
  ownership while rules or flows remain. Failed verification triggers stop.

The epoch token refers to an immutable backend snapshot; the controller does
not itself resolve interfaces or implement conntrack. All callbacks remain
unwired. The production backend must implement ownership, exact link and
listener checks, cross-table commits, precise conntrack deletion, and durable
crash recovery. It must use existing core process identities, not spawn a
second core. A stopped coordinator after an error is not a successful
resume or refresh; callers must propagate the failure to the native lifecycle.

Reproduce the host fault-injection test:

    cc -std=c17 -Wall -Wextra -Werror -DNF_LIFECYCLE_TEST buildScript/experiments/hev-gateway-dns/gateway_lifecycle.c -o /tmp/nf-lifecycle
    /tmp/nf-lifecycle

Actual host output: PASS 69 checks (47 fault positions). The backend is a
simulation, including partial installs and persistent NAT, conntrack and
core-stop failures; this is not live firewall validation. The staged file
also compiles without the test macro with -Wall -Wextra -Werror.
No APK build inputs, app settings, existing MANGLE refresh or other root
modes changed in this stage.

## Native gateway firewall adapter linked at build time

gateway_backend.c now connects the coordinator's firewall callbacks to the
actual supervisor xtables snapshot, queue, batch flush and removal APIs.
The INPUT and NAT slots keep independent ownership and expected listings,
including pending versions after a partial cross-family commit. A new slot
must be absent; existing entries must match exactly. Verification requires
an exact ordered chain and a unique hook at position one. Removal verifies
known rules before enqueueing exact hook deletion and chain cleanup, then
checks absence. Unknown state is retained as a cleanup obligation rather
than flushing foreign rules. This is an in-memory adapter, not durable
crash recovery. It cannot make a read/commit atomic across other writers.
The lifecycle retry can remove the guard after core, NAT and flows are gone
even if a previous attempt to close the guard failed.

The native build now compiles gateway_backend.c (which includes the staged
planner and coordinator as a single translation unit). No supervisor call
site or core config enables it yet; LTO may eliminate the unused code.
The non-firewall operations are explicitly delegated to a mandatory callback,
not stubbed as successful. Exclusive listener/port ownership, immutable link
snapshot checking, precise conntrack cleanup, and durable crash recovery
must be implemented before activation. Then integrate Service Control and
boot resolution with the real supervisor. Existing mode rules stay unchanged.

Host validation against the supplied pinned sources with the non-local IPv6
patch: GCC C17 -Wall -Wextra -Werror for the adapter, full host GCC and
Clang links with -D_GNU_SOURCE, 13 adapter listing/ownership tests and 73
coordinator assertions (47 fault positions). The host tests use real native
parsers and queued command generation, but synthetic listings. No live
iptables, Android NDK build, or device packet test was performed. Kernel
normalization of listing syntax and competing netd changes remain unverified.

## Strict HEV DNS listener observation

The pinned upstream readiness implementation in process/process.c
(process_listener_file_matches, system_context_listener_ready) searches a
TCP port marker and explicitly does not verify socket ownership. The new
gateway_socket.c is separate from that path and is linked by the native
build. It re-reads the existing verified core identity (PID, process group,
start-time ticks, executable device/inode and core spec/argv), reads all
four /proc/<pid>/net TCP/UDP tables, requires wildcard listening addresses,
and matches every relevant listener inode against /proc/<pid>/fd.
It takes two socket observations and rejects changes or unreadable tables.
TCP accepted connections and TIME_WAIT are not mistaken for listeners.
It returns observed transport/family bits, NOT a boolean readiness claim.

Host validation: PASS 27 socket checks, including actual TCP/UDP IPv4
sockets, IPv6-only socket, loopback rejection, changed inode and missing
transport, and a live foreign UDP socket owned by a different process.
The new translation unit compiles with GCC C17 -Wall -Wextra -Werror;
full host supervisor link with gateway_backend.c and gateway_socket.c
succeeded against the supplied pinned source with the prior IPv6 patch.
The test checks table/FD ownership primitives and invalid identity rejection,
not full readiness of a real sing-box child. Android SELinux/procfs access,
full NDK build, listener readiness and live DNS are not verified.

Do not infer IPv4 coverage from an IPv6 wildcard socket: dual-stack needs
an explicit IPV6_V6ONLY observation or separate listeners. This is not
atomic port reservation, nor proof that another process cannot hold a
duplicate socket FD. Recheck immediately before NAT publication and during
reconciliation; never activate on missing ownership evidence. Existing
readiness of other modes is unchanged.

Conntrack deletion remains deliberately disabled. Port 15353 alone, a
destination port of 53, DNAT status, or a matching gateway subnet does not
establish flow provenance. A production cleanup backend needs a collision-
checked session ownership mechanism plus immutable old tuples (including
family, protocol, both directions and zone), kernel identity checks and
verified deletion. Do not choose a CONNMARK bit without auditing netd,
vendor firewall and other VPN marks; never flush shared conntrack.
No new runtime activation or settings change.

## Exact conntrack request builder (not activated)

gateway_conntrack.c constructs an individual CTNETLINK DELETE request only
after matching an old owned inventory entry and a fresh GET observation:
both tuple directions, TCP/UDP, address family, zone, kernel ID, session
mark, DNAT status, original DNS port and redirected listener port. It
rejects the existing ASTERISKD_DNS_MARK_MASK bits, missing ownership
evidence, SNAT, dying entries and mismatched identities. No default
CONNMARK allocation is made. Test mark/zone values are synthetic only.

Android common kernel ctnetlink_del_conntrack verifies the supplied tuple,
zone and CTA_ID; a tuple-less DELETE can flush the table. See
https://android.googlesource.com/kernel/common/+/2d76dea417beb11b47beb4933a41f184bec6966a/net/netfilter/nf_conntrack_netlink.c
The builder always supplies the complete original tuple, zone and ID,
with REQUEST|ACK and without dump/flush flags. It does not send messages.
Kernel ID checks are not a guarantee of atomic mark/provenance matching.
A real backend must prove mark exclusivity, serialize trusted writers,
obtain validated complete dumps and fresh GETs, handle omitted default
zone explicitly in the parser, validate ACKs and recheck absence. An
ambiguous entry must retain cleanup debt; never issue a bulk deletion.

Host validation: PASS 42 conntrack request checks, including IPv4/UDP,
IPv6/TCP, rejected changed identity, foreign mark, zone, NAT status and
tuple differences, plus request attribute/header checks. Standalone
C17 -Wall -Wextra -Werror compile and full GCC host link with prior
gateway modules succeeded. No netfilter operations were executed; the
host lacks CAP_NET_ADMIN. Android NDK and device tests remain pending.
The build links this module but runtime gateway capture remains disabled.

## Durable gateway firewall write-ahead journal

gateway_journal.c stores a separate bounded, versioned record with generation,
boot/session identifiers, port, downstream epoch, cleanup debt and a
payload. It uses a trusted private directory descriptor, a cooperating
exclusive flock, no-follow opens, regular single-link 0600 files, CRC32
(integrity, not authentication), file fsync, atomic rename and directory
fsync. A damaged or unknown record blocks replacement; a stale temporary
file is not interpreted as a committed record. A failed save after rename
requires reload rather than assuming the previous generation is current.

gateway_backend.c now includes the journal and serializes exact old and
pending IPv4/IPv6 guard and NAT rule images before any batch flush for
creation, refresh or removal. A missing journal or failed checkpoint
prevents firewall mutation. Firewall checkpoints preserve cleanup debts
and never assert completion for core/conntrack. The session owner must
supply the lock and verified identities. No startup call site has been
enabled; decoding the firewall payload, reconciling actual owned resources
and implementing durable process/link/conntrack obligations remain required.
The ordinary upstream state format and other root modes are unchanged.

Host validation against the supplied pinned source with prior IPv6 patch:
PASS 34 journal checks (real filesystem, independent process exits with an
uncommitted temp, corrupted record, generation conflict, lock contention,
foreign session, symlink and private-directory checks); PASS 26 backend
checks (including exact persistent pending rule image and failed checkpoint).
Both translation units compile with C17 -Wall -Wextra -Werror in the host
tests; full GCC host link with previous gateway modules succeeded. No live
firewall mutation or Android NDK/device validation was performed. File
system durability across actual power loss has not been tested.

## Same-boot gateway firewall recovery reader

gateway_recovery.c decodes NFFW01 checkpoints, reconstructs only the four
fixed gateway chain/hook identities independent of today's configuration,
validates every saved image against the narrow generated rule grammar and
rejects wrong boot IDs, unknown flags, mismatched family/port, malformed
lengths and trailing data. Saved commands are evidence only, never replayed.
The recovery preflight compares current chain contents with old/pending
images and rejects additional foreign references. The existing backend
checks ownership again during deletion and verifies absence.

The recovery cleanup API requires actual non-firewall callbacks proving
the old listener stopped and old flows drained; NAT removal precedes flow
drain and INPUT removal. It does not clear the journal or assert that
process/conntrack/port recovery completed. No call site is activated.
A different boot must be reconciled separately; current recovery refuses
it. A foreign or ambiguous resource blocks removal rather than guessing.
Preflight and commit are not atomic against external firewall writers.

Host validation: PASS 32 recovery checks using real filesystem records,
native generated rule images and synthetic listings, including different
current settings, mismatched boot, invalid flags with valid CRC, foreign
references and changed rules. C17 -Wall -Wextra -Werror compile and full
GCC host link with previous gateway modules succeeded. The native build
now compiles gateway_recovery.c including gateway_backend.c once.
No live iptables operations, Android NDK build or device tests performed.
Still required: durable process/link/conntrack evidence, actual recovery
callbacks, new-boot handling and supervisor/Service Control call sites.

## Supervisor startup admission fence

The gateway recovery module now exports nf_gateway_admission_check.
build_asteriskd.sh patches supervisor/supervisor.c to call it for BOX
ownership after the global control listener is acquired and state store
opened, but before system_reset_telemetry_state overwrites old evidence.
This is a real production supervisor call site, shared by start, monitor
and boot. It does not execute firewall commands or enable a DNS listener.

Normal admission with no journal and no temporary record takes a read-only
fast path. If a journal exists, acquire its independent directory lock,
validate the record, and allow admission only for a clean record with no
debts, epoch or payload and no temporary file. A malformed, unreadable,
locked, dirty or ambiguous record fails admission without deleting files.
This intentionally also blocks switching to a different BOX ROOT mode
while old gateway DNS cleanup is unresolved. It changes no other mode's
ordinary traffic rules. No runtime journal producer is activated yet.
The lock is not held across a successful admission; future runtime
activation must establish a session-long lock under the control listener.

Host validation: PASS 27 admission checks and PASS 32 existing recovery
checks. Admission tests use real filesystem files, symlink, outstanding
debt, lock contention, clean record, interrupted temp and corrupt record.
The modified recovery translation unit compiled with GCC C17 -Wall
-Wextra -Werror. A full host supervisor binary with the admission call
and previous IPv6 patch linked successfully against the pinned source.
This was not the complete downstream Android build, and no live firewall
or Service Control/boot/device integration test was run.

Automatic cleanup is still NOT wired: the fence blocks unresolved state
rather than calling the recovery API with unproven listener and conntrack
callbacks. Before activating the gateway path, implement durable process
and flow evidence, real cleanup, re-admission and the Service Control
lifecycle hooks. Do not interpret this check as completed DNS hijacking.

## Reconcile previous-boot gateway journal during actual startup

Startup now attempts automatic reconciliation of a dirty gateway journal
before telemetry reset, using a temporary native action reactor only when
the ordinary admission check fails. The control listener remains held.
The kernel boot UUID is read from procfs and parsed strictly. A valid
journal from a different kernel boot is decoded under its exclusive lock;
old processes and in-memory conntrack cannot survive a kernel reboot.
All IPv4/IPv6 filter, NAT and mangle listings must be readable and none
may refer to any of the four gateway chain names. Only then atomically
commit a new clean journal generation and re-run admission. No firewall
rule or conntrack entry is deleted by this path. Missing/malformed
listings, same-boot debt, foreign references, corrupt journals, temp
records, lock failures and interrupted startup remain fail-closed.
The old boot/session identifiers are retained for the clean CAS record.
A fresh session can replace that clean record later.

Host validation: PASS 31 new reboot recovery checks (actual boot ID,
filesystem journal, synthetic firewall listings and injected absence
callback), PASS 27 admission checks and PASS 32 same-boot reader checks.
The changed recovery unit compiled with C17 -Wall -Wextra -Werror; the
modified supervisor linked against the pinned host source and previous
gateway modules. The complete downstream Android NDK build, real
iptables inspection and on-device reboot tests have not been performed.
Same-boot automatic cleanup still requires real old process/flow
identity and deletion callbacks. Gateway inbound remains disabled.

## Same-boot recovery before gateway core spawn

A certified pre-core checkpoint (NFFW01 byte 7 = 1) is now emitted only
after the gateway reservation callback succeeds for a clean new session.
The future supervisor integration must invoke that reservation before any
gateway listener/core spawn; no alternate spawn path may bypass this
contract. Before invoking NF_START_CORE, the adapter clears certification
and durably records core cleanup debt. Guard opening and NAT publication
are rejected while pre-core or without core debt. A failure to checkpoint
prevents the spawn callback. Older uncertified checkpoints do not qualify
for same-boot recovery.

The existing production startup reconciliation now also attempts same-boot
recovery of certified closed-guard-only records, with no epoch, NAT or
core/flow debt. It reconstructs and verifies the old guard, removes only
known rules through the existing native backend, checks absence of all
four chain names, then commits a clean generation. Ambiguous records,
active-session debt and failed cleanup retain the startup fence. No
conntrack deletion or old process termination is attempted.

Host checks using supplied pinned source: PASS 28 pre-core tests, PASS 31
reboot, PASS 32 recovery, PASS 27 admission, PASS 26 backend. The pre-core
tests exercise real journal files and simulated guard removal/spawn
callbacks. The source also compiles with GCC C17 -Wall -Wextra -Werror and
links with the host supervisor including its previously patched admission
call. The committed source blob hashes match the tested local files.
Full downstream Android NDK build and live firewall tests remain undone.
The gateway runtime producer is still not enabled; this recovery path
does not constitute active gateway hijacking or recovery of running
sessions. Next work must implement actual durable child/flow provenance,
or choose an alternative design that avoids the shared conntrack problem,
then wire runtime call sites and validate on Android.
