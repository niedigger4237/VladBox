package io.nekohasekai.sagernet.fmt

/** Keep the persisted dnsMain values compatible with existing installations. */
internal object DnsRoutingPolicy {
    data class Plan(val useRemote: Boolean, val finalServer: String, val proxyRuleServer: String)

    fun resolve(mode: String, forTest: Boolean): Plan {
        if (forTest || mode == "direct_only") return Plan(false, "dns-direct", "dns-direct")
        // The old builder treated any value other than direct as remote.
        return Plan(true, if (mode == "direct") "dns-direct" else "dns-remote", "dns-remote")
    }
}
