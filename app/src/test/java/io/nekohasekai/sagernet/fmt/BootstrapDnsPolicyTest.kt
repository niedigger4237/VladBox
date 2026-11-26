package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapDnsPolicyTest {
    @Test fun systemPreservesExistingBootstrap() {
        assertNull(BootstrapDnsPolicy.select("system", "invalid", "invalid"))
    }

    @Test fun allProviderTransports() {
        for ((provider, ip, name) in listOf(
            Triple("cloudflare", "1.1.1.1", "cloudflare-dns.com"),
            Triple("quad9", "9.9.9.9", "dns.quad9.net"),
            Triple("google", "8.8.8.8", "dns.google")
        )) {
            assertEquals(BootstrapDnsPolicy.Server("udp", ip), BootstrapDnsPolicy.select(provider, "", ""))
            assertEquals(BootstrapDnsPolicy.Server("https", ip, tlsName = name), BootstrapDnsPolicy.select(provider + "_doh", "", ""))
            assertEquals(BootstrapDnsPolicy.Server("tls", ip, tlsName = name), BootstrapDnsPolicy.select(provider + "_dot", "", ""))
        }
    }

    @Test fun customLiteralsAndTls() {
        assertEquals(BootstrapDnsPolicy.Server("udp", "1.1.1.1"), BootstrapDnsPolicy.parse("1.1.1.1", ""))
        assertEquals(BootstrapDnsPolicy.Server("tcp", "9.9.9.9", 5353), BootstrapDnsPolicy.parse("tcp://9.9.9.9:5353", ""))
        assertEquals(BootstrapDnsPolicy.Server("https", "2606:4700:4700::1111", 8443, "/custom", "cloudflare-dns.com"),
            BootstrapDnsPolicy.parse("https://[2606:4700:4700::1111]:8443/custom", "cloudflare-dns.com"))
        assertEquals(BootstrapDnsPolicy.Server("tls", "2001:4860:4860::8888"), BootstrapDnsPolicy.parse("tls://2001:4860:4860::8888", ""))
    }

    @Test fun rejectsRecursiveOrMalformedEndpoints() {
        for (address in listOf("dns.google", "https://dns.google/dns-query", "local", "dhcp://auto",
            "1.2.3.999", "1.2.3.4:0", "1.2.3.4:65536", "1.2.3.4:", "udp://1.2.3.4/path",
            "https://user@1.1.1.1/dns-query", "https://1.1.1.1/#x", "https://[bad::host]", "https://1.1.1.1?x"))
            assertTrue(address, runCatching { BootstrapDnsPolicy.parse(address, "") }.isFailure)
        assertTrue(runCatching { BootstrapDnsPolicy.parse("tls://1.1.1.1", "bad/name") }.isFailure)
    }
}
