package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.ktx.Logs

/**
 * Extra software forwarding guard for managed ROOT tethering with system IPv6 disabled.
 * No INPUT/OUTPUT changes, no global sysctl or Android offload setting changes.
 * This is not a hardware-offload kill switch; native interception still owns its rules.
 */
internal object RootTetherIpv6Guard {
    private const val TAG = "NF_ROOT_TETHER_V6_BLOCK"
    private const val TOOL = "ip6tables -w 3"
    private val iface = Regex("[A-Za-z0-9_.-]{1,15}")
    private fun valid(name: String) = iface.matches(name) &&
        name !in setOf("lo", RootDaemon.TUN_DEVICE, RootDaemon.HEV_TUN_DEVICE)

    internal fun ownedNames(output: String): Set<String> = output.lineSequence()
        .filter { Regex("""--comment\s+"?$TAG"?(?:\s|$)""").containsMatchIn(it) }
        .mapNotNull { Regex("""(?:^|\s)-i\s+([A-Za-z0-9_.-]{1,15})(?:\s|$)""").find(it)?.groupValues?.get(1) }
        .filter(::valid).toSet()

    private fun spec(name: String) = "-i $name -m comment --comment $TAG -j DROP"
    private fun list() = RootShell.run("$TOOL -S FORWARD", timeoutMs = 5000)

    @Synchronized
    fun ensure(names: List<String>): Boolean {
        if (names.isEmpty()) return true
        if (names.any { !valid(it) }) return false
        val before = list()
        if (!before.ok) return false
        val selected = names.distinct()
        // Verify our entire group precedes any native or netd accept/jump rule.
        val first = before.out.lineSequence().filter { it.startsWith("-A FORWARD ") }
            .take(selected.size).toList()
        if (first.size == selected.size && first.all { TAG in it } && ownedNames(first.joinToString("\n")) == selected.toSet()) return true
        val cmds = mutableListOf("set -e")
        for (name in selected) {
            // Insert a fresh rule before removing older copies: no intentional
            // unprotected interval while Android rebuilds its chain.
            cmds += "$TOOL -I FORWARD 1 ${spec(name)}"
        }
        val result = RootShell.run(*cmds.toTypedArray(), timeoutMs = 15000)
        if (!result.ok) return false
        // Instead of racing netd line-number updates, keep any older copies
        // until cleanup. A successful head verification prevents repeated adds.
        val after = list()
        val leading = after.out.lineSequence().filter { it.startsWith("-A FORWARD ") }
            .take(selected.size).toList()
        val ok = after.ok && leading.size == selected.size && leading.all { TAG in it } &&
            ownedNames(leading.joinToString("\n")) == selected.toSet()
        if (ok) Logs.i("ROOT tether IPv6 guard installed for $selected")
        return ok
    }

    @Synchronized
    fun clear(): Boolean {
        val before = list()
        if (!before.ok) {
            val error = before.err.lowercase(java.util.Locale.ROOT)
            return before.code == 127 || "protocol not supported" in error || "address family not supported" in error
        }
        if (before.out.lineSequence().none { TAG in it }) return true
        val cmds = mutableListOf("set -e")
        for (name in ownedNames(before.out)) {
            val rule = spec(name)
            cmds += "while $TOOL -C FORWARD $rule 2>/dev/null; do $TOOL -D FORWARD $rule || exit 1; done"
        }
        if (!RootShell.run(*cmds.toTypedArray(), timeoutMs = 15000).ok) return false
        val after = list()
        return after.ok && after.out.lineSequence().none { TAG in it }
    }
}
