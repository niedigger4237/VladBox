package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.*
import org.junit.Test

class ServiceControlValidationTest {
    @Test fun cronAcceptsNativeSyntax() {
        listOf("0 8 * * 1-5", "*/15 0-23 * * 0,7", "5/10 8 * * *", "0 0 1 1 7").forEach {
            assertTrue(it, parseServiceCron(it) is ServiceCronParseResult.Valid)
        }
    }

    @Test fun cronRejectsMalformedFields() {
        listOf("", "60 8 * * *", "0 24 * * *", "0 0 0 * *", "0 0 * 13 *", "0 0 * * 8",
            "*/0 * * * *", "*/4294967296 * * * *", "3-1 * * * *", "1, * * * *", "foo * * * *", "* * * * * *").forEach {
            assertTrue(it, parseServiceCron(it) is ServiceCronParseResult.Invalid)
        }
    }

    @Test fun wifiIdentifiersMatchNativeLimits() {
        assertEquals("aa:bb:cc:dd:ee:ff", normalizeBssidOrNull("AA-BB-CC-DD-EE-FF"))
        assertEquals("aa:bb:cc:dd:ee:ff", normalizeBssidOrNull("AABBCCDDEEFF"))
        assertNull(normalizeBssidOrNull("aa:bb:cc:dd:ee:gg"))
        assertTrue(validServiceControlSsids("я".repeat(16)))
        assertFalse(validServiceControlSsids("я".repeat(17)))
        assertFalse(validServiceControlSsids((1..65).joinToString("\n") { "network$it" }))
        assertFalse(validServiceControlBssids("not a MAC"))
    }
}
