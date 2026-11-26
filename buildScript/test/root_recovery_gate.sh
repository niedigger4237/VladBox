#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/buildScript/lib/core/recovery"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -I"$SRC" "$SRC/nf_sysctl_wal.c" "$SRC/nf_process_guard.c" "$SRC/nf_recovery_gate.c" "$ROOT/buildScript/test/root_recovery_gate.c" -o "$TMP/test"
"$TMP/test"
