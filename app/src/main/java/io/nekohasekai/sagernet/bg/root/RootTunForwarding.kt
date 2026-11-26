package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.ktx.Logs

/** Scoped exceptions to Android netd's tether forwarding chain for managed ROOT TUN.
 * Do not change INPUT, default policies, or other modes' chains.
 * Stable ownership marker allows cleanup after an interrupted app session.
 */
internal object RootTunForwarding {
    private const val TAG = "NF_ROOT_TUN_FWD"
    private const val CHAIN = "tetherctrl_FORWARD"
    private val iface = Regex("[A-Za-z0-9_.-]{1,15}")
    private val tunnel = RootDaemon.TUN_DEVICE

    private fun valid(name: String) = iface.matches(name) && name != tunnel &&
        name != RootDaemon.HEV_TUN_DEVICE && name != "lo"

    private fun specs(name: String): List<String> {
        require(valid(name))
        return listOf(
            "-i $name -o $tunnel -m comment --comment $TAG -j ACCEPT",
            "-i $tunnel -o $name -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment $TAG -j ACCEPT",
        )
    }

    private fun list(tool: String): RootShell.Result = RootShell.run("$tool -w 3 -S $CHAIN", timeoutMs = 5000L)

    // Parse only our own tagged rules. Never execute arbitrary text from iptables.
    private fun ownedNames(output: String): Set<String> = output.lineSequence()
        .filter { Regex("""--comment\s+"?$TAG"?(?:\s|$)""").containsMatchIn(it) }
        .mapNotNull { line ->
            val input = Regex("""(?:^|\s)-i\s+([A-Za-z0-9_.-]{1,15})(?:\s|$)""").find(line)?.groupValues?.get(1)
            val outputIf = Regex("""(?:^|\s)-o\s+([A-Za-z0-9_.-]{1,15})(?:\s|$)""").find(line)?.groupValues?.get(1)
            when {
                input == tunnel && outputIf != null && valid(outputIf) -> outputIf
                outputIf == tunnel && input != null && valid(input) -> input
                else -> null
            }
        }.toSet()

    @Synchronized
    fun clear(): Boolean {
        val v4 = clearFamily("iptables")
        // IPv6 may be compiled out; only skip a confirmed unsupported tool/table.
        val v6 = clearFamily("ip6tables", optional = true)
        return v4 && v6
    }

    private fun clearFamily(tool: String, optional: Boolean = false): Boolean {
        val before = list(tool)
        if (!before.ok) {
            // The chain can be absent on a device without Android tethering. Verify
            // against a successful full table listing rather than assuming failure is absence.
            val full = RootShell.run("$tool -w 3 -S", timeoutMs = 5000L)
            if (full.ok) return full.out.lineSequence().none { TAG in it }
            val error = full.err.lowercase(java.util.Locale.ROOT)
            if (optional && (full.code == 127 || "protocol not supported" in error ||
                        "address family not supported" in error)) return true
            return false
        }
        val commands = mutableListOf("set -e")
        for (name in ownedNames(before.out)) {
            for (spec in specs(name)) {
                commands += "while $tool -w 3 -C $CHAIN $spec 2>/dev/null; do $tool -w 3 -D $CHAIN $spec || exit 1; done"
            }
        }
        if (commands.size > 1 && !RootShell.run(*commands.toTypedArray(), timeoutMs = 15000L).ok) return false
        val after = list(tool)
        val clean = if (after.ok) after.out.lineSequence().none { TAG in it } else {
            val full = RootShell.run("$tool -w 3 -S", timeoutMs = 5000L)
            full.ok && full.out.lineSequence().none { TAG in it }
        }
        if (clean && commands.size > 1) Logs.i("ROOT TUN forwarding ($tool): owned exceptions removed")
        return clean
    }

    @Synchronized
    fun ensure(names: List<String>, ipv6: Boolean = false): Boolean {
        if (!ensureFamily("iptables", names)) return false
        return !ipv6 || ensureFamily("ip6tables", names)
    }

    private fun ensureFamily(tool: String, names: List<String>): Boolean {
        if (names.isEmpty()) return true
        if (names.any { !valid(it) }) return false
        val commands = mutableListOf("set -e", "ip link show dev $tunnel >/dev/null")
        for (name in names.distinct()) {
            commands += "ip link show dev $name >/dev/null"
            for (spec in specs(name)) {
                // Same position as the successful device test, before netd's terminal DROP.
                commands += "if ! $tool -w 3 -C $CHAIN $spec 2>/dev/null; then $tool -w 3 -I $CHAIN 2 $spec; echo installed; fi"
                commands += "$tool -w 3 -C $CHAIN $spec"
            }
        }
        val result = RootShell.run(*commands.toTypedArray(), timeoutMs = 15000L)
        if (!result.ok) Logs.w("ROOT TUN forwarding ($tool): cannot verify exceptions: ${result.err.take(500)}")
        else if (result.out.contains("installed")) Logs.i("ROOT TUN forwarding ($tool): exceptions installed for $names")
        return result.ok
    }
}
