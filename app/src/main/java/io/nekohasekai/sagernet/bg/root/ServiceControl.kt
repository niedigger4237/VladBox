package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs

/** Native schema 7, shared by manual ROOT and boot publication. */
object ServiceControl {
    val WIFI_RULES = listOf("connectStart", "connectStop", "disconnectStart", "disconnectStop")
    private val s get() = DataStore.configurationStore
    private fun b(k: String) = s.getBoolean(k, false)
    private fun t(k: String) = s.getString(k) ?: ""

    fun enabled(): Boolean = b("scEnabled")

    fun monitorRequested(): Boolean = enabled() && b("scMonitorOnly")

    fun hasStartRule(config: JsonObject): Boolean {
        if (!config.get("enabled").asBoolean) return false
        val wifi = config.getAsJsonObject("wifi")
        val keyguard = config.getAsJsonObject("keyguard")
        return config.getAsJsonObject("schedule").get("enabled").asBoolean ||
            (wifi.get("enabled").asBoolean && listOf("connectStart", "disconnectStart").any {
                wifi.getAsJsonObject(it).get("enabled").asBoolean
            }) ||
            (keyguard.get("enabled").asBoolean && listOf("lockStart", "unlockStart").any {
                keyguard.get(it).asBoolean
            })
    }

    fun toJson(): JsonObject = buildJson(::b, ::t, android.os.Build.VERSION.SDK_INT) { Logs.w(it) }

    internal fun buildJson(b: (String) -> Boolean, t: (String) -> String, sdk: Int, warn: (String) -> Unit = {}): JsonObject {
        val master = b("scEnabled")
        fun rule(name: String): JsonObject {
            val key = "scWifi_$name"
            val ssidInput = t("${key}_ssids")
            val bssidInput = t("${key}_bssids")
            val any = b("${key}_any")
            val valid = any || (validServiceControlSsids(ssidInput) && validServiceControlBssids(bssidInput))
            val ssids = if (any || !valid) emptyList() else serviceControlIdentifiers(ssidInput)
            val bssids = if (any || !valid) emptyList() else serviceControlIdentifiers(bssidInput).mapNotNull(::normalizeBssidOrNull).distinct()
            // Asteriskd rejects mutually exclusive rules; STOP wins for a
            // conflicting restored preference. Do not broaden legacy empty lists.
            val conflictingStop = name.endsWith("Start") && b("scWifi_" + name.removeSuffix("Start") + "Stop")
            val requested = master && b("scWifi") && b(key) && !conflictingStop
            val enabled = requested && valid && (any || ssids.isNotEmpty() || bssids.isNotEmpty())
            if (requested && !enabled) warn("Service Control: disabled incomplete or invalid Wi-Fi rule $name")
            return JsonObject().apply {
                addProperty("enabled", enabled)
                add("ssids", JsonArray().also { a -> ssids.forEach(a::add) })
                add("bssids", JsonArray().also { a -> bssids.forEach(a::add) })
            }
        }
        return JsonObject().apply {
            addProperty("enabled", master)
            add("schedule", JsonObject().apply {
                val start = parseServiceCron(t("scStartCron"))
                val stop = parseServiceCron(t("scStopCron"))
                val requested = master && b("scSchedule")
                val on = requested && start is ServiceCronParseResult.Valid && stop is ServiceCronParseResult.Valid
                if (requested && !on) warn("Service Control: disabled invalid cron schedule")
                addProperty("enabled", on)
                addProperty("startCron", if (on && start is ServiceCronParseResult.Valid) start.expression else "")
                addProperty("stopCron", if (on && stop is ServiceCronParseResult.Valid) stop.expression else "")
            })
            add("wifi", JsonObject().apply {
                WIFI_RULES.forEach { add(it, rule(it)) }
                addProperty("enabled", WIFI_RULES.any { getAsJsonObject(it).get("enabled").asBoolean })
            })
            add("keyguard", JsonObject().apply {
                val on = master && b("scKeyguard") && sdk >= 33
                addProperty("enabled", on && (b("scLockStart") || b("scLockStop") || b("scUnlockStart") || b("scUnlockStop")))
                addProperty("lockStart", on && b("scLockStart") && !b("scLockStop"))
                addProperty("lockStop", on && b("scLockStop"))
                addProperty("unlockStart", on && b("scUnlockStart") && !b("scUnlockStop"))
                addProperty("unlockStop", on && b("scUnlockStop"))
            })
            // Do not launch event sources or a resident supervisor for a master
            // switch without any valid, enabled actions.
            addProperty("enabled", listOf("schedule", "wifi", "keyguard").any {
                getAsJsonObject(it).get("enabled").asBoolean
            })
        }
    }
}
