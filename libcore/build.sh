#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

# Validate after sync_gomod.sh and immediately before gomobile bind.
bash ./verify_utls.sh "$PWD" || exit 1

export GOBIND=gobind-matsuri
SB_VERSION="${SING_BOX_TAG:-v1.14.3-extended-2.7.3}"
SB_VERSION="${SB_VERSION#v}"
# Experimental NaiveProxy/Cronet build. Keep the existing API-21 build
# available until the Android linker and APK packaging are verified.
TAGS="with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0"
ANDROID_API=21
if [ "${NATIVE_NAIVE:-0}" = 1 ]; then
  TAGS="$TAGS,with_naive_outbound"
  ANDROID_API=24
fi
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi "$ANDROID_API" ${LIBCORE_TARGETS:+-target=$LIBCORE_TARGETS} -cache "$(realpath $BUILD)" -trimpath -ldflags="-s -w -X github.com/sagernet/sing-box/constant.Version=${SB_VERSION}" -tags="$TAGS" . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
