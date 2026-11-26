package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.root.RootCore
import io.nekohasekai.sagernet.bg.root.RootDaemon
import io.nekohasekai.sagernet.bg.root.RootShell
import io.nekohasekai.sagernet.bg.root.RootHealth
import io.nekohasekai.sagernet.bg.root.RootHealthConfirmation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import io.nekohasekai.sagernet.bg.root.RootTunNetworkMonitor
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.ktx.Logs

/**
 * Runs the profile in an external sing-box executed as root instead of the in-process core.
 * asteriskd (RootDaemon) starts the core and owns all routing/firewall state:
 *  - Key.MODE_ROOT_TUN:    tun inbound (auto_route + auto_redirect) created by sing-box, device asterisk0
 *  - Key.MODE_ROOT_TPROXY: tproxy inbound + asteriskd's iptables/policy-routing rules
 *  - Key.MODE_ROOT_TUN2SOCKS / MODE_ROOT_BPF2SOCKS: the mixed (SOCKS) inbound fed by hev-socks5-tunnel / bpf2socks
 */
class RootProxyInstance(profile: ProxyEntity, service: BaseService.Interface?) :
    ProxyInstance(profile, service) {

    private var started = false
    private var tunNetworkMonitor: RootTunNetworkMonitor? = null

    override suspend fun loadConfig() {
        // no in-process core: `box` stays uninitialised
    }

    override fun startCore() {
        val start = android.os.SystemClock.elapsedRealtime()
        var last = start
        fun mark(stage: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            Logs.i("ROOT startup: $stage ${now - last} ms (total ${now - start} ms)")
            last = now
        }
        if (!RootShell.isAvailable()) throw IllegalStateException("Root access was denied")

        mark("su access")
        val core = RootCore.resolve() ?: throw IllegalStateException("No sing-box core for root mode")
        mark("resolve core")
        Logs.i("root core: ${core.label} (${core.path}); source=${if (core.bundled) "bundled" else if (core.patched) "patched" else "external"}; sha256=${RootCore.fingerprint(core.path)}")

        mark("core fingerprint")

        val mode = when (DataStore.serviceMode) {
            Key.MODE_ROOT_TPROXY -> RootDaemon.Mode.TPROXY
            Key.MODE_ROOT_TUN2SOCKS -> RootDaemon.Mode.TUN2SOCKS
            Key.MODE_ROOT_BPF2SOCKS -> RootDaemon.Mode.BPF2SOCKS
            else -> RootDaemon.Mode.TUN
        }
        val ipv6 = DataStore.ipv6Mode != io.nekohasekai.sagernet.IPv6Mode.DISABLE
        val managed = !profile.isRawCoreConfig()
        var err = RootDaemon.start(mode, core, config.config, DataStore.rootTproxyPort, ipv6, managed)
        if (err != null && !core.bundled && RootCore.bundled.exists()) {
            // an installed (unpatched / incompatible) core must not leave the user without root mode
            Logs.w("core ${core.label} failed: $err\nretrying with the bundled core")
            err = RootDaemon.start(
                mode, RootCore.Active(RootCore.bundled.absolutePath, "bundled", true),
                config.config, DataStore.rootTproxyPort, ipv6, managed
            )
        }
        if (err != null) throw IllegalStateException(err)
        started = true
        mark("daemon ready")
    }

    private var rootLooper: RootTrafficLooper? = null
    private var rootScope: kotlinx.coroutines.CoroutineScope? = null

    override fun launch() {
        super.launch()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        rootScope = scope
        if (started) scope.launch { monitorHealth() }
        rootLooper = service?.let { RootTrafficLooper(it.data, scope) }
        rootLooper?.start()
        val initial = RootDaemon.appliedDownstreams
        val activeMode = DataStore.serviceMode
        val mode = when (activeMode) {
            Key.MODE_ROOT_TUN -> RootDaemon.Mode.TUN
            Key.MODE_ROOT_TPROXY -> RootDaemon.Mode.TPROXY
            Key.MODE_ROOT_TUN2SOCKS -> RootDaemon.Mode.TUN2SOCKS
            Key.MODE_ROOT_BPF2SOCKS -> RootDaemon.Mode.BPF2SOCKS
            else -> null
        }
        if (started && initial != null && mode != null) {
            tunNetworkMonitor = RootTunNetworkMonitor(mode, initial) {
                val owner = service
                if (owner != null && owner.data.proxy === this && started &&
                    owner.data.state == BaseService.State.Connected &&
                    DataStore.serviceMode == activeMode) {
                    // Preserve the normal cleanup/startup ordering, including the external daemon.
                    owner.stopRunner(restart = true)
                    true
                } else false
            }.also { it.start() }
        }
    }

    private suspend fun monitorHealth() {
        val confirmation = RootHealthConfirmation()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(10000)
            val state = RootDaemon.health()
            if (!confirmation.observe(state)) continue
            // Never automatically resurrect a crashing core/helper, including
            // an experimental Flow Metadata session.
            val stopped = withContext(kotlinx.coroutines.Dispatchers.Main) {
                val owner = service
                if (owner != null && owner.data.proxy === this@RootProxyInstance &&
                    started && owner.data.state == BaseService.State.Connected) {
                    Logs.w("ROOT supervisor health: confirmed terminal state $state")
                    owner.stopRunner(restart = false, msg = "ROOT supervisor stopped or failed")
                    true
                } else false
            }
            if (stopped) return
            confirmation.reset()
        }
    }

    fun urlTest(url: String, timeoutMs: Int): Int = RootClashApi.urlTest(url, timeoutMs)

    override fun close() {
        tunNetworkMonitor?.close()
        tunNetworkMonitor = null
        rootLooper?.stop()
        rootLooper = null
        rootScope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancel()
        rootScope = null
        // asteriskd removes its rules before it stops the core
        if (started) {
            RootDaemon.shutdown()
            started = false
        }
        super.close()
    }
}
