#!/bin/bash
# Host regression test against the exact upstream revision, without Android or root.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PIN=a31fd6dfdbfb515b8c18cc91bc3637b523f0b255
git clone -q --depth 1 --branch v2.3.2 https://github.com/Asterisk4Magisk/asteriskd "$TMP/src"
test "$(git -C "$TMP/src" rev-parse HEAD)" = "$PIN"
sed -n "/^cat .*<<'NIEFIGER_ASTERISKD_PATCH'$/,/^NIEFIGER_ASTERISKD_PATCH$/p" "$ROOT/buildScript/lib/core/build_asteriskd.sh" | sed '1d;$d' > "$TMP/patch"
test -s "$TMP/patch"
git -C "$TMP/src" apply --check "$TMP/patch"
git -C "$TMP/src" apply "$TMP/patch"
"${CC:-cc}" -std=c11 -D_GNU_SOURCE -Wall -Wextra -I"$TMP/src" \
  "$ROOT/buildScript/test/root_cleanup_journal.c" "$TMP/src/resources/effect_journal.c" "$TMP/src/runtime/lifecycle.c" -o "$TMP/test"
"$TMP/test"
