package io.nekohasekai.sagernet.bg.proto

import android.app.ActivityManager
import android.content.Context
import io.nekohasekai.sagernet.SagerNet
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Limits temporary core creation, not live-core Clash API requests. */
internal object TestConcurrencyPolicy {
    const val MAX_WORKERS = 8
    const val LOW_RAM_WORKERS = 3
    const val HEAVY_WORKERS = 1

    fun limit(lowRam: Boolean): Int = if (lowRam) LOW_RAM_WORKERS else MAX_WORKERS

    fun workerCount(requested: Int, profiles: Int, lowRam: Boolean): Int =
        requested.coerceIn(1, limit(lowRam)).coerceAtMost(profiles.coerceAtLeast(0))

    fun isHeavy(types: List<String>, externalPlugins: Boolean, raw: Boolean): Boolean {
        if (externalPlugins || raw || types.any { it.equals("naive", ignoreCase = true) }) return true
        // Direct/bypass/reject/DNS are generated support outbounds, not hops.
        return types.count { it.lowercase(java.util.Locale.ROOT) !in setOf("direct", "block", "dns") } > 1
    }
}

internal object TestCoreAdmission {
    val lowRamDevice: Boolean by lazy {
        (SagerNet.application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true
    }
    private val all by lazy { Semaphore(TestConcurrencyPolicy.limit(lowRamDevice)) }
    private val heavy = Semaphore(TestConcurrencyPolicy.HEAVY_WORKERS)

    // Acquire the heavy lane first so queued heavy probes cannot consume all
    // permits needed by ordinary probes. Both permits are cancellation-safe.
    suspend fun <T> withSlot(isHeavy: Boolean, block: suspend () -> T): T =
        if (isHeavy) heavy.withPermit { all.withPermit { block() } }
        else all.withPermit { block() }
}
