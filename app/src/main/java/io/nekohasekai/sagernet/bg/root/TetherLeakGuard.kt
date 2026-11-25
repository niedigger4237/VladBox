package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.ktx.Logs
import java.util.UUID

/**
 * Separate from routing transactions: keep the guard during a failed or replaced VPN route.
 * Only verified Android tethering interfaces are passed here. Never change the phone's IPv6 sysctls.
 * All calls are serialized by TetherVpn's worker.
 */
internal class TetherLeakGuard {
    private data class Saved(val tag: String, val names: List<String>, val offloadBefore: String?)
    private val journal = TetherResourceJournal("guard", Saved::class.java)
    private var tag = "NFV_G_" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val tracked = linkedSetOf<String>()
    private var offloadBefore: String? = null
    private var mirrorReady = false

    fun hasJournal() = journal.exists()

    fun recover(): Boolean = runCatching {
        if (!journal.exists()) return true
        val saved = journal.read()
        require(saved.tag.matches(Regex("NFV_G_[0-9a-f]{12}")) &&
            saved.names.size <= 64 && saved.names.all { TetherResourceJournal.iface(it) } &&
            saved.offloadBefore in listOf(null, "null", "0", "1"))
        mirrorReady = false
        tag = saved.tag
        tracked.clear()
        tracked.addAll(saved.names)
        offloadBefore = saved.offloadBefore
        if (TetherCrashWatchdog.recovered("guard", record())) {
            if (!journal.clear()) return false
            tracked.clear()
            offloadBefore = null
            mirrorReady = false
            tag = "NFV_G_" + UUID.randomUUID().toString().replace("-", "").take(12)
        }
        true
    }.getOrElse {
        Logs.w("VPN tether: invalid guard recovery journal")
        false
    }

    private fun record() = TetherCrashWatchdog.guard(tag, offloadBefore ?: "null", tracked)

    private fun save(mirror: Boolean = true): Boolean {
        if (!journal.write(Saved(tag, tracked.toList(), offloadBefore))) return false
        // On recovery/removal the root record may safely remain a superset.
        return !mirror || TetherCrashWatchdog.publish("guard", record())
    }

    private fun rules(name: String): List<Pair<String, String>> = listOf("iptables", "ip6tables").flatMap { tool ->
        listOf(
            tool to "FORWARD -i $name -m comment --comment $tag -j DROP",
            tool to "INPUT -i $name -p udp --dport 53 -m comment --comment $tag -j DROP",
            tool to "INPUT -i $name -p tcp --dport 53 -m comment --comment $tag -j DROP",
        )
    }

    private fun remove(name: String): Boolean {
        val commands = mutableListOf("set -e")
        for ((tool, rule) in rules(name)) {
            val hook = rule.substringBefore(' ')
            val spec = rule.substringAfter(' ')
            commands += "while $tool -w 3 -C $hook $spec 2>/dev/null; do $tool -w 3 -D $hook $spec || exit 1; done"
        }
        val result = RootShell.run(*commands.toTypedArray(), timeoutMs = 15000)
        if (!result.ok) return false
        val check = RootShell.run("iptables -w 3 -S && ip6tables -w 3 -S", timeoutMs = 10000)
        if (!check.ok || check.out.lineSequence().any { tag in it && Regex("""\s-i\s+${Regex.escape(name)}(?:\s|$)""").containsMatchIn(it) })
            return false
        tracked -= name
        return save(mirror = false)
    }

    fun sync(names: Set<String>, retainExisting: Boolean = false): Boolean {
        // During route changes preserve guards until old forwarding rules have been removed.
        if (names.isNotEmpty()) {
            if (names.size > 64 || names.any { !TetherResourceJournal.iface(it) }) return false
            var changed = false
            if (offloadBefore == null) {
                val before = RootShell.run("settings get global tether_offload_disabled", timeoutMs = 5000)
                if (!before.ok || before.out !in listOf("null", "0", "1")) return false
                offloadBefore = before.out
                changed = true
            }
            if (tracked.addAll(names)) changed = true
            // Persist all newly claimed interfaces once, before any rule or
            // setting changes. On a failed mirror, force a retry next time.
            if (changed || !mirrorReady || !journal.exists()) {
                mirrorReady = save()
                if (!mirrorReady) return false
            }
            val commands = mutableListOf("set -e")
            for (name in names) for ((tool, rule) in rules(name)) {
                val hook = rule.substringBefore(' ')
                val spec = rule.substringAfter(' ')
                commands += "if ! $tool -w 3 -C $hook $spec 2>/dev/null; then $tool -w 3 -I $hook 1 $spec; fi"
            }
            commands += "if [ \"\$(settings get global tether_offload_disabled)\" != 1 ]; then settings put global tether_offload_disabled 1; fi"
            commands += "test \"\$(settings get global tether_offload_disabled)\" = 1"
            if (!RootShell.run(*commands.toTypedArray(), timeoutMs = 25000).ok) return false
        }
        return retainExisting || prune(names)
    }

    /** Only remove stale guards; do not repeat the checks just performed by sync(). */
    fun prune(names: Set<String>): Boolean {
        for (name in (tracked - names).toList()) if (!remove(name)) return false
        if (names.isEmpty()) {
            val before = offloadBefore
            if (before != null && before != "1") {
                val restore = if (before == "null") "settings delete global tether_offload_disabled"
                    else "settings put global tether_offload_disabled ${RootShell.q(before)}"
                // Read/verify explicitly: a failed settings get must not masquerade as a successful restore.
                val current = RootShell.run("settings get global tether_offload_disabled", timeoutMs = 5000)
                if (!current.ok || current.out !in listOf("null", "0", "1")) return false
                if (current.out == "1") {
                    val result = RootShell.run("set -e", restore,
                        "test \"\$(settings get global tether_offload_disabled)\" = ${RootShell.q(before)}",
                        timeoutMs = 5000)
                    if (!result.ok) {
                        Logs.w("VPN tether: Android offload restore not confirmed")
                        return false
                    }
                }
            }
            if (before != null || journal.exists()) {
                if (!TetherCrashWatchdog.retire("guard", record()) || !journal.clear()) return false
            }
            offloadBefore = null
            mirrorReady = false
            tag = "NFV_G_" + UUID.randomUUID().toString().replace("-", "").take(12)
        }
        return true
    }

    fun clear() = sync(emptySet())
}
