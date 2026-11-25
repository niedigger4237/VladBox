#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
"${HOST_CC:-cc}" -std=c11 -Wall -Wextra -Werror -O2 \
    -I "$ROOT/buildScript/lib/core/hev-patches/src" \
    "$ROOT/buildScript/test/hev_flow_bounds.c" -o "$D/hev-flow-test"
"$D/hev-flow-test"
