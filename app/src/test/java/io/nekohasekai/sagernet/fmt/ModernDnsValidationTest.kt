package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModernDnsValidationTest {
    private fun check(rules: String) = ModernDnsValidation.check(JsonParser.parseString(
        """{"dns":{"strategy":"prefer_ipv4","servers":[{"type":"fakeip","tag":"dns-fake"}],"rules":$rules}}"""
    ).asJsonObject)

    private fun rejects(rules: String, expected: String) {
        try {
            check(rules)
            fail("Expected invalid Modern DNS configuration")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(expected))
        }
    }

    @Test fun generatedRulesAndTaggedResponseAccepted() {
        check("""[
            {"domain":["resolver.example"],"server":"dns-direct"},
            {"domain_suffix":["example"],"action":"evaluate","server":"dns-remote","tag":"candidate"},
            {"domain_suffix":["example"],"match_response":"candidate","response_rcode":"NOERROR","action":"respond"},
            {"domain_suffix":["example"],"server":"dns-remote"},
            {"inbound":["tun-in"],"query_type":["A","AAAA"],"server":"dns-fake"}
        ]""")
    }

    @Test fun anonymousResponseAccepted() {
        check("""[{"action":"evaluate","server":"dns-remote"},{"match_response":true,"ip_accept_any":true,"action":"respond"}]""")
    }

    @Test fun legacyStrategyRejectedEvenInNestedRule() {
        rejects("""[{"server":"dns-direct","strategy":"ipv4_only"}]""", "legacy DNS rule strategy")
        rejects("""[{"type":"logical","mode":"and","rules":[{"domain":["example"],"strategy":"ipv4_only"}],"server":"dns-direct"}]""", "legacy DNS rule strategy")
    }

    @Test fun responseReferenceMustPrecedeUse() {
        rejects("""[{"action":"respond"}]""", "preceding untagged evaluate")
        rejects("""[{"match_response":"later","action":"respond"},{"action":"evaluate","server":"dns-remote","tag":"later"}]""", "undefined or later")
        rejects("""[{"action":"evaluate","server":"dns-remote","tag":"a"},{"action":"evaluate","server":"dns-remote","tag":"a"}]""", "duplicate evaluate")
    }

    @Test fun rejectsFakeEvaluateAndLegacyAddressFilter() {
        rejects("""[{"action":"evaluate","server":"dns-fake"}]""", "FakeIP")
        rejects("""[{"ip_cidr":["10.0.0.0/8"],"server":"dns-direct"}]""", "require match_response")
    }

    @Test fun rejectsTaggedLogicalRespond() {
        rejects("""[{"action":"evaluate","server":"dns-remote","tag":"a"},{"type":"logical","mode":"and","rules":[{"match_response":"a","ip_accept_any":true}],"action":"respond"}]""", "non-logical")
    }
}
