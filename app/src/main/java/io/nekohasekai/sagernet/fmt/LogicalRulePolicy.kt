package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Explicit logical mode is opt-in. Ordinary rules retain native default-rule semantics. */
internal object LogicalRulePolicy {
    private const val KEY = "_nf_match_mode"
    val modes = listOf("", "and", "or", "not")
    val labels = listOf("Default", "AND (all)", "OR (any)", "NOT (not all)")
    private val advancedKeys = setOf("ip_version", "ip_is_private", "source_ip_is_private", "package_name_regex")

    private fun json(config: String): JsonObject =
        if (config.isBlank()) JsonObject() else JsonParser.parseString(config).asJsonObject

    fun mode(config: String): String = runCatching {
        json(config).get(KEY)?.asString.orEmpty()
    }.getOrDefault("")

    fun update(config: String, mode: String): String {
        require(mode in modes) { "Invalid match mode" }
        val obj = json(config)
        if (mode.isEmpty()) obj.remove(KEY)
        else {
            require(obj.keySet().all { it == KEY || it == RouteDnsChoice.KEY || it in advancedKeys }) {
                "Logical mode supports the visual match fields only. Use Custom configuration for other options."
            }
            obj.addProperty(KEY, mode)
        }
        return if (obj.entrySet().isEmpty()) "" else obj.toString()
    }

    fun clean(config: String): String {
        val mode = mode(config)
        if (mode.isEmpty()) return config
        // Validate again when loading imported/backed-up rules.
        update(config, mode)
        return update(config, "")
    }

    private val routeGroups = listOf(
        setOf("domain", "domain_suffix", "domain_keyword", "domain_regex"),
        setOf("ip_cidr", "ip_is_private"), setOf("rule_set"),
        setOf("port", "port_range"), setOf("source_port", "source_port_range"),
        setOf("source_ip_cidr", "source_ip_is_private"),
        setOf("network"), setOf("protocol"), setOf("user_id"), setOf("ip_version"),
        setOf("package_name_regex")
    )
    private val dnsGroups = listOf(
        setOf("domain", "domain_suffix", "domain_keyword", "domain_regex"),
        setOf("rule_set"), setOf("query_type")
    )

    private fun present(value: Any?): Boolean = when (value) {
        null, false -> false
        is String -> value.isNotBlank()
        is Collection<*> -> value.isNotEmpty()
        else -> true
    }

    private fun compose(mode: String, flat: Map<String, Any?>, groups: List<Set<String>>,
                        actionKeys: Set<String>): Map<String, Any?> {
        require(mode in modes && mode.isNotEmpty()) { "Invalid logical mode" }
        val matchKeys = groups.flatten().toSet()
        require(flat.filterValues(::present).keys.all { it in matchKeys || it in actionKeys }) {
            "Unsupported fields in visual logical rule"
        }
        val children = groups.map { group ->
            flat.filter { (key, value) -> key in group && present(value) }
        }.filter { it.isNotEmpty() }
        require(children.isNotEmpty()) { "Logical rule requires at least one match condition" }
        return linkedMapOf<String, Any?>(
            "type" to "logical",
            "mode" to if (mode == "or") "or" else "and",
            "rules" to children
        ).apply {
            if (mode == "not") put("invert", true)
            putAll(flat.filter { (key, value) -> key in actionKeys && present(value) })
        }
    }

    fun route(mode: String, flat: Map<String, Any?>): Map<String, Any?> =
        compose(mode, flat, routeGroups, setOf("action", "outbound"))

    fun dns(mode: String, flat: Map<String, Any?>): Map<String, Any?> =
        compose(mode, flat, dnsGroups, setOf("action", "server", "tag", "rcode", "timeout",
            "rewrite_ttl", "disable_cache", "disable_optimistic_cache", "remove_client_subnet"))
}
