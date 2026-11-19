#!/bin/bash
# Loopback AmneziaWG test: server endpoint <-> client endpoint on one host, then pull a big file through the tunnel.
# usage: awg_loopback.sh /path/to/sing-box <label> <extra-amnezia-json> <mtu>
set -u
SB=$1; LABEL=$2; EXTRA=${3:-"{}"}; MTU=${4:-1420}
W=$(mktemp -d); cd "$W"

S=$($SB generate wg-keypair); C=$($SB generate wg-keypair)
SPRIV=$(echo "$S" | awk '/PrivateKey/{print $2}'); SPUB=$(echo "$S" | awk '/PublicKey/{print $2}')
CPRIV=$(echo "$C" | awk '/PrivateKey/{print $2}'); CPUB=$(echo "$C" | awk '/PublicKey/{print $2}')

AMN=$(python3 -I - "$EXTRA" <<'P'
import json,sys
base={"jc":4,"jmin":40,"jmax":70,"s1":15,"s2":23,"s3":18,"s4":11,
      "h1":"1000000-1000100","h2":"2000000-2000100","h3":"3000000-3000100","h4":"4000000-4000100"}
base.update(json.loads(sys.argv[1]))
print(json.dumps(base))
P
)

head -c 20000000 /dev/urandom > big.bin
( python3 -I -m http.server 18080 --bind 127.0.0.1 >/dev/null 2>&1 & echo $! > http.pid )

cat > server.json <<J
{"log":{"level":"debug"},
 "endpoints":[{"type":"wireguard","tag":"wg-s","address":["10.9.0.1/24"],"private_key":"$SPRIV","listen_port":51821,"mtu":$MTU,
   "peers":[{"public_key":"$CPUB","allowed_ips":["10.9.0.2/32"]}],"amnezia":$AMN}],
 "outbounds":[{"type":"direct","tag":"direct"}],
 "route":{"final":"direct"}}
J
cat > client.json <<J
{"log":{"level":"debug"},
 "inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","listen_port":11080}],
 "endpoints":[{"type":"wireguard","tag":"wg-c","address":["10.9.0.2/32"],"private_key":"$CPRIV","mtu":$MTU,
   "peers":[{"address":"127.0.0.1","port":51821,"public_key":"$SPUB","allowed_ips":["0.0.0.0/0","::/0"],"persistent_keepalive_interval":25}],"amnezia":$AMN}],
 "outbounds":[{"type":"direct","tag":"direct"}],
 "route":{"final":"wg-c"}}
J

$SB check -c server.json && $SB check -c client.json || { echo "[$LABEL] CONFIG CHECK FAILED"; exit 2; }
$SB run -c server.json >server.log 2>&1 & SP=$!
sleep 2
$SB run -c client.json >client.log 2>&1 & CP=$!
sleep 4

RES=FAIL
# small request first (like a url test), then the 20 MB transfer
if curl -s -m 15 -x socks5h://127.0.0.1:11080 -o /dev/null -w "small: %{http_code} %{time_total}s\n" http://127.0.0.1:18080/; then :; fi
OUT=$(curl -s -m 60 -x socks5h://127.0.0.1:11080 -o got.bin -w "%{http_code} %{size_download} %{time_total}" http://127.0.0.1:18080/big.bin)
echo "big: $OUT"
if cmp -s big.bin got.bin; then RES=PASS; fi
echo "[$LABEL] mtu=$MTU extra=$EXTRA => $RES"
if [ $RES = FAIL ]; then echo "--- server.log"; tail -n 40 server.log; echo "--- client.log"; tail -n 40 client.log; fi
kill $SP $CP $(cat http.pid) 2>/dev/null
[ $RES = PASS ]
