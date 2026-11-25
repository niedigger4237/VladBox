package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RootTetherIdentityTest {
    @Test fun canonicalHotspotNetworkUsesActualPrefix() {
        assertEquals("172.25.73.0/24", RootTetherIdentity.ipv4Network("172.25.73.171/24"))
        assertEquals("192.168.43.128/25", RootTetherIdentity.ipv4Network("192.168.43.171/25"))
        assertEquals("10.42.3.64/26", RootTetherIdentity.ipv4Network("10.42.3.65/26"))
        assertEquals(null, RootTetherIdentity.ipv4Network("192.168.43.1/0"))
        assertEquals(null, RootTetherIdentity.ipv4Network("192.168.999.1/24"))
        assertEquals(null, RootTetherIdentity.ipv4Network("127.0.0.1/24"))
    }

    @Test fun ignoresUpstreamAndNeighborChanges() {
        val dump = """
            12: wlan0: <UP> mtu 1500
            15: ap0: <UP> mtu 1500
            15: ap0 inet 192.168.43.1/24 scope global ap0
            12: wlan0 inet 192.168.1.4/24 scope global wlan0
            15: ap0 inet6 fe80::1/64 scope link
        """.trimIndent()
        // Input normally combines ip -o link with ip -4 -o addr; ignore other lines.
        val selected = listOf("ap0")
        val ipv4Dump = dump.lines().filterNot { "inet6" in it }.joinToString("\n")
        assertEquals(listOf("15:ap0:", "15:ap0:192.168.43.1/24"), RootTetherIdentity.parse(selected, ipv4Dump))
        assertEquals(RootTetherIdentity.parse(selected, ipv4Dump),
            RootTetherIdentity.parse(selected, ipv4Dump.replace("192.168.1.4", "192.168.1.5")))
    }

    @Test fun detectsSameNameWithDifferentIndexOrPrefix() {
        val before = "15: ap0: <UP>\n15: ap0 inet 192.168.43.1/24"
        val selected = listOf("ap0")
        val original = RootTetherIdentity.parse(selected, before)
        assertNotEquals(original, RootTetherIdentity.parse(selected, before.replace("15:", "19:")))
        assertNotEquals(original, RootTetherIdentity.parse(selected, before.replace("/24", "/28")))
    }

    @Test fun handlesPeerSuffixAndExactInterfaceName() {
        val dump = "22: rndis0@if21: <UP>\n22: rndis0 inet 192.168.42.1/24\n23: rndis01: <UP>"
        assertEquals(listOf("22:rndis0:", "22:rndis0:192.168.42.1/24"),
            RootTetherIdentity.parse(listOf("rndis0"), dump))
    }
}
