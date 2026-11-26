package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonObject

/** Only explicit terminal status is actionable. An idle/paused supervisor
 * remains owned by native service-control policy. */
internal enum class RootHealth {
    RUNNING, ABSENT, FAILED, INACTIVE, TRANSITIONING, UNKNOWN;

    companion object {
        fun parse(result: JsonObject?, owner: String): RootHealth = runCatching {
            if (result == null) return UNKNOWN
            when (result.get("code")?.asString) {
                "not_running" -> ABSENT
                "ok" -> {
                    val snapshot = result.getAsJsonObject("snapshot") ?: return UNKNOWN
                    if (snapshot.get("owner")?.asString != owner ||
                        (snapshot.get("supervisorPid")?.asInt ?: 0) <= 0) return UNKNOWN
                    when (snapshot.get("phase")?.asString) {
                        "running" -> RUNNING
                        "failed" -> FAILED
                        "stopped", "paused" -> INACTIVE
                        "starting", "stopping" -> TRANSITIONING
                        else -> UNKNOWN
                    }
                }
                else -> UNKNOWN
            }
        }.getOrDefault(UNKNOWN)
    }
}

/** A failed supervisor may disappear during its own cleanup. Count FAILED
 * followed by ABSENT as confirmation, but reset on ambiguous status. */
internal class RootHealthConfirmation {
    private var consecutive = 0
    fun observe(state: RootHealth): Boolean {
        consecutive = if (state == RootHealth.ABSENT || state == RootHealth.FAILED)
            (consecutive + 1).coerceAtMost(2) else 0
        return consecutive >= 2
    }
    fun reset() { consecutive = 0 }
}
