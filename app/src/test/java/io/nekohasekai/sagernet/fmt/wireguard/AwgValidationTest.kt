package io.nekohasekai.sagernet.fmt.wireguard

import org.junit.Assert.*
import org.junit.Test

class AwgValidationTest {
    private fun check(headers: List<String> = List(4) { "" },
                      padding: List<Int> = List(4) { 12 },
                      key: String = "",
                      ranges: Map<String, String> = emptyMap()): String? =
        AwgValidation.error(listOf(4, 40, 70), padding, headers, ranges, key, emptyList())

    @Test fun unsignedRangesAndDefaultHeaders() {
        assertNull(check(headers = listOf("100-110", "200", "300", "4294967295")))
        assertNotNull(check(headers = listOf("100-110", "110", "", "")))
        assertNotNull(check(headers = listOf("2", "", "", "")))
        assertNotNull(check(headers = listOf("4294967296", "", "", "")))
        assertNotNull(check(ranges = mapOf("Rekey timeout" to "8-2")))
    }

    @Test fun headerProtectionAndPadding() {
        val key = "B" + "A".repeat(42) + "="
        assertNull(check(key = key))
        assertNotNull(check(key = key, padding = listOf(11, 12, 12, 12)))
        assertNotNull(check(key = "bad"))
        assertNotNull(check(padding = listOf(65536, 12, 12, 12)))
    }
}
