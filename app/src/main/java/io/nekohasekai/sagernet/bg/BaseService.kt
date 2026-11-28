package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.isRawCoreConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
        // Append: existing AIDL state ordinals stay stable.
        Waiting(true, true, false),
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                Action.SELECT_PROFILE -> applyProfileSelection()
                Action.SELECTOR_CHANGED -> selectorConfigurationChanged(
                    intent.getLongArrayExtra("groups") ?: longArrayOf(),
                    intent.getLongArrayExtra("profiles") ?: longArrayOf()
                )
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (SagerNet.power.isDeviceIdleMode) {
                            if (proxy?.isInitialized() == true) proxy?.box?.sleep()
                        } else {
                            if (proxy?.isInitialized() == true) proxy?.box?.wake()
                            if (DataStore.wakeResetConnections) {
                                io.nekohasekai.sagernet.bg.core.resetCoreConnections()
                            }
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    io.nekohasekai.sagernet.bg.core.resetCoreConnections()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null
        internal var stoppingJob: Job? = null
        internal var destroyed = false
        var networkListenerKey: Any? = null
        var networkReconnectJob: Job? = null
        @Volatile var restartAfterStop = false
        var nextRootStartWaiting: Boolean? = null
        @Volatile var selectorSessionId: Long = 0L

        private fun applyProfileSelection() {
            if (destroyed || !state.canStop) return
            val owner = proxy ?: return
            val selected = DataStore.selectedProxy
            // Queued broadcasts read the latest selection. Duplicate selections
            // must not turn a successful hot switch into a full restart.
            if (selected > 0 && ((state == State.Connecting && owner.profile.id == selected) ||
                    (state != State.Connecting && DataStore.currentProfile == selected))) return
            service.reload()
        }

        private fun selectorConfigurationChanged(groups: LongArray, profiles: LongArray) {
            if (destroyed || !state.canStop) return
            val owner = proxy ?: return
            val affected = if (owner.lastSelectorGroupId >= 0) {
                val config = owner.config
                config.selectorGroupId in groups ||
                    groups.any { it in config.selectorDependencyGroups } ||
                    profiles.any { it in config.selectorDependencyIds || it in config.selectorProfileTags }
            } else {
                // Enabling Selector on a running ordinary group also needs a rebuild.
                owner.profile.groupId in groups &&
                    SagerDatabase.groupDao.getById(owner.profile.groupId)?.isSelector == true
            }
            if (!affected) return
            val selected = DataStore.selectedProxy
            if (selected <= 0 || SagerDatabase.proxyDao.getById(selected) == null) {
                if (DataStore.selectedProxy == selected) DataStore.selectedProxy = 0L
                service.stopRunner(false, (service as Context).getString(R.string.profile_empty))
                return
            }
            // The running outbound list and nested chains are immutable. Bypass
            // hot selection and rebuild through the existing service lifecycle.
            Logs.i("Selector: configuration changed; rebuilding session")
            if (state == State.Waiting && !io.nekohasekai.sagernet.bg.root.ServiceControl.hasStartRule(
                    io.nekohasekai.sagernet.bg.root.ServiceControl.toJson())) {
                service.stopRunner(false)
            } else service.stopRunner(true)
        }

        fun getDashboardProfiles(sessionId: Long): String {
            if (state != State.Connected || destroyed || sessionId == 0L ||
                selectorSessionId != sessionId) return ""
            val owner = proxy ?: return ""
            val ready = if (owner is io.nekohasekai.sagernet.bg.proto.RootProxyInstance)
                owner.isStartedForProbe() else owner.isInitialized()
            if (!ready) return ""
            val config = owner.config
            val profiles = org.json.JSONObject()
            val tags = if (owner.profile.isRawCoreConfig()) emptyMap()
                else if (config.selectorGroupId >= 0) config.selectorProfileTags
                else config.profileTagMap + (owner.profile.id to io.nekohasekai.sagernet.fmt.TAG_PROXY)
            // Cap the response well below the Binder transaction limit.
            for ((id, tag) in tags.entries.take(1500)) {
                val member = config.trafficMap[tag]?.firstOrNull { it.id == id }
                    ?: owner.profile.takeIf { it.id == id } ?: continue
                profiles.put(tag, org.json.JSONObject()
                    .put("name", member.displayName().take(200))
                    .put("protocol", member.displayType().take(100)))
            }
            val snapshot = org.json.JSONObject().put("managedSelector", config.selectorGroupId >= 0)
                .put("profiles", profiles).toString()
            return snapshot.takeIf { it.toByteArray(Charsets.UTF_8).size <= 160_000 }
                ?: org.json.JSONObject().put("managedSelector", config.selectorGroupId >= 0).toString()
        }

        fun getSelectorProbeTag(sessionId: Long, profileId: Long): String {
            if (state != State.Connected || destroyed || sessionId == 0L ||
                selectorSessionId != sessionId) return ""
            val owner = proxy ?: return ""
            // ROOT owns an external daemon and intentionally has no LibCore box.
            val ready = if (owner is io.nekohasekai.sagernet.bg.proto.RootProxyInstance)
                owner.isStartedForProbe() else owner.isInitialized()
            if (!ready) return ""
            val config = owner.config
            val groupId = config.selectorGroupId
            if (groupId < 0) return ""
            val tag = config.selectorProfileTags[profileId] ?: return ""
            val profile = SagerDatabase.proxyDao.getById(profileId) ?: return ""
            val group = SagerDatabase.groupDao.getById(groupId) ?: return ""
            val running = config.trafficMap[tag].orEmpty()
            val saved = running.singleOrNull { it.id == profileId }
            val bean = profile.requireBean()
            if (!io.nekohasekai.sagernet.bg.proto.SelectorProbePolicy.eligible(
                    group.isSelector && profile.groupId == groupId,
                    group.frontProxy > 0 || group.landingProxy > 0,
                    running.map { it.id }.toSet() == setOf(profileId),
                    profile.type == ProxyEntity.TYPE_CHAIN || profile.type == ProxyEntity.TYPE_CONFIG ||
                        profile.needExternal(),
                    !bean.customConfigJson.isNullOrBlank() || !bean.customOutboundJson.isNullOrBlank() ||
                        !owner.profile.requireBean().customConfigJson.isNullOrBlank() ||
                        DataStore.globalCustomConfig.isNotBlank(),
                    saved != null && saved.type == profile.type && saved.requireBean() == bean &&
                        saved.displayName() == profile.displayName(),
                    config.selectorProfileTags.values.count { it == tag } == 1
                )) return ""
            return tag
        }

        fun selectManagedOutbound(sessionId: Long, group: String, tag: String): Int {
            if (state != State.Connected || destroyed || sessionId == 0L ||
                sessionId != selectorSessionId) return -1
            val owner = proxy ?: return -1
            val config = owner.config
            // Only the application-generated top-level selector owns a profile ID.
            if (config.selectorGroupId < 0 || group != io.nekohasekai.sagernet.fmt.TAG_PROXY) return 0
            val id = config.selectorProfileTags.entries.singleOrNull { it.value == tag }?.key ?: return -1
            val profile = SagerDatabase.proxyDao.getById(id) ?: return -1
            if (profile.groupId != config.selectorGroupId ||
                SagerDatabase.groupDao.getById(profile.groupId)?.isSelector != true) return -1
            DataStore.selectedProxy = id
            if (DataStore.currentProfile == id) {
                binder.selectorUpdated(id)
                return 1
            }
            // Reuse the service's validation and restart fallback, including external ROOT cores.
            service.reload()
            return if (state == State.Connected && DataStore.currentProfile == id) 1
            else if (state == State.Stopping || state == State.Connecting || restartAfterStop) 2
            else -1
        }

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            // Service Control may stop and recreate the external core without
            // replacing the Android service. Invalidate all old probe handles.
            if (s == State.Waiting) selectorSessionId = 0L
            if (s == State.Connected && selectorSessionId == 0L)
                selectorSessionId = android.os.SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun getSelectorSessionId(): Long =
            runBlocking(Dispatchers.Main.immediate) {
                data?.takeIf { it.state == State.Connected && !it.destroyed }?.selectorSessionId ?: 0L
            }

        override fun getDashboardProfiles(sessionId: Long): String =
            runBlocking(Dispatchers.Main.immediate) {
                data?.getDashboardProfiles(sessionId).orEmpty()
            }

        override fun getSelectorProbeTag(sessionId: Long, profileId: Long): String =
            runBlocking(Dispatchers.Main.immediate) {
                data?.getSelectorProbeTag(sessionId, profileId).orEmpty()
            }

        override fun selectManagedOutbound(sessionId: Long, group: String, tag: String): Int =
            runBlocking(Dispatchers.Main.immediate) {
                data?.selectManagedOutbound(sessionId, group, tag) ?: -1
            }

        override fun urlTest(): Int {
            val current = data?.proxy ?: error("core not started")
            if (data?.state?.connected != true) error("core not started")
            if (!current.profile.isRawCoreConfig()) {
                return try {
                    runBlocking { io.nekohasekai.sagernet.bg.proto.clashDelay() }
                } catch (e: Exception) {
                    error(Protocols.genFriendlyMsg(e.readableMessage))
                }
            }
            // Raw JSON configs may not expose our authenticated Clash controller.
            val root = current as? io.nekohasekai.sagernet.bg.proto.RootProxyInstance
            if (root != null) {
                try {
                    return io.nekohasekai.sagernet.bg.proto.LatencyPolicy.requirePositive(
                        root.urlTest(DataStore.connectionTestURL, io.nekohasekai.sagernet.bg.proto.LATENCY_TEST_TIMEOUT_MS)
                    )
                } catch (e: Exception) {
                    error(Protocols.genFriendlyMsg(e.readableMessage))
                }
            }
            if (data?.proxy?.isInitialized() != true) {
                error("core not started")
            }
            try {
                return io.nekohasekai.sagernet.bg.proto.LatencyPolicy.requirePositive(
                    data!!.proxy!!.box.urlTest(DataStore.connectionTestURL, io.nekohasekai.sagernet.bg.proto.LATENCY_TEST_TIMEOUT_MS)
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun selectorUpdated(id: Long) = launch {
            broadcast { it.cbSelectorUpdate(id) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            // Validate and apply the same requested profile, even if the UI changes
            // its persisted selection while this reload is being processed.
            val requestedId = DataStore.selectedProxy
            if (requestedId == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            val requested = SagerDatabase.proxyDao.getById(requestedId)
            if (requested == null) {
                if (DataStore.selectedProxy == requestedId) DataStore.selectedProxy = 0L
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            if (canReloadSelector(requested)) {
                val owner = data.proxy
                val ent = requested
                val tag = owner?.config?.selectorProfileTags?.get(ent?.id)
                if (owner != null && ent != null && !tag.isNullOrBlank()) {
                    val accepted = runCatching { owner.box.selectOutbound(tag) }.getOrDefault(false)
                    if (accepted) {
                        val title = ServiceNotification.genTitle(ent)
                        DataStore.currentProfile = ent.id
                        owner.displayProfileName = title
                        data.binder.stateChanged(State.Connected, null)
                        data.binder.selectorUpdated(ent.id)
                        data.binder.launch {
                            if (data.proxy === owner && data.state == State.Connected &&
                                DataStore.currentProfile == ent.id) {
                                data.notification?.postNotificationTitle(title)
                            }
                        }
                        Logs.i("Selector: active profile changed to ${ent.id}")
                        return
                    }
                    Logs.w("Selector: core rejected profile selection; restarting session")
                }
            }
            val s = data.state
            if (s == State.Waiting && !io.nekohasekai.sagernet.bg.root.ServiceControl.hasStartRule(
                    io.nekohasekai.sagernet.bg.root.ServiceControl.toJson())) {
                stopRunner(false)
                return
            }
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        fun newProxyInstance(profile: ProxyEntity): ProxyInstance = ProxyInstance(profile, this)

        fun canReloadSelector(ent: ProxyEntity? = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)): Boolean {
            // ROOT runs an external core; the in-process selector API cannot switch its outbound.
            // Use the normal service restart instead of silently treating the selection as applied.
            val owner = data.proxy ?: return false
            if (owner is io.nekohasekai.sagernet.bg.proto.RootProxyInstance ||
                data.state != State.Connected || !owner.isInitialized()) return false
            val groupId = owner.config.selectorGroupId
            if (groupId < 0) return false
            if (ent == null) return false
            if (ent.id == DataStore.currentProfile || ent.groupId != groupId ||
                SagerDatabase.groupDao.getById(groupId)?.isSelector != true) return false
            val tag = owner.config.selectorProfileTags[ent.id] ?: return false
            // An edited profile must be rebuilt rather than selecting stale runtime options.
            val running = owner.config.trafficMap[tag]?.firstOrNull { it.id == ent.id } ?: return false
            if (running.requireBean() != ent.requireBean() ||
                running.displayName() != ent.displayName()) return false
            // Full-config overrides are merged from the startup profile, not
            // from the selected member. A change requires a fresh config.
            if (!io.nekohasekai.sagernet.fmt.SelectorRoutingPolicy.sameConfigOverride(
                    owner.profile.requireBean().customConfigJson, ent.requireBean().customConfigJson)) return false
            // Explicit rules referencing the original or requested profile may depend
            // on the generated main-profile mapping. Preserve their restart semantics.
            if (SagerDatabase.rulesDao.enabledRules().any {
                    it.outbound == owner.config.mainEntId || it.outbound == ent.id
                }) return false
            return true
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        suspend fun beforeStopProcesses() {}

        fun killProcesses() {
            data.networkReconnectJob?.cancel()
            data.networkReconnectJob = null
            val listener = data.networkListenerKey
            data.networkListenerKey = null
            if (listener != null) runOnDefaultDispatcher {
                DefaultNetworkListener.stop(listener)
            }
            try {
                data.proxy?.close()
            } finally {
                val lock = wakeLock
                wakeLock = null
                if (lock?.isHeld == true) lock.release()
            }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            if (data.destroyed) return
            if (data.state == State.Stopping) {
                // A user stop or permission revocation must win over a pending reconnect.
                if (!restart) {
                    data.restartAfterStop = false
                    data.nextRootStartWaiting = null
                }
                return
            }
            data.restartAfterStop = restart
            val rootOwner = data.proxy as? io.nekohasekai.sagernet.bg.proto.RootProxyInstance
            data.nextRootStartWaiting = if (restart && rootOwner != null) when (data.state) {
                State.Waiting -> true
                State.Connected -> false
                State.Connecting -> rootOwner.startWaitingOverride
                else -> null
            } else null
            data.selectorSessionId = 0L
            if (this is VpnService) Logs.i("VPN service stop: state=${data.state}, restart=$restart, error=${msg != null}")
            data.networkReconnectJob?.cancel()
            data.networkReconnectJob = null
            if (DataStore.baseService === this) DataStore.baseService = null
            if (DataStore.vpnService === this) DataStore.vpnService = null
            this as Service
            data.changeState(State.Stopping)
            // Keep ROOT foreground until native cleanup completes. Other service
            // types retain their existing notification lifecycle.
            val root = this is RootService
            if (!root) removeServiceNotification()

            data.stoppingJob = runOnMainDispatcher {
                var stopMessage = msg
                try {
                    try {
                        data.connectingJob?.cancelAndJoin()
                        beforeStopProcesses()
                    } finally {
                        killProcesses()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    if (root) {
                        // A failed or unconfirmed native shutdown must not
                        // automatically create another ROOT session.
                        data.restartAfterStop = false
                        data.nextRootStartWaiting = null
                        stopMessage = listOfNotNull(msg, e.readableMessage).joinToString("\n")
                    }
                } finally {
                    releaseServiceReceiver()
                    data.proxy = null
                    removeServiceNotification()
                    val resume = data.restartAfterStop && !data.destroyed
                    data.restartAfterStop = false
                    if (!data.destroyed) {
                        data.changeState(State.Stopped, stopMessage)
                        if (resume) startRunner() else stopSelf()
                    }
                }
            }
        }

        fun removeServiceNotification() {
            val notification = data.notification
            data.notification = null
            try { notification?.destroy() } catch (e: Exception) { Logs.w(e) }
        }

        fun releaseServiceReceiver() {
            if (data.closeReceiverRegistered) {
                data.closeReceiverRegistered = false
                try { (this as Context).unregisterReceiver(data.receiver) }
                catch (_: IllegalArgumentException) {}
            }
        }

        fun cleanupOnDestroy(deferProcessCleanup: Boolean = false) {
            data.selectorSessionId = 0L
            data.restartAfterStop = false
            data.networkReconnectJob?.cancel()
            data.networkReconnectJob = null
            data.connectingJob?.cancel()
            removeServiceNotification()
            releaseServiceReceiver()
            if (DataStore.baseService === this) DataStore.baseService = null
            if (DataStore.vpnService === this) DataStore.vpnService = null
            if (!deferProcessCleanup && data.proxy != null) {
                try { killProcesses() } catch (e: Exception) { Logs.w(e) }
                data.proxy = null
            }
            data.changeState(State.Stopped)
            data.binder.close()
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            val owner = data.proxy ?: return
            val key = Any()
            data.networkListenerKey = key
            upstreamInterfaceName = null
            // ROOT uses its own native interface monitor; the app's embedded
            // libcore reset cannot reach the external process.
            val ordinaryVpn = this is VpnService
            val externalRoot = owner is io.nekohasekai.sagernet.bg.proto.RootProxyInstance
            var previous: Network? = null
            var lost = false
            val main = Handler(Looper.getMainLooper())
            DefaultNetworkListener.start(key) { network ->
                val link = network?.let { SagerNet.connectivity.getLinkProperties(it) }
                val isVpn = network?.let {
                    SagerNet.connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
                main.post {
                    if (data.networkListenerKey !== key || data.proxy !== owner || !data.state.canStop || (ordinaryVpn && isVpn)) return@post
                    if (network == null) {
                        if (ordinaryVpn) {
                            SagerNet.underlyingNetwork = null
                            if (data.state == State.Connected) DataStore.vpnService?.updateUnderlyingNetwork()
                            if (previous != null) {
                                lost = true
                                Logs.i("VPN reconnect: upstream lost ($previous); waiting for network")
                            }
                            data.networkReconnectJob?.cancel()
                            data.networkReconnectJob = null
                        } else if (externalRoot && previous != null && !lost) {
                            lost = true
                            Logs.i("ROOT upstream lost; native core monitors interface availability")
                        }
                        return@post
                    }
                    val name = link?.interfaceName ?: return@post
                    SagerNet.underlyingNetwork = network
                    if (ordinaryVpn && data.state == State.Connected) DataStore.vpnService?.updateUnderlyingNetwork()
                    val oldName = upstreamInterfaceName
                    val oldNetwork = previous
                    val restored = lost
                    upstreamInterfaceName = name
                    previous = network
                    lost = false
                    if (externalRoot) {
                        if (oldNetwork != null && (restored || oldNetwork != network || oldName != name)) {
                            Logs.i("ROOT upstream changed: $oldName -> $name, restored=$restored; native core owns socket recovery")
                        }
                        return@post
                    }
                    if (!ordinaryVpn) {
                        if (oldName != null && oldName != name && DataStore.networkChangeResetConnections) {
                            Logs.d("Network changed: $oldName -> $name")
                            io.nekohasekai.sagernet.bg.core.resetCoreConnections()
                        }
                        return@post
                    }
                    if (oldNetwork == null || (!restored && oldNetwork == network && oldName == name)) return@post
                    Logs.i("VPN reconnect: $oldName ($oldNetwork) -> $name ($network), restored=$restored")
                    data.networkReconnectJob?.cancel()
                    if (!DataStore.networkChangeResetConnections) return@post
                    data.networkReconnectJob = data.binder.launch {
                        // Coalesce handovers, then allow a startup already in progress to finish.
                        delay(900)
                        while (data.proxy === owner && data.networkListenerKey === key &&
                            data.state == State.Connecting) delay(250)
                        if (data.proxy === owner && data.networkListenerKey === key &&
                            data.state == State.Connected && !lost && previous == network &&
                            DataStore.networkChangeResetConnections && DataStore.serviceMode == Key.MODE_VPN) {
                            // Raw configurations and external processes may have their own
                            // network lifecycle. Keep their proven full-restart path.
                            val embedded = if (owner.isBoxInitialized())
                                owner.box as? io.nekohasekai.sagernet.bg.core.LibcoreBox else null
                            val refreshInPlace = DataStore.vpnInPlaceNetworkRefresh &&
                                !owner.profile.isRawCoreConfig() &&
                                owner.config.externalIndex.all { it.chain.isEmpty() } &&
                                embedded != null
                            if (refreshInPlace && embedded != null) {
                                try {
                                    withContext(Dispatchers.IO) { embedded.refreshNetwork() }
                                    Logs.i("VPN reconnect: in-place network reset requested on $name ($network)")
                                    data.networkReconnectJob = null
                                    return@launch
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Logs.w("VPN reconnect: in-place reset failed; falling back to restart", e)
                                }
                            }
                            // A newer handover or stop may have arrived during the native call.
                            if (data.proxy !== owner || data.networkListenerKey !== key ||
                                data.state != State.Connected || lost || previous != network ||
                                !DataStore.networkChangeResetConnections || DataStore.serviceMode != Key.MODE_VPN) return@launch
                            data.networkReconnectJob = null
                            Logs.i("VPN reconnect: restarting active session on $name ($network)")
                            stopRunner(restart = true)
                        }
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            DataStore.baseService = this
            data.restartAfterStop = false
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = newProxyInstance(profile)
            if (proxy is io.nekohasekai.sagernet.bg.proto.RootProxyInstance)
                proxy.startWaitingOverride = data.nextRootStartWaiting
            data.nextRootStartWaiting = null
            data.proxy = proxy
            data.selectorSessionId = android.os.SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Action.SELECTOR_CHANGED)
                    addAction(Action.SELECT_PROFILE)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            val rootStartup = proxy is io.nekohasekai.sagernet.bg.proto.RootProxyInstance
            val rootStartedAt = android.os.SystemClock.elapsedRealtime()
            var rootLastMark = rootStartedAt
            fun markRootStartup(stage: String) {
                if (!rootStartup) return
                val now = android.os.SystemClock.elapsedRealtime()
                Logs.i("ROOT service startup: $stage ${now - rootLastMark} ms (total ${now - rootStartedAt} ms)")
                rootLastMark = now
            }
            data.changeState(State.Connecting)
            data.connectingJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                try {
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    // A destroyed ROOT service may still be completing native
                    // cleanup. Wait before touching any processes or configuration.
                    if (this@Interface is RootService) this@Interface.awaitPreviousCleanup()

                    // Clear only our app's log, after previous-session cleanup and
                    // before the new core emits its startup diagnostics. Never clear
                    // Android logcat or erase logs on a mere network reset.
                    if (DataStore.clearLogsOnConnect) {
                        try {
                            libcore.Libcore.nekoLogClear()
                            Logs.i("New session: application log cleared")
                        } catch (e: Exception) {
                            Logs.w("Could not clear application log", e)
                        }
                    }
                    Executable.killAll()    // clean up old processes
                    markRootStartup("cleanup plugins")
                    preInit()
                    markRootStartup("preInit")
                    proxy.init()
                    markRootStartup("build profile config")

                    proxy.processes = GuardedProcessPool {
                        // A delayed plugin failure must not stop a replacement session.
                        if (data.proxy === proxy && data.state != State.Stopping && !data.destroyed) {
                            Logs.w(it)
                            stopRunner(false, it.readableMessage)
                        }
                    }

                    startProcesses()
                    markRootStartup("start processes")
                    // Only a successfully started core (or ready Service Control
                    // supervisor) may replace the last confirmed profile.
                    if (data.proxy !== proxy || data.state != State.Connecting || data.destroyed)
                        return@launch
                    // A selection can be persisted between the initial DB read
                    // and receiver registration, or while native startup is in flight.
                    val latestSelection = DataStore.selectedProxy
                    if (latestSelection != profile.id) {
                        if (latestSelection > 0 && SagerDatabase.proxyDao.getById(latestSelection) != null)
                            stopRunner(true)
                        else stopRunner(false, getString(R.string.profile_empty))
                        return@launch
                    }
                    DataStore.currentProfile = profile.id
                    val waiting = (proxy as? io.nekohasekai.sagernet.bg.proto.RootProxyInstance)?.waitingAtStart == true
                    data.changeState(if (waiting) State.Waiting else State.Connected)
                    if (proxy.config.selectorGroupId >= 0) data.binder.selectorUpdated(profile.id)
                    if (waiting) data.notification?.postServiceControlWaiting(true)
                    markRootStartup(if (waiting) "waiting for conditions" else "connected")

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    if (data.proxy === proxy && data.state == State.Connecting && !data.destroyed)
                        stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    if (data.proxy !== proxy || data.state != State.Connecting || data.destroyed) return@launch
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    // lateInit can fail after the Connected/Waiting transition.
                    if (data.proxy !== proxy || !data.state.canStop || data.destroyed) return@launch
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}