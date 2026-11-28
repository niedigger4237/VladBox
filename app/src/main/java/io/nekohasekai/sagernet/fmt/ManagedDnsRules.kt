package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import java.net.IDN
import java.util.Locale
import java.util.UUID

/** Ordered DNS rules with stable references to evaluated responses. */
internal object ManagedDnsRules {
    data class Rule(
        val id: String = UUID.randomUUID().toString(),
        val name: String = "",
        val enabled: Boolean = true,
        val domains: String = "",
        val match: String = "suffix",
        val queries: String = "",
        val target: String = "dns-direct",
        // Nullable additions keep stage-1 JSON and backups compatible.
        val operation: String? = null,
        val responseFrom: String? = null,
        val responseCode: String? = null,
        val responseIp: String? = null,
        val responseCidrs: String? = null
    )
    private val gson = Gson()
    private val type = object : TypeToken<List<Rule>>() {}.type
    private val queryTypes = setOf("A", "AAAA", "HTTPS", "SVCB", "TXT", "MX", "NS", "PTR", "SRV", "CNAME", "CAA", "SOA", "DNSKEY", "DS")
    private fun tokens(value: String) = value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private fun domains(rule: Rule): List<String> = tokens(rule.domains).map { raw ->
        val value = IDN.toASCII(raw.removePrefix(".").removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        require(value.isNotBlank() && value.length <= 253 && value.split('.').all { it.isNotBlank() && it.length <= 63 }) { "Invalid DNS domain" }
        value
    }.distinct()
    private fun queries(rule: Rule) = tokens(rule.queries).map { it.uppercase(Locale.ROOT) }.distinct().also {
        require(it.all { q -> q in queryTypes }) { "Unsupported DNS query type" }
    }
    val responseCodes = listOf("NOERROR", "FORMERR", "SERVFAIL", "NXDOMAIN", "NOTIMP", "REFUSED")
    fun operation(rule: Rule) = rule.operation ?: "route"
    fun responseId(rule: Rule) = rule.responseFrom.orEmpty()
    private fun tag(id: String) = "nf-eval-$id"

    private fun cidrs(rule: Rule): List<String> = tokens(rule.responseCidrs.orEmpty()).onEach { value ->
        val parts = value.split('/')
        require(parts.size in 1..2) { "Invalid IP/CIDR" }
        val host = parts.first()
        val ipv6 = ':' in host
        if (ipv6) {
            // Only numeric IPv6 literals; never resolve a hostname in validation.
            require(host.matches(Regex("[0-9a-fA-F:.]+")) &&
                runCatching { java.net.InetAddress.getByName(host) }.isSuccess) { "Invalid IPv6 address" }
        } else {
            val octets = host.split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) &&
                it.toInt() in 0..255 && (it.length == 1 || !it.startsWith('0')) }) { "Invalid IPv4 address" }
        }
        if (parts.size == 2) require(parts[1].matches(Regex("[0-9]{1,3}")) &&
            parts[1].toInt() in 0..(if (ipv6) 128 else 32)) { "Invalid IP prefix length" }
    }

    fun validate(rule: Rule) {
        require(rule.id.isNotBlank() && rule.name.isNotBlank()) { "Rule name is required" }
        require(rule.match in setOf("exact", "suffix")) { "Invalid domain match" }
        val op = operation(rule)
        val ref = responseId(rule)
        val code = rule.responseCode.orEmpty()
        val ip = rule.responseIp.orEmpty()
        require(op in setOf("route", "evaluate", "respond")) { "Invalid DNS action" }
        require(rule.target in setOf("dns-direct", "dns-remote", "block")) { "Invalid DNS server" }
        require(op != "evaluate" || rule.target != "block") { "Evaluate requires a real DNS server" }
        require(op != "evaluate" || ref.isEmpty()) { "Evaluate cannot reference another response" }
        require(op != "respond" || ref.isNotEmpty()) { "Respond requires an Evaluate rule" }
        require(code.isEmpty() || code in responseCodes) { "Invalid DNS response code" }
        require(ip in setOf("", "any", "private")) { "Invalid IP response filter" }
        val prefixes = cidrs(rule)
        require(ip.isEmpty() || prefixes.isEmpty()) { "Choose IP mode or CIDR ranges, not both" }
        require(ref.isNotEmpty() || (code.isEmpty() && ip.isEmpty() && prefixes.isEmpty())) { "Response filters require Evaluate" }
        require(domains(rule).isNotEmpty() || queries(rule).isNotEmpty() || ref.isNotEmpty()) { "Specify a domain, query type or evaluated response" }
    }
    fun decode(json: String): List<Rule> {
        val rules = requireNotNull(gson.fromJson<List<Rule>>(json, type)) { "Invalid DNS rules" }
        require(rules.size <= 256 && rules.map { it.id }.distinct().size == rules.size) { "Invalid DNS rule list" }
        validateOrder(rules)
        return rules
    }
    fun validateOrder(rules: List<Rule>) {
        val preceding = mutableMapOf<String, Rule>()
        for (rule in rules) {
            validate(rule)
            val ref = responseId(rule)
            if (ref.isNotEmpty()) {
                val evaluate = preceding[ref]
                require(evaluate != null && operation(evaluate) == "evaluate") {
                    "${rule.name}: referenced Evaluate must appear earlier in the list"
                }
                require(!rule.enabled || evaluate.enabled) {
                    "${rule.name}: disable the dependent rule before disabling Evaluate"
                }
            }
            preceding[rule.id] = rule
        }
    }
    fun encode(rules: List<Rule>): String = gson.toJson(rules).also { decode(it) }
    // When Remote DNS is unavailable, pause both its Evaluate and dependent
    // actions; never bind a response to a different DNS server.
    fun effective(rules: List<Rule>, remoteAvailable: Boolean): List<Rule> {
        validateOrder(rules)
        val available = mutableSetOf<String>()
        return rules.filter { rule ->
            val ref = responseId(rule)
            val active = rule.enabled &&
                (remoteAvailable || operation(rule) == "respond" || rule.target != "dns-remote") &&
                (ref.isEmpty() || ref in available)
            if (active && operation(rule) == "evaluate") available.add(rule.id)
            active
        }
    }

    fun options(rules: List<Rule>, remoteAvailable: Boolean): List<DNSRule_DefaultOptions> = effective(rules, remoteAvailable)
        .map { rule ->
            validate(rule)
            DNSRule_DefaultOptions().apply {
                val names = domains(rule)
                if (names.isNotEmpty()) {
                    if (rule.match == "exact") domain = names else domain_suffix = names
                }
                query_type = queries(rule).takeIf { it.isNotEmpty() }
                val ref = responseId(rule)
                if (ref.isNotEmpty()) {
                    _hack_config_map["match_response"] = tag(ref)
                    rule.responseCode?.takeIf { it.isNotEmpty() }?.let { _hack_config_map["response_rcode"] = it }
                    when (rule.responseIp) {
                        "any" -> _hack_config_map["ip_accept_any"] = true
                        "private" -> _hack_config_map["ip_is_private"] = true
                    }
                    cidrs(rule).takeIf { it.isNotEmpty() }?.let { _hack_config_map["ip_cidr"] = it }
                }
                when (operation(rule)) {
                    "evaluate" -> {
                        action = "evaluate"
                        server = rule.target
                        _hack_config_map["tag"] = tag(rule.id)
                    }
                    "respond" -> action = "respond"
                    else -> if (rule.target == "block") {
                        action = "predefined"
                        rcode = "NXDOMAIN"
                    } else {
                        action = "route"
                        server = rule.target
                    }
                }
            }
        }
}
