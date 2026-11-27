package io.nekohasekai.sagernet.fmt.naive

import org.junit.Assert.*
import org.junit.Test

class NaiveLinkTest {
    @Test fun exportsOriginalEndpointAndPreservesHeaders() {
        val bean = parseNaive("naive+quic://user:pass@example.org:5443?sni=example.net&extra-headers=X-Test%3A%20a%2Bb%25#Test")
        bean.finalAddress = "127.0.0.1"
        bean.finalPort = 12345
        val restored = parseNaive(bean.toUri())
        assertEquals("example.org", restored.serverAddress)
        assertEquals(5443, restored.serverPort.toInt())
        assertEquals("quic", restored.proto)
        assertEquals("example.net", restored.sni)
        assertEquals("X-Test: a+b%", restored.extraHeaders)
    }
}
