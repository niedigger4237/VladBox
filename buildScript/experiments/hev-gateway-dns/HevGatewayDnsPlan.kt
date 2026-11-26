package io.nekohasekai.sagernet.bg.root

/** Staged only: no shell execution, no live integration until lifecycle validation. */
internal object HevGatewayDnsPlan {
    data class Rule(val family: Int, val table: String, val chain: String, val args: List<String>)
    data class Plan(val port: Int, val interfaces: List<String>, val rules: List<Rule>)
    private val exact = Regex("[A-Za-z0-9_.-]{1,15}")
    private val reserved = setOf("lo", "asterisk0", "hevtun0", "all", "default", ".", "..")

    fun build(selected: Set<String>, active: Set<String>, uplinks: Set<String>,
              ignored: Set<String>, port: Int, occupied: Set<Int>, ipv6: Boolean): Plan? {
        if (port !in 1024..65535 || port in occupied || selected.size > 16) return null
        if (selected.any { !exact.matches(it) || it in reserved || it !in active || it in uplinks || it in ignored }) return null
        val names = selected.sorted()
        val rules = mutableListOf<Rule>()
        for (family in if (ipv6) listOf(4, 6) else listOf(4)) {
            val nat = "NF_HEV_DNS${family}_PRE"
            val input = "NF_HEV_DNS${family}_IN"
            for (name in names) for (protocol in listOf("udp", "tcp")) {
                val match = listOf("-i", name, "-p", protocol, "-m", protocol)
                rules += Rule(family, "nat", nat, match + listOf("--dport", "53", "-m", "addrtype", "--dst-type", "LOCAL", "-j", "REDIRECT", "--to-ports", port.toString()))
                rules += Rule(family, "filter", input, match + listOf("--dport", port.toString(), "-m", "conntrack", "--ctdir", "ORIGINAL", "--ctstate", "DNAT", "--ctorigdstport", "53", "-j", "ACCEPT"))
            }
            for (protocol in listOf("udp", "tcp")) {
                rules += Rule(family, "filter", input, listOf("-p", protocol, "-m", protocol, "--dport", port.toString(), "-j", "DROP"))
            }
        }
        return Plan(port, names, rules)
    }
}
