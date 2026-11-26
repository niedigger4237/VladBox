package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.DataStore

/**
 * Service control (asteriskd `serviceControl`): the supervisor starts / stops the service by
 * Wi-Fi connection, cron schedule and lock screen. The settings live in the app's key-value store.
 */
object ServiceControl {
    val WIFI_RULES = listOf("connectStart", "connectStop", "disconnectStart", "disconnectStop")

    private val s get() = DataStore.configurationStore
    private fun b(k: String) = s.getBoolean(k, false)
    private fun t(k: String) = s.getString(k) ?: ""

    private fun lines(v: String) = v.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun normBssid(v: String) = v.lowercase().replace('-', ':')
    private val BSSID = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

    // Numeric five-field cron syntax: lists, ranges, wildcards and positive steps.
    fun validCron(value: String): Boolean {
        val fields = value.trim().split(Regex("\\s+"))
        val limits = listOf(0..59, 0..23, 1..31, 1..12, 0..7)
        if (fields.size != limits.size) return false
        return fields.zip(limits).all { (field, limit) ->
            field.split(',').all { item ->
                val parts = item.split('/')
                if (parts.size > 2 || (parts.size == 2 && (parts[1].toIntOrNull() ?: 0) <= 0)) {
                    false
                } else {
                    val base = parts[0]
                    if (base == "*") true else {
                        val bounds = base.split('-')
                        val first = bounds.first().toIntOrNull()
                        val last = bounds.last().toIntOrNull()
                        bounds.size in 1..2 && first != null && last != null &&
                            first in limit && last in limit && first <= last
                    }
                }
            }
        }
    }

    fun toJson(): JsonObject {
        val master = b("scEnabled")
        fun rule(name: String) = JsonObject().apply {
            val oppositeStart = when (name) {
                "connectStop" -> "connectStart"
                "disconnectStop" -> "disconnectStart"
                else -> null
            }
            val on = master && b("scWifi") && b("scWifi_$name") &&
                (oppositeStart == null || !b("scWifi_$oppositeStart"))
            val ssids = lines(t("scWifi_${name}_ssids")).filter { it.toByteArray().size in 1..32 }.take(64)
            val bssids = lines(t("scWifi_${name}_bssids")).map(::normBssid).filter { BSSID.matches(it) }.distinct().take(64)
            // a rule without identifiers would match nothing: keep it disabled
            addProperty("enabled", on && (ssids.isNotEmpty() || bssids.isNotEmpty()))
            add("ssids", JsonArray().also { a -> ssids.forEach(a::add) })
            add("bssids", JsonArray().also { a -> bssids.forEach(a::add) })
        }
        return JsonObject().apply {
            addProperty("enabled", master)
            add("schedule", JsonObject().apply {
                val start = t("scStartCron").trim()
                val stop = t("scStopCron").trim()
                val on = master && b("scSchedule") && validCron(start) && validCron(stop)
                addProperty("enabled", on)
                addProperty("startCron", if (on) start else "")
                addProperty("stopCron", if (on) stop else "")
            })
            add("wifi", JsonObject().apply {
                addProperty("enabled", master && b("scWifi"))
                WIFI_RULES.forEach { add(it, rule(it)) }
            })
            add("keyguard", JsonObject().apply {
                val on = master && b("scKeyguard") && android.os.Build.VERSION.SDK_INT >= 33
                addProperty("enabled", on)
                addProperty("lockStart", on && b("scLockStart"))
                addProperty("lockStop", on && b("scLockStop") && !b("scLockStart"))
                addProperty("unlockStart", on && b("scUnlockStart"))
                addProperty("unlockStop", on && b("scUnlockStop") && !b("scUnlockStart"))
            })
        }
    }
}
