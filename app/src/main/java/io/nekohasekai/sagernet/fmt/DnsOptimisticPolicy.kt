package io.nekohasekai.sagernet.fmt

/** Opt-in stale-answer window; no config key is emitted when disabled. */
internal object DnsOptimisticPolicy {
    private val windows = setOf("30s", "5m", "1h", "24h", "72h")

    fun options(modern: Boolean, forTest: Boolean, window: String): Map<String, Any>? =
        if (modern && !forTest && window in windows)
            mapOf("enabled" to true, "timeout" to window)
        else null
}
