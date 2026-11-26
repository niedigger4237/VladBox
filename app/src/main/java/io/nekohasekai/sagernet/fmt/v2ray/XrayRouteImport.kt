package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Import only rules for which the managed routing engine has a clear equivalent. */
object XrayRouteImport {
    data class Target(val bean: AbstractBean? = null, val chain: XrayJsonImport.ImportedChain? = null, val special: Long? = null)
    data class Entry(val rule: RuleEntity, val target: Target)
    data class Dns(val address: String, val direct: Boolean, val strategy: String?)
    data class Plan(val name: String, val rules: List<Entry>, val final: Target, val dns: Dns?)

    private fun keys(o: JSONObject, allowed: Set<String>, context: String) {
        val unknown = o.keys().asSequence().filter { it !in allowed && !o.isNull(it) }.toList()
        require(unknown.isEmpty()) { "Unsupported Xray $context: " + unknown.joinToString() }
    }
    private fun strings(o: JSONObject, key: String): List<String> {
        if (!o.has(key) || o.isNull(key)) return emptyList()
        return when (val v = o.get(key)) {
            is String -> listOf(v)
            is Number -> listOf(v.toString())
            is JSONArray -> (0 until v.length()).map { v.getString(it) }
            else -> throw IllegalArgumentException("Invalid Xray $key")
        }
    }
    private fun clean(value: String): String {
        require(value.isNotBlank() && value.none { it == '\n' || it == '\r' || it == ',' }) { "Unsupported Xray selector containing separators" }
        return value
    }
    internal fun domain(value: String): String {
        clean(value)
        if (!value.contains(':')) return "keyword:$value"
        val kind = value.substringBefore(':')
        val body = value.substringAfter(':')
        require(body.isNotBlank()) { "Empty Xray domain selector" }
        return when (kind) {
            "domain", "full", "regexp", "keyword" -> "$kind:$body"
            "geosite" -> {
                require(body.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsupported geosite attribute or external file" }
                "geosite:" + body.lowercase(Locale.ROOT)
            }
            else -> throw IllegalArgumentException("Unsupported Xray domain selector: $kind")
        }
    }
    private fun ip(value: String, allowGeo: Boolean): String {
        clean(value)
        if (value.startsWith("geoip:")) {
            val code = value.substringAfter(':')
            require(allowGeo && code.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsupported Xray geoip selector" }
            return "geoip:" + code.lowercase(Locale.ROOT)
        }
        val address = value.substringBefore('/')
        require(address.matches(Regex("[0-9a-fA-F:.]+")) && (address.contains('.') || address.contains(':'))) { "Invalid Xray IP selector" }
        if (!address.contains(':')) {
            val octets = address.split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) { "Invalid Xray IPv4 address" }
        } else {
            require(java.net.InetAddress.getByName(address) is java.net.Inet6Address) { "Invalid Xray IPv6 address" }
        }
        val bits = if (address.contains(':')) 128 else 32
        val prefix = if (value.contains('/')) value.substringAfter('/').toIntOrNull() else bits
        require(prefix != null && prefix in 0..bits) { "Invalid Xray CIDR" }
        return "$address/$prefix"
    }
    private fun ports(o: JSONObject, name: String): String {
        return strings(o, name).flatMap { it.split(',') }.joinToString("\n") { raw ->
            val parts = raw.trim().split('-')
            require(parts.size in 1..2) { "Invalid Xray port range" }
            val nums = parts.map { it.toIntOrNull()?.takeIf { n -> n in 1..65535 } ?: error("Invalid Xray port") }
            require(nums.first() <= nums.last()) { "Reversed Xray port range" }
            nums.joinToString(":")
        }
    }
    private fun dns(root: JSONObject, final: Target, rules: List<Entry>): Dns? {
        val d = if (root.has("dns") && !root.isNull("dns")) root.getJSONObject("dns") else return null
        keys(d, setOf("servers", "queryStrategy", "disableCache", "disableFallback", "disableFallbackIfMatch"), "DNS")
        require(!d.optBoolean("disableCache") && !d.optBoolean("disableFallback") && !d.optBoolean("disableFallbackIfMatch")) { "Unsupported Xray DNS cache/fallback policy" }
        val servers = d.getJSONArray("servers")
        require(servers.length() == 1 && servers.opt(0) is String) { "Only one unfiltered Xray DNS server can be imported; split DNS needs separate conversion" }
        val raw = servers.getString(0)
        val local = raw == "localhost" || raw == "local" || raw.startsWith("https+local://") || raw.startsWith("tcp+local://")
        require(local || rules.isEmpty()) { "Routed Xray DNS with traffic rules requires explicit DNS routing conversion" }
        val address = when {
            raw == "localhost" || raw == "local" -> "local"
            raw.startsWith("https+local://") -> raw.replaceFirst("https+local://", "https://")
            raw.startsWith("tcp+local://") -> raw.replaceFirst("tcp+local://", "tcp://")
            raw.startsWith("https://") || raw.startsWith("tcp://") -> raw
            raw.matches(Regex("[0-9a-fA-F:.]+")) && (raw.contains('.') || raw.contains(':')) -> {
                ip(raw, false)
                raw
            }
            else -> throw IllegalArgumentException("Unsupported Xray DNS address")
        }
        if (address.contains("://")) {
            val uri = java.net.URI(address)
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.port in -1..65535) { "Invalid Xray DNS URL" }
            require(uri.rawQuery == null && (uri.scheme == "https" || uri.path.isNullOrEmpty())) { "Unsupported Xray DNS URL parameters" }
        }
        val strategy = when (d.optString("queryStrategy", "UseIP")) {
            "UseIP" -> ""
            "UseIPv4" -> "ipv4_only"
            "UseIPv6" -> "ipv6_only"
            else -> throw IllegalArgumentException("Unsupported Xray DNS queryStrategy")
        }
        return Dns(address, local || final.special == -1L, strategy)
    }
    fun plan(root: JSONObject, name: String, targets: Map<String, Target>, first: Target): Plan? {
        if (!root.has("routing") && !root.has("dns")) return null
        require(first.special != -2L) { "Xray default block outbound is unsupported" }
        val routing = if (root.has("routing") && !root.isNull("routing")) root.getJSONObject("routing") else JSONObject()
        keys(routing, setOf("domainStrategy", "rules"), "routing")
        require(routing.optString("domainStrategy", "AsIs") in setOf("", "AsIs")) { "Xray IPIfNonMatch/IPOnDemand require a separate resolve policy" }
        val arr = if (routing.has("rules") && !routing.isNull("rules")) routing.getJSONArray("rules") else JSONArray()
        val rules = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            keys(o, setOf("type", "ruleTag", "domain", "ip", "port", "sourcePort", "source", "network", "protocol", "outboundTag"), "route rule")
            require(o.optString("type", "field") == "field") { "Unsupported Xray route rule type" }
            val domains = strings(o, "domain").map(::domain)
            val ips = strings(o, "ip").map { ip(it, true) }
            require(domains.isEmpty() || ips.isEmpty()) { "Combined Xray domain AND IP matching requires logical rule conversion" }
            val sources = strings(o, "source").map { ip(it, false) }
            val network = strings(o, "network").flatMap { it.split(',') }.map { it.trim() }.distinct()
            require(network.all { it in setOf("tcp", "udp") }) { "Unsupported Xray network" }
            val protocols = strings(o, "protocol").map(::clean)
            require(protocols.all { it in setOf("http", "tls", "quic", "bittorrent", "dns") }) { "Unsupported Xray sniffed protocol" }
            val port = ports(o, "port")
            val sourcePort = ports(o, "sourcePort")
            require(domains.isNotEmpty() || ips.isNotEmpty() || sources.isNotEmpty() || port.isNotEmpty() || sourcePort.isNotEmpty() || network.isNotEmpty() || protocols.isNotEmpty()) { "Empty Xray route rule" }
            val target = targets[o.optString("outboundTag")] ?: error("Unknown Xray route outbound tag")
            val rule = RuleEntity(name = o.optString("ruleTag").ifBlank { "Xray rule " + (i+1) }, userOrder = i.toLong()+1, enabled = true,
                domains = domains.joinToString("\n"), ip = ips.joinToString("\n"), source = sources.joinToString("\n"),
                port = port, sourcePort = sourcePort, network = network.singleOrNull().orEmpty(), protocol = protocols.joinToString("\n"),
                config = if (network.size > 1) JSONObject().put("network", JSONArray(network)).toString() else "")
            Entry(rule, target)
        }
        return Plan(name.ifBlank { "Xray routing" }, rules, first, dns(root, first, rules))
    }
}
