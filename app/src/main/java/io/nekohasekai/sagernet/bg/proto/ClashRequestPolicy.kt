package io.nekohasekai.sagernet.bg.proto

/** Bounds the local control plane separately from latency measurements. */
internal object ClashRequestPolicy {
    const val CONTROL_WORKERS = 2
    const val PROBE_WORKERS = 5
    const val CONTROL_DEADLINE_MS = 12_000L

    fun isProbe(path: String, method: String): Boolean =
        method == "GET" && path.substringBefore('?').endsWith("/delay")
}
