#!/system/bin/sh
# Read-only autonomous downstream discovery. No firewall or route mutations.
set -eu
umask 077

parse() {
    # Arguments: policy, Android tether state, IPv4+IPv6 routes, links, IPv4 addresses.
    awk '
    function valid(n) { return length(n) >= 1 && length(n) <= 15 && n ~ /^[A-Za-z0-9_.-]+$/ && n != "lo" && n != "asterisk0" && n != "hevtun0" }
    function matchsel(n,s) { return substr(s,length(s),1)=="+" ? index(n,substr(s,1,length(s)-1))==1 : n==s }
    function selected(n, i) {
        if (!valid(n) || upstream[n]) return 0
        for (i in ignored) if (matchsel(n,i)) return 0
        for (i in selectors) if (matchsel(n,i)) return 1
        return 0
    }
    FILENAME==ARGV[1] {
        if ($1=="select" && NF==2) selectors[$2]=1
        if ($1=="ignore" && NF==2) ignored[$2]=1
        next
    }
    FILENAME==ARGV[2] {
        if ($0 ~ /TetheredState|AvailableState|UnavailableState|LocalHotspotState|Tether state:/) recognized=1
        if ($0 ~ / - TetheredState/) {
            if ($0 !~ /^[[:space:]]*[A-Za-z0-9_.-]+[[:space:]]+-[[:space:]]+TetheredState[[:space:]]+-[[:space:]]+lastError[[:space:]]*=[[:space:]]*0[[:space:]]*$/) bad=1
            else active[$1]=1
        }
        next
    }
    FILENAME==ARGV[3] {
        if ($1=="default") for(i=2;i<NF;i++) if($i=="dev") upstream[$(i+1)]=1
        next
    }
    FILENAME==ARGV[4] {
        n=$2; sub(/:$/,"",n); sub(/@.*/,"",n)
        idx=$1; sub(/:$/,"",idx)
        if(valid(n) && idx ~ /^[0-9]+$/) links[n]=idx
        next
    }
    FILENAME==ARGV[5] {
        n=$2; sub(/@.*/,"",n)
        if(valid(n) && $3=="inet" && $4 ~ /^[0-9.]+\/[0-9]+$/) address[n SUBSEP $4]=1
        next
    }
    END {
        if (!recognized || bad) exit 2
        for(n in active) if(selected(n)) {
            if (!(n in links)) exit 2
            print "link",links[n],n
            for (key in address) {
                split(key,parts,SUBSEP)
                if(parts[1]==n) print "ipv4",n,parts[2]
            }
        }
    }' "$1" "$2" "$3" "$4" "$5"
}

if [ "${1:-}" = "--parse" ]; then
    shift
    [ "$#" -eq 5 ] || exit 2
    parse "$@"
    exit
fi

[ "$#" -eq 2 ] || exit 2
BASE=$1
GEN=$2
BOOT=$BASE/boot
DAEMON=$BASE/bin/libasteriskd.so
POLICY=$BOOT/tether-policy
CONFIG=$BOOT/asteriskd.json
command -v timeout >/dev/null 2>&1 || exit 1
[ -f "$POLICY" ] || exit 0
work=$(mktemp -d "$BOOT/monitor.XXXXXXXX") || exit 1
trap 'rm -rf "$work"' EXIT
trap 'exit 0' HUP INT TERM

current() { [ "$(cat "$BOOT/ready" 2>/dev/null || true)" = "$GEN" ]; }
owned() {
    current || return 1
    status=$(timeout 5 "$DAEMON" status 2>/dev/null) || return 1
    printf '%s\n' "$status" | grep -Eq '"owner"[[:space:]]*:[[:space:]]*"asteriskbox"' || return 1
    printf '%s\n' "$status" | grep -Eq '"phase"[[:space:]]*:[[:space:]]*"running"' || return 1
    pid=$(printf '%s\n' "$status" | sed -n 's/.*"supervisorPid"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1)
    case "$pid" in ""|0|*[!0-9]*) return 1;; esac
    tr '\000' '\n' < "/proc/$pid/cmdline" | grep -Fx -- "$CONFIG" >/dev/null
}
snapshot() {
    timeout 5 dumpsys tethering --short > "$work/tether" 2>/dev/null || return 1
    if ! grep -q TetheredState "$work/tether"; then
        timeout 5 dumpsys tethering > "$work/tether" 2>/dev/null || return 1
    fi
    timeout 5 ip -4 route show table all > "$work/routes" || return 1
    timeout 5 ip -6 route show table all >> "$work/routes" || return 1
    timeout 5 ip -o link show > "$work/links" || return 1
    timeout 5 ip -4 -o addr show > "$work/addr" || return 1
    parse "$POLICY" "$work/tether" "$work/routes" "$work/links" "$work/addr" > "$work/unsorted" || return 1
    LC_ALL=C sort -u "$work/unsorted" > "$work/current"
}
# Wait only for a bounded initial readiness window.
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    current || exit 0
    if owned; then break; fi
    sleep 2
done
owned || exit 0
while owned; do
    if snapshot && owned; then
        # Require two consecutive identical observations before publishing.
        if [ -f "$work/candidate" ] && cmp -s "$work/current" "$work/candidate"; then
            {
                printf 'generation %s\n' "$GEN"
                printf 'state verified\n'
                cat "$work/current"
            } > "$work/report"
            if current && ! cmp -s "$work/report" "$BOOT/downstream-status"; then
                mv -f "$work/report" "$BOOT/downstream-status"
            fi
        else
            cp "$work/current" "$work/candidate"
        fi
    else
        rm -f "$work/candidate"
    fi
    sleep 5
done
