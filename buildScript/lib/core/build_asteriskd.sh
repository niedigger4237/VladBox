#!/bin/bash
# Builds asteriskd (the root supervisor from Asterisk4Magisk/asteriskd, GPL-3.0) for the root modes
# and drops it into jniLibs as libasteriskd.so. Same flags as the upstream AsteriskBOX build.
set -e
source "buildScript/init/env.sh"

ROOT="$(pwd)"
ASTERISKD_TAG=v2.3.2
ASTERISKD_COMMIT=a31fd6dfdbfb515b8c18cc91bc3637b523f0b255
SRC="$ROOT/../asteriskd-src"
API=24
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

if [ ! -d "$SRC/.git" ]; then
  git clone -q --depth 1 --branch "$ASTERISKD_TAG" https://github.com/Asterisk4Magisk/asteriskd "$SRC"
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$ASTERISKD_COMMIT" ] || { echo "asteriskd $ASTERISKD_TAG moved: refusing to build"; exit 1; }

SOURCES=$(cd "$SRC" && find . -name '*.c' -not -path './.git/*' | LC_ALL=C sort)

build() { # abi cc-prefix
  local abi=$1 cc=$2
  if [ -n "$ROOTCORE_ABIS" ] && [[ " $ROOTCORE_ABIS " != *" $abi "* ]]; then return; fi
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  ( cd "$SRC" && "$TOOLCHAIN/${cc}${API}-clang" -O2 -flto=full -std=c17 -fPIE -pie -I"$SRC" \
      $SOURCES -ldl -lz -o "$ROOT/app/src/main/jniLibs/$abi/libasteriskd.so" )
}

build arm64-v8a  aarch64-linux-android
build armeabi-v7a armv7a-linux-androideabi
build x86_64     x86_64-linux-android
