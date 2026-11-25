#!/system/bin/sh
# Autonomous recovery worker. Not a boot service and never restarts the VPN.
# Integration must create root-owned records before kernel changes and keep
# new VPN/ROOT sessions out until the worker acknowledges handoff.
set -eu
umask 077
DIR=/data/adb/nekobox/tether-recovery
EXEC=/data/adb/nekobox/bin/tether-crash-recovery.sh
[ "$#" -eq 1 ] || exit 2
token=$1
case "$token" in *[!0-9a-f]*|"") exit 2;; esac
[ "${#token}" -eq 32 ] || exit 2
[ "$(id -u)" = 0 ] || exit 1
[ -d "$DIR" ] && [ -x "$EXEC" ] || exit 1
command -v flock >/dev/null 2>&1 || exit 1
command -v timeout >/dev/null 2>&1 || exit 1

# This lock protects recovery and lease publication. A new owner must not
# publish records until the previous owner has released this lock.
(
flock -x -w 30 9 || exit 1
[ -f "$DIR/lease" ] || exit 1
read -r saved boot pid started extra < "$DIR/lease"
[ "$saved" = "$token" ] && [ -z "${extra:-}" ] || exit 1
case "$pid" in ""|0|1|*[!0-9]*) exit 1;; esac
case "$started" in ""|*[!0-9]*) exit 1;; esac
[ "$(cat /proc/sys/kernel/random/boot_id)" = "$boot" ] || exit 1

same_lease() {
    IFS= read -r lease_line < "$DIR/lease" 2>/dev/null || return 1
    [ "$lease_line" = "$token $boot $pid $started" ]
}
# Return 0: original process alive; 1: positively gone or PID reused;
# 2: unknown. Never mistake inaccessible /proc for process death.
owner_state() {
    if [ ! -d "/proc/$pid" ]; then
        if kill -0 "$pid" 2>/dev/null; then return 2; fi
        return 1
    fi
    IFS= read -r stat < "/proc/$pid/stat" 2>/dev/null || return 2
    # comm (field 2) can contain spaces and closing parentheses; remove
    # everything through its final delimiter before reading starttime (22).
    rest=${stat##*) }
    [ "$rest" != "$stat" ] || return 2
    set -f
    set -- $rest
    [ "$#" -ge 20 ] || return 2
    shift 19
    actual=$1
    case "$actual" in ""|*[!0-9]*) return 2;; esac
    [ "$actual" = "$started" ] || return 1
    return 0
}

owner_state || exit 1
printf '%s\n' "$token" > "$DIR/ready.new"
mv -f "$DIR/ready.new" "$DIR/ready"
dead=0
while same_lease; do
    # Graceful disarm is accepted only after the caller verified cleanup.
    release_token=
    if [ -f "$DIR/release" ]; then
        IFS= read -r release_token < "$DIR/release" 2>/dev/null || release_token=
    fi
    if [ "$release_token" = "$token" ] &&
        [ ! -e "$DIR/ipv4" ] && [ ! -e "$DIR/ipv6" ] && [ ! -e "$DIR/guard" ]; then
        rm -f "$DIR/ready" "$DIR/release"
        exit 0
    fi
    state=0
    owner_state || state=$?
    if [ "$state" = 1 ]; then dead=$((dead+1)); else dead=0; fi
    [ "$dead" -ge 2 ] && break
    sleep 3
done
same_lease || exit 1
[ "$dead" -ge 2 ] || exit 1
rm -f "$DIR/ready"
# The app may have died while a bounded su transaction was in flight.
# Allow its normal command deadline to pass before attempting cleanup.
# This grace period is not a guarantee against a hung kernel command.
sleep 25
same_lease || exit 1
state=0
owner_state || state=$?
[ "$state" = 1 ] || exit 1

recover_one() {
    name=$1
    same_lease || return 1
    [ -e "$DIR/$name" ] || return 0
    # Validate role as well as content: do not interpret a guard as a route.
    header=$(head -n 1 "$DIR/$name") || return 1
    case "$name:$header" in
        "ipv4:route 4 "*|"ipv6:route 6 "*|"guard:guard "*) ;;
        *) return 1;;
    esac
    timeout 45 "$EXEC" --recover "$DIR/$name" || return 1
    # Receipt is the exact recovered record. The app must match it to its
    # own AtomicFile journal before discarding that journal.
    mv -f "$DIR/$name" "$DIR/recovered-$name"
}

for delay in 0 2 5 10 20; do
    sleep "$delay"
    same_lease || exit 1
    v4=0; v6=0
    recover_one ipv4 || v4=1
    recover_one ipv6 || v6=1
    # Keep the guard while either routing family remains unverified.
    if [ "$v4" = 0 ] && [ "$v6" = 0 ] && recover_one guard; then
        printf '%s\n' "$token" > "$DIR/complete.new"
        mv -f "$DIR/complete.new" "$DIR/complete"
        exit 0
    fi
done
# Preserve all outstanding records on failure; no global flush or reboot.
printf '%s\n' "$token" > "$DIR/incomplete.new"
mv -f "$DIR/incomplete.new" "$DIR/incomplete"
exit 1
) 9>"$DIR/lock"
