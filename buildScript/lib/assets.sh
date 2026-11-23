#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

# Скачивает последний релиз файла без GitHub API (у API жёсткий лимит запросов
# без авторизации, из-за чего версия приходила пустой и ссылка давала 404).
# Версия берётся из редиректа /releases/latest -> /releases/tag/<tag>.
fetch_asset() { # <owner/repo> <file.db>
  local repo=$1 file=$2
  local name=${file%.db}
  local final version

  final=$(curl -fsSL --retry 3 --retry-delay 5 -o /dev/null -w '%{url_effective}' \
    "https://github.com/$repo/releases/latest")
  version=${final##*/}

  if [ -z "$version" ] || [ "$version" = "latest" ]; then
    echo "Failed to resolve latest release of $repo (got: $final)"
    exit 1
  fi

  echo "$name version: $version"
  echo -n "$version" > "$name.version.txt"
  curl -fLSs --retry 3 --retry-delay 5 -O \
    "https://github.com/$repo/releases/download/$version/$file"
  xz -9 "$file"
}

####
fetch_asset SagerNet/sing-geoip geoip.db

####
fetch_asset SagerNet/sing-geosite geosite.db
