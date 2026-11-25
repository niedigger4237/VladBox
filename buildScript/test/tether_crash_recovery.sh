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
echo "Tether recovery record fixtures passed"
