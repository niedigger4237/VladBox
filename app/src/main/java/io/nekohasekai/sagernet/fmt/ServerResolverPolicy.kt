package io.nekohasekai.sagernet.fmt

/** Keep bootstrap direct by default; preserve an explicit outbound resolver.
 * Raw full configurations bypass the managed builder.
 */
internal object ServerResolverPolicy {
    fun options(forTest: Boolean, strategy: String, explicit: Any?): Any {
        if (forTest) return "dns-direct"
        if (explicit != null && (explicit !is String || explicit.isNotBlank())) return explicit
        return if (strategy.isEmpty()) "dns-direct"
        else mapOf("server" to "dns-direct", "strategy" to strategy)
    }

    /** The existing first hop may have been emitted under a selector/single profile tag. */
    fun effectiveHopTag(proposed: String, existing: String?): String = existing ?: proposed
}
