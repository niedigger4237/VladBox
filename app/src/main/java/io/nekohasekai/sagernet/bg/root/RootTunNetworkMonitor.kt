package io.nekohasekai.sagernet.bg.root

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Core-managed TUN and daemon-managed root modes use verified downstream snapshots.
 * Refresh the published config via the normal service lifecycle on a stable change.
 */
internal class RootTunNetworkMonitor(
    private val mode: RootDaemon.Mode,
    private val applied: RootDaemon.DownstreamSnapshot,
    private val restart: (RootDaemon.DownstreamSnapshot) -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "root-tether-network").apply { isDaemon = true }
    }
    private val monitor = TetherNetworkMonitor { reason -> request(reason) }
    private var pending: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null
    @Volatile private var closed = false
    @Volatile private var restartPending = false
    private var candidate: RootDaemon.DownstreamSnapshot? = null
    private var candidateSince = 0L

    companion object {
        // Across service instances: avoid a restart storm while Android reconfigures the hotspot.
        private const val CONFIRM_MS = 1200L
        private const val MIN_RESTART_INTERVAL_MS = 10000L
        private var lastRestart = -MIN_RESTART_INTERVAL_MS // main thread only
    }

    fun start() {
        monitor.start()
        watchdog = worker.scheduleWithFixedDelay({ request("watchdog") }, 15, 15, TimeUnit.SECONDS)
        request("initial")
    }

    @Synchronized
    private fun request(reason: String, delay: Long = 750) {
        if (closed || restartPending || pending != null) return
        pending = worker.schedule({
            synchronized(this) { pending = null }
            if (!closed && !restartPending) {
                try { reconcile(reason) } catch (e: Exception) {
                    candidate = null
                    Logs.w(e)
                }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun reconcile(reason: String) {
        // No rule installation while native traffic is paused or being cleaned up.
        if (RootDaemon.health() != RootHealth.RUNNING) {
            candidate = null
            return
        }
        val observed = RootDaemon.resolveDownstreams(mode, requireReliable = true, ipv6 = applied.serviceIpv6)
        if (!closed) RootDaemon.reconcileTetherRules(mode, applied, observed)
        if (observed == null || observed == applied) {
            candidate = null
            return
        }
        if (candidate != observed) {
            // A transient interface/address change must not cause a restart.
            candidate = observed
            candidateSince = SystemClock.elapsedRealtime()
            request("confirm downstream change", CONFIRM_MS)
            return
        }
        val remaining = CONFIRM_MS - (SystemClock.elapsedRealtime() - candidateSince)
        if (remaining > 0) {
            request("confirm downstream change", remaining)
            return
        }
        candidate = null
        // Do not undo a stop initiated by asteriskd's own service-control policy.
        if (!RootDaemon.isRunning() || closed) return
        restartPending = true
        Logs.i("ROOT ${mode.wire} downstream changed: $applied -> $observed ($reason)")
        main.post {
            if (!closed) {
                val cooldown = MIN_RESTART_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastRestart)
                if (cooldown > 0) {
                    restartPending = false
                    request("restart cooldown", cooldown)
                } else if (restart(observed)) {
                    lastRestart = SystemClock.elapsedRealtime()
                } else {
                    restartPending = false
                    request("restart deferred", 1500)
                }
            }
        }
    }

    @Synchronized
    fun close() {
        closed = true
        monitor.close()
        pending?.cancel(false)
        watchdog?.cancel(false)
        pending = null
        watchdog = null
        // Let an in-flight read-only root snapshot finish naturally.
        worker.shutdown()
    }
}
