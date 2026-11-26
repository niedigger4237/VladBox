package io.nekohasekai.sagernet.fmt

/** A matching default strategy makes a DNS route-rule override redundant.
 * Keep real overrides until a behavior-preserving migration is available.
 * Do not apply this to outbound domain_resolver or route resolve actions.
 */
internal object DnsRuleStrategy {
    fun overrideOrNull(rule: String?, default: String?): String? =
        rule?.takeIf { it.isNotBlank() && it != default }

    data class FakeDnsPolicy(val ruleOverride: String?, val ipv6Range: String?)

    /**
     * The pinned sing-box FakeIP transport returns NOERROR with no answers
     * when the requested address family has no configured range. Removing
     * the IPv6 range therefore replaces the IPv4-only rule for A/AAAA
     * exchanges without changing rule order or redirecting AAAA elsewhere.
     * IPv6-only global strategy needs an explicit exception; the DNS client
     * would reject an A query before calling the FakeIP transport.
     *
     * Unknown default values are deliberately not modernized.
     */
    fun fakeDnsPolicy(default: String?): FakeDnsPolicy =
        when (default) {
            null, "", "prefer_ipv4", "prefer_ipv6", "ipv4_only" ->
                FakeDnsPolicy(null, null)
            else -> FakeDnsPolicy(overrideOrNull("ipv4_only", default), "fc00::/18")
        }
}
