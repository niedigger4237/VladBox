#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
ROOT="$(pwd)"
PATCH_DIR="$ROOT/buildScript/lib/core/patches"
pushd ..

####  sing-box-extended + NekoBox patches

if [ ! -d "sing-box/.git" ]; then
  rm -rf sing-box
  git clone --no-checkout "$SING_BOX_REPO" sing-box
fi
pushd sing-box
git reset -q --hard
git clean -qfd
git checkout -q "$SING_BOX_COMMIT"
# the tag must still point to the commit we audited
[ "$(git rev-parse "$SING_BOX_TAG^{commit}")" = "$SING_BOX_COMMIT" ] || {
  echo "ERROR: tag $SING_BOX_TAG no longer points to $SING_BOX_COMMIT" >&2
  exit 1
}
for p in "$PATCH_DIR"/*.patch; do
  echo ">> apply $(basename "$p")"
  git apply --whitespace=nowarn "$p"
done
popd

####  MetaCubeX uTLS upstream (Firefox 148, Safari 26.3)
# Keep the existing local directory name for libcore and ROOT build compatibility.
if [ ! -d "utls-lx/.git" ]; then
  git clone --no-checkout "$UTLS_REPO" utls-lx
else
  git -C utls-lx remote set-url origin "$UTLS_REPO"
fi
if ! git -C utls-lx cat-file -e "$UTLS_COMMIT^{commit}" 2>/dev/null; then
  git -C utls-lx fetch --no-tags origin "$UTLS_COMMIT"
fi
git -C utls-lx checkout --detach "$UTLS_COMMIT"
[ "$(git -C utls-lx rev-parse HEAD)" = "$UTLS_COMMIT" ]
# libcore sync sets its own relative replacement path.
(cd sing-box && go mod edit -replace=github.com/metacubex/utls=../utls-lx)
# Verify the production fingerprint resolver during ROOT validation.
cp "$ROOT/libcore/testdata/firefox_mapping_test.go" sing-box/common/tls/niefigger_firefox_mapping_test.go
cp "$ROOT/libcore/testdata/reality_hybrid_test.go" sing-box/common/tls/niefigger_reality_hybrid_test.go
cp "$ROOT/libcore/testdata/reality_mldsa_test.go" sing-box/common/tls/niefigger_reality_mldsa_test.go
cp "$ROOT/libcore/testdata/vless_record_test.go" sing-box/protocol/vless/encryption/niefigger_record_test.go
cp "$ROOT/libcore/testdata/xhttp_auto_test.go" sing-box/transport/v2rayxhttp/niefigger_auto_test.go

####  libneko

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout -q "$COMMIT_LIBNEKO"
popd

popd
