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

####  libneko

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout -q "$COMMIT_LIBNEKO"
popd

popd
