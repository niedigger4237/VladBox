package io.nekohasekai.sagernet.bg.root

/** Stable identity for an active downstream, ignoring client churn and temporary IPv6 addresses. */
internal object RootTetherIdentity {
    private val linePrefix = Regex("""^\s*(\d+):\s+([A-Za-z0-9_.-]{1,15})(?:@[^\s:]+)?(?::|\s)""")
    private val ipv4 = Regex("""\binet\s+(\d+\.\d+\.\d+\.\d+/\d+)""")

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
