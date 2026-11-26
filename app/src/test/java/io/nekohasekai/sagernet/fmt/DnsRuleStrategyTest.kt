package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsRuleStrategyTest {
    @Test fun removesOnlyRedundantOverrides() {
        for (strategy in listOf("ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6")) {
            assertNull(DnsRuleStrategy.overrideOrNull(strategy, strategy))
            assertEquals(strategy, DnsRuleStrategy.overrideOrNull(strategy, null))
        }
        assertNull(DnsRuleStrategy.overrideOrNull(null, "prefer_ipv4"))
        assertNull(DnsRuleStrategy.overrideOrNull("", "prefer_ipv4"))
        assertEquals("ipv4_only", DnsRuleStrategy.overrideOrNull("ipv4_only", "prefer_ipv4"))
        assertEquals("prefer_ipv6", DnsRuleStrategy.overrideOrNull("prefer_ipv6", "prefer_ipv4"))
    }
}
