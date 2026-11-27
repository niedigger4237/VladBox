# WireGuard / AmneziaWG ROOT verification

Run on a ROOT Android test device with the bundled core, one valid WireGuard
profile and one valid AmneziaWG profile. Repeat with each supported AWG profile
version. Do not assume a successful daemon health check proves tunnel connectivity.

| Mode | Ingress | Verify |
| --- | --- | --- |
| ROOT TUN | sing-box TUN, auto_route / auto_redirect | TCP, UDP and DNS; physical interface selection |
| ROOT TPROXY | transparent inbound, daemon policy routing | TCP and UDP; no recursive capture of core transport |
| ROOT TUN2SOCKS | HEV helper, local SOCKS5 | TCP, UDP and DNS; helper remains healthy |
| ROOT BPF2SOCKS | BPF helper, local SOCKS5 | TCP, UDP and DNS; helper remains healthy |

For every mode and profile:

1. Start on Wi-Fi; verify a fresh HTTPS request through the selected profile
   and a UDP request to a controlled test service. Check the expected tunnel exit.
2. Switch to mobile data; repeat both probes, then switch back to Wi-Fi.
3. Temporarily remove both upstream networks, then restore one. Verify new
   connections recover. Existing TCP connections may close.
4. Repeat with PersistentKeepalive 0 and 25. Use fresh traffic for the 0 case.
5. Check the native core log for default interface updates, handshake attempts,
   handshake completion, and bind failures. An Android network callback alone
   does not prove recovery. Record any daemon or core restart.
6. Repeat with a compatible WG/AWG chain and a domain-based peer. Keep Listen
   port 0 for a hop reached through a detour.
7. Stop the service normally; verify daemon/helper cleanup using the existing
   ROOT cleanup diagnostics. Do not manually flush firewall or conntrack state.

Optional: repeat using an already supported hotspot configuration without changing
the disabled experimental gateway DNS, tether DNS interception or recovery flags.

The Android application does not reset the embedded libcore for an external ROOT
session. The native core uses its own default-interface monitor. A custom or
external core may behave differently. This is a manual verification matrix, not
a record of passing tests.
