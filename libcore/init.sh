#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

./sync_gomod.sh || exit 1

# Install gomobile
if [ -n "${GOMOBILE_REVISION:-}" ] || [ ! -f "$GOPATH/bin/gomobile-matsuri" ]; then
    git clone https://github.com/MatsuriDayo/gomobile.git || exit 1
    pushd gomobile
	git checkout "${GOMOBILE_REVISION:-origin/master2}" || exit 1
    pushd cmd
    pushd gomobile
    go install -v || exit 1
    popd
    pushd gobind
    go install -v || exit 1
    popd
    popd
    rm -rf gomobile
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

GOBIND=gobind-matsuri gomobile-matsuri init
