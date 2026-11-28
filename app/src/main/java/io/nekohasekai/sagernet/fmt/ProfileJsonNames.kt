package io.nekohasekai.sagernet.fmt

import org.json.JSONArray
import org.json.JSONObject

/** Display names are encoded in valid sing-box tags, not unknown JSON fields. */
internal object ProfileJsonNames {
    private val helpers = setOf("direct", "block", "dns", "selector", "urltest", "bypass")
    private val generic = Regex("(?i)^(?:proxy|outbound|vless|vmess|trojan|wireguard)(?:[-_ ]?\\d+)?$|^g-\\d+$|^c-\\d+-\\d+-\\d+$")
    private fun text(obj: JSONObject, key: String) = (obj.opt(key) as? String)?.takeIf { it.isNotBlank() }
    fun metadata(obj: JSONObject, allowName: Boolean = true): String? =
        (if (allowName) listOf("remarks", "name", "ps") else listOf("remarks", "ps"))
            .firstNotNullOfOrNull { text(obj, it) }
    fun fileName(path: String): String? = path.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("(?i)\\.(json|conf|txt)$"), "").takeIf { it.isNotBlank() }
    fun isEndpoint(obj: JSONObject) = obj.optString("type") == "wireguard" && obj.optJSONArray("peers") != null
    fun isEndpoint(json: String): Boolean = runCatching { isEndpoint(JSONObject(json)) }.getOrDefault(false)
    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    fun nodes(config: JSONObject): List<JSONObject> = objects(config.optJSONArray("outbounds")) +
        objects(config.optJSONArray("endpoints")).filter(::isEndpoint)
    fun proxies(config: JSONObject) = nodes(config).filter {
        it.optString("type").isNotBlank() && it.optString("type") !in helpers
    }
    fun importedName(node: JSONObject, config: JSONObject? = null, file: String = "", single: Boolean = true): String {
        val tag = text(node, "tag")
        val primary = single || tag == "proxy" ||
            (tag != null && config?.optJSONObject("route")?.optString("final") == tag)
        return metadata(node, !isEndpoint(node))
            ?: (if (primary) config?.let { metadata(it) } else null)
            ?: tag?.takeUnless { generic.matches(it) }
            ?: (if (primary) fileName(file) else null)
            ?: text(node, "server")?.let { host -> text(node, "server_port")?.let { "$host:$it" } ?: host }
            ?: tag.orEmpty()
    }

    fun cleanOutbound(node: JSONObject): JSONObject = JSONObject(node.toString()).apply {
        remove("remarks")
        remove("ps")
        if (!isEndpoint(this)) remove("name")
    }

    /** Rename only declared outbound/endpoint tags and their schema-defined references.
     * Do not touch addresses, passwords, inbound tags or DNS server identifiers. */
    fun export(config: String, names: Map<String, String>): String {
        if (names.isEmpty()) return config
        val json = JSONObject(config)
        val nodes = nodes(json)
        val counts = nodes.mapNotNull { text(it, "tag") }.groupingBy { it }.eachCount()
        val reserved = counts.keys.toMutableSet()
        reserved += helpers
        val mapping = linkedMapOf<String, String>()
        for ((old, label) in names) {
            if (counts[old] != 1 || label.isBlank() || label == old) continue
            var candidate = label
            var suffix = 2
            while (candidate in reserved) candidate = "$label (${suffix++})"
            mapping[old] = candidate
            reserved += candidate
        }
        if (mapping.isEmpty()) return config
        fun rename(obj: JSONObject, key: String) {
            val old = text(obj, key)
            mapping[old]?.let { obj.put(key, it) }
        }
        fun renameList(obj: JSONObject, key: String) {
            val array = obj.optJSONArray(key) ?: return
            for (i in 0 until array.length()) {
                mapping[array.opt(i) as? String]?.let { array.put(i, it) }
            }
        }
        fun rules(items: JSONArray?) {
            for (rule in objects(items)) {
                rename(rule, "outbound")
                renameList(rule, "outbound")
                rules(rule.optJSONArray("rules"))
            }
        }
        for (node in nodes) {
            rename(node, "tag")
            rename(node, "detour")
            if (node.optString("type") in setOf("selector", "urltest")) {
                rename(node, "default")
                renameList(node, "outbounds")
            }
        }
        json.optJSONObject("route")?.let { route ->
            rename(route, "final")
            rules(route.optJSONArray("rules"))
            objects(route.optJSONArray("rule_set")).forEach { rename(it, "download_detour") }
        }
        json.optJSONObject("dns")?.let { dns ->
            rules(dns.optJSONArray("rules"))
            objects(dns.optJSONArray("servers")).forEach { rename(it, "detour") }
        }
        json.optJSONObject("experimental")?.optJSONObject("clash_api")?.let {
            rename(it, "external_ui_download_detour")
        }
        return json.toString(2)
    }

    // Raw full configurations remain user-owned; only rename an unambiguous proxy.
    fun exportRaw(config: String, label: String): String {
        val json = JSONObject(config)
        val proxies = proxies(json)
        val final = json.optJSONObject("route")?.optString("final")
        val primary = proxies.firstOrNull { final != null && text(it, "tag") == final }
            ?: proxies.singleOrNull() ?: return config
        val tag = text(primary, "tag") ?: return config
        return export(config, mapOf(tag to label))
    }
}
