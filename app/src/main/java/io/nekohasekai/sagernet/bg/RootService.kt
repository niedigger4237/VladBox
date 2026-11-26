package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.RootProxyInstance
import io.nekohasekai.sagernet.database.ProxyEntity

/** Root modes: the core runs as an external root process (tun2socks-style TUN or TPROXY). */
class RootService : Service(), BaseService.Interface {
    companion object {
        // All accesses happen on Main. A new RootService must not start while
        // a destroyed instance still owns a native startup/cleanup operation.
        private var pendingCleanup: Job? = null
    }

    internal suspend fun awaitPreviousCleanup() {
        val previous = pendingCleanup ?: return
        previous.join()
        if (pendingCleanup === previous) pendingCleanup = null
    }

    override val data = BaseService.Data(this)
    override val tag: String get() = "SagerNetRootService"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-proxy", true)

    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null

    override fun newProxyInstance(profile: ProxyEntity): ProxyInstance =
        RootProxyInstance(profile, this)

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:root")
            .apply { acquire() }
    }

    override suspend fun startProcesses() {
        (data.proxy as RootProxyInstance).launchRoot()
    }

    override suspend fun beforeStopProcesses() {
        val proxy = data.proxy as? RootProxyInstance ?: return
        check(proxy.stopNative()) { getString(R.string.root_cleanup_unconfirmed) }
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)
    override fun onDestroy() {
        data.destroyed = true
        val startup = data.connectingJob
        val stopping = data.stoppingJob
        val hasProxy = data.proxy != null
        try {
            // Android's onDestroy cannot suspend. Detach the UI and receivers
            // immediately, then finish native cleanup in a serialized job.
            cleanupOnDestroy(deferProcessCleanup = true)
        } finally {
            if (hasProxy) {
                val previous = pendingCleanup
                pendingCleanup = runOnMainDispatcher {
                    previous?.join()
                    if (stopping != null && !stopping.isCompleted) {
                        stopping.join()
                    } else {
                        startup?.cancelAndJoin()
                        try {
                            beforeStopProcesses()
                        } catch (e: Exception) {
                            Logs.w(e)
                        } finally {
                            try { killProcesses() } catch (e: Exception) { Logs.w(e) }
                            data.proxy = null
                        }
                    }
                }
            }
            super.onDestroy()
        }
    }
}
