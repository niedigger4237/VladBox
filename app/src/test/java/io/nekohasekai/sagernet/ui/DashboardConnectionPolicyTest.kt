package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class DashboardConnectionPolicyTest {
    @Test fun classifiesActualLeafAndSelector() {
        val types = mapOf("server" to "VLESS", "custom-direct" to "Direct", "reject" to "Reject")
        assertEquals(DashboardConnectionPolicy.Route.PROXY, DashboardConnectionPolicy.route(listOf("server", "proxy"), types))
        assertEquals(DashboardConnectionPolicy.Route.PROXY, DashboardConnectionPolicy.route(listOf("server"), types))
        assertEquals(DashboardConnectionPolicy.Route.DIRECT, DashboardConnectionPolicy.route(listOf("custom-direct", "proxy"), types))
        assertEquals(DashboardConnectionPolicy.Route.DIRECT, DashboardConnectionPolicy.route(listOf("bypass"), emptyMap()))
        assertEquals(DashboardConnectionPolicy.Route.OTHER, DashboardConnectionPolicy.route(listOf("reject", "proxy"), types))
        assertEquals(DashboardConnectionPolicy.Route.OTHER, DashboardConnectionPolicy.route(listOf("unknown"), emptyMap()))
        assertEquals(DashboardConnectionPolicy.Route.OTHER, DashboardConnectionPolicy.route(emptyList(), types))
    }

    @Test fun formatsAddressWithoutDoubleBrackets() {
        assertEquals("[2001:db8::1]:443", DashboardConnectionPolicy.address("2001:db8::1", "443"))
        assertEquals("[2001:db8::1]:443", DashboardConnectionPolicy.address("[2001:db8::1]", "443"))
        assertEquals("example.org:443", DashboardConnectionPolicy.address("example.org", "443"))
        assertEquals("", DashboardConnectionPolicy.address("", "443"))
    }
}
