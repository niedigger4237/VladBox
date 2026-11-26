#!/system/bin/sh
# Autonomous downstream discovery and owned ROOT tether reconciliation.
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
        # Connected IPv6 routes are already canonical network prefixes. Do not
        # track temporary client addresses or infer prefixes from default routes.
        if ($1 ~ /^[0-9a-fA-F:]+\/[0-9]+$/ && $1 ~ /:/ && tolower($1) !~ /^(fe[89ab]|ff|::)/) {
            split($1,cidr,"/")
            dev=""; kernel=0
            for(i=2;i<NF;i++) {
                if($i=="dev") dev=$(i+1)
                if($i=="proto" && $(i+1)=="kernel") kernel=1
            }
            if(valid(dev) && kernel && cidr[2]>=64 && cidr[2]<128) prefix6[dev SUBSEP $1]=1
        }
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
            for (key in prefix6) {
                split(key,parts,SUBSEP)
                if(parts[1]==n) print "ipv6-prefix",n,parts[2]
            }
        }
    }' "$1" "$2" "$3" "$4" "$5"
}

# Render a daemon template with only validated exact downstreams.
# The core listener config is unchanged. Empty selection is phone-only.
# Reject malformed/duplicate link identities and repeated placeholders.
render_hev_daemon() {
    [ "$#" -eq 3 ] || return 1
    ifaces=$(awk '
        $1=="link" {
            if(NF!=3 || $2!~/^[0-9]+$/ || $2+0<1 || $2+0>4294967295 ||
                length($3)<1 || length($3)>15 || $3!~/^[A-Za-z0-9_.-]+$/ ||
                $3=="lo" || $3=="asterisk0" || $3=="hevtun0" || $3=="." ||
                $3==".." || $3=="all" || $3=="default" || seen[$3]++ ||
                indices[$2+0]++ || ++n>16) {bad=1;next}
            if(n>1)printf ","
            printf "\"%s\"",$3
        }
        END {if(bad)exit 1}
    ' "$1") || return 1
    awk -v a="$ifaces" '
        {
            marker="\"__NF_BOOT_IFACES__\""
            if(index($0,marker)) {
                i++
                sub(/"__NF_BOOT_IFACES__"/,a)
                if(index($0,marker))exit 1
            }
            print
        }
        END {if(i!=1)exit 1}
    ' "$2" > "$3"
}

if [ "${1:-}" = "--render-hev" ]; then
    shift
    render_hev_daemon "$@"
    exit
fi

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
RUNTIME=$BOOT/runtime-asteriskd.json
CORE=$BASE/bin/libsingbox.so
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)
MODE=$(awk '$1=="mode"{print $2}' "$POLICY")
IPV6=$(awk '$1=="ipv6"{print $2}' "$POLICY")
BLOCK6=$(awk '$1=="block6"{print $2}' "$POLICY")
GATEWAY=$(awk '$1=="gateway"{print $2}' "$POLICY")
case "$GATEWAY" in ""|0) GATEWAY=0;; 1) [ "$MODE" = tun2socks ] || exit 1;; *) exit 1;; esac
if [ "$GATEWAY" = 1 ]; then
    [ -f "$BOOT/daemon-template.json" ] || exit 1
fi
command -v flock >/dev/null 2>&1 || exit 0
command -v timeout >/dev/null 2>&1 || exit 1
[ -f "$POLICY" ] || exit 0
work=$(mktemp -d "$BOOT/monitor.XXXXXXXX") || exit 1
trap 'rm -rf "$work"' EXIT
trap 'exit 0' HUP INT TERM

current() {
    [ "$(cat "$BOOT/ready" 2>/dev/null || true)" = "$GEN" ] &&
    [ "$(cat "$BOOT/manual-owner" 2>/dev/null || true)" != "$BOOT_ID" ]
}
owned() { current && owned_process; }
owned_process() { resident_process && [ "$native_phase" = running ]; }
# A policy STOP may leave the same supervisor resident with no active core.
# Keep discovery alive across that interval; authorize mutations only when running.
resident_process() {
    status=$(timeout 5 "$DAEMON" status 2>/dev/null) || return 1
    printf '%s\n' "$status" | grep -Eq '"owner"[[:space:]]*:[[:space:]]*"asteriskbox"' || return 1
    # The snapshot's phase is emitted before the nested DNS enhancement phase.
    native_phase=$(printf '%s\n' "$status" | grep -oE '"phase"[[:space:]]*:[[:space:]]*"[a-z-]+"' | head -n 1 | cut -d '"' -f 4)
    case "$native_phase" in running|paused|stopped|starting|stopping) ;; *) return 1;; esac
    pid=$(printf '%s\n' "$status" | sed -n 's/.*"supervisorPid"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1)
    case "$pid" in ""|0|*[!0-9]*) return 1;; esac
    tr '\000' '\n' < "/proc/$pid/cmdline" | grep -Fx -e "$CONFIG" -e "$RUNTIME" >/dev/null
}
snapshot() {
    if ! timeout 5 dumpsys tethering --short > "$work/tether" 2>/dev/null || ! grep -q TetheredState "$work/tether"; then
        timeout 5 dumpsys tethering > "$work/tether" 2>/dev/null || return 1
    fi
    timeout 5 ip -4 route show table all > "$work/routes" || return 1
    timeout 5 ip -6 route show table all >> "$work/routes" || return 1
    timeout 5 ip -o link show > "$work/links" || return 1
    timeout 5 ip -4 -o addr show > "$work/addr" || return 1
    parse "$POLICY" "$work/tether" "$work/routes" "$work/links" "$work/addr" > "$work/unsorted" || return 1
    LC_ALL=C sort -u "$work/unsorted" > "$work/current"
}

# Remove only exact rules inserted by this monitor. Names come from its own
# validated snapshots; no shell evaluation of iptables output.
forward_rule() {
    tool=$1; name=$2; action=$3
    if [ "$action" = add ]; then
        "$tool" -w 3 -C tetherctrl_FORWARD -i "$name" -o asterisk0 -m comment --comment NF_ROOT_TUN_FWD -j ACCEPT 2>/dev/null ||
            "$tool" -w 3 -I tetherctrl_FORWARD 2 -i "$name" -o asterisk0 -m comment --comment NF_ROOT_TUN_FWD -j ACCEPT || return 1
        "$tool" -w 3 -C tetherctrl_FORWARD -i asterisk0 -o "$name" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment NF_ROOT_TUN_FWD -j ACCEPT 2>/dev/null ||
            "$tool" -w 3 -I tetherctrl_FORWARD 2 -i asterisk0 -o "$name" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment NF_ROOT_TUN_FWD -j ACCEPT || return 1
    else
        while "$tool" -w 3 -C tetherctrl_FORWARD -i "$name" -o asterisk0 -m comment --comment NF_ROOT_TUN_FWD -j ACCEPT 2>/dev/null; do
            "$tool" -w 3 -D tetherctrl_FORWARD -i "$name" -o asterisk0 -m comment --comment NF_ROOT_TUN_FWD -j ACCEPT || return 1
        done
        while "$tool" -w 3 -C tetherctrl_FORWARD -i asterisk0 -o "$name" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment NF_ROOT_TUN_FWD -j ACCEPT 2>/dev/null; do
            "$tool" -w 3 -D tetherctrl_FORWARD -i asterisk0 -o "$name" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment NF_ROOT_TUN_FWD -j ACCEPT || return 1
        done
    fi
}
guard6() {
    name=$1; action=$2
    if [ "$action" = add ]; then
        ip6tables -w 3 -C FORWARD -i "$name" -m comment --comment NF_ROOT_TETHER_V6_BLOCK -j DROP 2>/dev/null ||
            ip6tables -w 3 -I FORWARD 1 -i "$name" -m comment --comment NF_ROOT_TETHER_V6_BLOCK -j DROP
    else
        while ip6tables -w 3 -C FORWARD -i "$name" -m comment --comment NF_ROOT_TETHER_V6_BLOCK -j DROP 2>/dev/null; do
            ip6tables -w 3 -D FORWARD -i "$name" -m comment --comment NF_ROOT_TETHER_V6_BLOCK -j DROP || return 1
        done
    fi
}
names() { awk '$1=="link"{print $3}' "$1"; }
apply_rules() {
    for n in $(names "$1"); do
        if [ "$MODE" = tun ]; then
            forward_rule iptables "$n" add || return 1
            if [ "$IPV6" = 1 ]; then forward_rule ip6tables "$n" add || return 1; fi
        fi
        if [ "$BLOCK6" = 1 ]; then guard6 "$n" add || return 1; fi
    done
}
remove_rules() {
    for n in $(names "$1"); do
        if [ "$MODE" = tun ]; then
            forward_rule iptables "$n" remove || return 1
            if [ "$IPV6" = 1 ]; then forward_rule ip6tables "$n" remove || return 1; fi
        fi
        if [ "$BLOCK6" = 1 ]; then guard6 "$n" remove || return 1; fi
    done
}
# Generate JSON using only exact sentinel strings. Validate all substitutions;
# no parsing or execution of arbitrary JSON as shell.
render_tun() {
    ifaces=$(awk '$1=="link"{if(n++)printf ",";printf "\"%s\"",$3}' "$work/current")
    exclusions=$(awk -v v6="$IPV6" '
        BEGIN { printf "\"255.255.255.255/32\",\"169.254.0.0/16\",\"224.0.0.0/24\""; if(v6==1)printf ",\"fe80::/10\",\"ff02::/16\"" }
        $1=="ipv4" {
            split($3,a,"/"); if(a[2]<16 || a[2]>30 || split(a[1],o,".")!=4) next
            for(i=1;i<=4;i++)if(o[i]<0 || o[i]>255 || o[i]!~/^[0-9]+$/)next
            if(o[1]==0 || o[1]==127 || o[1]>=224) next
            for(i=1;i<=4;i++){bits=a[2]-(i-1)*8;if(bits<0)bits=0;if(bits>8)bits=8;step=2^(8-bits);o[i]=int(o[i]/step)*step}
            printf ",\"%d.%d.%d.%d/%d\"",o[1],o[2],o[3],o[4],a[2]
        }
        $1=="ipv6-prefix" && v6==1 {printf ",\"%s\"",$3}
    ' "$work/current")
    # Empty downstream uses original phone-only config, not an empty JSON string.
    if [ -z "$ifaces" ]; then
        cp "$BOOT/config.json" "$work/core.json" || return 1
        return 0
    fi
    awk -v a="$ifaces" -v b="$exclusions" '
        {if(index($0,"\"__NF_BOOT_IFACES__\"")){i++;sub(/"__NF_BOOT_IFACES__"/,a)}
         if(index($0,"\"__NF_BOOT_LOCAL__\"")){j++;sub(/"__NF_BOOT_LOCAL__"/,b)} print}
        END{if(i!=1 || j!=1)exit 1}
    ' "$BOOT/tun-template.json" > "$work/core.json" || return 1
    awk -v a="$ifaces" '
        {if(index($0,"\"__NF_BOOT_IFACES__\"")){i++;sub(/"__NF_BOOT_IFACES__"/,a)}print}
        END{if(i!=1)exit 1}
    ' "$BOOT/daemon-template.json" > "$work/daemon.json"
}
wait_running() {
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        if owned_process; then return 0; fi
        sleep 2
    done
    return 1
}
reconcile() (
    timeout 5 flock -x 9 || exit 1
    owned || exit 1
    if [ "$MODE" != tun ] && [ "$GATEWAY" != 1 ]; then
        # Native selector handling owns these modes. Only maintain the
        # supplemental software IPv6 guard for confirmed downstreams.
        if [ "$BLOCK6" = 1 ]; then
            # Remember all attempted rules, including partial installations.
            cat "$work/applied" "$work/current" | LC_ALL=C sort -u > "$work/attempted"
            cp "$work/attempted" "$work/applied"
            apply_rules "$work/current" || exit 1
            # Remove rules for interfaces no longer selected.
            awk 'FILENAME==ARGV[1]{if($1=="link")a[$3]=1;next}$1=="link"&&!a[$3]' "$work/current" "$work/applied" > "$work/stale"
            remove_rules "$work/stale" || exit 1
        fi
        cp "$work/current" "$work/applied"
        exit 0
    fi
    [ -f "$BOOT/daemon-template.json" ] || exit 0
    if [ "$MODE" = tun ]; then [ -f "$BOOT/tun-template.json" ] || exit 0; fi
    if [ ! -f "$work/attempted" ] && cmp -s "$work/current" "$work/applied"; then
        apply_rules "$work/current"
        exit
    fi
    now=$(date +%s)
    last=$(cat "$work/last-restart" 2>/dev/null || echo 0)
    [ "$((now-last))" -ge 15 ] || exit 1
    if [ "$GATEWAY" = 1 ]; then
        cp "$BOOT/config.json" "$work/core.json" || exit 1
        render_hev_daemon "$work/current" "$BOOT/daemon-template.json" "$work/daemon.json" || exit 1
    else
        render_tun || exit 1
    fi
    # Only bundled sing-box is used to validate. External cores are excluded
    # from autonomous dynamic TUN until their executable identity is verified.
    [ -x "$CORE" ] || exit 1
    timeout 15 "$CORE" check -c "$work/core.json" -D "$BASE/run" >/dev/null 2>&1 || exit 1
    owned || exit 1
    printf '%s\n' "$now" > "$work/last-restart"
    # Keep the IPv6 guard in place across native teardown. Install guards
    # for new interfaces before any shutdown, and remember every attempted
    # rule so a partial failure can still be cleaned up.
    if [ "$BLOCK6" = 1 ]; then
        { cat "$work/applied" "$work/current"; if [ -f "$work/attempted" ]; then cat "$work/attempted"; fi; } |
            LC_ALL=C sort -u > "$work/attempted.new"
        mv -f "$work/attempted.new" "$work/attempted"
        for n in $(names "$work/attempted" | sort -u); do
            guard6 "$n" add || exit 1
        done
    fi
    if [ "$MODE" = tun ]; then
        for n in $(names "$work/applied" | sort -u); do
            forward_rule iptables "$n" remove || exit 1
            if [ "$IPV6" = 1 ]; then forward_rule ip6tables "$n" remove || exit 1; fi
        done
    fi
    # The app's handoff waits on this lock; check generation once more.
    owned || exit 1
    response=$(timeout 30 "$DAEMON" shutdown 2>/dev/null) || exit 1
    printf '%s\n' "$response" | grep -Eq '"code"[[:space:]]*:[[:space:]]*"ok"' || exit 1
    stopped=0
    for attempt in 1 2 3 4 5 6; do
        response=$(timeout 5 "$DAEMON" status 2>/dev/null || true)
        if printf '%s\n' "$response" | grep -Eq '"code"[[:space:]]*:[[:space:]]*"not_running"'; then stopped=1; break; fi
        sleep 1
    done
    [ "$stopped" = 1 ] && current || exit 1
    # Reclassify after teardown; do not publish a stale rendered snapshot.
    deferred=0
    if [ "$GATEWAY" = 1 ]; then
        cp "$work/current" "$work/rendered" || exit 1
        if ! snapshot || ! cmp -s "$work/current" "$work/rendered"; then
            : > "$work/current" # launch known-empty base; reclassify on next iteration
            deferred=1
        fi
    fi
    launch=$CONFIG
    if [ -n "$(names "$work/current")" ]; then
        mv -f "$work/core.json" "$BOOT/runtime-config.json" || exit 1
        mv -f "$work/daemon.json" "$RUNTIME" || exit 1
        launch=$RUNTIME
    fi
    current || exit 1
    # Close the lock fd in the daemon, otherwise it would retain the lock.
    nohup "$DAEMON" boot --config "$launch" </dev/null >>"$BASE/run/logs/boot.out" 2>&1 9>&- &
    if ! wait_running; then
        # Only recover if there is positively no supervisor and manual ownership
        # has not been requested. Never kill or overwrite a foreign service.
        response=$(timeout 5 "$DAEMON" status 2>/dev/null || true)
        if current && [ "$launch" != "$CONFIG" ] &&
            printf '%s\n' "$response" | grep -Eq '"code"[[:space:]]*:[[:space:]]*"not_running"'; then
            nohup "$DAEMON" boot --config "$CONFIG" </dev/null >>"$BASE/run/logs/boot.out" 2>&1 9>&- &
            wait_running || exit 1
            : > "$work/applied"
            # Preserve attempted guard ownership for the next reconcile.
        fi
        exit 1
    fi
    current || exit 1
    # On uncertain discovery keep all attempted IPv6 guards until a reliable snapshot.
    if [ "$deferred" = 1 ]; then
        : > "$work/applied"
        exit 1
    fi
    if [ "$BLOCK6" = 1 ] && [ -f "$work/attempted" ]; then
        # Do not lose ownership on a partially successful rule installation.
        cp "$work/attempted" "$work/applied"
        apply_rules "$work/current" || exit 1
        awk 'FILENAME==ARGV[1]{if($1=="link")a[$3]=1;next}$1=="link"&&!a[$3]' "$work/current" "$work/applied" > "$work/stale"
        remove_rules "$work/stale" || exit 1
    else
        cp "$work/current" "$work/applied"
        apply_rules "$work/current" || exit 1
    fi
    cp "$work/current" "$work/applied"
    rm -f "$work/attempted"
) 9>"$BOOT/monitor.lock"

# Wait only for a bounded initial readiness window.
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    current || exit 0
    if resident_process; then break; fi
    sleep 2
done
current && resident_process || exit 0
: > "$work/applied"
misses=0
while current; do
    if ! resident_process; then
        misses=$((misses+1))
        [ "$misses" -lt 3 ] || break
        sleep 5
        continue
    fi
    misses=0
    if [ "$native_phase" != running ]; then
        # Do not replace a policy-paused daemon with a fresh boot/start.
        # Keep the IPv6 safety guard and revalidate downstreams on resume.
        rm -f "$work/candidate"
        sleep 5
        continue
    fi
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
            if ! reconcile; then
                printf "%s\n" "boot tether: reconciliation deferred or failed" >> "$BASE/run/logs/boot-tether.log"
            fi
        else
            cp "$work/current" "$work/candidate"
        fi
    else
        rm -f "$work/candidate"
    fi
    sleep 5
done
# Clean software rules after a confirmed native stop. On app handoff the app
# owns cleanup; do not compete with it or delete rules from a new session.
(
    timeout 5 flock -x 9 || exit 0
    current || exit 0
    response=$(timeout 5 "$DAEMON" status 2>/dev/null || true)
    if printf '%s\n' "$response" | grep -Eq '"code"[[:space:]]*:[[:space:]]*"not_running"'; then
        if [ -f "$work/attempted" ]; then remove_rules "$work/attempted" || true; fi
        remove_rules "$work/applied" || true
    fi
) 9>"$BOOT/monitor.lock"
