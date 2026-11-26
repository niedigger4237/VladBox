#!/bin/sh
# Host-only test of the ROOT cache preflight asset. Run in a root Linux
# container; this does not exercise Android, SELinux or asteriskd itself.
set -eu
[ "$(id -u)" = 0 ] || { echo 'Run in a root Linux test container' >&2; exit 77; }
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
P="$ROOT/app/src/main/assets/root/dns-cache-prepare.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT HUP INT TERM
D="$T/nf-dns-cache-v1"
A="$(printf '%064d' 1)"
B="$(printf '%064d' 2)"
sh -n "$P"; echo 'PASS syntax'
[ "$(sh "$P" "$D" '')" = memory ] && [ ! -e "$D" ]; echo 'PASS empty memory'
[ "$(sh "$P" "$D" "$A")" = persistent ]
[ "$(stat -c %a "$D")" = 700 ]; [ "$(stat -c %a "$D/cache.db")" = 600 ]; echo 'PASS create private'
printf saved > "$D/cache.db"
[ "$(sh "$P" "$D" "$A")" = persistent ]; [ "$(cat "$D/cache.db")" = saved ]; echo 'PASS same scope'
(
    flock -n -x 8
    if sh "$P" "$D" "$B"; then exit 1; fi
    [ "$(cat "$D/cache.db")" = saved ]
) 8<>"$D/cache.db"; echo 'PASS locked DB'
(
    flock -n -x 9
    if sh "$P" "$D" ''; then exit 1; fi
) 9<>"$D/lease"; echo 'PASS locked lease'
[ "$(sh "$P" "$D" "$B")" = persistent ]; [ ! -s "$D/cache.db" ]
[ "$(cat "$D/scope")" = "$B" ]; echo 'PASS scope reset'
truncate -s 33554433 "$D/cache.db"
[ "$(sh "$P" "$D" "$B")" = persistent ]; [ ! -s "$D/cache.db" ]; echo 'PASS size reset'
[ "$(sh "$P" "$D" '')" = memory ]; [ ! -e "$D/cache.db" ]; [ ! -e "$D/scope" ]; echo 'PASS clear'
printf untouched > "$T/foreign"; chmod 600 "$T/foreign"
ln -s "$T/foreign" "$D/cache.db"
if sh "$P" "$D" "$A"; then exit 1; fi
[ "$(cat "$T/foreign")" = untouched ]; echo 'PASS symlink refusal'
rm "$D/cache.db"; ln "$T/foreign" "$D/cache.db"
if sh "$P" "$D" "$A"; then exit 1; fi
[ "$(cat "$T/foreign")" = untouched ]; echo 'PASS hardlink refusal'
if sh "$P" "$D" abc; then exit 1; fi
echo 'PASS invalid scope'
