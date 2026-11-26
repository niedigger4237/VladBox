package io.nekohasekai.sagernet.bg.root

/** Pure scheduling decisions; not used for health, forwarding or restart timing. */
internal object RootIdlePolling {
    fun skipEmptyRouteDump(
        requireReliable: Boolean,
        alreadyEmpty: Boolean,
        dumpOk: Boolean,
        manualEmpty: Boolean,
        selectedEmpty: Boolean,
    ): Boolean = requireReliable && alreadyEmpty && dumpOk && manualEmpty && selectedEmpty

    // Keep active log forwarding at the original 1.5s and cap idle at 12s.
    // Errors are not evidence that no log data is available.
    fun nextLogIdlePolls(previous: Int, readOk: Boolean, received: Boolean): Int =
        if (!readOk || received) 0 else (previous + 1).coerceIn(0, 3)

    fun logDelayMs(idlePolls: Int): Long = 1500L shl idlePolls.coerceIn(0, 3)
}
