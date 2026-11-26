package io.nekohasekai.sagernet.fmt.v2ray

import org.junit.Assert.*
import org.junit.Test

class RealityMldsaTest {
    @Test fun keyValidationAndImport() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952))
        assertTrue(isValidRealityMldsa65(key))
        assertFalse(isValidRealityMldsa65("invalid"))
        val url = "vless://00000000-0000-4000-8000-000000000001@example.org:443?security=reality&type=xhttp&pbk=abc&pqv=$key"
        assertEquals(key, parseV2Ray(url).realityMldsa65Verify)
    }
}
