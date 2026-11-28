package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RouteAdvancedConditionsTest {
    @Test fun preservesUnrelatedCustomFieldsAndRemovesDefaults() {
        val existing = """{"override_port":443,"ip_version":4,"package_name_regex":"^com.example"}"""
        val fields = RouteAdvancedConditions.read(existing)
        assertEquals("4", fields.ipVersion)
        assertEquals("^com.example", fields.packageRegex)
        val changed = RouteAdvancedConditions.update(existing, fields.copy(ipVersion = "6", packageRegex = "", sourcePrivate = true))
        val json = JsonParser.parseString(changed).asJsonObject
        assertEquals(443, json.get("override_port").asInt)
        assertEquals(6, json.get("ip_version").asInt)
        assertFalse(json.has("package_name_regex"))
        assertTrue(json.get("source_ip_is_private").asBoolean)
        assertEquals("", RouteAdvancedConditions.update("""{"ip_version":4}""", RouteAdvancedConditions.Fields()))
    }

    @Test fun advancedRulesDoNotBecomeBroaderDnsRules() {
        assertTrue(RouteAdvancedConditions.deriveDns("", "tcp"))
        assertFalse(RouteAdvancedConditions.deriveDns("""{"ip_version":6}""", ""))
        assertFalse(RouteAdvancedConditions.deriveDns("", "icmp"))
        assertFalse(RouteAdvancedConditions.deriveDns("""{"package_name_regex":["com.example"]}""", ""))
    }

    @Test fun limitsPlatformDependentConditions() {
        val regex = """{"package_name_regex":"com.example"}"""
        assertTrue(RouteAdvancedConditions.supported(regex, "tcp", true, false, 0))
        assertFalse(RouteAdvancedConditions.supported(regex, "tcp", false, true, 0))
        assertTrue(RouteAdvancedConditions.supported("", "icmp", true, false, -1))
        assertTrue(RouteAdvancedConditions.supported("", "icmp", false, true, -2))
        assertFalse(RouteAdvancedConditions.supported("", "icmp", false, false, -1))
        assertFalse(RouteAdvancedConditions.supported("", "icmp", true, false, 0))
    }
}
