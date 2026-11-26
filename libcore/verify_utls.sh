#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/buildScript/lib/core/get_source_env.sh"
[ "$#" -eq 1 ] || exit 2
MODULE_DIR="$(cd "$1" && pwd)"
FORK="$(cd "$ROOT/../utls-lx" && pwd -P)"
actual="$(git -C "$FORK" rev-parse HEAD)"
if [ "$actual" != "$UTLS_COMMIT" ]; then
    echo "ERROR: uTLS checkout does not match pinned revision: $actual" >&2
    exit 1
fi
if [ -n "$(git -C "$FORK" status --porcelain --untracked-files=all)" ]; then
    echo "ERROR: uTLS source checkout is not clean" >&2
    exit 1
fi

cd "$MODULE_DIR"
# Transitive module replacements are not inherited. Check the actual graph.
replacement="$(go list -m -f '{{if .Replace}}{{.Replace.Dir}}{{end}}' github.com/metacubex/utls)"
if [ -z "$replacement" ] || [ ! -d "$replacement" ] ||
    [ "$(cd "$replacement" && pwd -P)" != "$FORK" ]; then
    echo "ERROR: this core does not resolve uTLS to the pinned local fork" >&2
    exit 1
fi
printf 'uTLS verified: module=%s commit=%s replacement=%s\n' "$MODULE_DIR" "$actual" "$FORK"
GOOS="$(go env GOHOSTOS)" GOARCH="$(go env GOHOSTARCH)" CGO_ENABLED=0 \
    go test -count=1 -timeout=30s -v "$ROOT/libcore/testdata/firefox_wire_test.go"
