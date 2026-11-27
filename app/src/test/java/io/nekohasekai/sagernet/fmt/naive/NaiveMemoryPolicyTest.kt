package io.nekohasekai.sagernet.fmt.naive

import org.junit.Assert.*
import org.junit.Test

class NaiveMemoryPolicyTest {
    @Test fun automaticPreservesCoreDefaults() {
        for (transport in listOf("https", "quic"))
            assertTrue(NaiveMemoryPolicy.options(transport, "automatic").isEmpty())
    }

    @Test fun lowMemoryUsesTransportSpecificWindows() {
        assertEquals(mapOf("stream_receive_window" to "16 MB"),
            NaiveMemoryPolicy.options("https", "low_memory"))
        assertEquals(mapOf("stream_receive_window" to "2 MB", "quic_session_receive_window" to "6 MB"),
            NaiveMemoryPolicy.options("quic", "low_memory"))
    }

    @Test fun unknownProfileIsRejected() {
        assertTrue(runCatching { NaiveMemoryPolicy.options("https", "unknown") }.isFailure)
    }
}
