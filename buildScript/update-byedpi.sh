#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
version="${1:?Specify upstream tag or commit}"
[[ "$version" =~ ^(v[0-9][0-9A-Za-z.-]*|[0-9a-f]{40})$ ]] || exit 2
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git clone https://github.com/hufrea/byedpi.git "$tmp/src"
git -C "$tmp/src" checkout --detach "$version"
ndk="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
tc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$tc/aarch64-linux-android24-clang"
mkdir -p third_party/byedpi
git -C "$tmp/src" rev-parse HEAD > third_party/byedpi/commit.txt
printf '%s\n' "$version" > third_party/byedpi/version.txt
cp "$tmp/src/LICENSE" third_party/byedpi/LICENSE
build() {
  local target="$1" abi="$2" dest="$root/app/src/main/jniLibs/$2/libciadpi.so"
  make -C "$tmp/src" clean
  make -C "$tmp/src" -j"$(nproc)" CC="$tc/$target-clang" CPPFLAGS="-D_DEFAULT_SOURCE"
  test -s "$tmp/src/ciadpi"
  mkdir -p "$(dirname "$dest")"
  install -m 755 "$tmp/src/ciadpi" "$dest"
  "$tc/llvm-readelf" -h "$dest"
  "$tc/llvm-readelf" -l "$dest" | grep -E 'interpreter: /system/bin/linker(64)?]'
}
build aarch64-linux-android24 arm64-v8a
build armv7a-linux-androideabi24 armeabi-v7a
build x86_64-linux-android24 x86_64
sha256sum app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libciadpi.so > third_party/byedpi/SHA256SUMS
