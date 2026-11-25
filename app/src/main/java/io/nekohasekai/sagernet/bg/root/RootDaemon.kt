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

    internal data class DownstreamSnapshot(val names: List<String>, val identity: List<String>)

    // Android reports active downstreams explicitly; never guess from all wlan* devices.
    internal fun resolveDownstreams(mode: Mode, requireReliable: Boolean = false): DownstreamSnapshot? {
        val manual = if (mode == Mode.TUN) configuredTunInterfaces() else emptyList()
        val selectors = tetherSelectors().toMutableList()
        if (DataStore.tetherWifi) selectors += listOf("swlan+", "wifi+")
        val ignored = ignoredInterfaces()
        fun matches(name: String, selector: String): Boolean =
            if (selector.endsWith("+")) name.startsWith(selector.dropLast(1)) else name == selector
        if (manual.isEmpty() && selectors.isEmpty()) return DownstreamSnapshot(emptyList(), emptyList())
        val shortDump = RootShell.run("dumpsys tethering --short", timeoutMs = 5000)
        val dump = if (shortDump.ok && shortDump.out.contains("TetheredState")) shortDump
            else RootShell.run("dumpsys tethering", timeoutMs = 5000)
        // Both families must succeed; a partial route dump cannot prove that an interface is not WAN.
        val routes = RootShell.run("ip -4 route show table all && ip -6 route show table all", timeoutMs = 5000)
        if (!routes.ok) {
            Logs.w("ROOT ${mode.wire}: cannot verify upstream interfaces; skipping unverified downstreams")
            return if (requireReliable) null else DownstreamSnapshot(emptyList(), emptyList())
        }
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
        val upstream = routes.out.lineSequence().filter { it.trimStart().startsWith("default ") }
            .flatMap { Regex("""\bdev\s+([A-Za-z0-9_.-]{1,15})(?:\s|$)""").findAll(it).map { match -> match.groupValues[1] } }
            .toSet()
        val auto = if (dump.ok) active.filter { name -> selectors.any { matches(name, it) } } else emptyList()
        val result = (manual + auto).distinct().filter { name ->
            name !in internalInterfaces && name !in upstream && ignored.none { matches(name, it) }
        }
        if (!requireReliable) Logs.i("ROOT ${mode.wire} downstream: active=$active selected=$result upstream=$upstream dumpOk=${dump.ok} routesOk=${routes.ok}")
        if (!dump.ok) Logs.w("ROOT ${mode.wire}: Android tethering state unavailable; only verified manual downstreams can be used")
        if (!requireReliable && selectors.isNotEmpty() && result.isEmpty()) Logs.i("ROOT ${mode.wire}: no matching active downstream at startup")
        val names = result.sorted()
        if (names.isEmpty()) return DownstreamSnapshot(names, emptyList())
        val links = RootShell.run("ip -o link show && ip -4 -o addr show", timeoutMs = 5000)
        if (!links.ok) return if (requireReliable) null else DownstreamSnapshot(names, emptyList())
        // Include the ifindex and IPv4 gateway/prefix: Android may recreate ap0 using the same name.
        val identity = RootTetherIdentity.parse(names, links.out)
        if (requireReliable && (names - manual.toSet()).any { name -> identity.none { it.substringAfter(':').substringBefore(':') == name } }) return null
        return DownstreamSnapshot(names, identity)
    }

    @Volatile internal var appliedDownstreams: DownstreamSnapshot? = null
        private set

    internal fun isRunning(): Boolean = PHASE.find(status())?.groupValues?.get(1) == "running"

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
                local + if (ipv6) listOf("fe80::/10", "ff02::/16") else emptyList()
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

    fun buildConfig(mode: Mode, corePath: String, tproxyPort: Int, ipv6: Boolean, singBoxConfig: String, flowToken: String? = null, matcher: Boolean = false, downstreams: List<String>? = null): String {
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
            add("bypassPrivateCidrs", strings(if (mode == Mode.TUN) emptyList() else DEFAULT_BYPASS_CIDRS.filter { ipv6 || ':' !in it }))
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

        root.add("serviceControl", ServiceControl.toJson())
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

    /** forwards core/daemon log lines into the in-app log (Logs -> neko.log) while the service runs */
    fun startLogForward() {
        stopLogForward()
        tailer = Thread({
            val offsets = HashMap<String, Long>()
            val files = listOf(coreLogPath, logPath, "$logDir/error.log")
            try {
                val self = Thread.currentThread()
                val pending = HashMap<String, ByteArray>()
                fun active() = !self.isInterrupted && tailer === self
                while (active()) {
                    for (f in files) {
                        if (!active()) break
                        val off = offsets[f] ?: 0L
                        // Base64 preserves exact byte offsets despite RootShell's text/trim API.
                        val r = RootShell.run(
                            "[ -f ${RootShell.q(f)} ] && tail -c +${off + 1} ${RootShell.q(f)} | head -c 65536 | base64",
                            timeoutMs = 4_000
                        )
                        if (!active()) break
                        if (!r.ok || r.out.isBlank()) continue
                        val chunk = runCatching {
                            android.util.Base64.decode(r.out, android.util.Base64.DEFAULT)
                        }.getOrNull() ?: continue
                        offsets[f] = off + chunk.size
                        val data = (pending.remove(f) ?: ByteArray(0)) + chunk
                        val end = data.indexOfLast { it == 10.toByte() } + 1
                        if (end > 0) {
                            String(data, 0, end, Charsets.UTF_8).lineSequence()
                                .filter { it.isNotBlank() }.forEach {
                                    if (active()) Libcore.nekoLogPrintln(it)
                                }
                        }
                        // A log line may span chunks (including UTF-8 characters).
                        // Bound memory for malformed/very large lines.
                        val remainder = data.copyOfRange(end, data.size)
                        if (remainder.size <= 65536) pending[f] = remainder
                        else if (active()) Libcore.nekoLogPrintln(String(remainder, Charsets.UTF_8))
                    }
                    Thread.sleep(1500)
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
    fun start(mode: Mode, core: RootCore.Active, singBoxConfig: String, tproxyPort: Int, ipv6: Boolean, managed: Boolean = false): String? {
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

        RootBoot.stage()?.let { return it }
        mark("boot staging")
        // Never replace a live supervisor's configuration or truncate its logs.
        if (!shutdownVerified()) return "Previous ROOT supervisor did not shut down cleanly"
        mark("cleanup previous daemon")
        val stage = File(SagerNet.application.noBackupFilesDir, "root-stage").apply { mkdirs() }
        val useFlow = managed && core.bundled && DataStore.rootFlowMetadata &&
            mode in listOf(Mode.TUN2SOCKS, Mode.BPF2SOCKS)
        val flowToken = if (useFlow) newFlowToken() else null
        val dynamicDownstreams = if (managed && (mode != Mode.TUN ||
            managedTunInbound(com.google.gson.JsonParser.parseString(singBoxConfig).asJsonObject) != null))
            resolveDownstreams(mode) else null
        val effectiveConfig = when {
            managed && mode == Mode.TUN -> withTunDownstreams(singBoxConfig, dynamicDownstreams)
            flowToken != null -> withFlowMetadata(singBoxConfig, flowToken)
            else -> singBoxConfig
        }
        if (useFlow) Logs.i("ROOT flow metadata: enabled")
        val useMatcher = matcherAvailable(mode, ipv6)
        val cfg = File(stage, "config.json").apply { writeText(withLogFile(effectiveConfig)) }
        // A boot-started native daemon has no Android app monitor. Keep its native prefix
        // selectors for daemon-managed modes when start-on-boot is enabled.
        val daemonDownstreams = if (mode != Mode.TUN &&
            DataStore.configurationStore.getBoolean("startOnBoot", false)) null else dynamicDownstreams?.names
        val dcfg = File(stage, "asteriskd.json").apply {
            writeText(buildConfig(mode, RootBoot.exec(File(core.path)).absolutePath, tproxyPort, ipv6, effectiveConfig, flowToken, useMatcher, daemonDownstreams))
        }
        mark("generate configs")
        try {
            val prep = RootShell.run(
                "set -e",
                "umask 077",
                "mkdir -p ${RootShell.q(logDir)}",
                "rm -f ${RootShell.q(coreLogPath)} ${RootShell.q(logPath)} ${RootShell.q("$logDir/error.log")}",
                "rm -f ${RootShell.q(runDir)}/geoip.db ${RootShell.q(runDir)}/geosite.db",
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

        // Publication happened only after the previous control socket disappeared.
        val r = RootShell.run(
            "nohup ${RootShell.q(binary)} start --config ${RootShell.q(daemonConfigPath)} </dev/null >${RootShell.q("$logDir/asteriskd.out")} 2>&1 &",
            timeoutMs = 10_000,
        )
        if (!r.ok) return r.err.ifBlank { "failed to launch asteriskd" }

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
            if (phase == "running") {
                mark("wait for readiness")
                appliedDownstreams = dynamicDownstreams
                startLogForward()
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
        Logs.w("asteriskd start failed: $last\n$out")
        shutdown()
        return "asteriskd: ${last.ifBlank { "did not become ready" }}\n$out"
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
    private fun shutdownVerified(): Boolean {
        appliedDownstreams = null
        stopLogForward()
        val before = control("status", 5000) ?: return false
        if (before.get("code")?.asString == "not_running") return true
        val snapshot = before.get("snapshot")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val pid = snapshot.get("supervisorPid")?.asInt ?: return false
        if (snapshot.get("owner")?.asString != OWNER || pid <= 0) {
            Logs.w("ROOT supervisor: refusing to stop a foreign owner")
            return false
        }
        val owned = RootShell.run(
            "tr '\\000' '\\n' </proc/$pid/cmdline | grep -Fx -- ${RootShell.q(daemonConfigPath)} >/dev/null",
            timeoutMs = 3000
        )
        if (!owned.ok) {
            Logs.w("ROOT supervisor: cannot verify previous process ownership")
            return false
        }
        val result = control("shutdown", 30000) ?: return false
        val code = result.get("code")?.asString
        if (code != "ok" && code != "not_running") return false
        if (code == "ok" && result.get("snapshot")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("phase")?.asString != "stopped") return false
        repeat(6) {
            if (control("status", 5000)?.get("code")?.asString == "not_running") {
                Logs.i("ROOT supervisor: previous session cleaned and stopped")
                return true
            }
            Thread.sleep(250)
        }
        return false
    }

    /** Ends the cycle and the supervisor; it removes its own rules/routes/devices first. */
    fun shutdown() {
        if (!shutdownVerified()) Logs.w("ROOT supervisor: cleanup not confirmed; no forced kill")
    }
}
