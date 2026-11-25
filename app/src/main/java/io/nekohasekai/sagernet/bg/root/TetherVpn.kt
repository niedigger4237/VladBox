package io.nekohasekai.sagernet.bg.root

import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.TunAddressPlan
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Root-assisted IPv4 tethering for Android VpnService. All mutations run on one worker. */
object TetherVpn {
    private data class Addr(val name: String, val ip: String, val prefix: Int)
    private data class RuleSet(
        val up: String,
        val down: List<Addr>,
        val dns: String,
        val table: Int,
        val pref: Int,
        val suffix: String,
        val offloadBefore: String,
    ) {
        val forward get() = "NFV_F_$suffix"
        val prerouting get() = "NFV_D_$suffix"
        val postrouting get() = "NFV_N_$suffix"
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "tether-vpn").apply { isDaemon = true }
    }
    private var polling: ScheduledFuture<*>? = null
    @Volatile private var generation = 0L
    private var installed: RuleSet? = null
    private var installedReady = false
    private var lastSeen: String? = null
    private var rootGranted = false

    private fun enabled() = DataStore.tetherWifi || DataStore.tetherBluetooth || DataStore.tetherUsb ||
        DataStore.tetherEthernet || DataStore.tetherCustom.isNotBlank()

    private fun network(ip: String, prefix: Int): String {
        val p = ip.split('.').map { it.toInt() }
        var v = (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        v = v and mask
        return "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}/$prefix"
    }

    /** Never flush a shared table or delete rules by preference alone. */
    private fun cleanup(r: RuleSet): Boolean {
        val commands = mutableListOf("set -e")
        fun detach(table: String, hook: String, chain: String) {
            val cmd = "iptables -w 3 -t $table"
            commands += "while $cmd -C $hook -j $chain 2>/dev/null; do $cmd -D $hook -j $chain || exit 1; done"
            commands += "if $cmd -S $chain >/dev/null 2>&1; then $cmd -F $chain && $cmd -X $chain || exit 1; fi"
        }
        detach("filter", "FORWARD", r.forward)
        detach("nat", "PREROUTING", r.prerouting)
        detach("nat", "POSTROUTING", r.postrouting)
        for (d in r.down) {
            val net = network(d.ip, d.prefix)
            commands += "ip -4 rule del pref ${r.pref} iif ${d.name} lookup ${r.table} 2>/dev/null || true"
            commands += "ip -4 rule del pref ${r.pref - 1} iif ${r.up} to $net lookup ${r.table} 2>/dev/null || true"
            commands += "ip -4 route del $net dev ${d.name} scope link table ${r.table} 2>/dev/null || true"
        }
        commands += "ip -4 route del default dev ${r.up} table ${r.table} 2>/dev/null || true"
        // Restore only the value we changed, not a setting changed by another component meanwhile.
        val restore = if (r.offloadBefore == "null") "settings delete global tether_offload_disabled"
            else "settings put global tether_offload_disabled ${RootShell.q(r.offloadBefore)}"
        if (r.offloadBefore != "1") {
            commands += "if [ \"\$(settings get global tether_offload_disabled)\" = 1 ]; then $restore; fi"
        }
        val result = RootShell.run(*commands.toTypedArray(), timeoutMs = 20000)
        if (!result.ok) {
            Logs.w("VPN tether cleanup failed: ${result.err}")
            return false
        }
        // A missing rule is fine, but do not interpret a failed deletion as a successful cleanup.
        val firewall = RootShell.run("iptables -w 3 -t filter -S && iptables -w 3 -t nat -S", timeoutMs = 10000)
        val rules = RootShell.run("ip -4 rule show", timeoutMs = 5000)
        val routes = RootShell.run("ip -4 route show table ${r.table}", timeoutMs = 5000)
        val tableAbsent = !routes.ok &&
            (routes.err + routes.out).contains("FIB table does not exist", ignoreCase = true)
        val routeLines = routes.out.lineSequence().map { it.trim() }.toList()
        val ownedRoute = routeLines.any { line ->
            (line.startsWith("default dev ${r.up} ") || line == "default dev ${r.up}") ||
                r.down.any { d -> line.startsWith("${network(d.ip, d.prefix)} dev ${d.name} ") ||
                    line == "${network(d.ip, d.prefix)} dev ${d.name}" }
        }
        val ownedRule = rules.out.lineSequence().any { line ->
            val pref = line.substringBefore(':').trim().toIntOrNull()
            (pref == r.pref || pref == r.pref - 1) &&
                Regex("""\blookup\s+${r.table}\b""").containsMatchIn(line)
        }
        val ok = firewall.ok && rules.ok && (routes.ok || tableAbsent) &&
            listOf(r.forward, r.prerouting, r.postrouting).none { firewall.out.contains(it) } &&
            !ownedRule && !ownedRoute
        if (!ok) Logs.w("VPN tether cleanup verification failed: table=${r.table}, chains=${r.suffix}")
        return ok
    }

    private fun clearLocked(): Boolean {
        val r = installed ?: return true
        installedReady = false
        if (!cleanup(r)) return false
        installed = null
        return true
    }

    private fun allocate(up: String, down: List<Addr>, dns: String): RuleSet? {
        if (down.map { network(it.ip, it.prefix) }.distinct().size != down.size) {
            Logs.w("VPN tether: duplicate downstream subnet")
            return null
        }
        val rules = RootShell.run("ip -4 rule show", timeoutMs = 5000)
        val routes = RootShell.run("ip -4 route show table all", timeoutMs = 5000)
        val offload = RootShell.run("settings get global tether_offload_disabled", timeoutMs = 5000)
        val forwarding = RootShell.run("cat /proc/sys/net/ipv4/ip_forward", timeoutMs = 5000)
        if (!rules.ok || !routes.ok || !offload.ok || !forwarding.ok) return null
        if (forwarding.out != "1") {
            Logs.w("VPN tether: Android has not enabled IPv4 forwarding")
            return null
        }
        if (offload.out !in listOf("null", "0", "1")) return null
        val usedPrefs = Regex("""^\s*(\d+):""", RegexOption.MULTILINE)
            .findAll(rules.out).map { it.groupValues[1].toInt() }.toSet()
        val usedTables = Regex("""\b(?:lookup|table)\s+(\d+)\b""")
            .findAll(rules.out + "\n" + routes.out).map { it.groupValues[1].toInt() }.toSet()
        val pref = (17900..17998 step 2).firstOrNull { it !in usedPrefs && it - 1 !in usedPrefs } ?: return null
        val table = (17900..17999).firstOrNull { id ->
            if (id in usedTables) false else {
                val probe = RootShell.run("ip -4 route show table $id", timeoutMs = 2000)
                (probe.ok && probe.out.isBlank()) ||
                    (!probe.ok && (probe.err + probe.out).contains("FIB table does not exist", ignoreCase = true))
            }
        } ?: return null
        return RuleSet(up, down, dns, table, pref, UUID.randomUUID().toString().replace("-", "").take(12), offload.out)
    }

    private fun install(r: RuleSet): Boolean {
        val s = mutableListOf("set -e")
        val filter = "iptables -w 3 -t filter"
        val nat = "iptables -w 3 -t nat"
        s += "$filter -N ${r.forward}"
        s += "$nat -N ${r.prerouting}"
        s += "$nat -N ${r.postrouting}"
        s += "ip -4 route add default dev ${r.up} table ${r.table}"
        for (d in r.down) {
            val net = network(d.ip, d.prefix)
            s += "ip -4 route add $net dev ${d.name} scope link table ${r.table}"
            s += "ip -4 rule add pref ${r.pref - 1} iif ${r.up} to $net lookup ${r.table}"
            s += "$filter -A ${r.forward} -i ${d.name} -o ${r.up} -j ACCEPT"
            s += "$filter -A ${r.forward} -i ${r.up} -o ${d.name} -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT"
            s += "$nat -A ${r.postrouting} -s $net -o ${r.up} -j MASQUERADE"
            for (proto in listOf("udp", "tcp")) {
                s += "$nat -A ${r.prerouting} -i ${d.name} -p $proto --dport 53 -j DNAT --to-destination ${r.dns}:53"
            }
        }
        s += "$filter -I FORWARD 1 -j ${r.forward}"
        s += "$nat -I PREROUTING 1 -j ${r.prerouting}"
        s += "$nat -I POSTROUTING 1 -j ${r.postrouting}"
        s += "settings put global tether_offload_disabled 1"
        for (d in r.down) s += "ip -4 rule add pref ${r.pref} iif ${d.name} lookup ${r.table}"
        installed = r // remember partial installation for rollback
        installedReady = false
        val result = RootShell.run(*s.toTypedArray(), timeoutMs = 20000)
        if (!result.ok) {
            Logs.w("VPN tether install failed: ${result.err}")
            clearLocked()
            return false
        }
        installedReady = true
        Logs.i("VPN tether: ${r.down.map { it.name }} -> ${r.up}, table=${r.table}, pref=${r.pref}, chains=${r.suffix}")
        return true
    }

    private fun apply(plan: TunAddressPlan, token: Long) {
        try {
            if (token != generation) return
            if (!enabled()) { clearLocked(); return }
            if (!rootGranted) return
            val addresses = RootShell.run("ip -4 -o addr show", timeoutMs = 5000)
            if (!addresses.ok) { clearLocked(); return }
            val addrs = Regex("^\\d+:\\s+(\\S+)\\s+inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d+)", RegexOption.MULTILINE)
                .findAll(addresses.out)
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
            val routes = RootShell.run("ip -4 route show table all && ip -6 route show table all", timeoutMs = 5000)
            if (!routes.ok) { clearLocked(); return }
            val uplinks = Regex("^default .*?dev (\\S+)", RegexOption.MULTILINE)
                .findAll(routes.out).map { it.groupValues[1] }.toSet() + up
            val sel = RootDaemon.tetherSelectors().toMutableList()
            if (DataStore.tetherWifi) sel += listOf("swlan+", "wifi+")
            val skip = Regex("(lo|tun\\d*|rmnet.*|r_rmnet.*|ccmni.*|dummy.*|ifb.*|p2p.*|clat.*|v4-.*)")
            // the gateway address is not always x.x.x.1 (e.g. 10.242.217.50/24), so match by interface name
            val shortState = RootShell.run("dumpsys tethering --short", timeoutMs = 5000)
            val state = if (shortState.ok && shortState.out.contains("TetheredState")) shortState
                else RootShell.run("dumpsys tethering", timeoutMs = 5000)
            val active = if (state.ok) state.out.lineSequence().mapNotNull {
                Regex("""^\s*([A-Za-z0-9_.-]{1,15})\s+-\s+TetheredState\s+-\s+lastError\s*=\s*0\s*$""").matchEntire(it)?.groupValues?.get(1)
            }.toSet() else emptySet()
            val downs = addrs.filter { a ->
                a.name !in uplinks && !skip.matches(a.name) && a.prefix in 16..30 &&
                    a.name in active && sel.any { c -> if (c.endsWith("+")) a.name.startsWith(c.dropLast(1)) else a.name == c }
            }.distinctBy { it.name }

            val ordered = downs.sortedBy { it.name }
            val sig = "$up|${ordered.joinToString()}|${plan.dns4}"
            if (sig != lastSeen) {
                lastSeen = sig
                Logs.i("VPN tether discovery: $sig")
            }
            if (token != generation) return
            if (ordered.isEmpty()) { clearLocked(); return }
            val current = installed
            if (installedReady && current != null && current.up == up && current.down == ordered && current.dns == plan.dns4) return
            if (!clearLocked()) return
            val next = allocate(up, ordered, plan.dns4)
            if (next == null) {
                Logs.w("VPN tether: unable to reserve routing resources")
                return
            }
            if (token == generation) install(next)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    /** A stale stop cannot clear a newer VPN session. Never interrupt a running root transaction. */
    @Synchronized
    fun start(plan: TunAddressPlan): Long {
        val token = ++generation
        polling?.cancel(false)
        worker.execute {
            if (token == generation) {
                clearLocked()
                rootGranted = enabled() && RootShell.isAvailable()
                if (enabled() && !rootGranted) Logs.w("VPN tether: root unavailable")
                lastSeen = null
            }
        }
        polling = if (enabled()) worker.scheduleWithFixedDelay({ apply(plan, token) }, 0, 3, TimeUnit.SECONDS) else null
        return token
    }

    @Synchronized
    fun stop(token: Long) {
        if (token != generation) return
        val stopped = ++generation
        polling?.cancel(false)
        polling = null
        worker.execute {
            if (stopped == generation && !clearLocked()) {
                // Retain the failed transaction and retry on the same worker, never a newer session.
                worker.schedule({ if (stopped == generation) clearLocked() }, 3, TimeUnit.SECONDS)
            }
        }
    }
}
