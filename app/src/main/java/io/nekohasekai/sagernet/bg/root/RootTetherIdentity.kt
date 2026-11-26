package io.nekohasekai.sagernet.bg.root

/** Stable identity for an active downstream, ignoring client churn and temporary IPv6 addresses. */
internal object RootTetherIdentity {
    private val linePrefix = Regex("""^\s*(\d+):\s+([A-Za-z0-9_.-]{1,15})(?:@[^\s:]+)?(?::|\s)""")
    private val ipv4 = Regex("""\binet\s+(\d+\.\d+\.\d+\.\d+/\d+)""")

    /** Canonical subnet from a kernel-supplied IPv4 address/prefix, never DNS lookup. */
    fun ipv4Network(cidr: String): String? {
        val parts = cidr.split('/')
        if (parts.size != 2) return null
        val bits = parts[1].toIntOrNull()?.takeIf { it in 16..30 } ?: return null
        val octets = parts[0].split('.').map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        if (octets.size != 4 || octets[0] == 0 || octets[0] == 127 || octets[0] >= 224) return null
        val value = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
        val network = value and (0xffffffffL shl (32 - bits))
        return (3 downTo 0).joinToString(".") { ((network ushr (it * 8)) and 255L).toString() } + "/$bits"
    }

    /** Keep link identity, but compare IPv4 route exclusions by subnet rather than host address. */
    fun routingIdentity(identity: List<String>): List<String> = identity.map { item ->
        val address = item.substringAfter(':').substringAfter(':')
        val network = ipv4Network(address)
        if (network == null) item else item.substringBeforeLast(':') + ":" + network
    }.distinct().sorted()

    fun parse(names: List<String>, dump: String): List<String> {
        val selected = names.toSet()
        return dump.lineSequence().mapNotNull { line ->
            val match = linePrefix.find(line) ?: return@mapNotNull null
            val name = match.groupValues[2]
            if (name !in selected) return@mapNotNull null
            val address = ipv4.find(line)?.groupValues?.get(1)
            "${match.groupValues[1]}:$name:${address.orEmpty()}"
        }.distinct().sorted().toList()
    }
}
