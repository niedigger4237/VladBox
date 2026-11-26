package io.nekohasekai.sagernet.fmt

/** A matching default strategy makes a DNS route-rule override redundant.
 * Keep real overrides until a behavior-preserving migration is available.
 * Do not apply this to outbound domain_resolver or route resolve actions.
 */
internal object DnsRuleStrategy {
    fun overrideOrNull(rule: String?, default: String?): String? =
        rule?.takeIf { it.isNotBlank() && it != default }
}
