package io.nekohasekai.sagernet.bg.root

import android.os.SystemClock
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import libcore.Libcore
import java.io.File

/**
 * Root supervisor: asteriskd (Asterisk4Magisk/asteriskd, GPL-3.0, built in CI as libasteriskd.so).
 * It starts the sing-box core, owns every iptables / policy-routing / TUN side effect of the root modes,
 * keeps them in sync with the network and removes them on stop or when the core dies.
 * The app only writes its JSON config (schema 7) and calls `asteriskd start|status|shutdown`.
 */
object RootDaemon {

    const val TUN_DEVICE = "asterisk0"
    private const val OWNER = "asteriskbox"
    private const val SCHEMA = 7

    private val libDir get() = SagerNet.application.applicationInfo.nativeLibraryDir
    val binary: File get() = RootBoot.exec(File(libDir, "libasteriskd.so"))
    val runDir get() = "${RootCore.BASE}/run"
    val configPath get() = "$runDir/config.json"
    val daemonConfigPath get() = "$runDir/asteriskd.json"
    val statePath get() = "$runDir/asteriskd.state"
    val logDir get() = "$runDir/logs"
    val logPath get() = "$logDir/asteriskd.log"

    enum class Mode(val wire: String) { TUN("tun"), TPROXY("tproxy"), TUN2SOCKS("tun2socks"), BPF2SOCKS("bpf2socks") }

    /** the helpers are built next to asteriskd and packaged as native libraries */
    val hevBinary: File get() = RootBoot.exec(File(libDir, "libhevtun.so"))
    val bpfBinary: File get() = RootBoot.exec(File(libDir, "libbpf2socks.so"))
    const val HEV_TUN_DEVICE = "hevtun0"
    private val matcherBinary: File get() = RootBoot.exec(File(libDir, "libbpf-matcher.so"))
    private const val FLOW_USERNAME = "__asterisk_flow_v1"

    private fun matcherAvailable(mode: Mode, ipv6: Boolean): Boolean {
        if (!DataStore.rootEbpfMatcher || mode !in listOf(Mode.TPROXY, Mode.TUN2SOCKS)) return false
        if (!File(libDir, "libbpf-matcher.so").isFile) {
            Logs.w("ROOT eBPF matcher: not packaged; using iptables")
            return false
        }
        // Isolated probe directory; never remove a supervisor-owned BPF object.
        val result = RootShell.run(
            "d=$(mktemp -d /sys/fs/bpf/niefigger_probe_XXXXXX) || exit 1; " +
                "trap 'rmdir \$d 2>/dev/null' EXIT; " +
                "${RootShell.q(matcherBinary)} --probe --json --bpf-dir \"\$d\" --ipv6 ${if (ipv6) 1 else 0}",
            timeoutMs = 8_000
        )
        Logs.i(if (result.ok) "ROOT eBPF matcher: enabled" else "ROOT eBPF matcher: unavailable; using iptables")
        return result.ok
    }

    // Only managed, bundled helper sessions opt in. Raw/external cores retain normal SOCKS5.
    private fun withFlowMetadata(json: String, token: String): String {
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val inbound = root.getAsJsonArray("inbounds").firstOrNull {
            it.isJsonObject && it.asJsonObject.get("tag")?.asString == "socks-in"
        }?.asJsonObject ?: error("Managed SOCKS helper inbound is missing")
        require(inbound.get("type")?.asString == "socks" &&
            inbound.get("listen")?.asString == "127.0.0.1" &&
            inbound.get("listen_port")?.asInt == DataStore.socksPort) { "Invalid private SOCKS helper inbound" }
        inbound.add("users", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("username", FLOW_USERNAME)
                addProperty("password", token)
            })
        })
        return gson.toJson(root)
    }

    private fun newFlowToken(): String = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private val PHASE = Regex("\"phase\"\\s*:\\s*\"([a-z-]+)\"")
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    private fun strings(list: List<String>) = JsonArray().also { a -> list.forEach { a.add(it) } }
    private fun ints(list: List<Int>) = JsonArray().also { a -> list.forEach { a.add(it) } }

    /** uids of the apps selected in "per-app proxy" (empty = no restriction) */
    fun appUids(): List<Int> {
        val pm = SagerNet.application.packageManager
        val keys = DataStore.individual.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val priv = Regex("u(\\d+):(.+)")
        val uids = HashSet<Int>()
        // apps of the main space
        keys.filter { !priv.matches(it) }.forEach { k -> runCatching { pm.getApplicationInfo(k, 0).uid }.getOrNull()?.let(uids::add) }
        // apps of the private space / other users: "u<user>:<package>", the uid is looked up as root
        val byUser = keys.mapNotNull { priv.matchEntire(it) }.groupBy({ it.groupValues[1] }, { it.groupValues[2] })
        for ((user, pkgs) in byUser) {
            val out = RootShell.run("pm list packages -U --user $user", timeoutMs = 15_000).out
            val map = out.lineSequence().mapNotNull { Regex("package:(\\S+)\\s+uid:(\\d+)").find(it) }
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
            pkgs.forEach { p -> map[p]?.let(uids::add) }
        }
        return uids.sorted()
    }

    private val DEFAULT_BYPASS_CIDRS = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
        "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
        "255.255.255.255/32", "::/128", "::1/128", "::ffff:0:0/96", "100::/64",
        "64:ff9b::/96", "2001::/32", "2001:10::/28", "2001:20::/28", "2001:db8::/32",
        "2002::/16", "fc00::/7", "fe80::/10", "ff00::/8",
    )

    /** Interface selectors per tethering type, the same groups as Asterisk uses. */
    fun tetherSelectors(): List<String> {
        val l = LinkedHashSet<String>()
        if (DataStore.tetherWifi) l += listOf("wlan+", "ap+", "softap+")
        if (DataStore.tetherUsb) l += listOf("rndis+", "usb+", "ncm+")
        if (DataStore.tetherBluetooth) l += listOf("bnep+", "bt-pan+")
        if (DataStore.tetherEthernet) l += "eth+"
        DataStore.tetherCustom.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.forEach { l += it }
        return l.filter { Regex("[A-Za-z0-9_.\\-]+\\+?").matches(it) && it != "lo" }.take(32)
    }

    private val exactInterface = Regex("[A-Za-z0-9_.-]{1,15}")
    private val interfaceSelector = Regex("[A-Za-z0-9_.-]{1,15}\\+?")
    private val internalInterfaces = setOf("lo", TUN_DEVICE, HEV_TUN_DEVICE)

    private fun interfaceItems(value: String) =
        value.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.distinct()

    fun validTunInterfaces(value: String): Boolean = interfaceItems(value).let { items ->
        items.size <= 16 && items.all { exactInterface.matches(it) && it !in internalInterfaces }
    }

    fun validIgnoredInterfaces(value: String): Boolean = interfaceItems(value).let { items ->
        items.size <= 16 && items.all { interfaceSelector.matches(it) && it.removeSuffix("+") !in internalInterfaces }
    }

    fun configuredTunInterfaces(): List<String> {
        val value = DataStore.rootTunInterfaces
        require(validTunInterfaces(value)) { "Invalid ROOT TUN downstream interface name" }
        return interfaceItems(value)
    }

    internal data class DownstreamSnapshot(
        val names: List<String>,
        val identity: List<String>,
        val ipv6Enabled: Boolean = false,
        val ipv6Prefixes: List<TetherIpv6.Down> = emptyList(),
        val blockIpv6: Boolean = false,
        val serviceIpv6: Boolean = ipv6Enabled,
    ) {
        /**
         * asteriskd re-applies local addresses and interface-indexed TC/BPF
         * state on netlink events. Only changes to its published config need
         * a service restart. Managed TUN also has core-owned route exclusions;
         * retain its interface identity (ifindex) and canonical IPv4 subnets.
         */
        fun canRefreshNames(mode: Mode, observed: DownstreamSnapshot): Boolean =
            mode in listOf(Mode.TPROXY, Mode.TUN2SOCKS, Mode.BPF2SOCKS) && !blockIpv6 &&
                names != observed.names &&
                copy(names = emptyList(), identity = emptyList()) ==
                    observed.copy(names = emptyList(), identity = emptyList())

        fun requiresRestart(mode: Mode, observed: DownstreamSnapshot): Boolean {
            if (copy(identity = emptyList()) != observed.copy(identity = emptyList())) return true
            return mode == Mode.TUN &&
                RootTetherIdentity.routingIdentity(identity) != RootTetherIdentity.routingIdentity(observed.identity)
        }
    }

    // Android reports active downstreams explicitly; never guess from all wlan* devices.
    internal fun resolveDownstreams(
        mode: Mode, requireReliable: Boolean = false, ipv6: Boolean = false,
        allowIdleFastPath: Boolean = false,
    ): DownstreamSnapshot? {
        // Boot-owned native sessions retain their selector-based configuration;
        // a saved global prefix must not bypass traffic after reboot/renumbering.
        val trackV6 = ipv6 && (mode == Mode.TUN || (mode in listOf(Mode.TUN2SOCKS, Mode.TPROXY, Mode.BPF2SOCKS) &&
            !DataStore.configurationStore.getBoolean("startOnBoot", false)))
        val blockV6 = !ipv6 && DataStore.rootDisableSystemIpv6
        fun emptySnapshot() = DownstreamSnapshot(emptyList(), emptyList(), trackV6, blockIpv6 = blockV6, serviceIpv6 = ipv6)
        val manual = if (mode == Mode.TUN) configuredTunInterfaces() else emptyList()
        val selectors = tetherSelectors().toMutableList()
        if (DataStore.tetherWifi) selectors += listOf("swlan+", "wifi+")
        val ignored = ignoredInterfaces()
        fun matches(name: String, selector: String): Boolean =
            if (selector.endsWith("+")) name.startsWith(selector.dropLast(1)) else name == selector
        if (manual.isEmpty() && selectors.isEmpty()) return emptySnapshot()
        val shortDump = RootShell.run("dumpsys tethering --short", timeoutMs = 5000)
        val dump = if (shortDump.ok && shortDump.out.contains("TetheredState")) shortDump
            else RootShell.run("dumpsys tethering", timeoutMs = 5000)
        val state = Regex("""^\s*([A-Za-z0-9_.-]{1,15})\s+-\s+TetheredState\s+-\s+lastError\s*=\s*0\s*$""")
        if (requireReliable) {
            if (!dump.ok) return null
            val recognizable = Regex("""(?:TetheredState|AvailableState|UnavailableState|LocalHotspotState|Tether state:)""", RegexOption.IGNORE_CASE)
            if (!recognizable.containsMatchIn(dump.out)) return null
            // Never mistake an unsupported active-interface line for a disconnect.
            if (dump.out.lineSequence().any { " - TetheredState" in it && !state.matches(it) }) return null
        }
        val active = if (dump.ok) dump.out.lineSequence()
            .mapNotNull { state.matchEntire(it)?.groupValues?.get(1) }.distinct().toList() else emptyList()
        val auto = if (dump.ok) active.filter { name -> selectors.any { matches(name, it) } } else emptyList()
        // No interface can be selected when both lists are empty. Only use
        // this shortcut after a reliable dump and for an already empty
        // applied snapshot; never skip WAN checks on a detach or attach.
        if (RootIdlePolling.skipEmptyRouteDump(
                requireReliable, allowIdleFastPath, dump.ok, manual.isEmpty(), auto.isEmpty(),
            )) return emptySnapshot()
        // Both families must succeed; a partial route dump cannot prove that an interface is not WAN.
        val routes = RootShell.run("ip -4 route show table all && ip -6 route show table all", timeoutMs = 5000)
        if (!routes.ok) {
            Logs.w("ROOT ${mode.wire}: cannot verify upstream interfaces; skipping unverified downstreams")
            return if (requireReliable) null else emptySnapshot()
        }
        val upstream = routes.out.lineSequence().filter { it.trimStart().startsWith("default ") }
            .flatMap { Regex("""\bdev\s+([A-Za-z0-9_.-]{1,15})(?:\s|$)""").findAll(it).map { match -> match.groupValues[1] } }
            .toSet()
        val result = (manual + auto).distinct().filter { name ->
            name !in internalInterfaces && name !in upstream && ignored.none { matches(name, it) }
        }
        if (!requireReliable) Logs.i("ROOT ${mode.wire} downstream: active=$active selected=$result upstream=$upstream dumpOk=${dump.ok} routesOk=${routes.ok}")
        if (!dump.ok) Logs.w("ROOT ${mode.wire}: Android tethering state unavailable; only verified manual downstreams can be used")
        if (!requireReliable && selectors.isNotEmpty() && result.isEmpty()) Logs.i("ROOT ${mode.wire}: no matching active downstream at startup")
        val names = result.sorted()
        if (names.isEmpty()) return emptySnapshot()
        val links = RootShell.run("ip -o link show && ip -4 -o addr show", timeoutMs = 5000)
        if (!links.ok) return if (requireReliable) null else DownstreamSnapshot(names, emptyList(), trackV6, blockIpv6 = blockV6, serviceIpv6 = ipv6)
        // Include the ifindex and IPv4 gateway/prefix: Android may recreate ap0 using the same name.
        val identity = RootTetherIdentity.parse(names, links.out)
        if (requireReliable && (names - manual.toSet()).any { name -> identity.none { it.substringAfter(':').substringBefore(':') == name } }) return null
        val prefixes = if (trackV6) TetherIpv6.discover(names.toSet())?.filter {
            // Only on-link subnets, never a broad upstream/global aggregate.
            it.subnet.substringAfterLast('/').toIntOrNull()?.let { bits -> bits in 64..128 } == true
        } else emptyList()
        if (prefixes == null && requireReliable) return null
        if (prefixes == null) Logs.w("ROOT ${mode.wire}: cannot discover downstream IPv6 prefixes")
        return DownstreamSnapshot(names, identity, trackV6, prefixes.orEmpty(), blockV6, ipv6)
    }

    // The native mailbox is scoped to the live, root-owned runtime directory.
    // Never send a request to a raw, boot-selector or Service Control session.
    private var hotTetherEnabled = false

    @Synchronized
    internal fun tryRefreshTether(mode: Mode, applied: DownstreamSnapshot, observed: DownstreamSnapshot): Boolean {
        if (!hotTetherEnabled || appliedDownstreams !== applied || !applied.canRefreshNames(mode, observed)) return false
        val entries = observed.names.map { name ->
            val index = observed.identity.mapNotNull { item ->
                val fields = item.split(':', limit = 3)
                if (fields.size == 3 && fields[1] == name) fields[0].toLongOrNull() else null
            }.distinct().singleOrNull()?.takeIf { it in 1..4294967295L } ?: return false
            if (!exactInterface.matches(name) || name in internalInterfaces) return false
            "$name:$index"
        }
        val before = control("status", 5000) ?: return false
        if (RootHealth.parse(before, OWNER) != RootHealth.RUNNING) return false
        val pid = before.getAsJsonObject("snapshot")?.get("supervisorPid")?.asInt?.takeIf { it > 0 } ?: return false
        val token = newFlowToken()
        val request = "$runDir/nf-tether-request"
        val ack = "$runDir/nf-tether-ack"
        val temp = "$request.new"
        val payload = (listOf(pid.toString(), token) + entries).joinToString(" ")
        val publish = RootShell.run("set -e", "umask 077",
            "rm -f ${RootShell.q(ack)} ${RootShell.q(temp)}",
            "printf '%s\\n' ${RootShell.q(payload)} > ${RootShell.q(temp)}",
            "chmod 600 ${RootShell.q(temp)}",
            "mv -f ${RootShell.q(temp)} ${RootShell.q(request)}", timeoutMs = 5000)
        if (!publish.ok) return false
        try {
            // Status wakes the supervisor's normal reactor; it handles the request
            // outside the control callback and writes a nonce-bound acknowledgement.
            val deadline = SystemClock.elapsedRealtime() + 12000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val status = control("status", 5000) ?: return false
                if (RootHealth.parse(status, OWNER) != RootHealth.RUNNING ||
                    status.getAsJsonObject("snapshot")?.get("supervisorPid")?.asInt != pid) return false
                val response = RootShell.run("cat ${RootShell.q(ack)} 2>/dev/null", timeoutMs = 3000)
                if (response.ok && response.out == "$token applied") {
                    appliedDownstreams = observed
                    Logs.i("ROOT ${mode.wire}: downstream rules refreshed without restarting core")
                    return true
                }
                if (response.ok && response.out == "$token rejected") return false
                Thread.sleep(250)
            }
            return false
        } finally {
            RootShell.run("rm -f ${RootShell.q(request)} ${RootShell.q(temp)} ${RootShell.q(ack)}", timeoutMs = 3000)
        }
    }

    @Volatile internal var appliedDownstreams: DownstreamSnapshot? = null
        private set
    @Volatile internal var startedWaiting = false
        private set

    internal fun isRunning(): Boolean = health() == RootHealth.RUNNING

    // Never treat an inaccessible control socket, su timeout, or malformed
    // response as a confirmed crash. Paused service-control sessions are not failures.
    internal fun health(): RootHealth = RootHealth.parse(control("status", 5000), OWNER)

    // Use the same lock as shutdown so a late watchdog cannot reinstall rules
    // after the session has been invalidated. Identity distinguishes restarts
    // whose downstream names and addresses happen to be unchanged.
    @Synchronized
    internal fun reconcileTetherRules(mode: Mode, applied: DownstreamSnapshot, observed: DownstreamSnapshot?) {
        val repairForward = mode == Mode.TUN && observed != null &&
            !applied.requiresRestart(mode, observed) && applied.names.isNotEmpty()
        if (!applied.blockIpv6 && !repairForward) return
        // One fresh health check under the shutdown lock, not a cached result.
        if (appliedDownstreams !== applied || health() != RootHealth.RUNNING) return
        if (applied.blockIpv6) {
            val names = (applied.names + observed?.names.orEmpty()).distinct()
            if (!RootTetherIpv6Guard.ensure(names)) Logs.w("ROOT tether IPv6 guard: reconciliation failed")
        }
        if (repairForward && !RootTunForwarding.ensure(applied.names, applied.ipv6Enabled))
            Logs.w("ROOT TUN forwarding: reconciliation pending")
    }

    private fun managedTunInbound(root: JsonObject): JsonObject? = root.getAsJsonArray("inbounds")?.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("type")?.asString == "tun" &&
                it.asJsonObject.get("interface_name")?.asString == TUN_DEVICE
        }?.asJsonObject

    private fun withTunDownstreams(json: String, snapshot: DownstreamSnapshot?): String {
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val inbound = managedTunInbound(root) ?: return json
        val names = snapshot?.names.orEmpty()
        inbound.add("include_interface", strings(listOf("lo") + names))
        if (names.isNotEmpty()) {
            // Keep DHCP broadcast and the actual local downstream subnet out of the
            // native TUN route table. Do not bypass all private ranges or replace SRS.
            val existing = inbound.get("route_exclude_address")?.let { field ->
                if (field.isJsonArray) field.asJsonArray.map { it.asString } else listOf(field.asString)
            }.orEmpty()
            val local = snapshot?.identity.orEmpty().mapNotNull {
                RootTetherIdentity.ipv4Network(it.substringAfter(':').substringAfter(':'))
            }
            val ipv6 = inbound.getAsJsonArray("address")?.any { ':' in it.asString } == true
            val added = listOf("255.255.255.255/32", "169.254.0.0/16", "224.0.0.0/24") +
                local + if (ipv6) listOf("fe80::/10", "ff02::/16") +
                    snapshot?.ipv6Prefixes.orEmpty().map { it.subnet } else emptyList()
            inbound.add("route_exclude_address", strings((existing + added).distinct()))
            Logs.i("ROOT TUN: local tether route exclusions=${added.distinct()}")
        }
        return gson.toJson(root)
    }

    private fun ignoredInterfaces(): List<String> {
        val value = DataStore.rootIgnoredInterfaces
        require(validIgnoredInterfaces(value)) { "Invalid ROOT ignored interface selector" }
        return interfaceItems(value)
    }

    // The effective config (including raw/custom profiles) is the single source of truth
    // for offload preparation. Do not re-scan network addresses at startup.
    private fun tunInterfacesFromConfig(json: String): List<String> {
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val inbound = root.getAsJsonArray("inbounds")?.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("type")?.asString == "tun" &&
                it.asJsonObject.get("interface_name")?.asString == TUN_DEVICE
        }?.asJsonObject ?: return emptyList()
        val field = inbound.get("include_interface") ?: return emptyList()
        val names = if (field.isJsonArray) field.asJsonArray.map { it.asString }
            else if (field.isJsonPrimitive) listOf(field.asString) else emptyList()
        return names.filter { exactInterface.matches(it) && it !in internalInterfaces }.distinct()
    }

    fun buildConfig(mode: Mode, corePath: String, tproxyPort: Int, ipv6: Boolean, singBoxConfig: String, flowToken: String? = null, matcher: Boolean = false, downstreams: List<String>? = null, downstreamIpv6: List<String> = emptyList(), serviceControl: JsonObject = ServiceControl.toJson(), tetherDnsHijack: Boolean = false, gatewayDnsPort: Int? = null): String {
        require(gatewayDnsPort == null || (mode == Mode.TUN2SOCKS && tetherDnsHijack && downstreams != null &&
            gatewayDnsPort in 1024..65535)) { "Invalid HEV gateway DNS config" }
        val root = JsonObject()
        root.addProperty("schemaVersion", SCHEMA)
        root.addProperty("owner", OWNER)
        root.addProperty("coreType", "sing-box")
        root.addProperty("coreExecutablePath", corePath)
        root.addProperty("coreConfigPath", configPath)
        root.addProperty("statePath", statePath)
        root.addProperty("logPath", logPath)
        root.addProperty("mode", mode.wire)

        root.add("core", JsonObject().apply {
            addProperty("workingDirectory", runDir)
            addProperty("readinessTimeoutMilliseconds", 15000)
            add("ageSecretKey", JsonNull.INSTANCE)
        })

        // TUN mode: asteriskd requires the plain "global" policy, sing-box's auto_route/auto_redirect does the routing
        val perApp = mode != Mode.TUN && DataStore.proxyApps
        val uids = if (perApp) appUids() else emptyList()
        root.add("network", JsonObject().apply {
            addProperty("enableIpv6", ipv6)
            addProperty("disableSystemIpv6", !ipv6 && DataStore.rootDisableSystemIpv6)
            addProperty("enableLocalDns", false)
            // Optional extension: only emit for an eligible patched native mode.
            if (tetherDnsHijack) addProperty("nfTetherDnsHijack", true)
            if (gatewayDnsPort != null) addProperty("nfHevGatewayDnsPort", gatewayDnsPort)
            addProperty("enableFakeDns", false)
            add("fakeDnsIpv4Pool", JsonNull.INSTANCE)
            val ignored = if (mode == Mode.TUN) emptyList() else ignoredInterfaces()
            add("ignoredInterfaces", strings(ignored))
            add("virtualInterfaces", strings(if (mode == Mode.TUN2SOCKS) listOf(HEV_TUN_DEVICE) else emptyList()))
            val shared = if (mode == Mode.TUN) tunInterfacesFromConfig(singBoxConfig) else downstreams ?: tetherSelectors()
            add("hotspotInterfaces", strings(shared.also { Logs.i("hotspotInterfaces: $it") }))
            add("proxyPrivateCidrs", strings(emptyList()))
            // as in AsteriskBOX: private/local ranges are never captured, otherwise hotspot clients lose DHCP/gateway
            // traffic (their Wi-Fi drops); TUN mode leaves it to sing-box auto_route
            val localTetherV6 = if (mode in listOf(Mode.TUN2SOCKS, Mode.TPROXY, Mode.BPF2SOCKS) && ipv6 && downstreams != null)
                downstreamIpv6 else emptyList()
            if (localTetherV6.isNotEmpty()) Logs.i("ROOT ${mode.wire}: local tether IPv6 exclusions=$localTetherV6")
            add("bypassPrivateCidrs", strings(if (mode == Mode.TUN) emptyList() else
                (DEFAULT_BYPASS_CIDRS.filter { ipv6 || ':' !in it } + localTetherV6).distinct()))
            add("appPolicy", JsonObject().apply {
                addProperty(
                    "mode",
                    if (!perApp || uids.isEmpty()) "global" else if (DataStore.bypass) "blacklist" else "whitelist"
                )
                add("uids", ints(if (perApp) uids else emptyList()))
                add("bypassUids", ints(if (mode == Mode.TUN) emptyList() else listOf(android.os.Process.myUid())))
                add("directCidrPathV4", JsonNull.INSTANCE)
                add("directCidrPathV6", JsonNull.INSTANCE)
            })
        })

        root.add("modeOptions", JsonObject().apply {
            if (mode == Mode.TPROXY) addProperty("transparentPort", tproxyPort) else add("transparentPort", JsonNull.INSTANCE)
            if (mode == Mode.TUN) addProperty("tunnelName", TUN_DEVICE) else add("tunnelName", JsonNull.INSTANCE)
        })
        root.add("matcher", if (matcher) JsonObject().apply {
            addProperty("executablePath", matcherBinary.absolutePath)
        } else JsonNull.INSTANCE)
        root.add("helper", when (mode) {
            Mode.TUN2SOCKS -> JsonObject().apply {
                addProperty("type", "hev-socks5-tunnel")
                addProperty("executablePath", hevBinary.absolutePath)
                addProperty("socksHost", "127.0.0.1")
                addProperty("socksPort", DataStore.socksPort)
                if (flowToken != null) addProperty("flowMetadataToken", flowToken)
                addProperty("tunnelName", HEV_TUN_DEVICE)
                addProperty("mtu", DataStore.mtu.coerceIn(1280, 65535))
                addProperty("ipv4Address", "198.18.0.1")
                if (ipv6) addProperty("ipv6Address", "fc00:18::1") else add("ipv6Address", JsonNull.INSTANCE)
                addProperty("multiQueue", DataStore.rootHevMultiQueue)
                addProperty("tcpFastOpen", DataStore.rootHevTcpFastOpen)
                addProperty("tcpReadWriteTimeoutMilliseconds", 300000)
                addProperty("udpReadWriteTimeoutMilliseconds", 60000)
            }
            Mode.BPF2SOCKS -> JsonObject().apply {
                addProperty("type", "bpf2socks")
                addProperty("executablePath", bpfBinary.absolutePath)
                addProperty("bridgeListenAddress", "0.0.0.0")
                addProperty("bridgePort", DataStore.bpfBridgePort)
                addProperty("socksHost", "127.0.0.1")
                addProperty("socksPort", DataStore.socksPort)
                if (flowToken != null) addProperty("flowMetadataToken", flowToken)
                addProperty("workerCount", 0)
                addProperty("tcpBufferSize", 0)
                addProperty("maxTcpSessions", 4096)
                addProperty("connectTimeoutMilliseconds", 10000)
                addProperty("tcpIdleTimeoutMilliseconds", 300000)
                addProperty("udpSocketBufferSize", 262144)
                addProperty("udpBatchSize", 32)
                addProperty("maxUdpSessions", 4096)
                addProperty("maxUdpBindings", 16384)
                addProperty("udpIdleTimeoutMilliseconds", 60000)
                addProperty("maxUdpPendingBytes", 4194304)
                addProperty("dnsTransactionTimeoutMilliseconds", 5000)
            }
            else -> JsonNull.INSTANCE
        })

        root.add("serviceControl", serviceControl.deepCopy())
        root.add("dnsEnhancement", JsonNull.INSTANCE)
        root.add("runtimeEventReceiver", JsonNull.INSTANCE)
        return gson.toJson(root) + "\n"
    }

    val coreLogPath get() = "$logDir/sing-box.log"

    /** makes the root core write its log to a file the app can forward into its log screen */
    private fun withLogFile(json: String): String = try {
        val o = com.google.gson.JsonParser.parseString(json).asJsonObject
        val log = if (o.has("log") && o.get("log").isJsonObject) o.getAsJsonObject("log") else JsonObject().also { o.add("log", it) }
        log.addProperty("output", coreLogPath)
        log.addProperty("timestamp", true)
        gson.toJson(o)
    } catch (e: Exception) { json }

    @Volatile private var tailer: Thread? = null
    @Volatile private var tunDiagnostics: Thread? = null

    // Read-only, bounded snapshots; no probes are transmitted and no firewall is modified.
    // Never log the full core config: it contains credentials.
    private fun startTunDiagnostics(snapshot: DownstreamSnapshot, ipv6: Boolean) {
        stopTunDiagnostics()
        val names = snapshot.names.filter { exactInterface.matches(it) && it !in internalInterfaces }
        tunDiagnostics = Thread({
            val self = Thread.currentThread()
            fun active() = !self.isInterrupted && tunDiagnostics === self
            fun capture(label: String, command: String) {
                if (!active()) return
                // Each command has its own bound and reports failure separately.
                val result = RootShell.run(command, timeoutMs = 4000L)
                if (!active()) return
                Logs.i("ROOT TUN diagnostic [$label] rc=${result.code}")
                val redactor = io.nekohasekai.sagernet.ktx.LogRedactor.Stream()
                result.out.lineSequence().take(180).forEach { line ->
                    val safe = redactor.redact(line)
                    if (active() && line.isNotBlank()) Logs.i("ROOT TUN diagnostic [$label] ${safe.take(1000)}")
                }
                if (result.out.lineSequence().count() > 180) Logs.i("ROOT TUN diagnostic [$label] output truncated")
                if (result.err.isNotBlank()) Logs.w("ROOT TUN diagnostic [$label] ${result.err.take(500)}")
            }
            try {
                if (!active()) return@Thread
                Logs.i("ROOT TUN diagnostic: begin downstream=$names")
                fun hotspotState(label: String) {
                    capture("$label links", "ip -o link show | sed -E 's/([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}/[MAC]/g'")
                    capture("$label addresses", "ip -4 -o addr show")
                    capture("$label tether", "dumpsys tethering | grep -iE 'TetheredState|AvailableState|UnavailableState|LocalHotspotState|Tether state:|offload.*(started|stopped|enabled|disabled)' | head -n 60")
                    capture("$label Wi-Fi events", "logcat -d -t 1500 -v brief -s SoftApManager WifiNative IpServer DhcpServer DhcpClient 2>/dev/null | grep -iE 'state|fail|disconnect|connect|DHCP|interface|lease' | sed -E 's/([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}/[MAC]/g' | grep -ivE 'ssid|password|passphrase|credential|preSharedKey' | tail -n 40")
                }
                hotspotState("initial")
                capture("forwarding", "echo ip_forward; cat /proc/sys/net/ipv4/ip_forward; echo rp_filter; cat /proc/sys/net/ipv4/conf/all/rp_filter; echo tether_offload_disabled; settings get global tether_offload_disabled")
                capture("IPv4 rules", "ip -4 rule show")
                capture("IPv4 routes", "ip -4 route show table all")
                if (ipv6) {
                    capture("IPv6 rules", "ip -6 rule show")
                    capture("IPv6 routes", "ip -6 route show table all")
                }
                capture("filter", "iptables -w 1 -t filter -L -n -v -x")
                capture("nat", "iptables -w 1 -t nat -L -n -v -x")
                capture("mangle", "iptables -w 1 -t mangle -L -n -v -x")
                capture("offload", "dumpsys tethering | grep -iE 'offload|TetheredState|LocalHotspotState|current upstream' | head -n 80")
                for (name in names) {
                    val dev = RootShell.q(name)
                    capture("$name TC ingress", "tc -s filter show dev $dev ingress")
                    capture("$name TC egress", "tc -s filter show dev $dev egress")
                    capture("$name client routes", "ip -4 neigh show dev $dev | awk '/lladdr/ {print \$1}' | head -n 8 | while read -r client; do echo client=\$client; ip -4 route get 1.1.1.1 from \"\$client\" iif $dev; ip -4 route get \"\$client\"; done")
                }
                val devices = names + TUN_DEVICE
                repeat(2) { index ->
                    if (!active()) return@Thread
                    for (name in devices) {
                        val dev = RootShell.q(name)
                        capture("counters ${index + 1} $name", "ip -s link show dev $dev | sed '/link\\//d'")
                    }
                    if (index == 0) Thread.sleep(5000L)
                }
                hotspotState("after traffic")
                if (active()) Logs.i("ROOT TUN diagnostic: complete")
            } catch (_: InterruptedException) {
                // Normal service shutdown.
            } catch (e: Exception) {
                if (active()) Logs.w("ROOT TUN diagnostic failed: ${e.javaClass.simpleName}")
            }
        }, "root-tun-diagnostics").apply { isDaemon = true }
        tunDiagnostics?.start()
    }

    private fun stopTunDiagnostics() {
        val old = tunDiagnostics
        tunDiagnostics = null
        old?.interrupt()
    }

    /** forwards core/daemon log lines into the in-app log (Logs -> neko.log) while the service runs */
    fun startLogForward(mode: Mode? = null) {
        stopLogForward()
        tailer = Thread({
            val offsets = HashMap<String, Long>()
            val identities = HashMap<String, String>()
            val redactors = HashMap<String, io.nekohasekai.sagernet.ktx.LogRedactor.Stream>()
            val files = listOf(coreLogPath, logPath, "$logDir/error.log") +
                if (mode == Mode.TUN2SOCKS) listOf("$logDir/tun2socks.log") else emptyList()
            try {
                val self = Thread.currentThread()
                val pending = HashMap<String, ByteArray>()
                val discard = HashSet<String>()
                var idlePolls = 0
                fun active() = !self.isInterrupted && tailer === self
                while (active()) {
                    // One su transaction for all files; frame the binary chunks
                    // with their index, inode and starting byte offset.
                    val commands = mutableListOf<String>()
                    for ((index, f) in files.withIndex()) {
                        val off = offsets[f] ?: 0L
                        val inode = identities[f].orEmpty()
                        commands += """
                            if [ -f ${RootShell.q(f)} ]; then
                                meta=${'$'}(stat -c '%i %s' ${RootShell.q(f)} 2>/dev/null) || meta=''
                                if [ -n "${'$'}meta" ]; then
                                    set -- ${'$'}meta
                                    ino=${'$'}1; size=${'$'}2; start=$off
                                    if [ "${'$'}ino" != ${RootShell.q(inode)} ] || [ "${'$'}size" -lt "${'$'}start" ]; then start=0; fi
                                    if [ "${'$'}size" -gt "${'$'}start" ] || [ "${'$'}ino" != ${RootShell.q(inode)} ] || [ "${'$'}start" != "$off" ]; then
                                        printf '%s %s %s ' $index "${'$'}ino" "${'$'}start"
                                        tail -c +${'$'}((start+1)) ${RootShell.q(f)} | head -c 65536 | base64 | tr -d '\r\n'
                                        printf '\n'
                                    fi
                                fi
                            fi
                        """.trimIndent()
                    }
                    val result = RootShell.run(*commands.toTypedArray(), timeoutMs = 8000)
                    if (!active()) break
                    var received = false
                    if (result.ok) for (line in result.out.lineSequence()) {
                        if (!active()) break
                        val parts = line.split(' ', limit = 4)
                        if (parts.size < 3) continue
                        val f = files.getOrNull(parts[0].toIntOrNull() ?: -1) ?: continue
                        val inode = parts[1]
                        val start = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: continue
                        if (inode != identities[f] || start != (offsets[f] ?: 0L)) {
                            pending.remove(f)
                            discard.remove(f)
                            redactors.remove(f)
                        }
                        identities[f] = inode
                        val chunk = runCatching {
                            android.util.Base64.decode(parts.getOrElse(3) { "" }, android.util.Base64.DEFAULT)
                        }.getOrNull() ?: continue
                        offsets[f] = start + chunk.size
                        if (chunk.isEmpty()) continue
                        received = true
                        var data = (pending.remove(f) ?: ByteArray(0)) + chunk
                        if (f in discard) {
                            val firstEnd = data.indexOf(10.toByte())
                            if (firstEnd < 0) continue
                            discard.remove(f)
                            data = data.copyOfRange(firstEnd + 1, data.size)
                        }
                        val end = data.indexOfLast { it == 10.toByte() } + 1
                        if (end > 0) {
                            val redactor = redactors.getOrPut(f) { io.nekohasekai.sagernet.ktx.LogRedactor.Stream() }
                            String(data, 0, end, Charsets.UTF_8).lineSequence().forEach {
                                // Feed even filtered lines into the stateful redactor.
                                val safe = redactor.redact(it)
                                if (active() && it.isNotBlank() && (!f.endsWith("/tun2socks.log") || "flow metadata" in it))
                                    Libcore.nekoLogPrintln(safe)
                            }
                        }
                        val remainder = data.copyOfRange(end, data.size)
                        if (remainder.size <= 65536) pending[f] = remainder
                        else {
                            discard += f
                            if (active()) Libcore.nekoLogPrintln("[oversized native diagnostic omitted]")
                        }
                    }
                    // Only confirmed empty reads back off; failed reads retain
                    // the previous short retry cadence. A new line resets it.
                    idlePolls = RootIdlePolling.nextLogIdlePolls(idlePolls, result.ok, received)
                    Thread.sleep(RootIdlePolling.logDelayMs(idlePolls))
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Logs.w("log forward stopped: $e")
            }
        }, "root-log-forward").apply { isDaemon = true }
        tailer?.start()
    }

    fun stopLogForward() {
        val old = tailer
        tailer = null
        old?.interrupt()
    }

    /**
     * Writes the configs, starts asteriskd and waits until the core is up.
     * @return null on success, otherwise a readable error
     */
    fun start(mode: Mode, core: RootCore.Active, singBoxConfig: String, tproxyPort: Int, ipv6: Boolean, managed: Boolean = false,
              serviceControl: JsonObject = ServiceControl.toJson(), monitorOnly: Boolean = false,
              manageDnsCache: Boolean = false, dnsCacheScope: String? = null): String? {
        if (monitorOnly && !ServiceControl.hasStartRule(serviceControl))
            return "Service Control requires at least one valid start condition"
        startedWaiting = false
        hotTetherEnabled = false
        val startedAt = SystemClock.elapsedRealtime()
        var lastMark = startedAt
        fun mark(stage: String) {
            val now = SystemClock.elapsedRealtime()
            Logs.i("ROOT daemon startup: $stage ${now - lastMark} ms (total ${now - startedAt} ms)")
            lastMark = now
        }
        // Validate APK files, not copies under /data/adb which the app UID cannot stat.
        if (!File(libDir, "libasteriskd.so").isFile) return "libasteriskd.so is not part of this build"
        if (mode == Mode.TUN2SOCKS && !File(libDir, "libhevtun.so").isFile) return "libhevtun.so (hev-socks5-tunnel) is not part of this build"
        if (mode == Mode.BPF2SOCKS && !File(libDir, "libbpf2socks.so").isFile) return "libbpf2socks.so is not part of this build"

        if (!RootBoot.quiesceMonitor()) return "Autonomous ROOT monitor did not release ownership"
        if (!TetherVpn.prepareForRoot()) return "Previous VPN tether recovery is not confirmed"
        RootBoot.stage()?.let { return it }
        mark("boot staging")
        // Never replace a live supervisor's configuration or truncate its logs.
        if (!shutdownVerified(forStartup = true)) return "Previous ROOT supervisor did not shut down cleanly"
        mark("cleanup previous daemon")
        // A missing supervisor socket can still require native reconciliation.
        // Never change a cache file on that weaker evidence.
        val cacheConfig = if (managed && manageDnsCache &&
            cleanupEvidence() in listOf(RootCleanupEvidence.STOPPED, RootCleanupEvidence.NO_STATE)) {
            RootPersistentDnsCache.prepare(singBoxConfig, if (core.bundled) dnsCacheScope else null)
        } else singBoxConfig
        val useFlow = managed && core.patched && DataStore.rootFlowMetadata &&
            mode in listOf(Mode.TUN2SOCKS, Mode.BPF2SOCKS)
        val flowToken = if (useFlow) newFlowToken() else null
        val bootEnabled = DataStore.configurationStore.getBoolean("startOnBoot", false)
        // Preview trial: ordinary managed IPv4 HEV sessions only.
        // Boot, Service Control and dual-stack remain on the previous path.
        val gatewayEligible = false // Experimental tether DNS interception is suspended.
        // An explicit root-owned reservation is required for the marked test.
        // Native admission validates permissions, identity, contents and conflicts again.
        val leasePath = RootShell.q("$runDir/nf-hev-ctmark-lease")
        val gatewayTrial = gatewayEligible && RootShell.run(
            "test ! -L $leasePath && test -f $leasePath && test \"$(stat -c %u:%a $leasePath)\" = 0:600 && grep -Fxq 'NF_HEV_CTMARK_V1 0x00800000' $leasePath",
            timeoutMs = 3000
        ).ok
        if (gatewayEligible && !gatewayTrial)
            Logs.i("ROOT HEV gateway DNS trial: no explicit connmark reservation; using non-local DNS path")
        val verifiedGatewayDownstreams = if (gatewayTrial)
            resolveDownstreams(mode, requireReliable = true, ipv6 = ipv6) else null
        val dynamicDownstreams = verifiedGatewayDownstreams ?: if (managed && (mode != Mode.TUN ||
            managedTunInbound(com.google.gson.JsonParser.parseString(singBoxConfig).asJsonObject) != null))
            resolveDownstreams(mode, ipv6 = ipv6) else null
        val baseConfig = when {
            managed && mode == Mode.TUN -> withTunDownstreams(cacheConfig, dynamicDownstreams)
            flowToken != null -> withFlowMetadata(cacheConfig, flowToken)
            else -> cacheConfig
        }
        // Avoid opening the listener in an idle or uncertain initial session.
        val gatewayConfig = if (gatewayTrial && verifiedGatewayDownstreams != null &&
            verifiedGatewayDownstreams.names.isNotEmpty() &&
            RootTetherDnsPolicy.enabled(true, true, true, true, false, baseConfig)) {
            runCatching {
                HevGatewayDnsConfig.prepare(
                    com.google.gson.JsonParser.parseString(baseConfig).asJsonObject,
                    HevGatewayDnsConfig.PORT, ipv6,
                    setOf(DataStore.mixedPort, DataStore.socksPort, DataStore.rootTproxyPort, DataStore.bpfBridgePort),
                    eligible = true
                )
            }.getOrNull()
        } else null
        val gatewayDnsPort = gatewayConfig?.let { HevGatewayDnsConfig.PORT }
        if (gatewayTrial) {
            if (gatewayDnsPort != null)
                Logs.i("ROOT HEV gateway DNS trial: port=$gatewayDnsPort downstreams=${verifiedGatewayDownstreams!!.names}")
            else Logs.w("ROOT HEV gateway DNS trial: no reliable active downstream or incompatible core config; using non-local DNS path")
        }
        val effectiveConfig = gatewayConfig?.let { gson.toJson(it) } ?: baseConfig
        if (useFlow) Logs.i("ROOT flow metadata: enabled")
        val useMatcher = matcherAvailable(mode, ipv6)
        if (managed && mode == Mode.TUN) {
            val tun = managedTunInbound(com.google.gson.JsonParser.parseString(effectiveConfig).asJsonObject)
            if (tun != null) Logs.i("ROOT TUN effective config: include_interface=${tun.get("include_interface")} stack=${tun.get("stack")} mtu=${tun.get("mtu")} auto_route=${tun.get("auto_route")} auto_redirect=${tun.get("auto_redirect")}")
        }
        // A boot-started native daemon has no Android app monitor. Keep its native prefix
        // selectors for daemon-managed modes when start-on-boot is enabled.
        val daemonDownstreams = if (gatewayDnsPort != null) verifiedGatewayDownstreams!!.names
            else if (mode != Mode.TUN && bootEnabled) null else dynamicDownstreams?.names
        val stage = File(SagerNet.application.noBackupFilesDir, "root-stage").apply { mkdirs() }
        try {
            val cfg = File(stage, "config.json").apply { writeText(withLogFile(effectiveConfig)) }
            val dnsCaptureRequested = false // Ignore previously saved experimental settings.
            val tetherDnsHijack = gatewayDnsPort != null || RootTetherDnsPolicy.enabled(
                requested = dnsCaptureRequested,
                managed = managed && core.bundled,
                supportedMode = mode == Mode.TPROXY || mode == Mode.TUN2SOCKS,
                exactDownstreams = daemonDownstreams != null,
                startOnBoot = DataStore.configurationStore.getBoolean("startOnBoot", false),
                coreConfig = baseConfig
            )
            if (dnsCaptureRequested)
                Logs.i("ROOT ${mode.wire} tether DNS interception: ${if (gatewayDnsPort != null) "experimental gateway DNS" else if (!tetherDnsHijack) "not eligible" else if (mode == Mode.TUN2SOCKS) if (ipv6) "IPv4 and IPv6 non-local DNS only" else "IPv4 non-local unicast only" else "enabled"}")
            val dcfg = File(stage, "asteriskd.json").apply {
                writeText(buildConfig(mode, RootBoot.exec(File(core.path)).absolutePath, tproxyPort, ipv6, effectiveConfig, flowToken, useMatcher, daemonDownstreams,
                    dynamicDownstreams?.ipv6Prefixes.orEmpty().map { it.subnet }, serviceControl, tetherDnsHijack, gatewayDnsPort))
            }
            mark("generate configs")
            val prep = RootShell.run(
                "set -e",
                "umask 077",
                // The gateway WAL requires an owner-only runtime directory, including upgrades from 0755.
                // All app accesses to runDir already go through su; never chmod a symlink or a foreign-owned directory.
                if (gatewayDnsPort != null) "mkdir -p ${RootShell.q(runDir)} && test ! -L ${RootShell.q(runDir)} && test -d ${RootShell.q(runDir)} && test \"$(stat -c %u ${RootShell.q(runDir)})\" = 0 && chmod 700 ${RootShell.q(runDir)} || exit 1" else "true",
                "mkdir -p ${RootShell.q(logDir)} && chmod 700 ${RootShell.q(logDir)}",
                "rm -f ${RootShell.q(coreLogPath)} ${RootShell.q(logPath)} ${RootShell.q("$logDir/error.log")} ${RootShell.q("$logDir/tun2socks.log")}",
                "rm -f ${RootShell.q(runDir)}/geoip.db ${RootShell.q(runDir)}/geosite.db",
                "rm -f ${RootShell.q(runDir)}/nf-tether-request ${RootShell.q(runDir)}/nf-tether-request.new ${RootShell.q(runDir)}/nf-tether-ack ${RootShell.q(runDir)}/.nf-tether-ack.new",
                "cp -f ${RootShell.q(cfg)} ${RootShell.q("$configPath.new")} && chmod 600 ${RootShell.q("$configPath.new")} && mv -f ${RootShell.q("$configPath.new")} ${RootShell.q(configPath)} || exit 1",
                "cp -f ${RootShell.q(dcfg)} ${RootShell.q("$daemonConfigPath.new")} && chmod 600 ${RootShell.q("$daemonConfigPath.new")} && mv -f ${RootShell.q("$daemonConfigPath.new")} ${RootShell.q(daemonConfigPath)} || exit 1",
                "test -x ${RootShell.q(binary)}",
                when (mode) {
                    Mode.TUN2SOCKS -> "test -x ${RootShell.q(hevBinary)}"
                    Mode.BPF2SOCKS -> "test -x ${RootShell.q(bpfBinary)}"
                    else -> "true"
                },
            )
            if (!prep.ok) return prep.err.ifBlank { "cannot prepare $runDir" }
        } finally {
            stage.deleteRecursively()
        }

        mark("install configs")
        portConflict(mode)?.let { return it }
        RootCore.check(core.path, File(configPath))?.let { return "sing-box check: $it" }
        mark("validate config")

        // Protect selected downstreams before the daemon starts. This filter is
        // independent of its routing and does not change Android IPv6 sysctls.
        if (dynamicDownstreams?.blockIpv6 == true && !RootTetherIpv6Guard.ensure(dynamicDownstreams.names)) {
            RootTetherIpv6Guard.clear()
            return "ROOT tether: cannot install IPv6 forwarding guard"
        }
        // Publication happened only after the previous control socket disappeared.
        val command = if (monitorOnly) "monitor" else "start"
        val r = RootShell.run(
            "nohup ${RootShell.q(binary)} $command --config ${RootShell.q(daemonConfigPath)} </dev/null >${RootShell.q("$logDir/asteriskd.out")} 2>&1 &",
            timeoutMs = 10_000,
        )
        if (!r.ok) {
            shutdown()
            return r.err.ifBlank { "failed to launch asteriskd" }
        }

        mark("spawn daemon")
        // Keep log-forward SU processes out of the readiness critical path.
        // The tailer starts at byte zero, so startup logs are still collected.
        val waitStarted = SystemClock.elapsedRealtime()
        val deadline = waitStarted + 25_000
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(if (SystemClock.elapsedRealtime() - waitStarted < 2000L) 200L else 500L)
            val st = status()
            last = st
            val phase = PHASE.find(st)?.groupValues?.get(1)
            val nativeHealth = RootHealth.parse(runCatching {
                com.google.gson.JsonParser.parseString(st).asJsonObject.getAsJsonObject("result")
            }.getOrNull(), OWNER)
            val waiting = monitorOnly && nativeHealth == RootHealth.INACTIVE
            if (nativeHealth == RootHealth.RUNNING || waiting) {
                mark("wait for readiness")
                if (!waiting && mode == Mode.TUN && managed && dynamicDownstreams != null &&
                    !RootTunForwarding.ensure(dynamicDownstreams.names, dynamicDownstreams.ipv6Enabled)) {
                    shutdown()
                    return "ROOT TUN: cannot install Android hotspot forwarding exceptions"
                }
                if (dynamicDownstreams?.blockIpv6 == true && !RootTetherIpv6Guard.ensure(dynamicDownstreams.names)) {
                    shutdown()
                    return "ROOT tether: cannot verify IPv6 forwarding guard"
                }
                if (DataStore.configurationStore.getBoolean("startOnBoot", false)) {
                    // Keep boot files independent of the live JSON. A managed ROOT TUN
                    // boots with lo only until native downstream reconciliation is available.
                    val bootCore = if (managed && mode == Mode.TUN)
                        withTunDownstreams(cacheConfig, null) else effectiveConfig
                    val bootDaemon = com.google.gson.JsonParser.parseString(
                        buildConfig(mode, RootBoot.exec(File(core.path)).absolutePath, tproxyPort,
                            ipv6, bootCore, flowToken, useMatcher,
                            downstreams = if (gatewayDnsPort != null) emptyList() else null,
                            serviceControl = serviceControl, tetherDnsHijack = gatewayDnsPort != null,
                            gatewayDnsPort = gatewayDnsPort)
                    ).asJsonObject.apply {
                        addProperty("coreConfigPath", RootBoot.bootCoreConfigPath)
                    }
                    val bootPolicy = if (managed) buildString {
                        appendLine("mode ${mode.wire}")
                        appendLine("ipv6 ${if (ipv6) 1 else 0}")
                        appendLine("block6 ${if (!ipv6 && DataStore.rootDisableSystemIpv6) 1 else 0}")
                        val selectors = tetherSelectors() + if (DataStore.tetherWifi) listOf("swlan+", "wifi+") else emptyList()
                        (selectors + if (mode == Mode.TUN) configuredTunInterfaces() else emptyList())
                            .filter { interfaceSelector.matches(it) && it.removeSuffix("+") !in internalInterfaces }
                            .distinct().forEach { appendLine("select $it") }
                        ignoredInterfaces().forEach { appendLine("ignore $it") }
                    } else null
                    // Marker strings are inserted structurally and replaced only by
                    // validated kernel-derived values by the boot monitor.
                    val templateCore = if (managed && mode == Mode.TUN && core.bundled) {
                        val json = com.google.gson.JsonParser.parseString(withLogFile(bootCore)).asJsonObject
                        managedTunInbound(json)?.let { inbound ->
                            inbound.add("include_interface", strings(listOf("lo", "__NF_BOOT_IFACES__")))
                            val old = inbound.get("route_exclude_address")?.let {
                                if (it.isJsonArray) it.asJsonArray.map { item -> item.asString } else listOf(it.asString)
                            }.orEmpty()
                            inbound.add("route_exclude_address", strings(old + "__NF_BOOT_LOCAL__"))
                            gson.toJson(json)
                        }
                    } else null
                    val templateDaemon = if (templateCore != null || gatewayDnsPort != null) bootDaemon.deepCopy().apply {
                        addProperty("coreConfigPath", RootBoot.runtimeBootCorePath)
                        getAsJsonObject("network").add("hotspotInterfaces", strings(listOf("__NF_BOOT_IFACES__")))
                    } else null
                    val bootError = runCatching {
                        RootBoot.publish(withLogFile(bootCore), gson.toJson(bootDaemon), bootPolicy,
                            templateCore, templateDaemon?.let { gson.toJson(it) })
                    }
                        .getOrElse { it.message ?: "boot snapshot write failed" }
                    bootError?.let { Logs.w("ROOT boot: snapshot not published: $it") }
                    if (managed && mode == Mode.TUN)
                        Logs.i("ROOT boot: managed TUN starts phone-only; autonomous templates=${templateCore != null}")
                }
                hotTetherEnabled = managed && daemonDownstreams != null &&
                    mode in listOf(Mode.TPROXY, Mode.TUN2SOCKS, Mode.BPF2SOCKS) &&
                    serviceControl.get("enabled")?.asBoolean != true
                appliedDownstreams = dynamicDownstreams
                startedWaiting = waiting
                startLogForward(mode)
                if (!waiting && mode == Mode.TUN && managed) startTunDiagnostics(dynamicDownstreams ?: DownstreamSnapshot(emptyList(), emptyList()), ipv6)
                return null
            }
            if (phase == "failed") break
            // The first fast poll can race daemon/control-socket initialization.
            // Give it the initial 2s window before interpreting stopped/no PID as failure.
            if (SystemClock.elapsedRealtime() - waitStarted >= 2000L &&
                (phase == "stopped" || !alive())) break
        }
        mark("readiness failed")
        val out = RootShell.run("tail -n 15 ${RootShell.q("$logDir/asteriskd.out")}; echo ---; tail -n 25 ${RootShell.q(logPath)}; echo ---; tail -n 15 ${RootShell.q("$logDir/error.log")}").out
        val safe = io.nekohasekai.sagernet.ktx.LogRedactor.text(
            "asteriskd: ${last.ifBlank { "did not become ready" }}\n$out"
        )
        Logs.w(safe)
        shutdown()
        return safe
    }

    /** the daemon, the core and the helpers must not fight over a port */
    private fun portConflict(mode: Mode): String? {
        val ports = mutableListOf("local proxy" to DataStore.mixedPort)
        when (mode) {
            Mode.TPROXY -> ports += "tproxy" to DataStore.rootTproxyPort
            Mode.TUN2SOCKS -> ports += "socks5" to DataStore.socksPort
            Mode.BPF2SOCKS -> { ports += "socks5" to DataStore.socksPort; ports += "bpf2socks" to DataStore.bpfBridgePort }
            else -> {}
        }
        for (i in ports.indices) for (j in i + 1 until ports.size) {
            if (ports[i].second == ports[j].second) return "Port ${ports[i].second} is used by both ${ports[i].first} and ${ports[j].first}: change it in Settings → Local proxy"
        }
        return null
    }

    fun status(): String = RootShell.run("${RootShell.q(binary)} status 2>&1", timeoutMs = 5_000).out.trim()

    fun phase(): String? = runCatching {
        val result = control("status", 5000) ?: return null
        if (result.get("code")?.asString != "ok") return null
        val snapshot = result.getAsJsonObject("snapshot") ?: return null
        if (snapshot.get("owner")?.asString != OWNER ||
            (snapshot.get("supervisorPid")?.asInt ?: 0) <= 0) return null
        snapshot.get("phase")?.asString
    }.getOrNull()

    private fun alive(): Boolean =
        RootShell.run("pgrep -x libasteriskd.so >/dev/null", timeoutMs = 3_000).ok

    private fun control(command: String, timeout: Long): JsonObject? {
        val response = RootShell.run("${RootShell.q(binary)} $command", timeoutMs = timeout)
        return runCatching {
            if (response.code !in listOf(0, 3)) return null
            com.google.gson.JsonParser.parseString(response.out).asJsonObject.getAsJsonObject("result")
        }.getOrNull()
    }

    /**
     * Wait for the native cleanup acknowledgement and disappearance of the control socket.
     * Do not kill an arbitrary asteriskd by process name: that skips cleanup and can strand
     * TC/BPF hooks or Android offload settings.
     */
    // State is telemetry, not an ownership token. Never use its child PIDs
    // to kill processes or infer that an arbitrary kernel object is ours.
    private fun cleanupEvidence(): RootCleanupEvidence {
        val path = RootShell.q(statePath)
        val result = RootShell.run(
            "if [ -L $path ]; then exit 1; elif [ -f $path ]; then cat $path; elif [ -e $path ]; then exit 1; else echo NF_STATE_ABSENT; fi",
            timeoutMs = 5000,
        )
        return RootCleanupEvidence.parse(result.ok, result.out, OWNER)
    }

    private fun clearAppTetherRules(): Boolean {
        // Both families/guards must be attempted even when one fails.
        val forwarding = RootTunForwarding.clear()
        val ipv6 = RootTetherIpv6Guard.clear()
        return forwarding && ipv6
    }

    @Synchronized
    private fun shutdownVerified(forStartup: Boolean = false): Boolean {
        if (!RootBoot.quiesceMonitor()) return false
        hotTetherEnabled = false
        appliedDownstreams = null
        stopTunDiagnostics()
        stopLogForward()
        val before = control("status", 5000) ?: return false
        if (before.get("code")?.asString == "not_running") {
            val evidence = cleanupEvidence()
            val appClean = clearAppTetherRules()
            if (!appClean) return false
            if (evidence == RootCleanupEvidence.STOPPED || evidence == RootCleanupEvidence.NO_STATE) return true
            // A vanished socket is not a cleanup ACK. Preserve the existing
            // startup recovery path only: native startup reconciles its owned
            // catalog before applying new rules. Do not call an explicit stop
            // successful when prior native state is incomplete or unreadable.
            Logs.w("ROOT supervisor absent: persisted cleanup evidence=$evidence; " +
                if (forStartup) "native startup reconciliation required" else "cleanup not confirmed")
            return forStartup
        }
        val snapshot = before.get("snapshot")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val pid = snapshot.get("supervisorPid")?.asInt ?: return false
        if (snapshot.get("owner")?.asString != OWNER || pid <= 0) {
            Logs.w("ROOT supervisor: refusing to stop a foreign owner")
            return false
        }
        val owned = RootShell.run(
            "tr '\\000' '\\n' </proc/$pid/cmdline | grep -Fx -e ${RootShell.q(daemonConfigPath)} -e ${RootShell.q(RootBoot.bootConfigPath)} -e ${RootShell.q(RootBoot.runtimeBootConfigPath)} >/dev/null",
            timeoutMs = 3000
        )
        if (!owned.ok) {
            Logs.w("ROOT supervisor: cannot verify previous process ownership")
            return false
        }
        // Remove only our tagged exceptions before the native TUN is torn down.
        RootTunForwarding.clear()
        val result = control("shutdown", 30000) ?: return false
        val code = result.get("code")?.asString
        if (code != "ok" && code != "not_running") return false
        if (code == "ok" && result.get("snapshot")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("phase")?.asString != "stopped") return false
        repeat(6) {
            if (control("status", 5000)?.get("code")?.asString == "not_running") {
                // Do not trust the pre-shutdown forwarding check: netd may
                // rebuild chains while the native supervisor stops.
                val appClean = clearAppTetherRules()
                val evidence = cleanupEvidence()
                val confirmed = appClean && evidence == RootCleanupEvidence.STOPPED
                if (confirmed) Logs.i("ROOT supervisor: previous session cleaned and stopped")
                else Logs.w("ROOT supervisor: post-stop verification failed: appClean=$appClean state=$evidence")
                return confirmed
            }
            Thread.sleep(250)
        }
        return false
    }

    /** Ends the cycle and the supervisor; it removes its own rules/routes/devices first. */
    fun shutdown(): Boolean {
        val clean = runCatching { shutdownVerified() }.getOrElse {
            Logs.w(it)
            false
        }
        if (!clean) {
            Logs.w("ROOT supervisor: cleanup not confirmed; no forced kill")
            // Log forwarding has already stopped. Preserve the native shutdown
            // diagnostics now, before a later start rotates/truncates logPath.
            val tail = RootShell.run(
                "tail -n 30 ${RootShell.q(logPath)}",
                timeoutMs = 3000
            )
            if (tail.ok && tail.out.isNotBlank())
                Logs.w("ROOT native shutdown diagnostics:\n${tail.out}")
        }
        return clean
    }
}
