package io.nekohasekai.sagernet.ui.compose

data class DashboardTrafficSample(val elapsedMs: Long, val rx: Long, val tx: Long)

/** Monotonic, bounded speed history. No background polling and no fabricated samples. */
internal object DashboardTrafficHistory {
    const val WINDOW_MS = 60_000L
    private const val GAP_MS = 3000L
    private const val MAX_SAMPLES = 120

    fun visible(samples: List<DashboardTrafficSample>, now: Long): List<DashboardTrafficSample> =
        samples.filter { it.elapsedMs in (now - WINDOW_MS)..now }

    fun append(samples: List<DashboardTrafficSample>, now: Long, rx: Long, tx: Long): List<DashboardTrafficSample> {
        val recent = visible(samples, now)
        val sample = DashboardTrafficSample(now, rx.coerceAtLeast(0), tx.coerceAtLeast(0))
        val previous = recent.lastOrNull()
        // Coalesce duplicate/sub-second callbacks while retaining actual timing.
        val kept = if (previous != null && now / 1000 == previous.elapsedMs / 1000) recent.dropLast(1) else recent
        return (kept + sample).takeLast(MAX_SAMPLES)
    }

    fun position(timestamp: Long, now: Long): Float =
        (1f - (now - timestamp).toFloat() / WINDOW_MS).coerceIn(0f, 1f)

    fun segments(samples: List<DashboardTrafficSample>): List<List<DashboardTrafficSample>> {
        val result = mutableListOf<MutableList<DashboardTrafficSample>>()
        for (sample in samples) {
            val previous = result.lastOrNull()?.lastOrNull()
            if (previous == null || sample.elapsedMs - previous.elapsedMs > GAP_MS)
                result.add(mutableListOf(sample))
            else result.last().add(sample)
        }
        return result
    }
}
