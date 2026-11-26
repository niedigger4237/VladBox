package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonParser

/** Persisted schema-2 telemetry is additional evidence, not a kernel audit. */
internal enum class RootCleanupEvidence {
    NO_STATE, STOPPED, INCOMPLETE, UNKNOWN;

    companion object {
        fun parse(ok: Boolean, raw: String, owner: String): RootCleanupEvidence {
            if (!ok) return UNKNOWN
            if (raw.trim() == "NF_STATE_ABSENT") return NO_STATE
            return runCatching {
                val state = JsonParser.parseString(raw).asJsonObject
                if (state.get("schemaVersion")?.asInt != 2 ||
                    state.get("owner")?.asString != owner ||
                    state.get("coreType")?.asString != "sing-box" ||
                    state.get("mode")?.asString !in setOf("tun", "tproxy", "tun2socks", "bpf2socks", "ebpf"))
                    return UNKNOWN
                if (state.get("phase")?.asString != "stopped") return INCOMPLETE
                val children = state.getAsJsonObject("children") ?: return UNKNOWN
                val matcher = state.getAsJsonObject("matcher") ?: return UNKNOWN
                val rules = state.getAsJsonObject("rules") ?: return UNKNOWN
                if (children.get("core")?.isJsonNull != true ||
                    children.get("helper")?.isJsonNull != true ||
                    matcher.get("active")?.asBoolean != false ||
                    rules.get("active")?.asBoolean != false ||
                    rules.get("generation")?.asLong != 0L ||
                    rules.getAsJsonArray("categories")?.size() != 0)
                    return INCOMPLETE
                STOPPED
            }.getOrDefault(UNKNOWN)
        }
    }
}
