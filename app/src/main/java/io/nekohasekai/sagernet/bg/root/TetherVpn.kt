package io.nekohasekai.sagernet.bg.root

import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.TunAddressPlan
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
    private var lastDns = TunAddressPlan.legacy().dns4
    private var lastSig: String? = null
    private var lastSeen: String? = null
    @Volatile
    private var watcher: Thread? = null
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

    private fun cleanupScript(up: String?, down: List<String>, dns: String): List<String> {
        val l = ArrayList<String>()
        l += "for i in 1 2 3 4 5 6 7 8; do ip rule del pref $PREF 2>/dev/null; ip rule del pref ${PREF - 1} 2>/dev/null; done"
        l += "ip route flush table $TABLE 2>/dev/null"
        if (up != null) {
            l += "while iptables -w -t nat -D POSTROUTING -o $up -j MASQUERADE 2>/dev/null; do :; done"
            for (d in down) {
                for (proto in listOf("udp", "tcp"))
                    l += "while iptables -w -t nat -D PREROUTING -i $d -p $proto --dport 53 -j DNAT --to-destination ${dns}:53 2>/dev/null; do :; done"
                l += "while iptables -w -D FORWARD -i $d -o $up -j ACCEPT 2>/dev/null; do :; done"
                l += "while iptables -w -D FORWARD -i $up -o $d -m state --state ESTABLISHED,RELATED -j ACCEPT 2>/dev/null; do :; done"
            }
        }
        return l
    }

    @Synchronized
    fun apply(plan: TunAddressPlan = TunAddressPlan.legacy()) {
        try {
            if (!enabled()) { clearLocked(); return }
            if (!RootShell.isAvailable()) { Logs.w("tethering through VPN needs root"); return }
            val addrs = Regex("^\\d+:\\s+(\\S+)\\s+inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d+)", RegexOption.MULTILINE)
                .findAll(RootShell.run("ip -4 -o addr show").out)
                .map { Addr(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt()) }.toList()
            // Resolve the Android VPN network, then confirm its actual interface in the root view.
            val cm = SagerNet.connectivity
            @Suppress("DEPRECATION")
            val vpnNames = cm.allNetworks.mapNotNull { network ->
                if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true)
                    return@mapNotNull null
                val link = cm.getLinkProperties(network) ?: return@mapNotNull null
                if (link.linkAddresses.none { it.address.hostAddress == plan.ipv4 }) return@mapNotNull null
                link.interfaceName
            }.distinct()
            val up = vpnNames.singleOrNull()?.takeIf { name ->
                Regex("[A-Za-z0-9_.-]{1,15}").matches(name) &&
                    addrs.any { it.name == name && it.ip == plan.ipv4 }
            }
            if (up == null) { clearLocked(); Logs.w("tethering: current VPN interface not confirmed"); return }
            // interfaces carrying a default route are uplinks (e.g. wlan0 when the phone itself is on Wi-Fi)
            val uplinks = Regex("^default .*?dev (\\S+)", RegexOption.MULTILINE)
                .findAll(RootShell.run("ip -4 route show table all").out).map { it.groupValues[1] }.toSet() + up
            val sel = RootDaemon.tetherSelectors().toMutableList()
            if (DataStore.tetherWifi) sel += listOf("swlan+", "wifi+")
            val skip = Regex("(lo|tun\\d*|rmnet.*|r_rmnet.*|ccmni.*|dummy.*|ifb.*|p2p.*|clat.*|v4-.*)")
            // the gateway address is not always x.x.x.1 (e.g. 10.242.217.50/24), so match by interface name
            val state = RootShell.run("dumpsys tethering", timeoutMs = 5000)
            val active = if (state.ok) state.out.lineSequence().mapNotNull {
                Regex("""^\s*([A-Za-z0-9_.-]{1,15})\s+-\s+TetheredState\s+-\s+lastError\s*=\s*0\s*$""").matchEntire(it)?.groupValues?.get(1)
            }.toSet() else emptySet()
            val downs = addrs.filter { a ->
                a.name !in uplinks && !skip.matches(a.name) && a.prefix in 16..30 &&
                    a.name in active && sel.any { c -> if (c.endsWith("+")) a.name.startsWith(c.dropLast(1)) else a.name == c }
            }.distinctBy { it.name }
            val sig = up + "|" + downs.joinToString(",") { "${it.name}/${it.ip}/${it.prefix}" }
            if (sig != lastSeen) {
                lastSeen = sig
                Logs.i("tethering: addresses=${addrs.map { it.name + "/" + it.ip }} uplinks=$uplinks selectors=$sel -> ${downs.map { it.name }}")
            }
            if (downs.isEmpty()) {
                clearLocked()
                return // waiting: the watcher picks the interface up as soon as the hotspot is switched on
            }
            if (sig == lastSig) return // already installed for exactly this interface/address
            clearLocked()

            val s = ArrayList<String>()
            s += cleanupScript(up, downs.map { it.name }, plan.dns4)
            s += "set -e"
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
                s += "iptables -w -t nat -I PREROUTING 1 -i ${d.name} -p $proto --dport 53 -j DNAT --to-destination ${plan.dns4}:53"
            }
            // hardware tethering offload bypasses ip rules/NAT
            if (prevOffload == null) prevOffload = RootShell.run("settings get global tether_offload_disabled").out.trim()
            s += "settings put global tether_offload_disabled 1"
            val r = RootShell.run(*s.toTypedArray(), timeoutMs = 20_000)
            lastUp = up
            lastDns = plan.dns4
            lastSig = if (r.ok) sig else null
            lastDown = downs.map { it.name }
            Logs.i("tethering via VPN: $lastDown -> $up (exit ${r.code}${if (r.err.isNotBlank()) ", " + r.err.take(300) else ""})")
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    /** Keeps the rules in sync while the VPN is up: the hotspot may be switched on (or get a new subnet) later. */
    @Synchronized
    fun start(plan: TunAddressPlan = TunAddressPlan.legacy()) {
        if (!enabled() || watcher?.isAlive == true) return
        val t = Thread({
            while (!Thread.currentThread().isInterrupted) {
                apply(plan)
                try { Thread.sleep(3_000) } catch (e: InterruptedException) { break }
            }
        }, "tether-vpn")
        t.isDaemon = true
        watcher = t
        t.start()
    }

    fun clear() {
        watcher?.interrupt()
        watcher = null
        try { clearLockedSync() } catch (e: Exception) { Logs.w(e) }
    }

    @Synchronized
    private fun clearLockedSync() = clearLocked()

    private fun clearLocked() {
        val up = lastUp ?: return
        RootShell.run(*cleanupScript(up, lastDown, lastDns).toTypedArray(), timeoutMs = 10_000)
        prevOffload?.let { p ->
            RootShell.run(if (p.isBlank() || p == "null") "settings delete global tether_offload_disabled"
                else "settings put global tether_offload_disabled $p")
            prevOffload = null
        }
        lastUp = null
        lastSig = null
        lastDown = emptyList()
    }
}
