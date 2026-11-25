package io.nekohasekai.sagernet.bg

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class TunAddressPlanTest {
    @Test fun manualSubnetsKeepNetworkBits() {
        val plan = TunAddressPlan.fromManualSubnets("10.253.0.128/25", "fd12:3456::fc/126", true)
        assertEquals("10.253.0.129/25", plan.cidr4)
        assertEquals("10.253.0.130", plan.dns4)
        assertEquals(InetAddress.getByName("fd12:3456::fd"), InetAddress.getByName(plan.ipv6))
        assertEquals(InetAddress.getByName("fd12:3456::fe"), InetAddress.getByName(plan.dns6))
        assertEquals(126, plan.ipv6Prefix)
    }

    @Test fun ipv4Validation() {
        for (cidr in listOf("10.0.0.0/24", "172.19.0.0/28", "192.168.1.252/30"))
            assertNull(cidr, TunAddressPlan.validationError(cidr, false))
        for (cidr in listOf("172.19.0.1/28", "198.18.0.0/24", "8.8.8.0/24", "10.0.0.0/31", "10.0.0.0/23", "10.0.0.256/28", "localhost/28", "010.0.0.0/28"))
            assertNotNull(cidr, TunAddressPlan.validationError(cidr, false))
    }

    @Test fun ipv6Validation() {
        for (cidr in listOf("fd00::/64", "fdfe:dcba:9876::/126", "fd12::fc/126"))
            assertNull(cidr, TunAddressPlan.validationError(cidr, true))
        for (cidr in listOf("fd00::1/64", "fc00::/64", "fe80::/64", "2001:db8::/64", "fd00::/127", "fd00::/63", "::ffff:10.0.0.0/126"))
            assertNotNull(cidr, TunAddressPlan.validationError(cidr, true))
    }

    @Test fun disabledIpv6IgnoresUnusedManualField() {
        val plan = TunAddressPlan.fromManualSubnets("172.19.0.0/28", "invalid", false)
        assertEquals("172.19.0.1/28", plan.cidr4)
        assertEquals("172.19.0.2", plan.dns4)
    }
}
