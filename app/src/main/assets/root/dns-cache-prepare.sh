#!/system/bin/sh
# Only invoked after verified app-owned ROOT supervisor/boot monitor handoff.
# The native bbolt lock is checked even after shutdown verification.
set -eu
umask 077
dir="$1"
scope="$2"
limit=33554432
[ "$(id -u)" = 0 ] || exit 1
case "$dir" in /*/nf-dns-cache-v1) ;; *) exit 1 ;; esac
case "$scope" in "" ) ;; *[!0-9a-f]* ) exit 1 ;; *) [ "${#scope}" -eq 64 ] || exit 1 ;; esac
base="$(dirname "$dir")"
[ -d "$base" ] && [ ! -L "$base" ] || exit 1
[ "$(stat -c %u "$base")" = 0 ] || exit 1
base_mode="$(stat -c %a "$base")"
[ "$((0$base_mode & 0022))" -eq 0 ] || exit 1
if [ ! -e "$dir" ] && [ ! -L "$dir" ]; then
    if [ -z "$scope" ]; then echo memory; exit 0; fi
    mkdir -m 700 "$dir"
fi
[ -d "$dir" ] && [ ! -L "$dir" ] || exit 1
[ "$(stat -c '%u:%a' "$dir")" = '0:700' ] || exit 1
command -v flock >/dev/null 2>&1 || exit 1
safe_file() {
    [ -f "$1" ] && [ ! -L "$1" ] &&
        [ "$(stat -c '%u:%a:%h' "$1")" = '0:600:1' ]
}
create_file() {
    (set -C; : > "$1") && safe_file "$1"
}
lease="$dir/lease"
if [ ! -e "$lease" ] && [ ! -L "$lease" ]; then create_file "$lease"; fi
safe_file "$lease" || exit 1
(
    flock -n -x 9 || exit 1
    db="$dir/cache.db"
    marker="$dir/scope"
    previous=''
    if [ -e "$marker" ] || [ -L "$marker" ]; then
        safe_file "$marker" || exit 1
        [ "$(stat -c %s "$marker")" -le 64 ] || exit 1
        previous="$(cat "$marker")"
    fi
    if [ -e "$db" ] || [ -L "$db" ]; then
        safe_file "$db" || exit 1
        (
            flock -n -x 8 || exit 1
            size="$(stat -c %s "$db")"
            if [ -z "$scope" ] || [ "$previous" != "$scope" ] || [ "$size" -gt "$limit" ]; then
                rm -- "$db"
            fi
        ) 8<>"$db" || exit 1
    fi
    if [ -z "$scope" ]; then
        rm -f -- "$marker"
        sync -f "$dir"
        echo memory
        exit 0
    fi
    if [ ! -e "$db" ] && [ ! -L "$db" ]; then create_file "$db"; fi
    safe_file "$db" || exit 1
    if [ "$previous" != "$scope" ]; then
        tmp="$dir/scope.new"
        if [ -e "$tmp" ] || [ -L "$tmp" ]; then safe_file "$tmp" || exit 1; rm -- "$tmp"; fi
        (set -C; printf '%s' "$scope" > "$tmp")
        safe_file "$tmp" || exit 1
        mv -f -- "$tmp" "$marker"
    fi
    sync -f "$dir"
    echo persistent
) 9<>"$lease"
