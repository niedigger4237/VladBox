package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs

/**
 * Hotspot / USB / Bluetooth / Ethernet tethering through the ordinary VPN mode (needs root).
 * Android does not send tethered clients through a VPN, so the clients' traffic is routed to the VPN interface
 * with ip rules and NATed there (the same approach as VPNHotspot).
 */
object TetherVpn {
    private const val PREF = 17900
    private const val TABLE = 17900

    private data class Addr(val name: String, val ip: String, val prefix: Int)

    private var lastUp: String? = null
    private var lastDown: List<String> = emptyList()
    private var prevOffload: String? = null

    private fun enabled() = DataStore.tetherWifi || DataStore.tetherBluetooth || DataStore.tetherUsb ||
            DataStore.tetherEthernet || DataStore.tetherCustom.isNotBlank()

    private fun network(ip: String, prefix: Int): String {
        val p = ip.split('.').map { it.toInt() }
        var v = (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        v = v and mask
        return "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}/$prefix"
    }

    private fun cleanupScript(up: String?, down: List<String>): List<String> {
        val l = ArrayList<String>()
        l += "for i in 1 2 3 4 5 6 7 8; do ip rule del pref $PREF 2>/dev/null; ip rule del pref ${PREF - 1} 2>/dev/null; done"
        l += "ip route flush table $TABLE 2>/dev/null"
        if (up != null) {
            l += "while iptables -w -t nat -D POSTROUTING -o $up -j MASQUERADE 2>/dev/null; do :; done"
            for (d in down) {
                for (proto in listOf("udp", "tcp"))
                    l += "while iptables -w -t nat -D PREROUTING -i $d -p $proto --dport 53 -j DNAT --to-destination ${VpnService.PRIVATE_VLAN4_ROUTER}:53 2>/dev/null; do :; done"
                l += "while iptables -w -D FORWARD -i $d -o $up -j ACCEPT 2>/dev/null; do :; done"
                l += "while iptables -w -D FORWARD -i $up -o $d -m state --state ESTABLISHED,RELATED -j ACCEPT 2>/dev/null; do :; done"
            }
        }
        return l
    }

    @Synchronized
    fun apply() {
        try {
            clearLocked()
            if (!enabled()) return
            if (!RootShell.isAvailable()) { Logs.w("tethering through VPN needs root"); return }
            val addrs = Regex("^\\d+:\\s+(\\S+)\\s+inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d+)", RegexOption.MULTILINE)
                .findAll(RootShell.run("ip -4 -o addr show").out)
                .map { Addr(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt()) }.toList()
            val up = addrs.firstOrNull { it.ip == VpnService.PRIVATE_VLAN4_CLIENT }?.name
            if (up == null) { Logs.w("tethering: VPN interface not found"); return }
            // interfaces carrying a default route are uplinks (e.g. wlan0 when the phone itself is on Wi-Fi)
            val uplinks = Regex("^default .*?dev (\\S+)", RegexOption.MULTILINE)
                .findAll(RootShell.run("ip -4 route show table all").out).map { it.groupValues[1] }.toSet() + up
            val sel = RootDaemon.tetherSelectors()
            val skip = Regex("(lo|tun\\d*|rmnet.*|r_rmnet.*|ccmni.*|dummy.*|ifb.*|p2p.*|clat.*|v4-.*)")
            // the gateway address is not always x.x.x.1 (e.g. 10.242.217.50/24), so match by interface name
            fun candidates(byName: Boolean) = addrs.filter { a ->
                a.name !in uplinks && !skip.matches(a.name) && a.prefix in 16..30 &&
                    (!byName || sel.any { c -> if (c.endsWith("+")) a.name.startsWith(c.dropLast(1)) else a.name == c })
            }.distinctBy { it.name }
            // by the selected tethering types first; if nothing matches, any non-uplink interface with a private address
            val downs = candidates(true).ifEmpty { candidates(false) }
            Logs.i("tethering: addresses=${addrs.map { it.name + "/" + it.ip }} uplinks=$uplinks selectors=$sel -> ${downs.map { it.name }}")
            if (downs.isEmpty()) { Logs.w("tethering: no active tethering interface found (turn the hotspot/USB tethering on, then reconnect)"); return }

            val s = ArrayList<String>()
            s += cleanupScript(up, downs.map { it.name })
            s += "echo 1 > /proc/sys/net/ipv4/ip_forward"
            s += "ip route add default dev $up table $TABLE"
            for (d in downs) {
                val net = network(d.ip, d.prefix)
                s += "ip route add $net dev ${d.name} scope link table $TABLE"
                s += "ip rule add to $net lookup $TABLE pref ${PREF - 1}"
                s += "ip rule add iif ${d.name} lookup $TABLE pref $PREF"
                s += "iptables -w -I FORWARD 1 -i ${d.name} -o $up -j ACCEPT"
                s += "iptables -w -I FORWARD 1 -i $up -o ${d.name} -m state --state ESTABLISHED,RELATED -j ACCEPT"
            }
            s += "iptables -w -t nat -I POSTROUTING 1 -o $up -j MASQUERADE"
            // clients' DNS goes to the VPN resolver (sing-box hijack-dns), not to the system one
            for (d in downs) for (proto in listOf("udp", "tcp")) {
                s += "iptables -w -t nat -I PREROUTING 1 -i ${d.name} -p $proto --dport 53 -j DNAT --to-destination ${VpnService.PRIVATE_VLAN4_ROUTER}:53"
            }
            // hardware tethering offload bypasses ip rules/NAT
            if (prevOffload == null) prevOffload = RootShell.run("settings get global tether_offload_disabled").out.trim()
            s += "settings put global tether_offload_disabled 1"
            val r = RootShell.run(*s.toTypedArray(), timeoutMs = 20_000)
            lastUp = up
            lastDown = downs.map { it.name }
            Logs.i("tethering via VPN: $lastDown -> $up (exit ${r.code}${if (r.err.isNotBlank()) ", " + r.err.take(300) else ""})")
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    @Synchronized
    fun clear() {
        try { clearLocked() } catch (e: Exception) { Logs.w(e) }
    }

    private fun clearLocked() {
        val up = lastUp ?: return
        RootShell.run(*cleanupScript(up, lastDown).toTypedArray(), timeoutMs = 10_000)
        prevOffload?.let { p ->
            RootShell.run(if (p.isBlank() || p == "null") "settings delete global tether_offload_disabled"
                else "settings put global tether_offload_disabled $p")
            prevOffload = null
        }
        lastUp = null
        lastDown = emptyList()
    }
}
