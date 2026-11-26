package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsClientTuningPolicyTest {
    @Test fun defaultsAndInvalidValuesInheritCoreDefaults() {
        for (v in listOf("default", "", "0", "-1", "1ns", "999999999999999999999"))
            assertEquals(DnsClientTuningPolicy.Options(),
                DnsClientTuningPolicy.options(true, false, v, v))
    }

    @Test fun modernManualOptionsAndIndependentDefaults() {
        for (t in listOf("3s", "5s", "10s", "15s", "30s")) {
            for (c in listOf("1024", "2048", "4096", "8192", "16384"))
                assertEquals(DnsClientTuningPolicy.Options(t, c.toInt()),
                    DnsClientTuningPolicy.options(true, false, t, c))
        }
        assertEquals(DnsClientTuningPolicy.Options("5s", null),
            DnsClientTuningPolicy.options(true, false, "5s", "default"))
        assertEquals(DnsClientTuningPolicy.Options(null, 4096),
            DnsClientTuningPolicy.options(true, false, "default", "4096"))
    }

    @Test fun legacyAndUrlTestDoNotEmitTuning() {
        assertEquals(DnsClientTuningPolicy.Options(),
            DnsClientTuningPolicy.options(false, false, "30s", "16384"))
        assertEquals(DnsClientTuningPolicy.Options(),
            DnsClientTuningPolicy.options(true, true, "30s", "16384"))
    }
}
