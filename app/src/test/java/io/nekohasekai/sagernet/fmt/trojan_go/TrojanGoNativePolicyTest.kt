package io.nekohasekai.sagernet.fmt.trojan_go

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrojanGoNativePolicyTest {
    @Test fun standardTransportsUseNative() {
        assertTrue(canUseNativeTrojan("original", "none", ""))
        assertTrue(canUseNativeTrojan("ws", "none", ""))
    }

    @Test fun extraEncryptionAndUnknownOptionsStayExternal() {
        for (transport in listOf("original", "ws")) {
            for (method in listOf("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305")) {
                assertFalse(canUseNativeTrojan(transport, "ss;$method:secret", ""))
            }
        }
        assertFalse(canUseNativeTrojan("original", "none", "reserved"))
        assertFalse(canUseNativeTrojan("h2", "none", ""))
        assertFalse(canUseNativeTrojan("original", "unknown", ""))
    }
}
