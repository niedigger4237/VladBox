package io.nekohasekai.sagernet.ui

import java.util.Locale

internal object DashboardRulePolicy {
    enum class Kind { DIRECT, BLOCK, ROUTE, DNS, OTHER }
    data class Action(val label: String, val kind: Kind)

    private val fields = linkedMapOf(
        "inbound" to "Inbound", "network" to "Network", "protocol" to "Protocol",
        "port" to "Destination port", "port_range" to "Destination port range",
        "source_port" to "Source port", "source_port_range" to "Source port range",
        "ip_is_private" to "Private destination IP", "source_ip_is_private" to "Private source IP",
        "source_ip_cidr" to "Source IP CIDR", "ip_cidr" to "Destination IP CIDR",
        "ip_version" to "IP version", "rule_set" to "Rule set (SRS)",
        "domain_suffix" to "Domain suffix", "domain_keyword" to "Domain keyword",
        "domain_regex" to "Domain regex", "domain" to "Domain",
        "process_name" to "Application", "process_path" to "Application",
        "process_path_regex" to "Application", "package_name" to "Application",
        "package_name_regex" to "Application", "clash_mode" to "Clash mode",
        "network_type" to "Network type", "wifi_ssid" to "Wi-Fi SSID",
        "wifi_bssid" to "Wi-Fi BSSID", "auth_user" to "Authenticated user"
    )

    fun label(type: String, payload: String): String {
        val normalized = type.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
        val text = payload.trim()
        if (normalized == "logical") return if (text.startsWith("!(")) "Inverted logical rule" else "Logical rule"
        if (normalized == "default") {
            if (text.startsWith("!(")) return "Inverted rule"
            if (text.isBlank()) return "Unconditional rule"
            // The API exposes a human-readable expression, not a structured rule.
            // Do not split arbitrary regex/list values or reconstruct match semantics.
            val field = text.substringBefore('=').substringBefore(':').trim().lowercase(Locale.ROOT)
            return fields[field] ?: "Routing rule"
        }
        return when (normalized) {
            "match", "final" -> "Final"
            "domain" -> "Domain"
            "domainsuffix" -> "Domain suffix"
            "domainkeyword" -> "Domain keyword"
            "domainregex" -> "Domain regex"
            "ipcidr", "ipcidr6" -> "Destination IP CIDR"
            "sourceipcidr" -> "Source IP CIDR"
            "geoip" -> "GeoIP"
            "geosite" -> "Geosite"
            "ruleset" -> "Rule set (SRS)"
            else -> type.ifBlank { "Routing rule" }
        }
    }

    fun action(raw: String): Action {
        val text = raw.trim()
        val name = text.substringBefore('(').lowercase(Locale.ROOT)
        val argument = text.substringAfter('(', "").substringBeforeLast(')', "").substringBefore(',').trim()
        fun route(tag: String): Action = when (tag) {
            "direct" -> Action("Direct", Kind.DIRECT)
            "bypass" -> Action("Bypass", Kind.DIRECT)
            "block", "reject" -> Action("Block", Kind.BLOCK)
            "proxy" -> Action("Proxy", Kind.ROUTE)
            else -> Action(tag.ifBlank { text.ifBlank { "—" } }, Kind.ROUTE)
        }
        return when (name) {
            "route" -> route(argument)
            "bypass" -> if (text.contains('(')) Action("Bypass action" + if (argument.isNotBlank()) " → $argument" else "", Kind.OTHER) else route("bypass")
            "direct", "proxy", "block" -> route(name)
            "reject" -> Action(if (argument.isBlank()) "Block" else "Reject ($argument)", Kind.BLOCK)
            "hijack-dns" -> Action("DNS Hijack", Kind.DNS)
            "resolve" -> Action("DNS Resolve", Kind.DNS)
            "sniff" -> Action("Sniff", Kind.OTHER)
            "route-options" -> Action("Route options", Kind.OTHER)
            else -> Action(text.ifBlank { "—" }, Kind.OTHER)
        }
    }
}
