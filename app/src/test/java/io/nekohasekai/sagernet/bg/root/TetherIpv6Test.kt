package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.assertEquals
import org.junit.Test

class TetherIpv6Test {
    @Test fun temporaryAddressesShareStablePrefix() {
        val names = setOf("wlan2")
        val first = TetherIpv6.parsePrefixes(names, """
            44: wlan2 inet6 2001:db8:1234:1::1/64 scope global
            44: wlan2 inet6 2001:db8:1234:1::abcd/64 scope global temporary
            2: wlan0 inet6 2001:db8:ffff::1/64 scope global
        """.trimIndent())
        val second = TetherIpv6.parsePrefixes(names, "44: wlan2 inet6 2001:db8:1234:1::abcd/64 scope global temporary")
        assertEquals(1, first.size)
        assertEquals(first, second)
        assertEquals("2001:db8:1234:1:0:0:0:0/64", first.single().subnet)
    }

    @Test fun rejectsUnusableAndMalformedAddresses() {
        val prefixes = TetherIpv6.parsePrefixes(setOf("rndis0"), """
            3: rndis0@usb0 inet6 fd12:3456::1/64 scope global
            3: rndis0 inet6 fe80::1/64 scope link
            3: rndis0 inet6 fd12:9999::1/64 scope global tentative
            3: rndis0 inet6 fd12:aaaa::1/64 scope global dadfailed
            3: rndis0 inet6 2001:::1/64 scope global
            3: rndis0 inet6 fd12:bbbb::1/99999999999999999 scope global
        """.trimIndent())
        assertEquals(listOf(TetherIpv6.Down("rndis0", "fd12:3456:0:0:0:0:0:0/64")), prefixes)
    }
}
