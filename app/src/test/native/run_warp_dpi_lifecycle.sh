#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd "$(dirname "$0")" && pwd)"
main_dir="$test_dir/../../main"
native_dir="$main_dir/cpp/warp_dpi"
catalog="$main_dir/java/io/nekohasekai/sagernet/generator/warp/DpiStrategyCatalog.kt"
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT

python3 - "$catalog" > "$output/warp_dpi_test_strategies.h" <<'PY'
import json
from pathlib import Path
import re
import sys

presets = re.findall(r'preset\("[^"]+", "([^"]*)"\)', Path(sys.argv[1]).read_text())
assert len(presets) == 18
print("static struct { int length; char *argv[19]; } strategies[] = {")
for preset in presets:
    argv = ["warp_dpi", "-U", *preset.split()]
    print(f"    {{{len(argv)}, {{{', '.join(map(json.dumps, argv))}, NULL}}}},")
print("};")
PY

"${CC:-cc}" -D_GNU_SOURCE -std=c11 -O1 -g -pthread -Wl,--wrap=getaddrinfo \
    -I"$output" -I"$native_dir" \
    "$test_dir/warp_dpi_lifecycle.c" "$native_dir/warp_dpi_engine.c" \
    "$native_dir/private_stream.c" "$native_dir"/byedpi/*.c \
    -o "$output/warp_dpi_lifecycle"
"$output/warp_dpi_lifecycle"
