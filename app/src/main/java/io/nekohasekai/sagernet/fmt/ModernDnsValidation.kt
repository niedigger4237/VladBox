package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject

/** Early structural checks for managed Modern DNS configs.
 * The core remains authoritative for rule-set metadata and runtime response availability.
 * Never rewrite custom rules, choose a different DNS server, or log user JSON.
 */
internal object ModernDnsValidation {
    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.enabled(key: String): Boolean =
        get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true

    private fun JsonObject.nonEmpty(key: String): Boolean {
        val value = get(key) ?: return false
        return when {
            value.isJsonNull -> false
            value.isJsonArray -> value.asJsonArray.size() > 0
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.isNotBlank()
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
            else -> true
        }
    }

    private data class Use(val anonymous: Boolean = false, val tagged: Set<String> = emptySet()) {
        operator fun plus(other: Use) = Use(anonymous || other.anonymous, tagged + other.tagged)
    }

    private fun inspect(rule: JsonObject, path: String, nested: Boolean): Use {
        val action = rule.string("action").orEmpty().ifEmpty { "route" }
        require(!rule.nonEmpty("strategy")) {
            "$path: legacy DNS rule strategy is not supported in Modern mode; use global DNS Strategy or Legacy mode"
        }
        require(!rule.enabled("rule_set_ip_cidr_accept_empty")) {
            "$path: rule_set_ip_cidr_accept_empty is not supported in Modern mode"
        }
        val logical = rule.string("type") == "logical"
        require(!nested || action != "evaluate" && action != "respond") {
            "$path: evaluate and respond must be top-level DNS actions"
        }
        if (logical) {
            val children = rule.get("rules")
            require(children?.isJsonArray == true) { "$path: logical DNS rule requires rules array" }
            var use = Use()
            children!!.asJsonArray.forEachIndexed { i, child ->
                require(child.isJsonObject) { "$path.rules[$i]: expected DNS rule object" }
                use += inspect(child.asJsonObject, "$path.rules[$i]", true)
            }
            if (action == "respond") {
                require(use.tagged.isEmpty()) { "$path: tagged respond requires a non-logical DNS rule" }
                use += Use(anonymous = true)
            }
            return use
        }
        val match = rule.get("match_response")
        val tag = rule.string("match_response")?.takeIf { it.isNotEmpty() }
        val anonymous = rule.enabled("match_response")
        require(match == null || match.isJsonNull || match.isJsonPrimitive &&
            (match.asJsonPrimitive.isBoolean || tag != null)) {
            "$path: match_response must be a boolean or evaluate tag"
        }
        val responseFields = listOf("ip_cidr", "ip_is_private", "ip_accept_any", "response_rcode",
            "response_answer", "response_ns", "response_extra")
        require(!responseFields.any { rule.nonEmpty(it) } || anonymous || tag != null) {
            "$path: DNS response filters require match_response in Modern mode"
        }
        return Use(anonymous || action == "respond" && tag == null, tag?.let { setOf(it) }.orEmpty())
    }

    fun check(config: JsonObject) {
        val dns = config.get("dns")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val rules = dns.get("rules") ?: return
        require(rules.isJsonArray) { "dns.rules must be an array" }
        val fakeServers = dns.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject && it.asJsonObject.string("type") == "fakeip" }
            ?.mapNotNull { it.asJsonObject.string("tag") }?.toSet().orEmpty()
        var anonymous = false
        val tags = mutableSetOf<String>()
        rules.asJsonArray.forEachIndexed { i, element ->
            val path = "dns.rules[$i]"
            require(element.isJsonObject) { "$path: expected DNS rule object" }
            val rule = element.asJsonObject
            val use = inspect(rule, path, false)
            require(!use.anonymous || anonymous) { "$path: requires preceding untagged evaluate action" }
            require(tags.containsAll(use.tagged)) { "$path: match_response references an undefined or later evaluate tag" }
            if (rule.string("action") == "evaluate") {
                val server = rule.string("server")
                require(!server.isNullOrBlank()) { "$path: evaluate requires a DNS server" }
                require(server !in fakeServers) { "$path: evaluate cannot use a FakeIP server" }
                val tag = rule.string("tag").orEmpty()
                if (tag.isEmpty()) anonymous = true
                else require(tags.add(tag)) { "$path: duplicate evaluate tag" }
            }
        }
    }
}
