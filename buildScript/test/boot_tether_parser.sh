#!/bin/sh
# Host fixture tests for the Android-independent boot downstream parser.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
MON="$ROOT/app/src/main/assets/root/boot-tether-monitor.sh"
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
printf 'select wlan+\nselect rndis+\nignore rndis1\n' > "$D/policy"
cat > "$D/tether" <<'EOF'
Tether state:
wlan0 - TetheredState - lastError = 0
wlan2 - TetheredState - lastError = 0
rndis1 - TetheredState - lastError = 0
EOF
cat > "$D/routes" <<'EOF'
default via 192.0.2.1 dev wlan0
default via fe80::1 dev wlan0
2001:db8:1::/64 dev wlan2 proto kernel metric 256
fe80::/64 dev wlan2 proto kernel metric 256
2001:db8:2::/64 dev wlan0 proto kernel metric 256
EOF
printf '1: wlan0: UP\n44: wlan2: UP\n5: rndis1: UP\n' > "$D/links"
printf '44: wlan2 inet 192.168.43.1/24 scope global\n' > "$D/addr"
parse() { sh "$MON" --parse "$D/policy" "$D/tether" "$D/routes" "$D/links" "$D/addr"; }
parse | LC_ALL=C sort > "$D/actual"
printf 'ipv4 wlan2 192.168.43.1/24\nipv6-prefix wlan2 2001:db8:1::/64\nlink 44 wlan2\n' > "$D/expected"
diff -u "$D/expected" "$D/actual"
printf 'wlan2 - TetheredState - unexpected format\n' > "$D/tether"
if parse > "$D/actual"; then echo "Accepted ambiguous tether state" >&2; exit 1; fi
printf 'Tether state:\nwlan2 - AvailableState - lastError = 0\n' > "$D/tether"
parse > "$D/actual"
test ! -s "$D/actual"
printf 'wlan2 - TetheredState - lastError = 0\n' > "$D/tether"
: > "$D/links"
if parse > "$D/actual"; then echo "Accepted missing interface identity" >&2; exit 1; fi
echo "Boot downstream parser fixtures passed"
