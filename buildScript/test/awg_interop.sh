#!/bin/bash
# Interop test: sing-box-extended AWG client  <->  reference amneziawg-go server (real TUN, needs sudo).
# usage: awg_interop.sh <sing-box> <amneziawg-go> <label> <extra-amnezia-json> <mtu>
set -u
SB=$1; AWG=$2; LABEL=$3; EXTRA=${4:-"{}"}; MTU=${5:-1420}
W=$(mktemp -d); cd "$W"

S=$($SB generate wg-keypair); C=$($SB generate wg-keypair)
SPRIV=$(echo "$S" | awk '/PrivateKey/{print $2}'); SPUB=$(echo "$S" | awk '/PublicKey/{print $2}')
CPRIV=$(echo "$C" | awk '/PrivateKey/{print $2}'); CPUB=$(echo "$C" | awk '/PublicKey/{print $2}')

PARAMS=$(python3 -I - "$EXTRA" <<'P'
import json,sys
base={"jc":4,"jmin":40,"jmax":70,"s1":15,"s2":23,"s3":18,"s4":11,
      "h1":"1000000-1000100","h2":"2000000-2000100","h3":"3000000-3000100","h4":"4000000-4000100"}
base.update(json.loads(sys.argv[1]))
print(json.dumps(base))
P
)

# --- reference server on a real TUN device
sudo rm -f /var/run/amneziawg/awg0.sock
sudo "$AWG" -f awg0 >server.log 2>&1 &
sleep 2
python3 -I - "$SPRIV" "$CPUB" "$PARAMS" <<'P'
import base64,json,socket,sys
hexk=lambda b:base64.b64decode(b).hex()
priv,cpub,params=sys.argv[1],sys.argv[2],json.loads(sys.argv[3])
lines=["set=1","private_key="+hexk(priv),"listen_port=51821"]
for k,v in params.items():
    if v is not None and v!="" and v!=0: lines.append(f"{k}={v}")
lines+=["public_key="+hexk(cpub),"allowed_ip=10.9.0.2/32",""]
s=socket.socket(socket.AF_UNIX); s.connect("/var/run/amneziawg/awg0.sock")
s.sendall(("\n".join(lines)+"\n").encode()); print("uapi:",s.recv(100).decode().strip())
P
sudo ip addr add 10.9.0.1/24 dev awg0; sudo ip link set awg0 mtu $MTU up

head -c 20000000 /dev/urandom > big.bin
( python3 -I -m http.server 18081 --bind 10.9.0.1 >/dev/null 2>&1 & echo $! > http.pid )

cat > client.json <<J
{"log":{"level":"debug"},
 "inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","listen_port":11081}],
 "endpoints":[{"type":"wireguard","tag":"wg-c","address":["10.9.0.2/32"],"private_key":"$CPRIV","mtu":$MTU,
   "peers":[{"address":"127.0.0.1","port":51821,"public_key":"$SPUB","allowed_ips":["0.0.0.0/0"],"persistent_keepalive_interval":25}],"amnezia":$PARAMS}],
 "outbounds":[{"type":"direct","tag":"direct"}],
 "route":{"final":"wg-c"}}
J
$SB check -c client.json || { echo "[$LABEL] CONFIG CHECK FAILED"; exit 2; }
$SB run -c client.json >client.log 2>&1 & CP=$!
sleep 4
RES=FAIL
OUT=$(curl -s -m 40 -x socks5h://127.0.0.1:11081 -o got.bin -w "%{http_code} %{size_download} %{time_total}" http://10.9.0.1:18081/big.bin)
echo "big: $OUT"
cmp -s big.bin got.bin && RES=PASS
echo "[$LABEL] mtu=$MTU => $RES"
if [ $RES = FAIL ]; then echo "--- server.log"; tail -n 30 server.log; echo "--- client.log"; tail -n 30 client.log; fi
kill $CP $(cat http.pid) 2>/dev/null; sudo pkill -x "$(basename "$AWG")" 2>/dev/null
[ $RES = PASS ]
