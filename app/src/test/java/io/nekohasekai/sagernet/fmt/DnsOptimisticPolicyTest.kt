package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsOptimisticPolicyTest {
    @Test fun optInOnly() {
        assertNull(DnsOptimisticPolicy.options(true, false, "off"))
        assertNull(DnsOptimisticPolicy.options(true, false, "invalid"))
        assertNull(DnsOptimisticPolicy.options(false, false, "5m"))
        assertNull(DnsOptimisticPolicy.options(true, true, "5m"))
    }

    @Test fun allSupportedWindows() {
        for (window in listOf("30s", "5m", "1h", "24h", "72h")) {
            assertEquals(mapOf("enabled" to true, "timeout" to window),
                DnsOptimisticPolicy.options(true, false, window))
        }
    }
}
