package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.*
import org.junit.Test

class TetherResourceJournalTest {
    @Test fun validatesInterfaceAndResourceOwnership() {
        assertTrue(TetherResourceJournal.iface("wlan2"))
        assertFalse(TetherResourceJournal.iface("lo"))
        assertFalse(TetherResourceJournal.iface("wlan2;id"))
        assertFalse(TetherResourceJournal.iface("wlan+"))
        assertTrue(TetherResourceJournal.suffix("abcdef012345"))
        assertFalse(TetherResourceJournal.suffix("abcdef01234;"))
        assertTrue(TetherResourceJournal.ids(17900, 17998))
        assertFalse(TetherResourceJournal.ids(254, 17998))
        assertFalse(TetherResourceJournal.ids(17900, 17901))
    }

    @Test fun validatesNumericAddressesAndBootIdentity() {
        assertTrue(TetherResourceJournal.ipv4("192.168.43.1"))
        assertFalse(TetherResourceJournal.ipv4("192.168.43.256"))
        assertFalse(TetherResourceJournal.ipv4("example.org"))
        assertTrue(TetherResourceJournal.cidr6("fd00:1234::/64"))
        assertFalse(TetherResourceJournal.cidr6("fd00:1234::/129"))
        assertFalse(TetherResourceJournal.cidr6("fd00:1234::/64;id"))
        assertTrue(TetherResourceJournal.validBoot("12345678-1234-1234-1234-123456789abc"))
        assertFalse(TetherResourceJournal.validBoot("not-a-boot-id"))
    }
}
