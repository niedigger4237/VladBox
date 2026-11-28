package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class DashboardRulePolicyTest {
    @Test fun distinguishesSourceAndDestinationConditions() {
        assertEquals("Rule set (SRS)", DashboardRulePolicy.label("default", "rule_set=[geosite-ru geoip-ru]"))
        assertEquals("Source IP CIDR", DashboardRulePolicy.label("default", "source_ip_cidr=192.168.1.0/24"))
        assertEquals("Destination IP CIDR", DashboardRulePolicy.label("default", "ip_cidr=10.0.0.0/8"))
        assertEquals("Inverted rule", DashboardRulePolicy.label("default", "!(domain=example.org)"))
        assertEquals("Logical rule", DashboardRulePolicy.label("logical", "domain=a || domain=b"))
        assertEquals("Unconditional rule", DashboardRulePolicy.label("default", ""))
    }

    @Test fun separatesActionsAndOutboundTags() {
        assertEquals("Bypass", DashboardRulePolicy.action("route(bypass)").label)
        assertEquals("Direct", DashboardRulePolicy.action("route(direct,override-port=443)").label)
        assertEquals("server-DE", DashboardRulePolicy.action("route(server-DE)").label)
        assertEquals("DNS Resolve", DashboardRulePolicy.action("resolve(remote,ipv4_only)").label)
        assertEquals("DNS Hijack", DashboardRulePolicy.action("hijack-dns").label)
        assertEquals(DashboardRulePolicy.Kind.BLOCK, DashboardRulePolicy.action("reject(drop)").kind)
        assertEquals(DashboardRulePolicy.Kind.OTHER, DashboardRulePolicy.action("bypass()").kind)
    }
}
