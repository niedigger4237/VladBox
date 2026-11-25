package io.nekohasekai.sagernet.bg.root

import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.TunAddressPlan
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Root-assisted VPN tethering. IPv6 NAT66 is optional; guards remain independent of routing. */
object TetherVpn {
    private data class Addr(val name: String, val ip: String, val prefix: Int)
    private data class RuleSet(
        val up: String,
        val down: List<Addr>,
        val dns: String,
        val plan: TunAddressPlan,
        val table: Int,
        val pref: Int,
        val suffix: String,
        val down6: List<TetherIpv6.Down>,
        val dns6: String?,
    ) {
        val forward get() = "NFV_F_$suffix"
        val prerouting get() = "NFV_D_$suffix"
        val postrouting get() = "NFV_N_$suffix"
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "tether-vpn").apply { isDaemon = true }
    }
    private var monitor: TetherNetworkMonitor? = null
    private var pending: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null
    private data class Session(val plan: TunAddressPlan, val ipv6: Boolean, val token: Long)
    private var session: Session? = null
    private const val EVENT_DELAY_MS = 250L
    private const val WATCHDOG_SECONDS = 15L
    @Volatile private var generation = 0L
    private data class Saved4(val boot: String, val rules: RuleSet)
    private data class Saved6(val boot: String, val rules: TetherIpv6.Rules)
    private val journal4 = TetherResourceJournal("ipv4", Saved4::class.java)
    private val journal6 = TetherResourceJournal("ipv6", Saved6::class.java)
    private var journalBoot: String? = null
    private var recoveryReady = false
    private var installed: RuleSet? = null
    private var installedReady = false
    private val guard = TetherLeakGuard()
    private var installed6: TetherIpv6.Rules? = null
    private var ipv6Ready = false
    private var ipv6RetryAt = 0L
    private const val IPV6_RETRY_MS = 60_000L
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

    /** Restore only exact, validated resources from the previous worker. A
     * kernel reboot discards routing/firewall state, but not global settings. */
    private fun recoverLocked(): Boolean = runCatching {
        val boot = TetherResourceJournal.bootId() ?: return false
        journalBoot = boot
        if (journal4.exists() && installed == null) {
            val saved = journal4.read()
            require(TetherResourceJournal.validBoot(saved.boot))
            val r = saved.rules
            require(TetherResourceJournal.iface(r.up) && TetherResourceJournal.ids(r.table, r.pref) &&
                TetherResourceJournal.suffix(r.suffix) && TetherResourceJournal.ipv4(r.dns) &&
                TetherResourceJournal.ipv4(r.plan.ipv4) && r.down.size in 1..64 &&
                r.down.all { TetherResourceJournal.iface(it.name) && it.name != r.up &&
                    TetherResourceJournal.ipv4(it.ip) && it.prefix in 16..30 })
            if (saved.boot == boot) installed = r
            else if (!journal4.clear()) return false
        }
        if (journal6.exists() && installed6 == null) {
            val saved = journal6.read()
            require(TetherResourceJournal.validBoot(saved.boot))
            val r = saved.rules
            require(TetherResourceJournal.iface(r.up) && TetherResourceJournal.ids(r.table, r.pref) &&
                TetherResourceJournal.suffix(r.suffix) && TetherResourceJournal.ipv6(r.dns) &&
                TetherResourceJournal.ipv6(r.tunAddress) && r.down.size in 1..256 &&
                r.down.all { TetherResourceJournal.iface(it.name) && it.name != r.up &&
                    TetherResourceJournal.cidr6(it.subnet) })
            if (saved.boot == boot) installed6 = r
            else if (!journal6.clear()) return false
        }
        // Preserve the old leak guard until both routing families are clean.
        if (!guard.recover()) return false
        if (!clearLocked() || !guard.clear()) return false
        true
    }.getOrElse {
        Logs.w("VPN tether: invalid or unreadable recovery journal; leaving it intact")
        false
    }

    private fun clearLocked(): Boolean {
        installedReady = false
        ipv6Ready = false
        ipv6RetryAt = 0L
        var clean = true
        installed6?.let {
            if (TetherIpv6.cleanup(it) && journal6.clear()) installed6 = null
            else {
                Logs.w("VPN tether: IPv6 cleanup incomplete; keeping guard")
                clean = false
            }
        }
        // Failure in one address family must not leave the other family's routes installed.
        installed?.let {
            if (cleanup(it) && journal4.clear()) {
                TetherConntrack.clear("ipv4", it.down.map { d -> network(d.ip, d.prefix) }, it.plan.ipv4, it.dns)
                installed = null
            } else clean = false
        }
        return clean
    }

    private fun allocate(up: String, down: List<Addr>, plan: TunAddressPlan, down6: List<TetherIpv6.Down>, dns6: String?): RuleSet? {
        if (down.map { network(it.ip, it.prefix) }.distinct().size != down.size) {
            Logs.w("VPN tether: duplicate downstream subnet")
            return null
        }
        val rules = RootShell.run("ip -4 rule show", timeoutMs = 5000)
        val routes = RootShell.run("ip -4 route show table all", timeoutMs = 5000)
        val rules6 = RootShell.run("ip -6 rule show", timeoutMs = 5000)
        val routes6 = RootShell.run("ip -6 route show table all", timeoutMs = 5000)
        val forwarding = RootShell.run("cat /proc/sys/net/ipv4/ip_forward", timeoutMs = 5000)
        if (!rules.ok || !routes.ok || !rules6.ok || !routes6.ok || !forwarding.ok) return null
        if (forwarding.out != "1") {
            Logs.w("VPN tether: Android has not enabled IPv4 forwarding")
            return null
        }
        val usedPrefs = Regex("""^\s*(\d+):""", RegexOption.MULTILINE)
            .findAll(rules.out + "\n" + rules6.out).map { it.groupValues[1].toInt() }.toSet()
        val usedTables = Regex("""\b(?:lookup|table)\s+(\d+)\b""")
            .findAll(rules.out + "\n" + routes.out + "\n" + rules6.out + "\n" + routes6.out).map { it.groupValues[1].toInt() }.toSet()
        val pref = (17900..17998 step 2).firstOrNull { it !in usedPrefs && it - 1 !in usedPrefs } ?: return null
        val table = (17900..17999).firstOrNull { id ->
            if (id in usedTables) false else {
                val probe = RootShell.run("ip -4 route show table $id", timeoutMs = 2000)
                val probe6 = RootShell.run("ip -6 route show table $id", timeoutMs = 2000)
                fun free(p: RootShell.Result) = (p.ok && p.out.isBlank()) ||
                    (!p.ok && (p.err + p.out).contains("FIB table does not exist", ignoreCase = true))
                free(probe) && free(probe6)
            }
        } ?: return null
        return RuleSet(up, down, plan.dns4, plan, table, pref, UUID.randomUUID().toString().replace("-", "").take(12), down6, dns6)
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
            s += "$filter -A ${r.forward} -i ${d.name} -s $net -o ${r.up} -j ACCEPT"
            s += "$filter -A ${r.forward} -i ${r.up} -o ${d.name} -d $net -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT"
            s += "$nat -A ${r.postrouting} -s $net -o ${r.up} -j MASQUERADE"
            for (proto in listOf("udp", "tcp")) {
                s += "$nat -A ${r.prerouting} -i ${d.name} -p $proto --dport 53 -j DNAT --to-destination ${r.dns}:53"
            }
        }
        s += "$filter -I FORWARD 1 -j ${r.forward}"
        s += "$nat -I PREROUTING 1 -j ${r.prerouting}"
        s += "$nat -I POSTROUTING 1 -j ${r.postrouting}"
        for (d in r.down) s += "ip -4 rule add pref ${r.pref} iif ${d.name} lookup ${r.table}"
        val boot = journalBoot ?: return false
        if (!journal4.write(Saved4(boot, r))) return false
        installed = r // remember partial installation for rollback
        installedReady = false
        val result = RootShell.run(*s.toTypedArray(), timeoutMs = 20000)
        if (!result.ok) {
            Logs.w("VPN tether install failed: ${result.err}")
            clearLocked()
            return false
        }
        reconcileIpv6(r)
        installedReady = true
        Logs.i("VPN tether: ${r.down.map { it.name }} -> ${r.up}, table=${r.table}, pref=${r.pref}, chains=${r.suffix}")
        return true
    }

    /**
     * Optional IPv6 must not force a healthy IPv4 session to be rebuilt.
     * Keep its guard on failures, retry on a bounded interval, and retain partial ownership.
     */
    private fun reconcileIpv6(r: RuleSet) {
        val dns = r.dns6 ?: return
        if (r.down6.isEmpty() || ipv6Ready) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < ipv6RetryAt) return
        ipv6RetryAt = now + IPV6_RETRY_MS
        installed6?.let {
            if (!TetherIpv6.cleanup(it)) {
                Logs.w("VPN tether: IPv6 rollback still incomplete; guard retained")
                return
            }
            if (!journal6.clear()) return
            installed6 = null
        }
        val nat = RootShell.run("ip6tables -w 3 -t nat -S", timeoutMs = 5000)
        if (!nat.ok) {
            Logs.w("VPN tether: NAT66 unavailable; IPv6 forwarding remains blocked")
            return
        }
        val rules = TetherIpv6.Rules(r.up, r.down6, dns, r.table, r.pref, r.suffix, r.plan.ipv6)
        val boot = journalBoot ?: return
        if (!journal6.write(Saved6(boot, rules))) return
        installed6 = rules
        ipv6Ready = TetherIpv6.install(rules)
        if (!ipv6Ready && TetherIpv6.cleanup(rules) && journal6.clear()) installed6 = null
    }

    /** Check hooks and owned routing entries before treating an unchanged snapshot as healthy. */
    private fun routingHealthy(r: RuleSet): Boolean {
        fun family(tool: String, ip: String, forward: String, pre: String, post: String, names: List<String>): Boolean {
            val snapshot = RootShell.run(
                "set -e",
                "$tool -w 3 -t filter -S FORWARD",
                "$tool -w 3 -t nat -S PREROUTING",
                "$tool -w 3 -t nat -S POSTROUTING",
                "$ip rule show",
                "$ip route show table ${r.table}",
                timeoutMs = 10000
            )
            if (!snapshot.ok) return false
            val lines = snapshot.out.lineSequence().map { it.trim() }.toList()
            // An earlier guard (for example after netd resets iptables) would drop VPN traffic too.
            val first = lines.firstOrNull { it.startsWith("-A FORWARD ") }
            if (first != "-A FORWARD -j $forward" ||
                "-A PREROUTING -j $pre" !in lines || "-A POSTROUTING -j $post" !in lines) return false
            if (lines.none { it == "default dev ${r.up}" || it.startsWith("default dev ${r.up} ") }) return false
            return names.all { name ->
                lines.any { line ->
                    line.substringBefore(':').trim().toIntOrNull() == r.pref &&
                        Regex("""\biif\s+${Regex.escape(name)}\s+lookup\s+${r.table}\b""").containsMatchIn(line)
                }
            }
        }
        if (!family("iptables", "ip -4", r.forward, r.prerouting, r.postrouting, r.down.map { it.name })) return false
        installed6?.takeIf { ipv6Ready }?.let { v6 ->
            if (!family("ip6tables", "ip -6", v6.forward, v6.prerouting, v6.postrouting, v6.down.map { it.name }.distinct())) {
                ipv6Ready = false
                ipv6RetryAt = 0L
            }
        }
        return true
    }

    private fun apply(plan: TunAddressPlan, token: Long, ipv6: Boolean) {
        try {
            if (token != generation) return
            if (!rootGranted) return
            if (!recoveryReady) {
                recoveryReady = recoverLocked()
                if (!recoveryReady) return
            }
            if (!enabled()) { if (clearLocked()) guard.clear(); return }
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
                    addrs.any { it.name == name && it.ip == plan.ipv4 && it.prefix == plan.ipv4Prefix }
            }
            // interfaces carrying a default route are uplinks (e.g. wlan0 when the phone itself is on Wi-Fi)
            val routes = RootShell.run("ip -4 route show table all && ip -6 route show table all", timeoutMs = 5000)
            if (!routes.ok) { clearLocked(); return }
            val uplinks = Regex("^default .*?dev (\\S+)", RegexOption.MULTILINE)
                .findAll(routes.out).map { it.groupValues[1] }.toSet() + listOfNotNull(up)
            val sel = RootDaemon.tetherSelectors().toMutableList()
            if (DataStore.tetherWifi) sel += listOf("swlan+", "wifi+")
            val skip = Regex("(lo|tun\\d*|rmnet.*|r_rmnet.*|ccmni.*|dummy.*|ifb.*|p2p.*|clat.*|v4-.*)")
            // the gateway address is not always x.x.x.1 (e.g. 10.242.217.50/24), so match by interface name
            val shortState = RootShell.run("dumpsys tethering --short", timeoutMs = 5000)
            val state = if (shortState.ok && shortState.out.contains("TetheredState")) shortState
                else RootShell.run("dumpsys tethering", timeoutMs = 5000)
            if (!state.ok) { clearLocked(); return }
            // A different dumpsys format is not proof that previously protected clients disconnected.
            if (!Regex("""(?:TetheredState|AvailableState|UnavailableState|LocalHotspotState|Tether state:)""", RegexOption.IGNORE_CASE).containsMatchIn(state.out)) {
                clearLocked()
                Logs.w("VPN tether: unrecognized Android tethering snapshot; keeping existing guards")
                return
            }
            val tetherLine = Regex("""^\s*([A-Za-z0-9_.-]{1,15})\s+-\s+TetheredState\s+-\s+lastError\s*=\s*0\s*$""")
            if (state.out.lineSequence().any { " - TetheredState" in it && !tetherLine.matches(it) }) {
                clearLocked()
                Logs.w("VPN tether: ambiguous active downstream; retaining existing guards")
                return
            }
            val active = state.out.lineSequence().mapNotNull { tetherLine.matchEntire(it)?.groupValues?.get(1) }.toSet()
            val names = active.filter { name ->
                name !in uplinks && !skip.matches(name) &&
                    sel.any { c -> if (c.endsWith("+")) name.startsWith(c.dropLast(1)) else name == c }
            }.toSet()
            if (token != generation) return
            if (!guard.sync(names, retainExisting = true)) {
                clearLocked()
                Logs.w("VPN tether: leak guard could not be installed; no new routing rules")
                return
            }
            if (up == null) { clearLocked(); Logs.w("VPN tether: current VPN interface not confirmed; guard retained"); return }
            val downs = addrs.filter { a ->
                a.name in names && a.prefix in 16..30
            }.distinctBy { it.name }

            val ordered = downs.sortedBy { it.name }
            if (ordered.any { TunAddressPlan.intersects(it.ip, it.prefix, plan.cidr4) }) {
                clearLocked()
                Logs.w("VPN tether: downstream IPv4 subnet overlaps active TUN; reconnect with a different subnet")
                return
            }
            val v6Ready = ipv6 && cm.allNetworks.any { network ->
                val link = cm.getLinkProperties(network)
                link != null && link.interfaceName == up && link.linkAddresses.any {
                    it.address == java.net.InetAddress.getByName(plan.ipv6)
                }
            }
            val discovered6 = if (v6Ready) TetherIpv6.discover(names).orEmpty() else emptyList()
            val conflict6 = discovered6.any {
                TunAddressPlan.intersects(it.subnet.substringBefore('/'), it.subnet.substringAfter('/').toInt(), plan.cidr6)
            }
            val down6 = if (conflict6) {
                Logs.w("VPN tether: downstream IPv6 subnet overlaps active TUN; keeping IPv6 blocked")
                emptyList()
            } else discovered6
            val dns6 = if (v6Ready) plan.dns6 else null
            val sig = "$up|${ordered.joinToString()}|${plan.dns4}|$down6|$dns6"
            if (sig != lastSeen) {
                lastSeen = sig
                Logs.i("VPN tether discovery: $sig")
            }
            if (token != generation) return
            if (ordered.isEmpty()) { if (clearLocked()) guard.prune(names); return }
            val current = installed
            if (installedReady && current != null && current.up == up && current.down == ordered && current.dns == plan.dns4 && current.down6 == down6 && current.dns6 == dns6 && routingHealthy(current)) {
                if (guard.prune(names)) reconcileIpv6(current)
                return
            }
            if (!clearLocked() || !guard.prune(names)) return
            val next = allocate(up, ordered, plan, down6, dns6)
            if (next == null) {
                Logs.w("VPN tether: unable to reserve routing resources")
                return
            }
            if (token == generation) install(next)
        } catch (e: Exception) {
            Logs.w(e)
            clearLocked()
        }
    }

    /** Coalesce bursts without postponing the first event indefinitely. */
    @Synchronized
    private fun requestRefresh(token: Long, reason: String, delayMs: Long = EVENT_DELAY_MS) {
        val active = session ?: return
        if (token != generation || active.token != token || pending != null) return
        pending = worker.schedule({
            val valid = synchronized(this) {
                if (generation != token) false else {
                    pending = null
                    true
                }
            }
            if (valid) {
                Logs.d("VPN tether refresh: $reason")
                apply(active.plan, token, active.ipv6)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Callbacks are hints; watchdog covers lost events and OEM-specific tethering behavior. */
    @Synchronized
    private fun stopMonitor() {
        monitor?.close()
        monitor = null
        pending?.cancel(false)
        pending = null
        watchdog?.cancel(false)
        watchdog = null
        session = null
    }

    @Synchronized
    private fun registerMonitor(token: Long) {
        if (token != generation || session?.token != token) return
        monitor = TetherNetworkMonitor { reason -> requestRefresh(token, reason) }.also { it.start() }
        watchdog = worker.scheduleWithFixedDelay({
            if (token == generation && rootGranted) requestRefresh(token, "watchdog", 0)
        }, WATCHDOG_SECONDS, WATCHDOG_SECONDS, TimeUnit.SECONDS)
        requestRefresh(token, "initial snapshot", 0)
    }

    /** A stale stop cannot clear a newer VPN session. Never interrupt a running root transaction. */
    @Synchronized
    fun start(plan: TunAddressPlan, ipv6: Boolean): Long {
        val token = ++generation
        stopMonitor()
        val requested = enabled()
        if (requested) session = Session(plan, ipv6, token)
        worker.execute {
            if (token == generation) {
                recoveryReady = false
                val pendingRecovery = journal4.exists() || journal6.exists() || guard.hasJournal()
                if (!requested && !pendingRecovery && installed == null && installed6 == null) {
                    rootGranted = false
                    return@execute
                }
                rootGranted = RootShell.isAvailable()
                if (!rootGranted) {
                    Logs.w("VPN tether: root unavailable; recovery deferred")
                    return@execute
                }
                recoveryReady = recoverLocked()
                lastSeen = null
                if (requested) registerMonitor(token)
                else if (!recoveryReady) Logs.w("VPN tether: old journal recovery incomplete")
            }
        }
        return token
    }

    fun stop(token: Long) = beginStop(token, null)

    // A normal service shutdown waits asynchronously for the first cleanup attempt.
    // The worker is never blocked by the main thread; timeout does not cancel cleanup.
    suspend fun stopAndAwait(token: Long): Boolean {
        val completion = CompletableDeferred<Boolean>()
        beginStop(token, completion)
        return withTimeoutOrNull(8_000L) { completion.await() } == true
    }

    @Synchronized
    private fun beginStop(token: Long, completion: CompletableDeferred<Boolean>?) {
        if (token != generation) {
            completion?.complete(false)
            return
        }
        val stopped = ++generation
        stopMonitor()
        worker.execute { finishStop(stopped, 0, completion) }
    }

    private fun finishStop(token: Long, attempt: Int, completion: CompletableDeferred<Boolean>? = null) {
        if (token != generation) {
            completion?.complete(false)
            return
        }
        val clean = runCatching {
            if (!recoveryReady && (journal4.exists() || journal6.exists() || guard.hasJournal())) recoverLocked()
            else clearLocked() && guard.clear()
        }.getOrElse {
            Logs.w("VPN tether: teardown exception: ${it.message}")
            false
        }
        completion?.complete(clean)
        if (clean) {
            Logs.i("VPN tether: previous session routes and guards cleared")
            return
        }
        if (token != generation) return
        val delays = longArrayOf(1, 3, 6, 12)
        if (attempt < delays.size) {
            Logs.w("VPN tether: teardown incomplete, retrying in ${delays[attempt]} s")
            worker.schedule({ finishStop(token, attempt + 1) }, delays[attempt], TimeUnit.SECONDS)
        } else Logs.w("VPN tether: teardown not confirmed; retained owned state for next session")
    }
}
