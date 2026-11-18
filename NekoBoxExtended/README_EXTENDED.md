# NekoBox Extended (fork)

Fork of NekoBoxForAndroid on top of **sing-box-extended v1.14.3-extended-2.7.3**
(commit `d9442d14`, pinned and verified in `buildScript/lib/core/get_source.sh`).

## What changed
- Core: Matsuri sing-box 1.12.19 -> shtorm-7/sing-box-extended 1.14.3. NekoBox patches live in
  `buildScript/lib/core/patches/0001-neko-on-extended.patch` (applied to the pinned commit).
  `libcore/go.mod` is regenerated from the core's go.mod + go.sum (`libcore/sync_gomod.sh`), so all
  dependencies are checked against the hashes published by the core's author.
- Config generator migrated to the sing-box >= 1.13 format: route-action `sniff`/`resolve`/`hijack-dns`,
  new DNS server objects (`buildDnsServer`), `domain_resolver`, tun `address`, WireGuard as an *endpoint*.
- **AmneziaWG**: WireGuard profile has Jc/Jmin/Jmax/S1-S4/H1-H4/I1-I5; `.conf` import reads them.
- **XHTTP**: transport `xhttp` for VLESS/Trojan/VMess (host, path, mode, Xray-style `extra` JSON),
  share-link parse/export (`type=xhttp&mode=&extra=`).
- **Root modes** (Settings -> Service mode): `Root: TUN` (sing-box creates the tun device, no VpnService)
  and `Root: TPROXY` (tproxy inbound + iptables/ip6tables). The core runs as an external root process.
- **Seamless core update (root)**: versions are stored side by side in `/data/adb/nekobox/cores`,
  verified with `sing-box version`, activated by atomically swapping `current`; a running core is never
  touched, the next start uses the new one; `Roll back` and automatic fallback to the bundled core.
  Update from GitHub releases or import a binary file.

## Known limitations
- WARP `reserved` bytes are not supported by sing-box-extended's WireGuard endpoint (field removed from UI).
- `sniff_override_destination` has no equivalent in 1.13+; "sniff + override" now behaves like "sniff".
- **bpf2socks is not implemented** (needs a compiled eBPF program + loader; see issue notes).
- This tree was written without being able to run Gradle/NDK/Go modules in the authoring sandbox:
  **it has never been compiled**. Expect to fix compile errors on the first CI run.

## Build
```
./run lib core       # libcore.aar (gomobile)
./run lib rootcore   # libsingbox.so for the root modes
./gradlew app:assembleOssRelease
```
