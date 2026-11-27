package io.nekohasekai.sagernet.fmt.hysteria

import org.junit.Assert.assertEquals
import org.junit.Test

class Hysteria1QuicOptionsTest {
    @Test fun mapsWindowsWithoutSwappingOrRounding() {
        assertEquals(mapOf("stream_receive_window" to "15728640 B",
            "connection_receive_window" to "67108864 B",
            "disable_path_mtu_discovery" to true),
            hysteria1QuicOptions(15728640, 67108864, true))
    }

    @Test fun leavesDefaultsUnset() {
        assertEquals(emptyMap<String, Any>(), hysteria1QuicOptions(0, 0, false))
        assertEquals(mapOf("connection_receive_window" to "12345 B"),
            hysteria1QuicOptions(0, 12345, false))
    }
}
