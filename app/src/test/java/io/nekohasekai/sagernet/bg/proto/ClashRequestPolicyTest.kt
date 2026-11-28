package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.*
import org.junit.Test

class ClashRequestPolicyTest {
    @Test fun onlyDelayReadsUseProbeLane() {
        assertTrue(ClashRequestPolicy.isProbe("proxies/name/delay?timeout=5000", "GET"))
        assertTrue(ClashRequestPolicy.isProbe("group/proxy/delay?timeout=10000", "GET"))
        assertFalse(ClashRequestPolicy.isProbe("proxies", "GET"))
        assertFalse(ClashRequestPolicy.isProbe("connections", "GET"))
        assertFalse(ClashRequestPolicy.isProbe("rules", "GET"))
        assertFalse(ClashRequestPolicy.isProbe("proxies/delay", "PUT"))
        assertFalse(ClashRequestPolicy.isProbe("proxies?url=/delay", "GET"))
    }
}
