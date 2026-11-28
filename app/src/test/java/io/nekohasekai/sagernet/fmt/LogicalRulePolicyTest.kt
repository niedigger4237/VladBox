package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class LogicalRulePolicyTest {
    @Test fun routeActionsRemainOutsideMatchChildren() {
        val flat = mapOf("domain_suffix" to listOf("example.org"), "port" to listOf(443),
            "port_range" to listOf("1000:2000"), "action" to "route", "outbound" to "proxy")
        val result = LogicalRulePolicy.route("or", flat)
        assertEquals("logical", result["type"])
        assertEquals("or", result["mode"])
        assertEquals("proxy", result["outbound"])
        val children = result["rules"] as List<*>
        assertEquals(2, children.size)
        assertEquals(mapOf("port" to listOf(443), "port_range" to listOf("1000:2000")), children[1])
        assertEquals(true, LogicalRulePolicy.route("not", flat)["invert"])
        assertEquals("and", LogicalRulePolicy.route("not", flat)["mode"])
    }
    @Test fun markerIsNotSentToCoreAndDnsDerivationIsDisabled() {
        val value = LogicalRulePolicy.update("""{"ip_version":4}""", "and")
        assertEquals("and", LogicalRulePolicy.mode(value))
        assertEquals("""{"ip_version":4}""", LogicalRulePolicy.clean(value))
        assertFalse(RouteAdvancedConditions.deriveDns(value, "tcp"))
        assertTrue(runCatching { LogicalRulePolicy.update("""{"action":"sniff"}""", "or") }.isFailure)
        assertTrue(runCatching { LogicalRulePolicy.route("and", mapOf("outbound" to "proxy")) }.isFailure)
    }
    @Test fun dnsEvaluateKeepsActionAndTagAtTopLevel() {
        val flat = mapOf("domain" to listOf("example.org"), "query_type" to listOf("A"),
            "action" to "evaluate", "server" to "dns-direct", "tag" to "eval", "rewrite_ttl" to 0L)
        val result = LogicalRulePolicy.dns("and", flat)
        assertEquals("evaluate", result["action"])
        assertEquals("eval", result["tag"])
        assertEquals(0L, result["rewrite_ttl"])
        assertEquals(2, (result["rules"] as List<*>).size)
        val rule = ManagedDnsRules.Rule(name = "Response", operation = "respond",
            responseFrom = "eval", logicalMode = "or")
        assertTrue(runCatching { ManagedDnsRules.validate(rule) }.isFailure)
    }
}
