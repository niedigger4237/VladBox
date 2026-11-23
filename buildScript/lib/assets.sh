#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ASSET_DIR="$ROOT/app/src/main/assets/sing-box"
RULESET_DIR="$ASSET_DIR/rule-set"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$ASSET_DIR" "$RULESET_DIR"

# Download only official binary sing-box Rule Sets (.srs). No legacy MMDB databases.
clone_rule_sets() { # <repo> <prefix>
  local repo="$1"
  local prefix="$2"
  local checkout="$TMP_DIR/${prefix}-source"
  local commit

  git clone --quiet --depth 1 --single-branch --branch rule-set \
    "https://github.com/SagerNet/$repo.git" "$checkout"
  commit="$(git -C "$checkout" rev-parse HEAD)"

  shopt -s nullglob
  local files=("$checkout"/*.srs)
  shopt -u nullglob
  if (( ${#files[@]} == 0 )); then
    echo "No .srs files found in SagerNet/$repo rule-set branch" >&2
    return 1
  fi

  # Bundle only a small, predictable offline starter set. Additional SRS
  # files can be downloaded on demand from the Assets screen.
  local wanted=()
  if [[ "$prefix" == geoip ]]; then
    wanted=(geoip-ru.srs)
  else
    wanted=(geosite-category-gov-ru.srs geosite-category-ads-all.srs
            geosite-google.srs geosite-youtube.srs geosite-telegram.srs
            geosite-discord.srs geosite-openai.srs)
  fi

  for name in "${wanted[@]}"; do
    if [[ ! -f "$checkout/$name" ]]; then
      if [[ "$name" == "geoip-ru.srs" ]]; then
        echo "Missing required RU Rule Set: $repo/$name" >&2
        return 1
      fi
      echo "Optional starter Rule Set unavailable: $repo/$name" >&2
      continue
    fi
    local file="$checkout/$name"
    local base
    base="$(basename "$file")"
    [[ "$base" == "$prefix-"*.srs ]] || {
      echo "Unexpected Rule Set name: $base" >&2
      return 1
    }
    install -m 0644 "$file" "$RULESET_DIR/$base"
  done
  printf '%s' "$commit"
}

# Remove stale legacy databases and old SRS files before packaging a clean snapshot.
rm -f "$ASSET_DIR/geoip.db" "$ASSET_DIR/geosite.db" \
  "$ASSET_DIR/geoip.db.xz" "$ASSET_DIR/geosite.db.xz" \
  "$ASSET_DIR/geoip.version.txt" "$ASSET_DIR/geosite.version.txt"
rm -f "$RULESET_DIR"/*.srs "$RULESET_DIR/manifest.txt" \
  "$RULESET_DIR/checksums.txt" "$RULESET_DIR/version.txt"

GEOIP_COMMIT="$(clone_rule_sets sing-geoip geoip)"
GEOSITE_COMMIT="$(clone_rule_sets sing-geosite geosite)"
(
  cd "$RULESET_DIR"
  find . -maxdepth 1 -type f -name '*.srs' -printf '%f\n' | sort > manifest.txt
  sha256sum ./*.srs | sed 's#  ./#  #' > checksums.txt
)
printf 'geoip=%s\ngeosite=%s\n' "$GEOIP_COMMIT" "$GEOSITE_COMMIT" > "$RULESET_DIR/version.txt"
echo "Packaged $(wc -l < "$RULESET_DIR/manifest.txt") official SRS files"
