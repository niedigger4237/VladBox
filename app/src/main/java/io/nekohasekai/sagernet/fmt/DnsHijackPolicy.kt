package io.nekohasekai.sagernet.fmt

/** Decide managed core sniffing independently of the general sniff toggle. */
internal object DnsHijackPolicy {
    enum class Sniff { NONE, DNS_ONLY, ALL }
    data class Plan(val sniff: Sniff, val protocol: Boolean, val port: Boolean)

    fun plan(mode: String, generalSniff: Boolean): Plan {
        val protocol = mode == "both" || mode == "protocol"
        val port = mode == "both" || mode == "port"
        return Plan(
            when {
                generalSniff -> Sniff.ALL
                protocol -> Sniff.DNS_ONLY
                else -> Sniff.NONE
            },
            protocol,
            port
        )
    }
}
