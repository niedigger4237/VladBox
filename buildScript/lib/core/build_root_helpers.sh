#!/bin/bash
# Builds the helpers asteriskd supervises in the TUN2SOCKS / BPF2SOCKS root modes:
#   hev-socks5-tunnel (heiher, MIT)         -> jniLibs/<abi>/libhevtun.so
#   bpf2socks (Asterisk4Magisk, GPL-3.0)    -> jniLibs/<abi>/libbpf2socks.so
# Both are PIE executables packaged as native libraries (like libasteriskd.so).
set -e
source "buildScript/init/env.sh"

ROOT="$(pwd)"
HEV_TAG=2.20.0
HEV_COMMIT=75dbc1e9ace6c70d3d1b94df7c283efe1de7effc
B2S_TAG=v1.2.4
B2S_COMMIT=be058b9ac8d7b8ba05ddba9424d159ca0dab7a28
MATCHER_TAG=v1.0.3
MATCHER_COMMIT=7822395eb543f49f0c2360580377b51f3c0205be
API=24
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

HEV="$ROOT/../hev-socks5-tunnel-src"
B2S="$ROOT/../bpf2socks-src"
MATCHER="$ROOT/../bpfmatcher-src"
HEV_PATCHES="$ROOT/buildScript/lib/core/hev-patches"

if [ ! -d "$HEV/.git" ]; then
  git clone -q --depth 1 --branch "$HEV_TAG" --recurse-submodules --shallow-submodules \
    https://github.com/heiher/hev-socks5-tunnel "$HEV"
fi
[ "$(git -C "$HEV" rev-parse HEAD)" = "$HEV_COMMIT" ] || { echo "hev-socks5-tunnel $HEV_TAG moved: refusing to build"; exit 1; }

if [ ! -d "$B2S/.git" ]; then
  git clone -q --depth 1 --branch "$B2S_TAG" https://github.com/Asterisk4Magisk/bpf2socks "$B2S"
fi
[ "$(git -C "$B2S" rev-parse HEAD)" = "$B2S_COMMIT" ] || { echo "bpf2socks $B2S_TAG moved: refusing to build"; exit 1; }

if [ ! -d "$MATCHER/.git" ]; then
  git clone -q --depth 1 --branch "$MATCHER_TAG" https://github.com/Asterisk4Magisk/bpfmatcher "$MATCHER"
fi
[ "$(git -C "$MATCHER" rev-parse HEAD)" = "$MATCHER_COMMIT" ] || { echo "bpfmatcher tag moved"; exit 1; }

# Restore the two upstream files on repeated local builds before applying the pinned overlay.
git -C "$HEV" restore -- src/hev-socks5-session.c src/hev-socks5-session-udp.c
cp "$HEV_PATCHES/src/"* "$HEV/src/"
git -C "$HEV" apply --check "$HEV_PATCHES/0001-flow-metadata.patch"
git -C "$HEV" apply "$HEV_PATCHES/0001-flow-metadata.patch"

want() { # abi
  [ -z "$ROOTCORE_ABIS" ] || [[ " $ROOTCORE_ABIS " == *" $1 "* ]]
}

build_hev() { # abi triple
  local abi=$1 triple=$2
  want "$abi" || return 0
  local cc="$TOOLCHAIN/${triple}${API}-clang"
  # bionic has no separate libpthread: give the Makefile's -lpthread an empty archive
  local stub; stub="$(mktemp -d)"; "$TOOLCHAIN/llvm-ar" rc "$stub/libpthread.a"
  ( cd "$HEV" && make clean >/dev/null 2>&1 || true
    make -j"$(nproc)" REV_ID="$HEV_TAG" \
      CC="$cc -fPIE -pie -L$stub" PP="$cc -E" AR="$TOOLCHAIN/llvm-ar" STRIP="$TOOLCHAIN/llvm-strip" \
      CFLAGS="-fPIE -Wno-error -DFD_SET_DEFINED" bin/hev-socks5-tunnel )
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$HEV/bin/hev-socks5-tunnel" "$ROOT/app/src/main/jniLibs/$abi/libhevtun.so"
  "$TOOLCHAIN/llvm-strip" "$ROOT/app/src/main/jniLibs/$abi/libhevtun.so"
}

build_b2s() { # abi
  local abi=$1
  want "$abi" || return 0
  ( cd "$B2S" && make clean >/dev/null 2>&1 || true
    make android ABI="$abi" ANDROID_API="$API" )
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$B2S/build/android/$abi/libbpf2socks.so" "$ROOT/app/src/main/jniLibs/$abi/libbpf2socks.so"
}

build_matcher() {
  local abi=$1 triple=$2
  want "$abi" || return 0
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  "$TOOLCHAIN/${triple}${API}-clang" -O2 -std=gnu17 -fPIE -pie \
    "$MATCHER/bpf-matcher.c" -o "$ROOT/app/src/main/jniLibs/$abi/libbpf-matcher.so"
  "$TOOLCHAIN/llvm-strip" "$ROOT/app/src/main/jniLibs/$abi/libbpf-matcher.so"
}

for spec in "arm64-v8a aarch64-linux-android" "armeabi-v7a armv7a-linux-androideabi" "x86_64 x86_64-linux-android"; do
  set -- $spec
  build_hev "$1" "$2"
  build_b2s "$1"
  build_matcher "$1" "$2"
done
