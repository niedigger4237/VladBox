package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A per-route DNS target is app metadata, not a sing-box route field. */
internal object RouteDnsChoice {
    const val KEY = "_nf_dns_target"
    val choices = listOf("" to "Automatic") + DnsTargetCatalog.entries
    private fun json(config: String): JsonObject =
        if (config.isBlank()) JsonObject() else JsonParser.parseString(config).asJsonObject
    fun read(config: String): String = runCatching {
        json(config).get(KEY)?.asString.orEmpty()
    }.getOrDefault("")
    fun label(config: String) = choices.firstOrNull { it.first == read(config) }?.second ?: "Invalid DNS target"
    fun update(config: String, target: String): String {
        require(choices.any { it.first == target }) { "Invalid DNS target" }
        val obj = json(config)
        if (target.isEmpty()) obj.remove(KEY) else obj.addProperty(KEY, target)
        return if (obj.entrySet().isEmpty()) "" else obj.toString()
    }
    fun clean(config: String): String = if (read(config).isEmpty()) config else update(config, "")
    /** Only rules whose entire condition is meaningful during DNS lookup.
     * Do not silently discard traffic-only predicates or custom match logic. */
    fun eligible(config: String, domains: String, packages: Collection<String>,
                 ip: String, port: String, sourcePort: String, network: String, source: String, protocol: String): Boolean =
        (domains.isNotBlank() || packages.isNotEmpty()) &&
            listOf(ip, port, sourcePort, network, source, protocol).all { it.isBlank() } &&
            runCatching { json(clean(config)).entrySet().isEmpty() }.getOrDefault(false)

    fun resolve(target: String, remote: Boolean, bootstrap: Boolean): String? = when (target) {
        "dns-remote" -> target.takeIf { remote }
        "dns-bootstrap" -> if (bootstrap) target else "dns-local"
        "dns-local", "dns-direct", "block" -> target
        else -> null
    }
}
