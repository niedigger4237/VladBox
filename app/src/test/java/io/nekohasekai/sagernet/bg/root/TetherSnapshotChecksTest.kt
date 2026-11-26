package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.*
import org.junit.Test

class TetherSnapshotChecksTest {
    @Test fun ambiguousUplinkIsNotEmptySnapshot() {
        val selectors = listOf("wlan+")
        val reserved = Regex("tun.*")
        assertNull(TetherSnapshotChecks.selected(setOf("wlan1"), selectors, setOf("wlan1"), reserved))
        assertEquals(emptySet<String>(), TetherSnapshotChecks.selected(emptySet(), selectors, setOf("wlan1"), reserved))
        assertEquals(setOf("wlan1"), TetherSnapshotChecks.selected(setOf("wlan1"), selectors, setOf("wlan0"), reserved))
        assertNull(TetherSnapshotChecks.selected(setOf("tun0"), listOf("tun+"), emptySet(), reserved))
    }

    @Test fun everyActiveDownstreamNeedsExactlyOneSubnet() {
        val names = setOf("ap0", "rndis0")
        val valid = mapOf("ap0" to listOf("192.168.43.0/24", "192.168.43.0/24"), "rndis0" to listOf("192.168.42.0/24"))
        assertTrue(TetherSnapshotChecks.uniqueSubnets(names, valid))
        assertFalse(TetherSnapshotChecks.uniqueSubnets(names, valid - "rndis0"))
        assertFalse(TetherSnapshotChecks.uniqueSubnets(names, valid + ("ap0" to listOf("192.168.43.0/24", "192.168.44.0/24"))))
    }

    @Test fun missingReturnRouteOrPolicyIsUnhealthy() {
        val rows = listOf("17900: from all iif ap0 lookup 17900", "17899: from all to 192.168.43.0/24 iif tun0 lookup 17900", "192.168.43.0/24 dev ap0 scope link")
        val down = listOf("ap0" to "192.168.43.0/24")
        assertTrue(TetherSnapshotChecks.hasRouting(rows, "tun0", 17900, 17900, down))
        for (i in rows.indices) assertFalse(TetherSnapshotChecks.hasRouting(rows.filterIndexed { j, _ -> j != i }, "tun0", 17900, 17900, down))
        assertFalse(TetherSnapshotChecks.hasRouting(rows.map { it.replace("dev ap0", "dev wlan0") }, "tun0", 17900, 17900, down))
    }

    @Test fun ipv6CompressedPrefixesCompareNumerically() {
        val rows = listOf("17900: from all iif ap0 lookup 17900", "17899: from all to fd12:34::/64 iif tun0 lookup 17900", "fd12:34::/64 dev ap0 metric 1024")
        assertTrue(TetherSnapshotChecks.hasRouting(rows, "tun0", 17900, 17900, listOf("ap0" to "fd12:0034:0:0:0:0:0:0/64")))
    }
}
