package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class PersistentDnsPolicyTest {
    private val config = """{"dns":{"servers":[{"tag":"dns-direct","type":"https","server":"1.1.1.1"}]},"outbounds":[{"type":"vless","tag":"proxy","server":"example.org"}]}"""

    @Test fun scopeChangesWithProviderAndProfile() {
        val a = PersistentDnsPolicy.scope(config, 1, "default", "")
        assertNotNull(a)
        assertEquals(a, PersistentDnsPolicy.scope(config, 1, "default", ""))
        assertNotEquals(a, PersistentDnsPolicy.scope(config.replace("1.1.1.1", "9.9.9.9"), 1, "default", ""))
        assertNotEquals(a, PersistentDnsPolicy.scope(config, 2, "default", ""))
        assertNotEquals(a, PersistentDnsPolicy.scope(config, 1, "other", ""))
        assertNotEquals(a, PersistentDnsPolicy.scope(config, 1, "default", "clear"))
    }

    @Test fun explicitCacheAndDynamicOutboundsExcluded() {
        assertNull(PersistentDnsPolicy.scope(config.dropLast(1) + ""","experimental":{"cache_file":{"enabled":true}}}""", 1, "default", ""))
        assertNull(PersistentDnsPolicy.scope(config.replace("vless", "selector"), 1, "default", ""))
        assertNull(PersistentDnsPolicy.scope(config.replace("vless", "urltest"), 1, "default", ""))
    }
}
