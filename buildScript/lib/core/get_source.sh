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

####  uTLS Firefox 148 (sing-box-lx SPEC 086)
# Keep the metacubex module path and pin the reviewed fork revision.
UTLS_COMMIT=6b7f051602cf724395f6c5aea055e951c37853a4
if [ ! -d "utls-lx/.git" ]; then
  git clone --no-checkout https://github.com/Leadaxe/utls-lx.git utls-lx
fi
git -C utls-lx checkout --detach "$UTLS_COMMIT"
[ "$(git -C utls-lx rev-parse HEAD)" = "$UTLS_COMMIT" ]
# libcore sync sets its own relative replacement path.
(cd sing-box && go mod edit -replace=github.com/metacubex/utls=../utls-lx)

####  libneko

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout -q "$COMMIT_LIBNEKO"
popd

popd
