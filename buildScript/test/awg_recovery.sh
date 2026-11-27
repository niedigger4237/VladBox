#!/usr/bin/env bash
# Local integration test: server disappears and returns while the client stays running.
# Usage: bash awg_recovery.sh /absolute/path/to/sing-box [keepalive:0|25]
# No firewall, root access or Android device required. Run on a Linux host.
set -euo pipefail
SB=$(realpath "$1")
KEEPALIVE=${2:-25}
case "$KEEPALIVE" in 0|25) ;; *) echo "Expected keepalive 0 or 25" >&2; exit 2;; esac
W=$(mktemp -d)
SP=""; CP=""; HP=""
cleanup() {
    status=$?
    trap - EXIT
    for pid in "$CP" "$SP" "$HP"; do
        if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
    done
    if [ "$status" -ne 0 ]; then
        for file in server.log client.log server-restarted.log; do
            if [ -f "$W/$file" ]; then echo "--- $file" >&2; tail -n 35 "$W/$file" >&2; fi
        done
    fi
    rm -rf "$W"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cd "$W"
umask 077
HOSTIP=$(ip -4 route get 1.1.1.1 | sed -n 's/.* src \([^ ]*\).*/\1/p' | head -n1)
[ -n "$HOSTIP" ] || { echo "No local IPv4 route for test HTTP server" >&2; exit 2; }
read -r WGPORT SOCKSPORT HTTPPORT < <(python3 -I - <<'PY'
import socket
socks = []
for kind in (socket.SOCK_DGRAM, socket.SOCK_STREAM, socket.SOCK_STREAM):
    sock = socket.socket(socket.AF_INET, kind)
    sock.bind(("127.0.0.1", 0))
    socks.append(sock)
print(*(s.getsockname()[1] for s in socks))
PY
)
"$SB" generate wg-keypair >server.keys
"$SB" generate wg-keypair >client.keys
SPRIV=$(awk '/PrivateKey/{print $2}' server.keys)
SPUB=$(awk '/PublicKey/{print $2}' server.keys)
CPRIV=$(awk '/PrivateKey/{print $2}' client.keys)
CPUB=$(awk '/PublicKey/{print $2}' client.keys)
AMN='{"jc":4,"jmin":40,"jmax":70,"s1":15,"s2":23,"s3":18,"s4":12,"h1":"1000000-1000100","h2":"2000000-2000100","h3":"3000000-3000100","h4":"4000000-4000100","rekey_timeout":"2","max_handshake_attempts":"3"}'
printf 'awg-recovery-test\n' >payload.txt
python3 -I -m http.server "$HTTPPORT" --bind "$HOSTIP" >http.log 2>&1 & HP=$!
cat > server.json <<JSON
{"log":{"level":"info"},"endpoints":[{"type":"wireguard","tag":"wg-s","address":["10.9.0.1/24"],"private_key":"$SPRIV","listen_port":$WGPORT,"mtu":1280,"peers":[{"public_key":"$CPUB","allowed_ips":["10.9.0.2/32"]}],"amnezia":$AMN}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}
JSON
cat > client.json <<JSON
{"log":{"level":"info"},"inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","listen_port":$SOCKSPORT}],"endpoints":[{"type":"wireguard","tag":"wg-c","address":["10.9.0.2/32"],"private_key":"$CPRIV","mtu":1280,"peers":[{"address":"127.0.0.1","port":$WGPORT,"public_key":"$SPUB","allowed_ips":["0.0.0.0/0"],"persistent_keepalive_interval":$KEEPALIVE}],"amnezia":$AMN}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"wg-c"}}
JSON
"$SB" check -c server.json
"$SB" check -c client.json
"$SB" run -c server.json >server.log 2>&1 & SP=$!
"$SB" run -c client.json >client.log 2>&1 & CP=$!
probe() {
    curl --silent --fail --max-time 5 --noproxy "" \
        --proxy "socks5h://127.0.0.1:$SOCKSPORT" \
        "http://$HOSTIP:$HTTPPORT/payload.txt" -o got.txt &&
    cmp -s payload.txt got.txt
}
wait_probe() {
    local deadline=$((SECONDS + 60))
    while [ "$SECONDS" -lt "$deadline" ]; do
        kill -0 "$CP"
        if probe; then return 0; fi
        sleep 1
    done
    return 1
}
wait_probe
echo "Initial tunnel works"
kill "$SP"
wait "$SP" || true
SP=""
# Verify actual failure; leave the server offline past the configured retry window.
if probe; then echo "Unexpected successful request during outage" >&2; exit 1; fi
sleep 18
kill -0 "$CP"
"$SB" run -c server.json >server-restarted.log 2>&1 & SP=$!
started=$SECONDS
wait_probe
kill -0 "$CP"
echo "PASS: AWG recovered in $((SECONDS-started))s, original client PID=$CP, keepalive=$KEEPALIVE"
