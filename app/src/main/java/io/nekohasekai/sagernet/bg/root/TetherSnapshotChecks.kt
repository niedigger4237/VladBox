package io.nekohasekai.sagernet.bg.root

import java.net.InetAddress

/** Pure checks for ordinary VpnService tethering, not the four ROOT modes. */
internal object TetherSnapshotChecks {
    fun selected(active: Set<String>, selectors: List<String>, uplinks: Set<String>, reserved: Regex): Set<String>? {
        val candidates = active.filter { name ->
            selectors.any { c -> if (c.endsWith("+")) name.startsWith(c.dropLast(1)) else name == c }
        }.toSet()
        // Never interpret a conflicting observation as proof of disconnect.
        if (candidates.any { it in uplinks || reserved.matches(it) }) return null
        return candidates
    }

    fun uniqueSubnets(names: Set<String>, subnets: Map<String, List<String>>): Boolean =
        names.all { subnets[it]?.distinct()?.size == 1 }

    // Kernel IPv6 dumps compress addresses; compare numeric prefixes, not strings.
    private fun canonical(cidr: String): Pair<List<Byte>, Int>? = runCatching {
        val parts = cidr.split('/')
        if (parts.size != 2 || !parts[0].matches(Regex("[0-9a-fA-F:.]+"))) return null
        val bytes = InetAddress.getByName(parts[0]).address
        val bits = parts[1].toInt()
        if (bits !in 0..bytes.size * 8) return null
        for (i in bytes.indices) {
            val keep = (bits - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and ((0xff shl (8 - keep)) and 0xff)).toByte()
        }
        bytes.toList() to bits
    }.getOrNull()

    private fun value(tokens: List<String>, key: String): String? =
        tokens.indexOf(key).takeIf { it >= 0 }?.let { tokens.getOrNull(it + 1) }

    fun hasRouting(lines: List<String>, up: String, table: Int, pref: Int, downstreams: List<Pair<String, String>>): Boolean {
        val rows = lines.map { it.trim().split(Regex("\\s+")) }
        return downstreams.all { (name, subnet) ->
            val prefix = canonical(subnet) ?: return false
            val route = rows.any { tokens ->
                canonical(tokens.firstOrNull().orEmpty()) == prefix && value(tokens, "dev") == name &&
                    value(tokens, "via") == null
            }
            val outbound = rows.any { tokens ->
                tokens.firstOrNull() == "$pref:" && value(tokens, "iif") == name &&
                    value(tokens, "lookup") == table.toString()
            }
            val inbound = rows.any { tokens ->
                tokens.firstOrNull() == "${pref - 1}:" && value(tokens, "iif") == up &&
                    value(tokens, "lookup") == table.toString() && canonical(value(tokens, "to").orEmpty()) == prefix
            }
            route && outbound && inbound
        }
    }
}
