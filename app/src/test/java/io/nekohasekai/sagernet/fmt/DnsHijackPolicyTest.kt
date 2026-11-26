package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsHijackPolicyTest {
    @Test fun allModesWithAndWithoutGeneralSniffing() {
        for (general in listOf(false, true)) {
            for ((mode, protocol, port) in listOf(
                Triple("both", true, true),
                Triple("protocol", true, false),
                Triple("port", false, true),
                Triple("off", false, false),
                Triple("unknown", false, false)
            )) {
                val sniff = if (general) DnsHijackPolicy.Sniff.ALL else if (protocol)
                    DnsHijackPolicy.Sniff.DNS_ONLY else DnsHijackPolicy.Sniff.NONE
                assertEquals(DnsHijackPolicy.Plan(sniff, protocol, port),
                    DnsHijackPolicy.plan(mode, general))
            }
        }
    }
}
