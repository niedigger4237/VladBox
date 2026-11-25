#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SCRIPT="$ROOT/app/src/main/assets/root/tether-crash-recovery.sh"
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
boot=$(cat /proc/sys/kernel/random/boot_id)
valid() { sh "$SCRIPT" --validate "$D/record"; }
reject() { if valid; then echo "Invalid record accepted" >&2; exit 1; fi; }
printf 'route 4 %s abcdef012345 17900 17902 tun0\ndown wlan2 192.168.43.0/24\n' "$boot" > "$D/record"
valid
printf 'route 6 %s abcdef012345 17900 17902 tun0\ndown wlan2 fd00::/64\n' "$boot" > "$D/record"
valid
printf 'guard NFV_G_abcdef012345 null\niface wlan2\n' > "$D/record"
valid
printf 'route 4 %s abcdef012345 254 17902 tun0\ndown wlan2 192.168.43.0/24\n' "$boot" > "$D/record"
reject
printf 'guard NFV_G_abcdef012345 0\niface wlan2;id\n' > "$D/record"
reject
printf 'route 4 %s abcdef012345 17900 17902 tun0\ndown wlan2 192.168.43.256/24\n' "$boot" > "$D/record"
reject
printf 'guard NFV_G_abcdef012345 0\niface wlan2\niface wlan2\n' > "$D/record"
reject
printf 'guard NFV_G_abcdef012345 0\niface asterisk0\n' > "$D/record"
reject
printf 'route 6 %s abcdef012345 17900 17902 tun0\ndown wlan2 fd00::/129\n' "$boot" > "$D/record"
reject
printf 'route 4 %s abcdef012345 17900 17902 tun0\n' "$boot" > "$D/record"
reject

# Exercise the cleanup executor against fakes, never the host firewall.
mkdir "$D/bin"
export MOCK_LOG="$D/commands" MOCK_SETTING="$D/setting" MOCK_BUSY="$D/busy"
cat > "$D/bin/ip" <<'EOF'
#!/bin/sh
printf 'ip %s\n' "$*" >> "$MOCK_LOG"
case "$*" in
    *"rule show") ;;
    *"route show table "*) if [ -f "$MOCK_BUSY" ]; then echo "192.0.2.0/24 dev foreign0"; fi ;;
esac
exit 0
EOF
cat > "$D/bin/iptables" <<'EOF'
#!/bin/sh
printf 'firewall %s\n' "$*" >> "$MOCK_LOG"
case " $* " in
    *" -C "*) exit 1;;
    *" -S NFV"*) exit 1;;
esac
exit 0
EOF
cp "$D/bin/iptables" "$D/bin/ip6tables"
cat > "$D/bin/settings" <<'EOF'
#!/bin/sh
printf 'settings %s\n' "$*" >> "$MOCK_LOG"
case "$1" in
    get) cat "$MOCK_SETTING";;
    delete) echo null > "$MOCK_SETTING";;
    put) printf '%s\n' "$4" > "$MOCK_SETTING";;
    *) exit 1;;
esac
EOF
chmod +x "$D/bin/"*
recover() { PATH="$D/bin:$PATH" sh "$SCRIPT" --recover "$D/record"; }
: > "$MOCK_LOG"
printf 'route 4 %s abcdef012345 17900 17902 tun0\ndown wlan2 192.168.43.0/24\n' "$boot" > "$D/record"
recover
grep -Fx 'ip -4 route del default dev tun0 table 17900' "$MOCK_LOG"
grep -Fx 'ip -4 rule del pref 17902 iif wlan2 lookup 17900' "$MOCK_LOG"
if grep -q 'ip .*flush' "$MOCK_LOG"; then exit 1; fi
touch "$MOCK_BUSY"
if recover; then echo "Accepted remaining foreign route" >&2; exit 1; fi
rm "$MOCK_BUSY"
printf 'route 6 %s abcdef012345 17900 17902 tun0\ndown wlan2 fd00::/64\n' "$boot" > "$D/record"
recover
grep -Fx 'ip -6 route del fd00::/64 dev wlan2 table 17900' "$MOCK_LOG"
printf 'guard NFV_G_abcdef012345 null\niface wlan2\n' > "$D/record"
echo 1 > "$MOCK_SETTING"
recover
test "$(cat "$MOCK_SETTING")" = null
grep -Fx 'settings delete global tether_offload_disabled' "$MOCK_LOG"
# A changed setting is not overwritten.
echo 0 > "$MOCK_SETTING"
recover
test "$(cat "$MOCK_SETTING")" = 0
echo "Tether recovery record and mocked cleanup fixtures passed"

