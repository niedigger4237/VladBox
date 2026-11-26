package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DnsRuleStrategyTest {
    @Test fun modernModeUsesGlobalPreferenceAndNeverGeneratesRuleStrategy() {
        assertEquals("prefer_ipv6", DnsRuleStrategy.globalStrategy(true, "ipv4_only", "prefer_ipv6"))
        assertEquals("ipv4_only", DnsRuleStrategy.globalStrategy(false, "ipv4_only", "prefer_ipv6"))
        for (strategy in listOf("prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")) {
            assertNull(DnsRuleStrategy.ruleOverride(true, strategy, "prefer_ipv4"))
        }
        assertEquals("prefer_ipv6", DnsRuleStrategy.ruleOverride(false, "prefer_ipv6", "prefer_ipv4"))
        assertFalse(DnsRuleStrategy.isModern("legacy"))
        assertFalse(DnsRuleStrategy.isModern("unknown"))
        assertTrue(DnsRuleStrategy.isModern("modern"))
    }

    @Test fun modernFakeDnsOnlyHandlesAddressQueriesAndRespectsIpv6Only() {
        assertEquals(listOf("A", "AAAA"), DnsRuleStrategy.fakeQueryTypes(true))
        assertNull(DnsRuleStrategy.fakeQueryTypes(false))
        assertFalse(DnsRuleStrategy.fakeDnsEnabled(true, true, "ipv6_only"))
        assertTrue(DnsRuleStrategy.fakeDnsEnabled(true, false, "ipv6_only"))
        assertTrue(DnsRuleStrategy.fakeDnsEnabled(true, true, "prefer_ipv6"))
        assertFalse(DnsRuleStrategy.fakeDnsEnabled(false, true, "ipv4_only"))
    }

    @Test fun fakeDnsDoesNotRequireLegacyRuleForDualStackOrIpv4Defaults() {
        for (default in listOf(null, "", "prefer_ipv4", "prefer_ipv6", "ipv4_only")) {
            val policy = DnsRuleStrategy.fakeDnsPolicy(default)
            assertNull(policy.ruleOverride)
            assertNull(policy.ipv6Range)
        }
    }

    @Test fun fakeDnsKeepsExplicitExceptionForIpv6OnlyDefault() {
        val policy = DnsRuleStrategy.fakeDnsPolicy("ipv6_only")
        assertEquals("ipv4_only", policy.ruleOverride)
        assertEquals("fc00::/18", policy.ipv6Range)
    }

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
