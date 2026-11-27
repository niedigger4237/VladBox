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

    /** The managed NAT chain contains exactly one UDP and TCP redirect per
     * selected interface. Accept iptables' optional explicit protocol match
     * and numeric IPv6 compression, but no extra matches or foreign rules. */
    fun hasDnsRedirects(lines: List<String>, chain: String, names: Set<String>, dns: String): Boolean {
        val bits = if (':' in dns) 128 else 32
        val expectedAddress = canonical("$dns/$bits") ?: return false
        val expected = names.flatMap { name -> listOf(name to "udp", name to "tcp") }.toSet()
        val actual = mutableSetOf<Pair<String, String>>()
        val prefix = listOf("-A", chain)
        for (line in lines) {
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.take(2) != prefix) continue
            val iface = value(tokens, "-i") ?: return false
            val proto = value(tokens, "-p") ?: return false
            val dest = value(tokens, "--to-destination") ?: return false
            val addr = if (dest.startsWith("[")) {
                if (!dest.endsWith("]:53")) return false
                dest.removePrefix("[").removeSuffix("]:53")
            } else {
                if (!dest.endsWith(":53") || dest.count { it == ':' } != 1) return false
                dest.removeSuffix(":53")
            }
            if (canonical("$addr/$bits") != expectedAddress) return false
            val common = prefix + listOf("-i", iface, "-p", proto)
            val tail = listOf("--dport", "53", "-j", "DNAT", "--to-destination", dest)
            if (tokens != common + tail && tokens != common + listOf("-m", proto) + tail) return false
            if (!actual.add(iface to proto)) return false
        }
        return actual == expected
    }

    /** Verify the owned forwarding and source NAT chains, not only their hooks.
     * Compare numeric prefixes because ip6tables may compress IPv6 addresses.
     * Reject duplicates, extra matches and unexpected actions. */
    fun hasForwardingAndNat(
        lines: List<String>, forward: String, post: String, up: String,
        downstreams: List<Pair<String, String>>,
    ): Boolean {
        fun normalized(tokens: List<String>): List<String>? {
            val result = tokens.toMutableList()
            for (key in listOf("-s", "-d")) {
                val index = result.indexOf(key)
                if (index < 0) continue
                val raw = result.getOrNull(index + 1) ?: return null
                val cidr = if ('/' in raw) raw else "$raw/" + if (':' in raw) "128" else "32"
                val prefix = canonical(cidr) ?: return null
                result[index + 1] = prefix.toString()
            }
            // xtables may render the state set in either order.
            val state = result.indexOf("--ctstate")
            if (state >= 0) {
                val value = result.getOrNull(state + 1) ?: return null
                result[state + 1] = value.split(',').sorted().joinToString(",")
            }
            return result
        }
        fun rules(chain: String): List<List<String>>? {
            val found = mutableListOf<List<String>>()
            for (line in lines) {
                val tokens = line.trim().split(Regex("\\s+"))
                if (tokens.take(2) == listOf("-A", chain))
                    found.add(normalized(tokens) ?: return null)
            }
            return found
        }
        val expectedForward = mutableListOf<List<String>>()
        val expectedPost = mutableListOf<List<String>>()
        for ((name, subnet) in downstreams) {
            expectedForward.add(normalized(listOf("-A", forward, "-s", subnet, "-i", name, "-o", up, "-j", "ACCEPT")) ?: return false)
            expectedForward.add(normalized(listOf("-A", forward, "-d", subnet, "-i", up, "-o", name, "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT")) ?: return false)
            expectedPost.add(normalized(listOf("-A", post, "-s", subnet, "-o", up, "-j", "MASQUERADE")) ?: return false)
        }
        val actualForward = rules(forward) ?: return false
        val actualPost = rules(post) ?: return false
        return actualForward.size == expectedForward.size && actualForward.toSet() == expectedForward.toSet() &&
            actualPost.size == expectedPost.size && actualPost.toSet() == expectedPost.toSet()
    }

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
