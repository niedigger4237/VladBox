#!/bin/bash
# Builds Throne's core (throneproj sing-box fork + throneproj wireguard-go, ThroneCore module)
# as a standalone runner and drops it into jniLibs as libsingbox_throne.so.
# Needs ANDROID_NDK_HOME. ROOTCORE_ABIS limits the ABIs.
set -e
source "buildScript/init/env.sh"
ROOT="$(pwd)"
THRONE_REF=e363c6eca17dd7aeb9062f3de0a961becf21ad6c
TD="$ROOT/../Throne"
if [ ! -d "$TD/.git" ]; then
  rm -rf "$TD"; git init -q "$TD"
  git -C "$TD" remote add origin https://github.com/throneproj/Throne
fi
git -C "$TD" fetch -q --depth 1 origin "$THRONE_REF"
git -C "$TD" checkout -q --detach -f FETCH_HEAD
[ "$(git -C "$TD" rev-parse HEAD)" = "$THRONE_REF" ] || { echo "ERROR: throne ref mismatch" >&2; exit 1; }
mkdir -p "$TD/core/cmd/vladbox-run"
cp "$ROOT/buildScript/lib/core/throne/vladbox_run.go" "$TD/core/cmd/vladbox-run/main.go"

TAGS=$(sed -n 's/^TAGS="\(.*\)"$/\1/p' "$TD/script/build_android.sh")
test -n "$TAGS"
API=24
TOOLCHAIN="${THRONE_NDK_HOME:-$ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"

build() { # abi goarch cc-prefix [GOARM]
  local abi=$1 goarch=$2 cc=$3 goarm=$4
  if [ -n "$ROOTCORE_ABIS" ] && [[ " $ROOTCORE_ABIS " != *" $abi "* ]]; then return; fi
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  ( cd "$TD/core" && \
    CGO_ENABLED=1 GOOS=android GOARCH=$goarch GOARM=$goarm \
    CC="$TOOLCHAIN/${cc}${API}-clang" \
    go build -trimpath -buildvcs=false -tags "$TAGS" \
      -ldflags "-s -w -buildid= -checklinkname=0" \
      -o "$ROOT/app/src/main/jniLibs/$abi/libsingbox_throne.so" ./cmd/vladbox-run )
}
build arm64-v8a  arm64 aarch64-linux-android
build armeabi-v7a arm  armv7a-linux-androideabi 7
build x86_64     amd64 x86_64-linux-android
