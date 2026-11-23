#!/bin/bash

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ASSET_DIR="$ROOT/app/src/main/assets/sing-box"
RULESET_DIR="$ASSET_DIR/rule-set"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$ASSET_DIR" "$RULESET_DIR"

# Keep legacy MMDB databases for older profiles and custom compatibility hooks.
fetch_db() { # <owner/repo> <file.db>
  local repo="$1" file="$2" name="${2%.db}" final version
  final=$(curl -fsSL --retry 3 --retry-delay 5 -o /dev/null -w '%{url_effective}' \
    "https://github.com/$repo/releases/latest")
  version=${final##*/}
  if [[ -z "$version" || "$version" == latest ]]; then
    echo "Failed to resolve latest release of $repo (got: $final)" >&2
    exit 1
  fi
  echo "$name version: $version"
  printf '%s' "$version" > "$ASSET_DIR/$name.version.txt"
  curl -fLSs --retry 3 --retry-delay 5 -o "$ASSET_DIR/$file" \
    "https://github.com/$repo/releases/download/$version/$file"
  xz -9 -f "$ASSET_DIR/$file"
}

fetch_db SagerNet/sing-geoip geoip.db
fetch_db SagerNet/sing-geosite geosite.db

# Current sing-box Rule Sets are published from the rule-set branches.
# Package the complete official sets so the app can work offline on first start.
clone_rule_sets() { # <repo> <prefix>
  local repo="$1"
  local prefix="$2"
  local checkout="$TMP_DIR/${prefix}-source"
  local commit count

  git clone --quiet --depth 1 --single-branch --branch rule-set \
    "https://github.com/SagerNet/$repo.git" "$checkout"

  commit=$(git -C "$checkout" rev-parse HEAD)

  shopt -s nullglob
  local files=("$checkout"/*.srs)
  shopt -u nullglob

  count=${#files[@]}

  if (( count == 0 )); then
    echo "No .srs files found in SagerNet/$repo rule-set branch" >&2
    exit 1
  fi

  for file in "${files[@]}"; do
    local base
    base="$(basename "$file")"

    [[ "$base" == "$prefix-"* ]] || {
      echo "Unexpected Rule Set name: $base" >&2
      exit 1
    }

    install -m 0644 "$file" "$RULESET_DIR/$base"
  done

  printf '%s' "$commit"
}

rm -f "$RULESET_DIR"/*.srs "$RULESET_DIR/manifest.txt" "$RULESET_DIR/version.txt"
GEOIP_COMMIT=$(clone_rule_sets sing-geoip geoip)
GEOSITE_COMMIT=$(clone_rule_sets sing-geosite geosite)

(
  cd "$RULESET_DIR"
  find . -maxdepth 1 -type f -name '*.srs' -printf '%f\n' | sort > manifest.txt
  sha256sum ./*.srs | sed 's#  ./#  #' > checksums.txt
)
printf 'geoip=%s\ngeosite=%s\n' "$GEOIP_COMMIT" "$GEOSITE_COMMIT" > "$RULESET_DIR/version.txt"

echo "Packaged $(wc -l < "$RULESET_DIR/manifest.txt") official SRS files"
