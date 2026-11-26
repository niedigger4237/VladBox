package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.root.RootCore
import io.nekohasekai.sagernet.bg.root.RootDaemon
import io.nekohasekai.sagernet.bg.root.RootShell
import io.nekohasekai.sagernet.bg.root.RootHealth
import io.nekohasekai.sagernet.bg.root.RootHealthConfirmation
import io.nekohasekai.sagernet.bg.root.ServiceControl
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    @Volatile private var started = false
    internal var startWaitingOverride: Boolean? = null
    internal var waitingAtStart = false
        private set
    private var serviceControlArmed = false
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
        val fingerprint = RootCore.fingerprint(core.path)
        Logs.i("root core: ${core.label} (${core.path}); source=${if (core.bundled) "bundled" else if (core.patched) "patched" else "external"}; sha256=$fingerprint")

        mark("core fingerprint")

        val mode = when (DataStore.serviceMode) {
            Key.MODE_ROOT_TPROXY -> RootDaemon.Mode.TPROXY
            Key.MODE_ROOT_TUN2SOCKS -> RootDaemon.Mode.TUN2SOCKS
            Key.MODE_ROOT_BPF2SOCKS -> RootDaemon.Mode.BPF2SOCKS
            else -> RootDaemon.Mode.TUN
        }
        val ipv6 = DataStore.ipv6Mode != io.nekohasekai.sagernet.IPv6Mode.DISABLE
        val managed = !profile.isRawCoreConfig()
        val policy = ServiceControl.toJson()
        val monitorOnly = startWaitingOverride ?: ServiceControl.monitorRequested()
        if (monitorOnly && !ServiceControl.hasStartRule(policy))
            throw IllegalStateException("Service Control requires at least one valid start condition")
        serviceControlArmed = policy.get("enabled").asBoolean
        val manageDnsCache = managed && profile.type != ProxyEntity.TYPE_CONFIG &&
            (DataStore.dnsRootCacheStorage == "persistent" || DataStore.dnsRootCacheGeneration.isNotEmpty())
        fun cacheScope(active: RootCore.Active, hash: String): String? {
            if (!manageDnsCache || !active.bundled || DataStore.dnsEngineMode != "modern" ||
                DataStore.dnsRootCacheStorage != "persistent" ||
                config.selectorGroupId >= 0 || config.externalIndex.any { it.chain.isNotEmpty() } ||
                !hash.matches(Regex("[0-9a-fA-F]{64}"))) return null
            return runCatching {
                io.nekohasekai.sagernet.fmt.PersistentDnsPolicy.scope(
                    config.config, profile.id, io.nekohasekai.sagernet.database.RouteProfiles.selectedId(),
                    "root:${mode.wire}:$hash:${DataStore.dnsRootCacheGeneration}"
                )
            }.getOrNull()
        }
        var err = RootDaemon.start(mode, core, config.config, DataStore.rootTproxyPort, ipv6, managed, policy, monitorOnly,
            manageDnsCache = manageDnsCache, dnsCacheScope = cacheScope(core, fingerprint))
        if (err != null && !core.bundled && RootCore.bundled.exists()) {
            // an installed (unpatched / incompatible) core must not leave the user without root mode
            Logs.w("core ${core.label} failed: $err\nretrying with the bundled core")
            val fallback = RootCore.Active(RootCore.bundled.absolutePath, "bundled", true)
            err = RootDaemon.start(
                mode, fallback, config.config, DataStore.rootTproxyPort, ipv6, managed, policy, monitorOnly,
                manageDnsCache = manageDnsCache, dnsCacheScope = cacheScope(fallback, RootCore.fingerprint(fallback.path))
            )
        }
        if (err != null) throw IllegalStateException(err)
        waitingAtStart = RootDaemon.startedWaiting
        started = true
        mark("daemon ready")
    }

    private var rootLooper: RootTrafficLooper? = null
    private var rootScope: kotlinx.coroutines.CoroutineScope? = null

    override fun launch() {
        super.launch()
        startMonitoring()
    }

    /** Called by RootService: the blocking su/readiness commands run on IO.
     * Cancellation joins the startup before the service attempts shutdown. */
    internal suspend fun launchRoot() {
        launchPlugins()
        withContext(Dispatchers.IO) { startCore() }
        startMonitoring()
    }

    private fun startMonitoring() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        rootScope = scope
        rootLooper = service?.let { RootTrafficLooper(it.data, scope) }
        if (!waitingAtStart) rootLooper?.start()
        if (started) scope.launch { monitorHealth() }
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
            delay(if (serviceControlArmed) 2000L else 10000L)
            val state = RootDaemon.health()
            if (serviceControlArmed && state in listOf(RootHealth.RUNNING, RootHealth.INACTIVE)) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val owner = service
                    if (owner != null && owner.data.proxy === this@RootProxyInstance && started) {
                        if (state == RootHealth.INACTIVE && owner.data.state == BaseService.State.Connected) {
                            rootLooper?.stop()
                            owner.data.changeState(BaseService.State.Waiting)
                            owner.data.notification?.postServiceControlWaiting(true)
                            Logs.i("Service Control: waiting for next start condition")
                        } else if (state == RootHealth.RUNNING && owner.data.state == BaseService.State.Waiting) {
                            owner.data.changeState(BaseService.State.Connected)
                            owner.data.notification?.postServiceControlWaiting(false)
                            rootLooper?.start()
                            // Native policy starts the core independently of Android's
                            // connectivity callbacks. Reconcile tethering immediately.
                            tunNetworkMonitor?.onServiceResumed()
                            Logs.i("Service Control: service resumed")
                        }
                    }
                }
            }
            if (!confirmation.observe(state)) continue
            // Never automatically resurrect a crashing core/helper, including
            // an experimental Flow Metadata session.
            val stopped = withContext(kotlinx.coroutines.Dispatchers.Main) {
                val owner = service
                if (owner != null && owner.data.proxy === this@RootProxyInstance &&
                    started && owner.data.state in listOf(BaseService.State.Connected, BaseService.State.Waiting)) {
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

    private fun stopMonitoring() {
        tunNetworkMonitor?.close()
        tunNetworkMonitor = null
        rootLooper?.stop()
        rootLooper = null
        rootScope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancel()
        rootScope = null
    }

    /** Stop the native session before closing the app-side plugin pool.
     * Never retry a failed native shutdown synchronously from close(). */
    internal suspend fun stopNative(): Boolean {
        stopMonitoring()
        if (!started) return true
        return withContext(Dispatchers.IO + NonCancellable) {
            try {
                RootDaemon.shutdown()
            } finally {
                started = false
            }
        }
    }

    override fun close() {
        stopMonitoring()
        // Fallback for callers outside RootService; the normal service paths
        // always await stopNative() before close().
        try {
            if (started) {
                try { RootDaemon.shutdown() } finally { started = false }
            }
        } finally {
            super.close()
        }
    }
}
