package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class ManagedDnsRulesTest {
    @Test fun orderedRulesAndRemotePause() {
        val direct = ManagedDnsRules.Rule(name = "Direct", domains = "example.org")
        val remote = ManagedDnsRules.Rule(name = "Remote", domains = "example.net", target = "dns-remote")
        val block = ManagedDnsRules.Rule(name = "Block AAAA", queries = "aaaa", target = "block")
        val disabled = direct.copy(id = "disabled", enabled = false)
        val rules = ManagedDnsRules.decode(ManagedDnsRules.encode(listOf(remote, direct, block, disabled)))
        assertEquals(listOf("dns-remote", "dns-direct", null), ManagedDnsRules.options(rules, true).map { it.server })
        val generated = ManagedDnsRules.options(rules, false)
        assertEquals(2, generated.size)
        assertEquals(listOf("example.org"), generated.first().domain_suffix)
        assertEquals("NXDOMAIN", generated.last().rcode)
        assertEquals(listOf("AAAA"), generated.last().query_type)
    }
    @Test fun noEmptyMatchAndValidDomains() {
        assertTrue(runCatching { ManagedDnsRules.validate(ManagedDnsRules.Rule(name = "Empty")) }.isFailure)
        assertTrue(runCatching { ManagedDnsRules.validate(ManagedDnsRules.Rule(name = "Bad", domains = "https://example.org")) }.isFailure)
        val exact = ManagedDnsRules.Rule(name = "Exact", domains = "EXAMPLE.ORG.", match = "exact")
        assertEquals(listOf("example.org"), ManagedDnsRules.options(listOf(exact), true).single().domain)
    }
}
