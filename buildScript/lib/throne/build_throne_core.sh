#!/bin/bash
# Builds ThroneCore.aar (core/mobile of throneproj/Throne) so that it can live next to libcore.aar in one APK.
# Both AARs are gomobile bindings and would both ship `go.Seq` / `go.Universe` and `Java_go_Seq_*` JNI entry
# points, so Throne's binding is generated with a patched gomobile whose runtime classes are `gt.*` and whose
# Seq loads libthrone.so.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
source "$HERE/throne.env"
WORK="${WORK:-$ROOT/../throne-build}"
DEST="${DEST:-$ROOT/app/libs}"
TARGETS="${THRONE_TARGETS:-android/arm64,android/arm,android/amd64}"
mkdir -p "$WORK" "$DEST"

# --- sources
rm -rf "$WORK/Throne" "$WORK/gomobile"
git init -q "$WORK/Throne"
git -C "$WORK/Throne" remote add origin "$THRONE_REPO"
git -C "$WORK/Throne" fetch -q --depth 1 origin "$THRONE_REF"
git -C "$WORK/Throne" checkout -q --detach FETCH_HEAD
[ "$(git -C "$WORK/Throne" rev-parse HEAD)" = "$THRONE_REF" ] || { echo "Throne ref mismatch" >&2; exit 1; }
git clone -q --depth 1 --branch "$GOMOBILE_TAG" "$GOMOBILE_REPO" "$WORK/gomobile"

# --- rename the gomobile runtime package go -> gt
pushd "$WORK/gomobile" >/dev/null
sed -i 's/^package go;/package gt;/; s/^import go\.Universe;/import gt.Universe;/; s/System.loadLibrary("gojni")/System.loadLibrary("throne")/' bind/java/Seq.java
sed -i 's/Java_go_/Java_gt_/g; s#"go/Seq#"gt/Seq#g; s#Lgo/Seq#Lgt/Seq#g' bind/java/context_android.c bind/java/seq_android.c.support
sed -i 's/^import go\.Seq;/import gt.Seq;/; s/return "go"$/return "gt"/' bind/genjava.go
sed -i 's#filepath.Join("java", "go", javaFile)#filepath.Join("java", "gt", javaFile)#' cmd/gobind/gen.go
echo "--- remaining go.* runtime references:"
grep -rn 'go\.Seq\|go/Seq\|go\.Universe\|go/Universe\|Java_go_' bind cmd | grep -v '_test\|Test\.\|Bench' || true
GOFLAGS=-mod=mod go install ./cmd/gomobile ./cmd/gobind
popd >/dev/null
export PATH="$(go env GOPATH)/bin:$PATH"

# --- bind
pushd "$WORK/Throne/core" >/dev/null
go mod edit -replace "github.com/sagernet/gomobile=$WORK/gomobile"
TAGS=$(sed -n 's/^TAGS="\(.*\)"$/\1/p' ../script/build_android.sh)
VERSION_SINGBOX=$(go list -m -f '{{.Version}}' github.com/sagernet/sing-box)
gomobile init
gomobile bind -v -o "$DEST/ThroneCore.aar" -target "$TARGETS" -androidapi 21 -javapkg=io.throneproj -libname=throne -trimpath \
  -ldflags "-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=${VERSION_SINGBOX} -X runtime.godebugDefault=multipathtcp=0" \
  -tags "$TAGS" ./mobile
popd >/dev/null

unzip -l "$DEST/ThroneCore.aar"
unzip -p "$DEST/ThroneCore.aar" classes.jar > "$WORK/classes.jar"
unzip -l "$WORK/classes.jar" | grep -E 'gt/Seq.class|io/throneproj/mobile/Mobile.class'
! unzip -l "$WORK/classes.jar" | grep -E ' go/Seq'
