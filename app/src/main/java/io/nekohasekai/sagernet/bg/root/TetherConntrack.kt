package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.ktx.Logs

/** Best effort: remove only translations pointing to this session's virtual addresses. */
internal object TetherConntrack {
    fun clear(family: String, subnets: List<String>, tun: String, dns: String) {
        if (subnets.isEmpty()) return
        if (!RootShell.run("command -v conntrack >/dev/null", timeoutMs = 2000).ok) {
            Logs.i("VPN tether: conntrack utility unavailable; old NAT flows may need to expire")
            return
        }
        val commands = mutableListOf<String>()
        for (subnet in subnets.distinct()) {
            val filter = "conntrack -D -f ${RootShell.q(family)} --orig-src ${RootShell.q(subnet)}"
            commands += "$filter --reply-dst ${RootShell.q(tun)}"
            for (proto in listOf("tcp", "udp"))
                commands += "$filter --reply-src ${RootShell.q(dns)} -p $proto --dport 53"
        }
        // Exit status 1 can mean no matching entries. Preserve diagnostics without aborting cleanup.
        val result = RootShell.run(*commands.map { "$it 2>&1 || true" }.toTypedArray(), timeoutMs = 5000)
        if (!result.ok) Logs.w("VPN tether: scoped conntrack cleanup incomplete: ${result.err}")
        else Logs.i("VPN tether: scoped $family conntrack cleanup: ${result.out.take(500)}")
    }
}
