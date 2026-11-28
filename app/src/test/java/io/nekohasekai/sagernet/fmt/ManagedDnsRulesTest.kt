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
    @Test fun taggedEvaluateAndRespond() {
        val evaluate = ManagedDnsRules.Rule(id = "one", name = "Evaluate", domains = "example.org", operation = "evaluate")
        val respond = ManagedDnsRules.Rule(name = "Respond", operation = "respond", responseFrom = "one",
            responseCode = "NOERROR", responseCidrs = "192.0.2.0/24, 2001:db8::/32")
        val generated = ManagedDnsRules.options(listOf(evaluate, respond), true)
        assertEquals("evaluate", generated[0].action)
        assertEquals("nf-eval-one", generated[0]._hack_config_map["tag"])
        assertEquals("respond", generated[1].action)
        assertNull(generated[1].server)
        assertEquals("nf-eval-one", generated[1]._hack_config_map["match_response"])
        assertEquals("NOERROR", generated[1]._hack_config_map["response_rcode"])
        assertEquals(listOf("192.0.2.0/24", "2001:db8::/32"), generated[1]._hack_config_map["ip_cidr"])
        assertTrue(runCatching { ManagedDnsRules.encode(listOf(respond, evaluate)) }.isFailure)
        assertTrue(runCatching { ManagedDnsRules.encode(listOf(evaluate.copy(enabled = false), respond)) }.isFailure)
        assertTrue(runCatching { ManagedDnsRules.encode(listOf(respond)) }.isFailure)
        assertTrue(ManagedDnsRules.options(listOf(evaluate.copy(target = "dns-remote"), respond), false).isEmpty())
    }
    @Test fun oldJsonAndInvalidResponseFilters() {
        val old = """[{"id":"old","name":"Old","enabled":true,"domains":"example.org","match":"suffix","queries":"","target":"dns-direct"}]"""
        assertEquals("route", ManagedDnsRules.options(ManagedDnsRules.decode(old), true).single().action)
        val invalid = ManagedDnsRules.Rule(name = "Invalid", domains = "example.org", responseCode = "NXDOMAIN")
        assertTrue(runCatching { ManagedDnsRules.validate(invalid) }.isFailure)
        assertTrue(runCatching { ManagedDnsRules.validate(invalid.copy(responseFrom = "one", responseCidrs = "300.0.0.1/24")) }.isFailure)
    }
}
