package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonParser

internal object RootTetherDnsPolicy {
    // Only an unqualified port-53 hijack at the head of the effective route
    // can justify intercepting arbitrary local DNS before the native bypass.
    fun enabled(requested: Boolean, managed: Boolean, tproxy: Boolean,
                exactDownstreams: Boolean, startOnBoot: Boolean, coreConfig: String): Boolean {
        if (!requested || !managed || !tproxy || !exactDownstreams || startOnBoot) return false
        return runCatching {
            val root = JsonParser.parseString(coreConfig).asJsonObject
            val rules = root.getAsJsonObject("route").getAsJsonArray("rules")
            for (item in rules) {
                val rule = item.asJsonObject
                val keys = rule.keySet() - "type"
                if (rule.has("type") && rule.get("type").asString != "default") return false
                val action = rule.get("action")?.asString
                if (action == "sniff" && keys.all { it in setOf("action", "inbound", "sniffer", "timeout") }) continue
                if (action == "hijack-dns" && keys == setOf("action", "protocol") &&
                    rule.getAsJsonArray("protocol").map { it.asString } == listOf("dns")) continue
                return action == "hijack-dns" && keys == setOf("action", "port") &&
                    rule.getAsJsonArray("port").map { it.asInt } == listOf(53)
            }
            false
        }.getOrDefault(false)
    }
}
