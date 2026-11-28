package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Store optional match conditions in the existing custom-rule JSON.
 * Preserve unrelated options and avoid changing the Room/Parcel backup format.
 */
internal object RouteAdvancedConditions {
    data class Fields(
        val ipVersion: String = "",
        val destinationPrivate: Boolean = false,
        val sourcePrivate: Boolean = false,
        val packageRegex: String = ""
    )
    private val keys = setOf("ip_version", "ip_is_private", "source_ip_is_private", "package_name_regex")

    private fun json(config: String): JsonObject =
        if (config.isBlank()) JsonObject() else JsonParser.parseString(config).asJsonObject

    private fun active(obj: JsonObject, key: String): Boolean {
        val value = obj.get(key) ?: return false
        return when {
            value.isJsonNull -> false
            value.isJsonArray -> value.asJsonArray.size() > 0
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.isNotBlank()
            else -> true
        }
    }

    private fun parse(config: String): JsonObject? = runCatching { json(config) }.getOrNull()

    fun read(config: String): Fields {
        val obj = json(config)
        require(obj.get("type")?.asString.orEmpty() in setOf("", "default")) {
            "Advanced conditions require a default rule; edit logical rules in Custom configuration."
        }
        val regex = obj.get("package_name_regex")?.let {
            if (it.isJsonNull) "" else if (it.isJsonArray) it.asJsonArray.joinToString("\n") { value -> value.asString } else it.asString
        }.orEmpty()
        return Fields(obj.get("ip_version")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            obj.get("ip_is_private")?.takeUnless { it.isJsonNull }?.asBoolean == true,
            obj.get("source_ip_is_private")?.takeUnless { it.isJsonNull }?.asBoolean == true,
            regex)
    }

    fun update(config: String, fields: Fields): String {
        val old = read(config)
        if (old == fields) return config
        require(fields.ipVersion in setOf("", "4", "6")) { "Select IPv4, IPv6 or Any" }
        val obj = json(config)
        if (old.ipVersion != fields.ipVersion) {
            if (fields.ipVersion.isEmpty()) obj.remove("ip_version")
            else obj.addProperty("ip_version", fields.ipVersion.toInt())
        }
        if (old.destinationPrivate != fields.destinationPrivate) {
            if (fields.destinationPrivate) obj.addProperty("ip_is_private", true) else obj.remove("ip_is_private")
        }
        if (old.sourcePrivate != fields.sourcePrivate) {
            if (fields.sourcePrivate) obj.addProperty("source_ip_is_private", true) else obj.remove("source_ip_is_private")
        }
        if (old.packageRegex != fields.packageRegex) {
            val patterns = fields.packageRegex.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
            for (pattern in patterns) {
                require(runCatching { Regex(pattern) }.isSuccess) { "Invalid package regular expression" }
                require(!pattern.contains("(?=") && !pattern.contains("(?!") &&
                    !pattern.contains("(?<=") && !pattern.contains("(?<!") &&
                    !Regex("\\\\[1-9]").containsMatchIn(pattern)) { "Lookaround and backreferences are not supported by sing-box" }
            }
            if (patterns.isEmpty()) obj.remove("package_name_regex")
            else obj.add("package_name_regex", com.google.gson.JsonArray().apply { patterns.forEach { add(it) } })
        }
        return if (obj.entrySet().isEmpty()) "" else obj.toString()
    }

    fun hasConditions(config: String): Boolean = parse(config)?.let { obj -> keys.any { active(obj, it) } } == true

    fun hasPackageRegex(config: String): Boolean = parse(config)?.let { active(it, "package_name_regex") } == true

    /** An advanced traffic-only condition must never become an unconditional or
     * domain-only DNS rule. Users can define its DNS policy separately. */
    fun deriveDns(config: String, transport: String): Boolean =
        !hasConditions(config) && LogicalRulePolicy.mode(config).isEmpty() && transport != "icmp"

    fun supported(config: String, transport: String, vpn: Boolean, rootTun: Boolean, outbound: Long): Boolean {
        if (hasPackageRegex(config) && !vpn) return false
        if (transport == "icmp" && (!(vpn || rootTun) || outbound !in setOf(-1L, -2L))) return false
        return true
    }
}
