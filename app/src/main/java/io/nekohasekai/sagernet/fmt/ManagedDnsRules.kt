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
        val responseCidrs: String? = null,
        // Query options inherit global settings when absent.
        val queryTimeout: String? = null,
        val rewriteTtl: Long? = null,
        val cacheMode: String? = null,
        val removeClientSubnet: Boolean? = null,
        val logicalMode: String? = null,
        val srs: List<String>? = null
    )
    private val gson = Gson()
    private val type = object : TypeToken<List<Rule>>() {}.type
    private val queryTypes = setOf("A", "AAAA", "HTTPS", "SVCB", "TXT", "MX", "NS", "PTR", "SRV", "CNAME", "CAA", "SOA", "DNSKEY", "DS")
    private fun tokens(value: String) = value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val targets = listOf("dns-local" to "System DNS", "dns-direct" to "Direct DNS",
        "dns-remote" to "Remote DNS", "dns-bootstrap" to "Bootstrap DNS", "block" to "Block (NXDOMAIN)")
    fun targetLabel(target: String) = targets.firstOrNull { it.first == target }?.second ?: target

    private fun domainName(raw: String): String {
        val value = IDN.toASCII(raw.removePrefix(".").removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        require(value.isNotBlank() && value.length <= 253 && value.split('.').all { it.isNotBlank() && it.length <= 63 }) { "Invalid DNS domain" }
        return value
    }
    private fun domainFields(rule: Rule): Map<String, List<String>> {
        val entries = tokens(rule.domains).map { raw ->
            when {
                raw.startsWith("full:") -> "domain" to domainName(raw.removePrefix("full:"))
                raw.startsWith("domain:") -> "domain_suffix" to domainName(raw.removePrefix("domain:"))
                raw.startsWith("regexp:") -> "domain_regex" to raw.removePrefix("regexp:").also {
                    require(it.isNotBlank() && runCatching { Regex(it) }.isSuccess) { "Invalid domain regular expression" }
                }
                raw.startsWith("keyword:") -> "domain_keyword" to raw.removePrefix("keyword:").also {
                    require(it.isNotBlank()) { "Empty domain keyword" }
                }.lowercase(Locale.ROOT)
                else -> (if (rule.match == "exact") "domain" else "domain_suffix") to domainName(raw)
            }
        }
        return entries.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
    }
    fun ruleSets(rule: Rule): List<String> = rule.srs.orEmpty().distinct().also { sets ->
        require(sets.size <= 512 && sets.all { it.matches(Regex("geosite:[A-Za-z0-9._!-]+")) }) {
            "DNS query matching supports Geosite SRS only"
        }
    }

    /** Copy only the domain part, not transport/IP/application predicates or actions.
     * The user reviews the new independent DNS rule before saving it.
     */
    fun copyDomainConditions(name: String, input: String): Rule {
        val entries = tokens(input)
        val result = Rule(name = name, domains = entries.filterNot { it.startsWith("geosite:") }.joinToString("\n"),
            srs = entries.filter { it.startsWith("geosite:") }, target = "dns-local")
        validate(result)
        return result
    }

    private fun queries(rule: Rule) = tokens(rule.queries).map { it.uppercase(Locale.ROOT) }.distinct().also {
        require(it.all { q -> q in queryTypes }) { "Unsupported DNS query type" }
    }
    val responseCodes = listOf("NOERROR", "FORMERR", "SERVFAIL", "NXDOMAIN", "NOTIMP", "REFUSED")
    fun operation(rule: Rule) = rule.operation ?: "route"
    fun responseId(rule: Rule) = rule.responseFrom.orEmpty()
    val queryTimeouts = listOf("3s", "5s", "10s", "15s", "30s")
    fun performsQuery(rule: Rule): Boolean =
        operation(rule) == "evaluate" || (operation(rule) == "route" && rule.target != "block")
    fun tuningSummary(rule: Rule): String = buildList {
        if (performsQuery(rule)) {
            rule.queryTimeout?.takeIf { it.isNotEmpty() }?.let { add("Timeout: $it") }
            rule.rewriteTtl?.let { add("TTL: ${it}s") }
            when (rule.cacheMode) {
                "no_cache" -> add("No cache")
                "no_optimistic" -> add("No optimistic cache")
            }
            if (rule.removeClientSubnet == true) add("Remove ECS")
        }
    }.joinToString(" · ")
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
        require(targets.any { it.first == rule.target }) { "Invalid DNS server" }
        require(op != "evaluate" || rule.target != "block") { "Evaluate requires a real DNS server" }
        require(op != "evaluate" || ref.isEmpty()) { "Evaluate cannot reference another response" }
        require(op != "respond" || ref.isNotEmpty()) { "Respond requires an Evaluate rule" }
        require(code.isEmpty() || code in responseCodes) { "Invalid DNS response code" }
        require(ip in setOf("", "any", "private")) { "Invalid IP response filter" }
        val prefixes = cidrs(rule)
        require(rule.logicalMode.orEmpty() in LogicalRulePolicy.modes) { "Invalid logical mode" }
        require(rule.logicalMode.isNullOrEmpty() || ref.isEmpty()) {
            "Logical request matching cannot be combined with Match Response or Respond"
        }
        require(ip.isEmpty() || prefixes.isEmpty()) { "Choose IP mode or CIDR ranges, not both" }
        require(ref.isNotEmpty() || (code.isEmpty() && ip.isEmpty() && prefixes.isEmpty())) { "Response filters require Evaluate" }
        val sets = ruleSets(rule)
        require(domainFields(rule).isNotEmpty() || sets.isNotEmpty() || queries(rule).isNotEmpty() || ref.isNotEmpty()) {
            "Specify a domain, Geosite SRS, query type or evaluated response"
        }
        require(rule.queryTimeout.isNullOrEmpty() || rule.queryTimeout in queryTimeouts) { "Invalid DNS query timeout" }
        require(rule.rewriteTtl == null || rule.rewriteTtl in 0L..4294967295L) { "TTL must be between 0 and 4294967295 seconds" }
        require(rule.cacheMode.orEmpty() in setOf("", "no_cache", "no_optimistic")) { "Invalid DNS cache mode" }
        require(performsQuery(rule) || (rule.queryTimeout.isNullOrEmpty() && rule.rewriteTtl == null &&
            rule.cacheMode.isNullOrEmpty() && rule.removeClientSubnet != true)) {
            "DNS query options are only valid for Route and Evaluate"
        }
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
    fun effective(rules: List<Rule>, remoteAvailable: Boolean, bootstrapAvailable: Boolean = false): List<Rule> {
        validateOrder(rules)
        val available = mutableSetOf<String>()
        return rules.filter { rule ->
            val ref = responseId(rule)
            val active = rule.enabled &&
                (remoteAvailable || operation(rule) == "respond" || rule.target != "dns-remote") &&
                (bootstrapAvailable || operation(rule) == "respond" || rule.target != "dns-bootstrap") &&
                (ref.isEmpty() || ref in available)
            if (active && operation(rule) == "evaluate") available.add(rule.id)
            active
        }
    }

    fun options(rules: List<Rule>, remoteAvailable: Boolean, bootstrapAvailable: Boolean = false): List<DNSRule_DefaultOptions> =
        effective(rules, remoteAvailable, bootstrapAvailable)
        .map { rule ->
            validate(rule)
            DNSRule_DefaultOptions().apply {
                val fields = domainFields(rule)
                domain = fields["domain"]
                domain_suffix = fields["domain_suffix"]
                domain_regex = fields["domain_regex"]
                domain_keyword = fields["domain_keyword"]
                rule_set = ruleSets(rule).takeIf { it.isNotEmpty() }
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
                if (performsQuery(rule)) {
                    rule.queryTimeout?.takeIf { it.isNotEmpty() }?.let { _hack_config_map["timeout"] = it }
                    rule.rewriteTtl?.let { _hack_config_map["rewrite_ttl"] = it }
                    when (rule.cacheMode) {
                        "no_cache" -> disable_cache = true
                        "no_optimistic" -> _hack_config_map["disable_optimistic_cache"] = true
                    }
                    if (rule.removeClientSubnet == true) _hack_config_map["remove_client_subnet"] = true
                }
            }.let { flat ->
                val mode = rule.logicalMode.orEmpty()
                if (mode.isEmpty()) flat else DNSRule_DefaultOptions().apply {
                    _hack_config_map.putAll(LogicalRulePolicy.dns(mode, flat.asMap()))
                }
            }
        }
}
