package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.assertEquals
import org.junit.Test

class TetherCrashWatchdogTest {
    @Test fun routeRecordsAreCanonical() {
        val boot = "12345678-1234-1234-1234-123456789abc"
        assertEquals(
            "route 4 $boot abcdef012345 17900 17902 tun0\ndown rndis0 192.168.42.0/24\ndown wlan2 192.168.43.0/24\n",
            TetherCrashWatchdog.route(4, boot, "abcdef012345", 17900, 17902, "tun0",
                listOf("wlan2" to "192.168.43.0/24", "rndis0" to "192.168.42.0/24", "wlan2" to "192.168.43.0/24"))
        )
    }

    @Test fun guardRecordsPreserveOriginalSettingAndUniqueNames() {
        assertEquals(
            "guard NFV_G_abcdef012345 null\niface rndis0\niface wlan2\n",
            TetherCrashWatchdog.guard("NFV_G_abcdef012345", "null", listOf("wlan2", "rndis0", "wlan2"))
        )
    }
}
