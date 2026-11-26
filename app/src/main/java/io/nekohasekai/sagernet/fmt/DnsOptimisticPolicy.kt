package io.nekohasekai.sagernet.fmt

/** Opt-in stale-answer window; no config key is emitted when disabled. */
internal object DnsOptimisticPolicy {
    private val windows = setOf("30s", "5m", "1h", "24h", "72h")

    fun options(modern: Boolean, forTest: Boolean, window: String): Any? =
        when {
            !modern || forTest -> null
            window == "default" -> true // Inherit the core's default stale window (3 days in 1.14).
            window in windows -> mapOf("enabled" to true, "timeout" to window)
            else -> null
        }
}
