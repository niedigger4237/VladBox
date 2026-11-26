#!/system/bin/sh
# Recovery executor for ordinary VPN tethering. Input is data, never shell code.
# Caller must hold the exclusive session lock and confirm the owner is gone.
set -eu
umask 077

# Format (one file per routing family or guard):
# route 4 <boot-id> <suffix> <table> <pref> <up>
# down <ifname> <network-cidr>
# guard <tag> <original-offload: null|0|1>
# iface <ifname>
validate() {
    awk '
    function iface(s) {return length(s)>=1 && length(s)<=15 && s~/^[A-Za-z0-9_.-]+$/ && s!="lo" && s!="asterisk0" && s!="hevtun0"}
    function number(s) {return s~/^[0-9]+$/}
    function hex(s,n) {return length(s)==n && s~/^[a-f0-9]+$/}
    function boot(s,a) {
        return split(s,a,"-")==5 && hex(a[1],8) && hex(a[2],4) && hex(a[3],4) && hex(a[4],4) && hex(a[5],12)
    }
    function cidr(s,f, a,o,i) {
        if(split(s,a,"/")!=2 || !number(a[2])) return 0
        if(f==4) {
            if(a[2]<16 || a[2]>30 || split(a[1],o,".")!=4) return 0
            for(i=1;i<=4;i++) if(!number(o[i]) || o[i]>255 || length(o[i])>3) return 0
            return 1
        }
        return a[1]~/^[0-9a-fA-F:]+$/ && a[1]~/:/ && length(a[1])<=39 && a[2]>=1 && a[2]<=128
    }
    NR==1 {
        if($1=="route" && NF==7 && ($2=="4" || $2=="6") && boot($3) && hex($4,12) &&
            number($5) && length($5)==5 && $5>=17900 && $5<=17999 && number($6) && length($6)==5 && $6>=17900 && $6<=17998 && $6%2==0 && iface($7)) {
            type="route"; family=$2; up=$7; next
        }
        if($1=="guard" && NF==3 && substr($2,1,6)=="NFV_G_" && hex(substr($2,7),12) &&
            ($3=="null" || $3=="0" || $3=="1")) { type="guard"; next }
        exit 2
    }
    type=="route" && $1=="down" && NF==3 && iface($2) && $2!=up && cidr($3,family) {
        if(seen[$2 SUBSEP $3]++) exit 2
        count++; next
    }
    type=="guard" && $1=="iface" && NF==2 && iface($2) {
        if(seen[$2]++) exit 2
        count++; next
    }
    {exit 2}
    END {
        if(type=="route" && (count<1 || count>(family==4?64:256))) exit 2
        if(type=="guard" && count>64) exit 2
        if(type=="") exit 2
    }' "$1"
}

[ "$#" -eq 2 ] || exit 2
action=$1
record=$2
[ -f "$record" ] || exit 2
[ "$(wc -c < "$record")" -le 65536 ] || exit 2
validate "$record" || exit 2
[ "$action" = --validate ] && exit 0
[ "$action" = --recover ] || exit 2

read -r kind a b c d e f < "$record"

# Every command is constructed from validated tokens and fixed operations.
# Verify listing commands separately; a failed check is not proof of absence.
detach() (
    tool=$1 table=$2 hook=$3 chain=$4
    "$tool" -w 3 -t "$table" -S "$hook" >/dev/null || return 1
    while "$tool" -w 3 -t "$table" -C "$hook" -j "$chain" 2>/dev/null; do
        "$tool" -w 3 -t "$table" -D "$hook" -j "$chain" || return 1
    done
    if "$tool" -w 3 -t "$table" -S "$chain" >/dev/null 2>&1; then
        "$tool" -w 3 -t "$table" -F "$chain" && "$tool" -w 3 -t "$table" -X "$chain" || return 1
    fi
    listing=$("$tool" -w 3 -t "$table" -S) || return 1
    if printf '%s\n' "$listing" | grep -F -- "$chain" >/dev/null; then return 1; fi
)

if [ "$kind" = route ]; then
    family=$a boot=$b suffix=$c table=$d pref=$e up=$f
    # Kernel routes and firewall entries do not survive reboot.
    actual_boot=$(cat /proc/sys/kernel/random/boot_id) || exit 1
    [ "$boot" = "$actual_boot" ] || exit 0
    if [ "$family" = 4 ]; then tool=iptables; stem=NFV; else tool=ip6tables; stem=NFV6; fi
    detach "$tool" filter FORWARD "${stem}_F_$suffix" || exit 1
    detach "$tool" nat PREROUTING "${stem}_D_$suffix" || exit 1
    detach "$tool" nat POSTROUTING "${stem}_N_$suffix" || exit 1
    oldpref=$((pref-1))
    # Delete only the exact interface/prefix/table combinations we recorded.
    while read -r row name net extra; do
        [ "$row" = down ] || continue
        ip "-$family" rule del pref "$pref" iif "$name" lookup "$table" 2>/dev/null || true
        ip "-$family" rule del pref "$oldpref" iif "$up" to "$net" lookup "$table" 2>/dev/null || true
        if [ "$family" = 4 ]; then
            ip -4 route del "$net" dev "$name" scope link table "$table" 2>/dev/null || true
        else
            ip -6 route del "$net" dev "$name" table "$table" 2>/dev/null || true
        fi
    done < "$record"
    ip "-$family" route del default dev "$up" table "$table" 2>/dev/null || true
    rules=$(ip "-$family" rule show) || exit 1
    # A matching priority/table is a conservative reason to retain the record.
    if printf '%s\n' "$rules" | awk -v p="$pref" -v t="$table" '
        ($1==p":" || $1==(p-1)":"){for(i=2;i<NF;i++)if($i=="lookup" && $(i+1)==t)found=1}
        END{exit !found}'; then exit 1; fi
    if ! routes=$(ip "-$family" route show table "$table" 2>&1); then
        case "$routes" in *"FIB table does not exist"*) routes="";; *) exit 1;; esac
    fi
    # Do not flush remaining routes, even in the reserved table range.
    [ -z "$routes" ] || exit 1
    exit 0
fi

tag=$a before=$b
# Caller must have successfully recovered both routing families before invoking
# guard recovery. Otherwise forwarding protection must remain installed.
remove_guard() (
    tool=$1 hook=$2
    shift 2
    "$tool" -w 3 -S "$hook" >/dev/null || return 1
    while "$tool" -w 3 -C "$hook" "$@" 2>/dev/null; do
        "$tool" -w 3 -D "$hook" "$@" || return 1
    done
)
while read -r row name extra; do
    [ "$row" = iface ] || continue
    for tool in iptables ip6tables; do
        remove_guard "$tool" FORWARD -i "$name" -m comment --comment "$tag" -j DROP || exit 1
        for proto in tcp udp; do
            remove_guard "$tool" INPUT -i "$name" -p "$proto" --dport 53 -m comment --comment "$tag" -j DROP || exit 1
        done
    done
done < "$record"
for tool in iptables ip6tables; do
    listing=$("$tool" -w 3 -S) || exit 1
    if printf '%s\n' "$listing" | grep -F -- "$tag" >/dev/null; then exit 1; fi
done
if [ "$before" != 1 ]; then
    current=$(settings get global tether_offload_disabled) || exit 1
    case "$current" in null|0|1) ;; *) exit 1;; esac
    # Avoid overwriting an externally changed setting.
    if [ "$current" = 1 ]; then
        if [ "$before" = null ]; then
            settings delete global tether_offload_disabled >/dev/null || exit 1
        else
            settings put global tether_offload_disabled "$before" || exit 1
        fi
        [ "$(settings get global tether_offload_disabled)" = "$before" ] || exit 1
    fi
fi
