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

    fun buildConfig(mode: Mode, corePath: String, tproxyPort: Int, ipv6: Boolean, singBoxConfig: String, flowToken: String? = null, matcher: Boolean = false): String {
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
            val shared = if (mode == Mode.TUN) tunInterfacesFromConfig(singBoxConfig) else tetherSelectors()
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
        if (!binary.exists()) return "libasteriskd.so is not part of this build"
        if (mode == Mode.TUN2SOCKS && !hevBinary.exists()) return "libhevtun.so (hev-socks5-tunnel) is not part of this build"
        if (mode == Mode.BPF2SOCKS && !bpfBinary.exists()) return "libbpf2socks.so is not part of this build"

        RootBoot.stage()
        mark("boot staging")
        val stage = File(SagerNet.application.noBackupFilesDir, "root-stage").apply { mkdirs() }
        val useFlow = managed && core.bundled && DataStore.rootFlowMetadata &&
            mode in listOf(Mode.TUN2SOCKS, Mode.BPF2SOCKS)
        val flowToken = if (useFlow) newFlowToken() else null
        val effectiveConfig = if (flowToken != null) withFlowMetadata(singBoxConfig, flowToken) else singBoxConfig
        if (useFlow) Logs.i("ROOT flow metadata: enabled")
        val useMatcher = matcherAvailable(mode, ipv6)
        val cfg = File(stage, "config.json").apply { writeText(withLogFile(effectiveConfig)) }
        val dcfg = File(stage, "asteriskd.json").apply {
            writeText(buildConfig(mode, RootBoot.exec(File(core.path)).absolutePath, tproxyPort, ipv6, effectiveConfig, flowToken, useMatcher))
        }
        mark("generate configs")
        try {
            val prep = RootShell.run(
                "mkdir -p ${RootShell.q(logDir)}",
                "rm -f ${RootShell.q(coreLogPath)} ${RootShell.q(logPath)} ${RootShell.q("$logDir/error.log")}",
                "rm -f ${RootShell.q(runDir)}/geoip.db ${RootShell.q(runDir)}/geosite.db",
                "cp -f ${RootShell.q(cfg)} ${RootShell.q(configPath)} && chmod 600 ${RootShell.q(configPath)}",
                "cp -f ${RootShell.q(dcfg)} ${RootShell.q(daemonConfigPath)} && chmod 600 ${RootShell.q(daemonConfigPath)}",
                "chmod 755 ${RootShell.q(binary)} ${RootShell.q(hevBinary)} ${RootShell.q(bpfBinary)} ${RootShell.q(matcherBinary)} 2>/dev/null; true",
            )
            if (!prep.ok) return prep.err.ifBlank { "cannot prepare $runDir" }
        } finally {
            stage.deleteRecursively()
        }

        mark("install configs")
        portConflict(mode)?.let { return it }
        RootCore.check(core.path, File(configPath))?.let { return "sing-box check: $it" }
        mark("validate config")

        // a leftover supervisor of ours must be gone first
        shutdown()
        mark("cleanup previous daemon")
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

    /** Ends the cycle and the supervisor; it removes its own rules/routes/devices first. */
    fun shutdown() {
        stopLogForward()
        RootShell.run("${RootShell.q(binary)} shutdown >/dev/null 2>&1; true", timeoutMs = 20_000)
        // belt and braces: a wedged supervisor must not keep the rules
        RootShell.run("pkill -x libasteriskd.so 2>/dev/null; true", timeoutMs = 3_000)
    }
}
