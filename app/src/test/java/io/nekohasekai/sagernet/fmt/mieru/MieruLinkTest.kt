package io.nekohasekai.sagernet.fmt.mieru

import org.junit.Assert.*
import org.junit.Test

class MieruLinkTest {
    @Test fun simpleLinkRoundTrip() {
        val bean = parseMieruSimple("mierus://user:p%40ss@example.org?profile=Test&port=8443&protocol=UDP&mtu=1300")
        val restored = parseMieruSimple(bean.toUri())
        assertEquals("p@ss", restored.password)
        assertEquals("example.org", restored.serverAddress)
        assertEquals("UDP", restored.protocol)
        assertEquals(8443, restored.serverPort.toInt())
        assertEquals(1300, restored.mtu.toInt())
        assertEquals("Test", restored.name)
    }

    @Test fun rejectsUnsupportedOptionsInsteadOfDroppingThem() {
        val base = "mierus://user:pass@example.org?profile=Test&protocol=TCP"
        assertTrue(runCatching { parseMieruSimple("$base&port=100-200") }.isFailure)
        assertTrue(runCatching { parseMieruSimple("$base&port=443&multiplexing=MULTIPLEXING_HIGH") }.isFailure)
    }
}
