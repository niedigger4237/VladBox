#!/bin/bash
# Builds the external sing-box (sing-box-extended + NekoBox patches) used by the
# root modes and drops it into jniLibs as libsingbox.so (extracted on install, see
# useLegacyPackaging). Needs ANDROID_NDK_HOME and the sources from get_source.sh.
set -e
source "buildScript/init/env.sh"
source "buildScript/lib/core/get_source_env.sh"

ROOT="$(pwd)"
SB="$ROOT/../sing-box"
TAGS="with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0"
API=24
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

# Verify the actual module selected by Go and its serialized ClientHello.
bash "$ROOT/libcore/verify_utls.sh" "$SB"
( cd "$SB" && GOOS="$(go env GOHOSTOS)" GOARCH="$(go env GOHOSTARCH)" CGO_ENABLED=1 go test -count=1 -tags "$TAGS" -run '^TestNieFigger(FirefoxFingerprint|RealityHybridOffer|RealityHybridRequiredRejectsUnsupportedFingerprint|RealityMldsa65)$' -v ./common/tls )
( cd "$SB" && GOOS="$(go env GOHOSTOS)" GOARCH="$(go env GOHOSTARCH)" CGO_ENABLED=1 go test -count=1 -run '^TestNieFiggerVlessRecordBounds$' -v ./protocol/vless/encryption )
( cd "$SB" && GOOS="$(go env GOHOSTOS)" GOARCH="$(go env GOHOSTARCH)" CGO_ENABLED=1 go test -count=1 -tags "$TAGS" -run '^TestNieFiggerXHTTPAutoMode$' -v ./transport/v2rayxhttp )
( cd "$SB" && GOOS="$(go env GOHOSTOS)" GOARCH="$(go env GOHOSTARCH)" CGO_ENABLED=1 go test -count=1 -tags "$TAGS" -run '^TestNieFigger(NetworkPauseRecovery|PlatformMonitorNoPause)$' -v ./route )

# Exercise the host-side wire codec and relay generation table before packaging
# an experimental native core. This is not an Android device stress test.
( cd "$SB" && GOOS="$(go env GOHOSTOS)" GOARCH="$(go env GOHOSTARCH)" CGO_ENABLED=1 \
  go test -race -count=1 -v ./common/androidcli/flowmeta )

# ROOTCORE_ABIS="arm64-v8a" builds a single ABI (fast CI); empty = all
build() { # abi goarch cc-prefix [GOARM]
  local abi=$1 goarch=$2 cc=$3 goarm=$4
  if [ -n "$ROOTCORE_ABIS" ] && [[ " $ROOTCORE_ABIS " != *" $abi "* ]]; then return; fi
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  ( cd "$SB" && \
    CGO_ENABLED=1 GOOS=android GOARCH=$goarch GOARM=$goarm \
    CC="$TOOLCHAIN/${cc}${API}-clang" \
    go build -trimpath -buildvcs=false -tags "$TAGS" \
      -ldflags "-s -w -buildid= -X github.com/sagernet/sing-box/constant.Version=${SING_BOX_TAG#v}" \
      -o "$ROOT/app/src/main/jniLibs/$abi/libsingbox.so" ./cmd/sing-box )
}

build arm64-v8a  arm64 aarch64-linux-android
build armeabi-v7a arm  armv7a-linux-androideabi 7
build x86_64     amd64 x86_64-linux-android
