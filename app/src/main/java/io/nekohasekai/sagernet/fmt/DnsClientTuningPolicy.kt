package io.nekohasekai.sagernet.fmt

/** Managed opt-in DNS tuning. Null inherits the selected core's defaults. */
internal object DnsClientTuningPolicy {
    private val timeouts = setOf("3s", "5s", "10s", "15s", "30s")
    private val capacities = setOf("1024", "2048", "4096", "8192", "16384")

    data class Options(val timeout: String? = null, val cacheCapacity: Int? = null)

    fun options(modern: Boolean, forTest: Boolean, timeout: String, capacity: String): Options {
        if (!modern || forTest) return Options()
        return Options(timeout.takeIf { it in timeouts },
            capacity.takeIf { it in capacities }?.toInt())
    }
}
