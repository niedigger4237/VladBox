package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.*
import org.junit.Test

class ServiceControlConfigTest {
    private fun config(
        enabled: Set<String> = emptySet(),
        text: Map<String, String> = emptyMap(),
        sdk: Int = 36
    ) = ServiceControl.buildJson({ it in enabled }, { text[it].orEmpty() }, sdk)

    @Test fun disabledMasterNeverArmsRules() {
        val c = config(setOf("scWifi", "scWifi_connectStart", "scWifi_connectStart_any"))
        assertFalse(c.get("enabled").asBoolean)
        assertFalse(ServiceControl.hasStartRule(c))
    }

    @Test fun wildcardMustBeExplicitAndStopWinsConflicts() {
        val c = config(setOf("scEnabled", "scWifi", "scWifi_connectStart",
            "scWifi_connectStart_any", "scWifi_connectStop", "scWifi_connectStop_any"))
        val w = c.getAsJsonObject("wifi")
        assertFalse(w.getAsJsonObject("connectStart").get("enabled").asBoolean)
        assertTrue(w.getAsJsonObject("connectStop").get("enabled").asBoolean)
        assertEquals(0, w.getAsJsonObject("connectStop").getAsJsonArray("ssids").size())
        assertFalse(ServiceControl.hasStartRule(c))
        val legacy = config(setOf("scEnabled", "scWifi", "scWifi_connectStart"))
        assertFalse(legacy.get("enabled").asBoolean)
    }

    @Test fun wifiActionsAndIdentifiers() {
        for (rule in ServiceControl.WIFI_RULES) {
            val key = "scWifi_$rule"
            val c = config(setOf("scEnabled", "scWifi", key),
                mapOf("${key}_ssids" to "Home;Home", "${key}_bssids" to "AA-BB-CC-DD-EE-FF"))
            val w = c.getAsJsonObject("wifi").getAsJsonObject(rule)
            assertTrue(w.get("enabled").asBoolean)
            assertEquals(1, w.getAsJsonArray("ssids").size())
            assertEquals("aa:bb:cc:dd:ee:ff", w.getAsJsonArray("bssids")[0].asString)
            assertEquals(rule.endsWith("Start"), ServiceControl.hasStartRule(c))
        }
    }

    @Test fun cronAndKeyguardRespectNativeConstraints() {
        val schedule = setOf("scEnabled", "scSchedule")
        assertFalse(config(schedule, mapOf("scStartCron" to "60 8 * * *", "scStopCron" to "0 22 * * *")).get("enabled").asBoolean)
        assertTrue(ServiceControl.hasStartRule(config(schedule, mapOf("scStartCron" to "0 8 * * *", "scStopCron" to "0 22 * * *"))))
        val lock = setOf("scEnabled", "scKeyguard", "scLockStart", "scLockStop", "scUnlockStart")
        assertFalse(config(lock, sdk = 32).get("enabled").asBoolean)
        val c = config(lock)
        val keyguard = c.getAsJsonObject("keyguard")
        assertFalse(keyguard.get("lockStart").asBoolean)
        assertTrue(keyguard.get("lockStop").asBoolean)
        assertTrue(keyguard.get("unlockStart").asBoolean)
        assertTrue(ServiceControl.hasStartRule(c))
    }
}
