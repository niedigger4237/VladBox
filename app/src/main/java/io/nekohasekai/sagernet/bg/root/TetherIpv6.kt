package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.ktx.Logs
import java.net.Inet6Address
import java.net.InetAddress

/** Optional NAT66 routing. Failure leaves the independent IPv6 guard in place. */
internal object TetherIpv6 {
    data class Down(val name: String, val subnet: String)
    data class Rules(val up: String, val down: List<Down>, val dns: String, val table: Int, val pref: Int, val suffix: String, val tunAddress: String) {
        val forward get() = "NFV6_F_$suffix"
        val prerouting get() = "NFV6_D_$suffix"
        val postrouting get() = "NFV6_N_$suffix"
    }

    private fun subnet(value: String, bits: Int): String? {
        if (bits !in 1..128 || ':' !in value || !value.matches(Regex("[0-9a-fA-F:]+"))) return null
        val address = runCatching { InetAddress.getByName(value) }.getOrNull() as? Inet6Address ?: return null
        if (address.isLinkLocalAddress || address.isLoopbackAddress || address.isMulticastAddress || address.isAnyLocalAddress) return null
        val bytes = address.address
        for (i in bytes.indices) {
            val keep = (bits - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and ((0xff shl (8 - keep)) and 0xff)).toByte()
        }
        return "${InetAddress.getByAddress(bytes).hostAddress}/$bits"
    }

    fun discover(names: Set<String>): List<Down>? {
        val result = RootShell.run("ip -6 -o addr show scope global", timeoutMs = 5000)
        if (!result.ok) return null
        return parsePrefixes(names, result.out)
    }

    internal fun parsePrefixes(names: Set<String>, output: String): List<Down> {
        val pattern = Regex("""^\s*\d+:\s+([A-Za-z0-9_.-]{1,15})(?:@[^\s]+)?\s+inet6\s+([0-9a-fA-F:]+)/(\d+)""")
        val invalidFlags = Regex("""\b(?:tentative|dadfailed)\b""")
        return output.lineSequence().filterNot { invalidFlags.containsMatchIn(it) }
            .mapNotNull { line ->
                val m = pattern.find(line) ?: return@mapNotNull null
                val name = m.groupValues[1]
                if (name !in names) return@mapNotNull null
                val bits = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                subnet(m.groupValues[2], bits)?.let { Down(name, it) }
            }.distinct().sortedWith(compareBy({ it.name }, { it.subnet })).toList()
    }

    fun install(r: Rules): Boolean {
        if (r.down.isEmpty() || r.down.groupBy { it.subnet }.any { it.value.size > 1 }) return false
        val s = mutableListOf("set -e")
        val filter = "ip6tables -w 3 -t filter"
        val nat = "ip6tables -w 3 -t nat"
        s += "test \"\$(cat /proc/sys/net/ipv6/conf/all/forwarding)\" = 1"
        s += "$filter -N ${r.forward}"
        s += "$nat -N ${r.prerouting}"
        s += "$nat -N ${r.postrouting}"
        s += "ip -6 route add default dev ${r.up} table ${r.table}"
        for (d in r.down) {
            s += "ip -6 route add ${d.subnet} dev ${d.name} table ${r.table}"
            s += "ip -6 rule add pref ${r.pref - 1} iif ${r.up} to ${d.subnet} lookup ${r.table}"
            s += "$filter -A ${r.forward} -i ${d.name} -s ${d.subnet} -o ${r.up} -j ACCEPT"
            s += "$filter -A ${r.forward} -i ${r.up} -o ${d.name} -d ${d.subnet} -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT"
            s += "$nat -A ${r.postrouting} -s ${d.subnet} -o ${r.up} -j MASQUERADE"
        }
        for (name in r.down.map { it.name }.distinct()) {
            for (proto in listOf("udp", "tcp"))
                s += "$nat -A ${r.prerouting} -i $name -p $proto --dport 53 -j DNAT --to-destination [${r.dns}]:53"
        }
        s += "$filter -I FORWARD 1 -j ${r.forward}"
        s += "$nat -I PREROUTING 1 -j ${r.prerouting}"
        s += "$nat -I POSTROUTING 1 -j ${r.postrouting}"
        for (name in r.down.map { it.name }.distinct())
            s += "ip -6 rule add pref ${r.pref} iif $name lookup ${r.table}"
        val result = RootShell.run(*s.toTypedArray(), timeoutMs = 20000)
        if (!result.ok) Logs.w("VPN tether IPv6 unavailable: ${result.err}; keeping IPv6 guard")
        else Logs.i("VPN tether IPv6: ${r.down} -> ${r.up}")
        return result.ok
    }

    fun cleanup(r: Rules): Boolean {
        val s = mutableListOf("set -e")
        fun detach(table: String, hook: String, chain: String) {
            val cmd = "ip6tables -w 3 -t $table"
            s += "while $cmd -C $hook -j $chain 2>/dev/null; do $cmd -D $hook -j $chain || exit 1; done"
            s += "if $cmd -S $chain >/dev/null 2>&1; then $cmd -F $chain && $cmd -X $chain || exit 1; fi"
        }
        detach("filter", "FORWARD", r.forward)
        detach("nat", "PREROUTING", r.prerouting)
        detach("nat", "POSTROUTING", r.postrouting)
        for (name in r.down.map { it.name }.distinct())
            s += "ip -6 rule del pref ${r.pref} iif $name lookup ${r.table} 2>/dev/null || true"
        for (d in r.down) {
            s += "ip -6 rule del pref ${r.pref - 1} iif ${r.up} to ${d.subnet} lookup ${r.table} 2>/dev/null || true"
            s += "ip -6 route del ${d.subnet} dev ${d.name} table ${r.table} 2>/dev/null || true"
        }
        s += "ip -6 route del default dev ${r.up} table ${r.table} 2>/dev/null || true"
        if (!RootShell.run(*s.toTypedArray(), timeoutMs = 20000).ok) return false
        val filter = RootShell.run("ip6tables -w 3 -t filter -S", timeoutMs = 5000)
        val nat = RootShell.run("ip6tables -w 3 -t nat -S", timeoutMs = 5000)
        val rules = RootShell.run("ip -6 rule show", timeoutMs = 5000)
        val routes = RootShell.run("ip -6 route show table ${r.table}", timeoutMs = 5000)
        val absent = !routes.ok && (routes.err + routes.out).contains("FIB table does not exist", true)
        val clean = filter.ok && nat.ok && rules.ok && (routes.ok || absent) &&
            listOf(r.forward, r.prerouting, r.postrouting).none { it in filter.out || it in nat.out } &&
            rules.out.lineSequence().none {
                val pref = it.substringBefore(':').trim().toIntOrNull()
                (pref == r.pref || pref == r.pref - 1) && Regex("""\blookup\s+${r.table}\b""").containsMatchIn(it)
            } && routes.out.isBlank()
        if (clean) TetherConntrack.clear("ipv6", r.down.map { it.subnet }, r.tunAddress, r.dns)
        return clean
    }
}
