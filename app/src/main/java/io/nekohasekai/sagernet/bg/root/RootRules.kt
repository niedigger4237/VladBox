package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.ktx.Logs

/**
 * iptables/ip6tables TPROXY rules for the root tproxy mode.
 *
 * The external sing-box runs as uid 0 and is excluded by `--uid-owner 0`, so no
 * routing mark is needed to avoid loops. Needs kernel support for xt_TPROXY.
 */
object RootRules {

    private const val CHAIN_PRE = "NEKO_PRE"
    private const val CHAIN_OUT = "NEKO_OUT"
    private const val MARK = "0x1ee0"
    private const val TABLE = "1566"

    private val reserved4 = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.168.0.0/16", "224.0.0.0/3"
    )
    private val reserved6 = listOf("::1/128", "fe80::/10", "fc00::/7", "ff00::/8")

    private fun family(ipt: String, reserved: List<String>, port: Int, ipv6: Boolean, excludeUids: List<Int>): List<String> {
        val ip = if (ipv6) "ip -6" else "ip"
        val lo = if (ipv6) "::/0" else "0.0.0.0/0"
        val c = mutableListOf<String>()
        // policy routing: marked packets are delivered locally (to the tproxy socket)
        c += "$ip rule add fwmark $MARK lookup $TABLE pref 100 2>/dev/null"
        c += "$ip route add local $lo dev lo table $TABLE 2>/dev/null"
        for (chain in listOf(CHAIN_PRE, CHAIN_OUT)) {
            c += "$ipt -t mangle -N $chain 2>/dev/null"
            c += "$ipt -t mangle -F $chain"
            reserved.forEach { c += "$ipt -t mangle -A $chain -d $it -j RETURN" }
        }
        // transit traffic (hotspot, tethering)
        c += "$ipt -t mangle -A $CHAIN_PRE -m mark --mark $MARK -j RETURN"
        for (proto in listOf("tcp", "udp")) {
            c += "$ipt -t mangle -A $CHAIN_PRE -p $proto -j TPROXY --on-port $port --tproxy-mark $MARK/$MARK"
        }
        // locally generated traffic: skip the core itself (root) and excluded apps, then
        // mark the rest so it is re-routed through lo -> PREROUTING -> TPROXY
        c += "$ipt -t mangle -A $CHAIN_OUT -m owner --uid-owner 0 -j RETURN"
        excludeUids.forEach { c += "$ipt -t mangle -A $CHAIN_OUT -m owner --uid-owner $it -j RETURN" }
        c += "$ipt -t mangle -A $CHAIN_OUT -m mark --mark $MARK -j RETURN"
        for (proto in listOf("tcp", "udp")) {
            c += "$ipt -t mangle -A $CHAIN_OUT -p $proto -j MARK --set-mark $MARK"
        }
        c += "$ipt -t mangle -C PREROUTING -j $CHAIN_PRE 2>/dev/null || $ipt -t mangle -A PREROUTING -j $CHAIN_PRE"
        c += "$ipt -t mangle -C OUTPUT -j $CHAIN_OUT 2>/dev/null || $ipt -t mangle -A OUTPUT -j $CHAIN_OUT"
        return c
    }

    private fun clearFamily(ipt: String, ipv6: Boolean): List<String> {
        val ip = if (ipv6) "ip -6" else "ip"
        val c = mutableListOf<String>()
        c += "$ipt -t mangle -D PREROUTING -j $CHAIN_PRE 2>/dev/null"
        c += "$ipt -t mangle -D OUTPUT -j $CHAIN_OUT 2>/dev/null"
        for (chain in listOf(CHAIN_PRE, CHAIN_OUT)) {
            c += "$ipt -t mangle -F $chain 2>/dev/null"
            c += "$ipt -t mangle -X $chain 2>/dev/null"
        }
        c += "$ip rule del fwmark $MARK lookup $TABLE 2>/dev/null"
        c += "$ip route flush table $TABLE 2>/dev/null"
        return c
    }

    fun clear() {
        val r = RootShell.run(*(clearFamily("iptables", false) + clearFamily("ip6tables", true)).toTypedArray(), "true")
        if (!r.ok) Logs.w("tproxy cleanup: ${r.err}")
    }

    /** @return null on success, otherwise an error message */
    fun apply(port: Int, ipv6: Boolean, excludeUids: List<Int>): String? {
        clear()
        val v4 = RootShell.run(*family("iptables", reserved4, port, false, excludeUids).toTypedArray(), "true")
        // TPROXY target missing => the kernel cannot do it, report that explicitly
        val check = RootShell.run("iptables -t mangle -S $CHAIN_PRE")
        if (!check.ok || !check.out.contains("TPROXY")) {
            clear()
            return "TPROXY rules were not installed (kernel without xt_TPROXY?): ${v4.err.ifBlank { check.err }}"
        }
        if (ipv6) RootShell.run(*family("ip6tables", reserved6, port, true, excludeUids).toTypedArray(), "true")
        return null
    }
}
