#!/bin/bash
# Explicit experimental build preparation. Never touches the normal source cache.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="${1:?usage: $0 /path/to/new/experimental-source}"
PIN=a31fd6dfdbfb515b8c18cc91bc3637b523f0b255
test ! -e "$DEST"
git clone -q --depth 1 --branch v2.3.2 https://github.com/Asterisk4Magisk/asteriskd "$DEST"
test "$(git -C "$DEST" rev-parse HEAD)" = "$PIN"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
sed -n "/^cat .*<<'NIEFIGER_ASTERISKD_PATCH'$/,/^NIEFIGER_ASTERISKD_PATCH$/p" "$ROOT/buildScript/lib/core/build_asteriskd.sh" | sed '1d;$d' > "$TMP"
git -C "$DEST" apply --check "$TMP"
git -C "$DEST" apply "$TMP"
REC="$ROOT/buildScript/lib/core/recovery"
git -C "$DEST" apply --check "$REC/native-recovery-experiment.patch"
git -C "$DEST" apply "$REC/native-recovery-experiment.patch"
mkdir "$DEST/recovery"
cp "$REC"/nf_*.c "$REC"/nf_*.h "$DEST/recovery/"
echo "Prepared experimental asteriskd source only; no production artifact installed."
