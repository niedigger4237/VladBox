package io.nekohasekai.sagernet.bg.root

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

    val binary: File get() = File(SagerNet.application.applicationInfo.nativeLibraryDir, "libasteriskd.so")
    val runDir get() = "${RootCore.BASE}/run"
    val configPath get() = "$runDir/config.json"
    val daemonConfigPath get() = "$runDir/asteriskd.json"
    val statePath get() = "$runDir/asteriskd.state"
    val logDir get() = "$runDir/logs"
    val logPath get() = "$logDir/asteriskd.log"

    enum class Mode(val wire: String) { TUN("tun"), TPROXY("tproxy") }

    private val PHASE = Regex("\"phase\"\\s*:\\s*\"([a-z-]+)\"")
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    private fun strings(list: List<String>) = JsonArray().also { a -> list.forEach { a.add(it) } }
    private fun ints(list: List<Int>) = JsonArray().also { a -> list.forEach { a.add(it) } }

    /** uids of the apps selected in "per-app proxy" (empty = no restriction) */
    private fun appUids(): List<Int> {
        val pm = SagerNet.application.packageManager
        return DataStore.individual.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { runCatching { pm.getApplicationInfo(it, 0).uid }.getOrNull() }
            .distinct().sorted()
    }

    fun buildConfig(mode: Mode, corePath: String, tproxyPort: Int, ipv6: Boolean): String {
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
        val perApp = mode == Mode.TPROXY && DataStore.proxyApps
        val uids = if (perApp) appUids() else emptyList()
        root.add("network", JsonObject().apply {
            addProperty("enableIpv6", ipv6)
            addProperty("disableSystemIpv6", false)
            addProperty("enableLocalDns", false)
            addProperty("enableFakeDns", false)
            add("fakeDnsIpv4Pool", JsonNull.INSTANCE)
            add("ignoredInterfaces", strings(emptyList()))
            add("virtualInterfaces", strings(emptyList()))
            add("hotspotInterfaces", strings(emptyList()))
            add("proxyPrivateCidrs", strings(emptyList()))
            add("bypassPrivateCidrs", strings(emptyList()))
            add("appPolicy", JsonObject().apply {
                addProperty(
                    "mode",
                    if (!perApp || uids.isEmpty()) "global" else if (DataStore.bypass) "blacklist" else "whitelist"
                )
                add("uids", ints(if (perApp) uids else emptyList()))
                add("bypassUids", ints(emptyList()))
                add("directCidrPathV4", JsonNull.INSTANCE)
                add("directCidrPathV6", JsonNull.INSTANCE)
            })
        })

        root.add("modeOptions", JsonObject().apply {
            if (mode == Mode.TPROXY) addProperty("transparentPort", tproxyPort) else add("transparentPort", JsonNull.INSTANCE)
            if (mode == Mode.TUN) addProperty("tunnelName", TUN_DEVICE) else add("tunnelName", JsonNull.INSTANCE)
        })
        root.add("matcher", JsonNull.INSTANCE)
        root.add("helper", JsonNull.INSTANCE)

        fun wifiRule() = JsonObject().apply {
            addProperty("enabled", false)
            add("ssids", strings(emptyList()))
            add("bssids", strings(emptyList()))
        }
        root.add("serviceControl", JsonObject().apply {
            addProperty("enabled", false)
            add("schedule", JsonObject().apply {
                addProperty("enabled", false)
                addProperty("startCron", "")
                addProperty("stopCron", "")
            })
            add("wifi", JsonObject().apply {
                addProperty("enabled", false)
                add("connectStart", wifiRule())
                add("connectStop", wifiRule())
                add("disconnectStart", wifiRule())
                add("disconnectStop", wifiRule())
            })
            add("keyguard", JsonObject().apply {
                addProperty("enabled", false)
                addProperty("lockStart", false)
                addProperty("lockStop", false)
                addProperty("unlockStart", false)
                addProperty("unlockStop", false)
            })
        })
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

    private var tailer: Thread? = null

    /** forwards core/daemon log lines into the in-app log (Logs -> neko.log) while the service runs */
    fun startLogForward() {
        stopLogForward()
        tailer = Thread({
            val offsets = HashMap<String, Long>()
            val files = listOf(coreLogPath, logPath, "$logDir/error.log")
            try {
                while (!Thread.currentThread().isInterrupted) {
                    for (f in files) {
                        val off = offsets[f] ?: 0L
                        val r = RootShell.run("[ -f ${RootShell.q(f)} ] && tail -c +${off + 1} ${RootShell.q(f)} | head -c 65536", timeoutMs = 4_000)
                        if (r.out.isNotEmpty()) {
                            offsets[f] = off + r.out.toByteArray().size
                            r.out.lineSequence().filter { it.isNotBlank() }.forEach { Libcore.nekoLogPrintln(it) }
                        }
                    }
                    Thread.sleep(1500)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Logs.w("log forward stopped: $e")
            }
        }, "root-log-forward").apply { isDaemon = true; start() }
    }

    fun stopLogForward() {
        tailer?.interrupt()
        tailer = null
    }

    /**
     * Writes the configs, starts asteriskd and waits until the core is up.
     * @return null on success, otherwise a readable error
     */
    fun start(mode: Mode, core: RootCore.Active, singBoxConfig: String, tproxyPort: Int, ipv6: Boolean): String? {
        if (!binary.exists()) return "libasteriskd.so is not part of this build"

        val stage = File(SagerNet.application.noBackupFilesDir, "root-stage").apply { mkdirs() }
        val cfg = File(stage, "config.json").apply { writeText(withLogFile(singBoxConfig)) }
        val dcfg = File(stage, "asteriskd.json").apply { writeText(buildConfig(mode, core.path, tproxyPort, ipv6)) }
        val assets = SagerNet.application.externalAssets
        try {
            val prep = RootShell.run(
                "mkdir -p ${RootShell.q(logDir)}",
                "rm -f ${RootShell.q(coreLogPath)} ${RootShell.q(logPath)} ${RootShell.q("$logDir/error.log")}",
                // the geoip:/geosite: rule-sets are read from the core's working directory (patch 0002)
                "for f in geoip.db geosite.db; do [ -f ${RootShell.q(assets)}/\$f ] && cp -f ${RootShell.q(assets)}/\$f ${RootShell.q(runDir)}/\$f; done",
                "cp -f ${RootShell.q(cfg)} ${RootShell.q(configPath)} && chmod 600 ${RootShell.q(configPath)}",
                "cp -f ${RootShell.q(dcfg)} ${RootShell.q(daemonConfigPath)} && chmod 600 ${RootShell.q(daemonConfigPath)}",
                "chmod 755 ${RootShell.q(binary)} 2>/dev/null; true",
            )
            if (!prep.ok) return prep.err.ifBlank { "cannot prepare $runDir" }
        } finally {
            stage.deleteRecursively()
        }

        RootCore.check(core.path, File(configPath))?.let { return "sing-box check: $it" }

        // a leftover supervisor of ours must be gone first
        shutdown()
        val r = RootShell.run(
            "nohup ${RootShell.q(binary)} start --config ${RootShell.q(daemonConfigPath)} </dev/null >${RootShell.q("$logDir/asteriskd.out")} 2>&1 &",
            timeoutMs = 10_000,
        )
        if (!r.ok) return r.err.ifBlank { "failed to launch asteriskd" }

        startLogForward()
        // poll `status` until running, or fail with the daemon's own words
        val deadline = System.currentTimeMillis() + 25_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            val st = status()
            last = st
            val phase = PHASE.find(st)?.groupValues?.get(1)
            if (phase == "running") return null
            if (phase == "failed" || phase == "stopped") break
            if (!alive()) break
        }
        val out = RootShell.run("tail -n 15 ${RootShell.q("$logDir/asteriskd.out")}; echo ---; tail -n 25 ${RootShell.q(logPath)}; echo ---; tail -n 15 ${RootShell.q("$logDir/error.log")}").out
        Logs.w("asteriskd start failed: $last\n$out")
        shutdown()
        return "asteriskd: ${last.ifBlank { "did not become ready" }}\n$out"
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
