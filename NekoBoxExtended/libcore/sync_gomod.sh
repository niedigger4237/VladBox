#!/bin/bash
# Regenerate libcore/go.mod + go.sum from the sing-box-extended checkout.
# go.sum entries come from the upstream repository, so every dependency
# (including shtorm-7/* forks) is verified against hashes the author published.
set -e
SB=../../sing-box
[ -f "$SB/go.mod" ] || { echo "sing-box source missing, run buildScript/lib/core/get_source.sh"; exit 1; }

cp "$SB/go.mod" go.mod
cp "$SB/go.sum" go.sum
go mod edit -module libcore
go mod edit \
  -require=github.com/sagernet/sing-box@v1.0.0 -replace=github.com/sagernet/sing-box=../../sing-box \
  -require=github.com/matsuridayo/libneko@v1.0.0 -replace=github.com/matsuridayo/libneko=../../libneko \
  -require=github.com/miekg/dns@v1.1.67 \
  -require=github.com/oschwald/maxminddb-golang@v1.13.1 \
  -require=github.com/ulikunitz/xz@v0.5.15 \
  -require=golang.org/x/mobile@v0.0.0-20231108233038-35478a0c49da
go mod tidy
