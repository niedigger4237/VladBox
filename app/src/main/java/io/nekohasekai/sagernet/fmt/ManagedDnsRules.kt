package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import java.net.IDN
import java.util.Locale
import java.util.UUID

/** Stage 1: explicit, ordered DNS rules independent of traffic routing. */
internal object ManagedDnsRules {
    data class Rule(
        val id: String = UUID.randomUUID().toString(),
        val name: String = "",
        val enabled: Boolean = true,
        val domains: String = "",
        val match: String = "suffix",
        val queries: String = "",
        val target: String = "dns-direct"
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
    fun validate(rule: Rule) {
        require(rule.id.isNotBlank() && rule.name.isNotBlank()) { "Rule name is required" }
        require(rule.match in setOf("exact", "suffix")) { "Invalid domain match" }
        require(rule.target in setOf("dns-direct", "dns-remote", "block")) { "Invalid DNS action" }
        require(domains(rule).isNotEmpty() || queries(rule).isNotEmpty()) { "Specify a domain or query type" }
    }
    fun decode(json: String): List<Rule> {
        val rules = requireNotNull(gson.fromJson<List<Rule>>(json, type)) { "Invalid DNS rules" }
        require(rules.size <= 256 && rules.map { it.id }.distinct().size == rules.size) { "Invalid DNS rule list" }
        rules.forEach(::validate)
        return rules
    }
    fun encode(rules: List<Rule>): String = gson.toJson(rules).also { decode(it) }
    fun options(rules: List<Rule>, remoteAvailable: Boolean): List<DNSRule_DefaultOptions> = rules
        .filter { it.enabled && (remoteAvailable || it.target != "dns-remote") }
        .map { rule ->
            validate(rule)
            DNSRule_DefaultOptions().apply {
                val names = domains(rule)
                if (names.isNotEmpty()) {
                    if (rule.match == "exact") domain = names else domain_suffix = names
                }
                query_type = queries(rule).takeIf { it.isNotEmpty() }
                if (rule.target == "block") {
                    action = "predefined"
                    rcode = "NXDOMAIN"
                } else {
                    action = "route"
                    server = rule.target
                }
            }
        }
}
