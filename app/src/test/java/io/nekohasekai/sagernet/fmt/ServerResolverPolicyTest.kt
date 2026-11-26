package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ServerResolverPolicyTest {
    @Test fun defaultResolverUsesDirectBootstrap() {
        assertEquals("dns-direct", ServerResolverPolicy.options(false, "", null))
        for (strategy in listOf("prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")) {
            assertEquals(mapOf("server" to "dns-direct", "strategy" to strategy),
                ServerResolverPolicy.options(false, strategy, null))
        }
    }

    @Test fun explicitResolverIsNotOverwritten() {
        val custom = mapOf("server" to "dns-direct", "strategy" to "ipv6_only",
            "timeout" to "5s", "disable_optimistic_cache" to true)
        assertSame(custom, ServerResolverPolicy.options(false, "prefer_ipv4", custom))
        assertEquals("my-resolver", ServerResolverPolicy.options(false, "", "my-resolver"))
        assertEquals("dns-direct", ServerResolverPolicy.options(false, "", ""))
    }

    @Test fun urlTestRetainsDirectDefault() {
        assertEquals("dns-direct", ServerResolverPolicy.options(true, "ipv6_only", "my-resolver"))
    }

    @Test fun sharedHopUsesEmittedTag() {
        assertEquals("proxy", ServerResolverPolicy.effectiveHopTag("g-123", "proxy"))
        assertEquals("Selector server", ServerResolverPolicy.effectiveHopTag("g-123", "Selector server"))
        assertEquals("g-123", ServerResolverPolicy.effectiveHopTag("g-123", null))
    }
}
