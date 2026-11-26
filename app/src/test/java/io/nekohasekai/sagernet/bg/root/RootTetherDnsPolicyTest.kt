package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.*
import org.junit.Test

class RootTetherDnsPolicyTest {
    private val valid = """{"route":{"rules":[{"action":"sniff","inbound":["tproxy-in"]},{"action":"hijack-dns","protocol":["dns"]},{"action":"hijack-dns","port":[53]}]}}"""
    @Test fun requiresExplicitEligibleCapture() {
        assertTrue(RootTetherDnsPolicy.enabled(true, true, true, true, false, valid))
        assertFalse(RootTetherDnsPolicy.enabled(false, true, true, true, false, valid))
        assertFalse(RootTetherDnsPolicy.enabled(true, false, true, true, false, valid))
        assertFalse(RootTetherDnsPolicy.enabled(true, true, false, true, false, valid))
        assertFalse(RootTetherDnsPolicy.enabled(true, true, true, false, false, valid))
        assertFalse(RootTetherDnsPolicy.enabled(true, true, true, true, true, valid))
        assertFalse(RootTetherDnsPolicy.enabled(true, true, true, true, false, valid.replace("[53]", "[853]")))
        assertFalse(RootTetherDnsPolicy.enabled(true, true, true, true, false, valid.replace("\"port\":[53]", "\"port\":[53],\"inbound\":[\"mixed-in\"]")))
        assertFalse(RootTetherDnsPolicy.enabled(true, true, true, true, false, valid.replace("\"action\":\"sniff\"", "\"action\":\"route\"")))
    }
}
