#!/usr/bin/env python3
"""Host checks for the real boot monitor renderer; no root or firewall calls."""
import json
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[3]
monitor = root / "app/src/main/assets/root/boot-tether-monitor.sh"
subprocess.run(["sh", "-n", str(monitor)], check=True)
base = '{"network":{"hotspotInterfaces":["__NF_BOOT_IFACES__"],"nfHevGatewayDnsPort":15353}}'
cases = [
    ("", []),
    ("link 7 wlan2\nipv4 wlan2 192.168.1.1/24\n", ["wlan2"]),
    ("link 1 rndis0\nlink 4294967295 wlan2\n", ["rndis0", "wlan2"]),
    *[(f"link 2 {name}\n", None) for name in
      ["lo", "hevtun0", "asterisk0", "all", "default", ".", "..", "wlan+", 'a"b', "abcdefghijklmnop"]],
    *[(f"link {index} wlan2\n", None) for index in ["0", "4294967296", "-1"]],
    ("link 2 wlan2 x\n", None),
    ("link 2 wlan2\nlink 3 wlan2\n", None),
    ("link 2 wlan2\nlink 2 rndis0\n", None),
    ("".join(f"link {i+1} ap{i}\n" for i in range(16)), [f"ap{i}" for i in range(16)]),
    ("".join(f"link {i+1} ap{i}\n" for i in range(17)), None),
]
checks = 0
with tempfile.TemporaryDirectory() as temp:
    p = pathlib.Path(temp)
    src, tpl, dst = (p / name for name in ("snapshot", "template", "out"))
    for snapshot, expected in cases:
        src.write_text(snapshot)
        tpl.write_text(base)
        result = subprocess.run(["sh", str(monitor), "--render-hev", str(src), str(tpl), str(dst)])
        if expected is None:
            assert result.returncode != 0, snapshot
        else:
            assert result.returncode == 0, snapshot
            assert json.loads(dst.read_text())["network"] == {
                "hotspotInterfaces": expected, "nfHevGatewayDnsPort": 15353}
        checks += 1
    for template in ["{}", '{"a":["__NF_BOOT_IFACES__","__NF_BOOT_IFACES__"]}',
                     '{"a":["__NF_BOOT_IFACES__"],\n"b":["__NF_BOOT_IFACES__"]}']:
        tpl.write_text(template)
        src.write_text("link 1 wlan2\n")
        assert subprocess.run(["sh", str(monitor), "--render-hev", str(src), str(tpl), str(dst)]).returncode != 0
        checks += 1
print(f"PASS {checks} HEV boot template renderer cases")
