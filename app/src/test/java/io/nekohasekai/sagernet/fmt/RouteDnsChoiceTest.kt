package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class RouteDnsChoiceTest {
    @Test fun metadataRoundTripPreservesOtherFields() {
        val config = RouteDnsChoice.update("""{"ip_version":4}""", "dns-local")
        assertEquals("dns-local", RouteDnsChoice.read(config))
        assertEquals("""{"ip_version":4}""", RouteDnsChoice.clean(config))
        assertEquals("", RouteDnsChoice.update(RouteDnsChoice.update("", "dns-remote"), ""))
    }
    @Test fun onlyDnsCompatibleConditionsAreEligible() {
        val config = RouteDnsChoice.update("", "dns-local")
        fun eligible(config: String, domains: String = "geosite:category-ru", ip: String = "", network: String = "") =
            RouteDnsChoice.eligible(config, domains, emptyList(), ip, "", "", network, "", "")
        assertTrue(eligible(config))
        assertFalse(eligible(config, domains = ""))
        assertFalse(eligible(config, ip = "geoip:ru"))
        assertFalse(eligible(config, network = "udp"))
        assertFalse(eligible(LogicalRulePolicy.update(config, "not")))
        assertFalse(eligible(RouteDnsChoice.update("""{"ip_version":6}""", "dns-local")))
    }
    @Test fun respectsGlobalServerAvailability() {
        assertEquals("dns-local", RouteDnsChoice.resolve("dns-local", false, false))
        assertNull(RouteDnsChoice.resolve("dns-remote", false, false))
        assertEquals("dns-remote", RouteDnsChoice.resolve("dns-remote", true, false))
        assertEquals("dns-local", RouteDnsChoice.resolve("dns-bootstrap", true, false))
        assertEquals("dns-bootstrap", RouteDnsChoice.resolve("dns-bootstrap", true, true))
    }
}
