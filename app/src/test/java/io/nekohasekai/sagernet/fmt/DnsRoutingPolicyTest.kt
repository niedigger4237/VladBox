package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsRoutingPolicyTest {
    @Test fun splitPreservesExistingDirectDefault() {
        assertEquals(DnsRoutingPolicy.Plan(true, "dns-direct", "dns-remote"),
            DnsRoutingPolicy.resolve("direct", false))
    }
    @Test fun remotePreferredPreservesExistingRemoteDefault() {
        assertEquals(DnsRoutingPolicy.Plan(true, "dns-remote", "dns-remote"),
            DnsRoutingPolicy.resolve("remote", false))
    }
    @Test fun directOnlyNeverReferencesManagedRemote() {
        assertEquals(DnsRoutingPolicy.Plan(false, "dns-direct", "dns-direct"),
            DnsRoutingPolicy.resolve("direct_only", false))
    }
    @Test fun urlTestAlwaysDirect() {
        for (mode in listOf("direct", "remote", "direct_only"))
            assertEquals(DnsRoutingPolicy.Plan(false, "dns-direct", "dns-direct"),
                DnsRoutingPolicy.resolve(mode, true))
    }
}
