package io.nekohasekai.sagernet.fmt.v2ray

import org.junit.Assert.*
import org.junit.Test

class RealityHybridModeTest {
    @Test fun queryModes() {
        assertEquals("auto", parseRealityHybridMode(null))
        assertEquals("enabled", parseRealityHybridMode("true"))
        assertEquals("disabled", parseRealityHybridMode("0"))
        assertNull(realityHybridQueryValue("auto"))
        assertEquals("true", realityHybridQueryValue("enabled"))
        assertEquals("false", realityHybridQueryValue("disabled"))
    }

    @Test fun importedRealityWithoutFlagIsAutomatic() {
        val link = "vless://00000000-0000-4000-8000-000000000001@example.org:443?security=reality&type=xhttp&fp=chrome&pbk=abc"
        assertEquals("auto", parseV2Ray(link).effectiveRealityHybridMode())
        assertEquals("disabled", parseV2Ray(link + "&support-x25519mlkem768=false").effectiveRealityHybridMode())
        assertEquals("enabled", parseV2Ray(link + "&support_x25519mlkem768=1").effectiveRealityHybridMode())
    }
}
