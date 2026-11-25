package io.nekohasekai.sagernet.bg.root

import android.os.Process
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.util.UUID

/** Worker-owned bridge between app journals and the independent root watchdog.
 * A live lease is never replaced. A failed handoff blocks new routing mutations.
 */
internal object TetherCrashWatchdog {
    private const val DIR = "${RootCore.BASE}/tether-recovery"
    private const val BIN = "${RootCore.BASE}/bin"
    private const val EXEC = "$BIN/tether-crash-recovery.sh"
    private const val WATCH = "$BIN/tether-crash-watchdog.sh"
    private var token: String? = null

    private fun name(kind: String): String {
        require(kind in setOf("ipv4", "ipv6", "guard"))
        return "$DIR/$kind"
    }

    fun route(family: Int, boot: String, suffix: String, table: Int, pref: Int,
              up: String, down: List<Pair<String, String>>): String = buildString {
        appendLine("route $family $boot $suffix $table $pref $up")
        down.distinct().sortedWith(compareBy({ it.first }, { it.second })).forEach {
            appendLine("down ${it.first} ${it.second}")
        }
    }

    fun guard(tag: String, before: String, names: Collection<String>): String = buildString {
        appendLine("guard $tag $before")
        names.distinct().sorted().forEach { appendLine("iface $it") }
    }

    /** Old watcher holds this lock for its whole lifetime. Do not race it. */
    fun awaitPrevious(): Boolean {
        if (token != null) return true
        val result = RootShell.run(
            "set -e", "umask 077",
            "mkdir -p ${RootShell.q(DIR)} && chmod 700 ${RootShell.q(DIR)}",
            "command -v flock >/dev/null && command -v timeout >/dev/null",
            "(flock -x -w 8 9) 9>${RootShell.q("$DIR/lock")}",
            timeoutMs = 12000,
        )
        return result.ok
    }

    private fun install(asset: String): Boolean {
        val tmp = File.createTempFile("tether-watch-", ".sh", SagerNet.application.noBackupFilesDir)
        return try {
            SagerNet.application.assets.open("root/$asset").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val target = "$BIN/$asset"
            RootShell.run("set -e", "umask 077", "mkdir -p ${RootShell.q(BIN)}",
                "cp ${RootShell.q(tmp)} ${RootShell.q(target + ".new")}",
                "chmod 700 ${RootShell.q(target + ".new")}",
                "mv -f ${RootShell.q(target + ".new")} ${RootShell.q(target)}").ok
        } finally { tmp.delete() }
    }

    fun healthy(): Boolean {
        val id = token ?: return false
        return RootShell.run(
            "set -e",
            "test \"\$(cat ${RootShell.q("$DIR/ready")})\" = ${RootShell.q(id)}",
            // A stale ready file is not enough: the worker must hold its lock.
            "command -v flock >/dev/null",
            "if (flock -n -x 9) 9>${RootShell.q("$DIR/lock")}; then exit 1; else test \"\$?\" = 1; fi",
            timeoutMs = 5000,
        ).ok
    }

    fun arm(boot: String): Boolean = runCatching {
        if (token != null) return healthy()
        if (!awaitPrevious() || !install("tether-crash-recovery.sh") || !install("tether-crash-watchdog.sh")) return false
        val id = UUID.randomUUID().toString().replace("-", "")
        val pid = Process.myPid()
        val r = RootShell.run(
            "set -e", "umask 077",
            "(",
            "flock -x -w 5 9",
            "test ! -e ${RootShell.q("$DIR/ipv4")} && test ! -e ${RootShell.q("$DIR/ipv6")} && test ! -e ${RootShell.q("$DIR/guard")}",
            "started=\$(sed 's/^.*) //' /proc/$pid/stat | awk '{print \$20}')",
            "case \"\$started\" in ''|*[!0-9]*) exit 1;; esac",
            "rm -f ${RootShell.q("$DIR/ready")} ${RootShell.q("$DIR/release")}",
            "printf '%s %s %s %s\\n' ${RootShell.q(id)} ${RootShell.q(boot)} $pid \"\$started\" > ${RootShell.q("$DIR/lease.new")}",
            "mv -f ${RootShell.q("$DIR/lease.new")} ${RootShell.q("$DIR/lease")}",
            ") 9>${RootShell.q("$DIR/lock")}",
            "nohup sh ${RootShell.q(WATCH)} ${RootShell.q(id)} </dev/null >/dev/null 2>&1 &",
            timeoutMs = 10000,
        )
        if (!r.ok) return false
        token = id
        repeat(20) {
            if (healthy()) {
                Logs.i("VPN tether: independent recovery watchdog ready")
                return true
            }
            Thread.sleep(250)
        }
        false
    }.getOrElse {
        Logs.w("VPN tether: watchdog startup failed")
        false
    }

    /** Must succeed before the matching kernel mutation. Root-owned records
     * are validated by the same executor that will consume them. */
    fun publish(kind: String, record: String): Boolean {
        val id = token ?: return false
        if (!healthy()) return false
        val target = name(kind)
        return RootShell.run(
            "set -e", "umask 077",
            "test \"\$(cat ${RootShell.q("$DIR/ready")})\" = ${RootShell.q(id)}",
            "printf '%s' ${RootShell.q(record)} > ${RootShell.q(target + ".new")}",
            "sh ${RootShell.q(EXEC)} --validate ${RootShell.q(target + ".new")}",
            "rm -f ${RootShell.q("$DIR/recovered-$kind")}",
            "mv -f ${RootShell.q(target + ".new")} ${RootShell.q(target)}",
            timeoutMs = 5000,
        ).ok
    }

    private fun match(kind: String, record: String, receipt: Boolean): String {
        val path = if (receipt) "$DIR/recovered-$kind" else name(kind)
        // Guard names can shrink during successful partial cleanup. Its unique
        // tag and original offload value identify the ownership transaction.
        return if (kind == "guard")
            "test \"\$(head -n 1 ${RootShell.q(path)})\" = ${RootShell.q(record.lineSequence().first())}"
        else "test \"\$(cat ${RootShell.q(path)})\" = ${RootShell.q(record.trimEnd())}"
    }

    fun recovered(kind: String, record: String): Boolean {
        if (token != null) return false
        return RootShell.run("test -f ${RootShell.q("$DIR/recovered-$kind")} && ${match(kind, record, true)}",
            timeoutMs = 5000).ok
    }

    /** Called only after verified cleanup, or when matching a receipt.
     * Never remove someone else's pending record. */
    fun retire(kind: String, record: String): Boolean {
        val path = name(kind)
        return RootShell.run(
            "set -e",
            "if [ -e ${RootShell.q(path)} ]; then ${match(kind, record, false)}; mv -f ${RootShell.q(path)} ${RootShell.q("$DIR/recovered-$kind")}; fi",
            timeoutMs = 5000,
        ).ok
    }

    fun release(): Boolean {
        val id = token ?: return awaitPrevious()
        val r = RootShell.run(
            "set -e",
            "test ! -e ${RootShell.q("$DIR/ipv4")} && test ! -e ${RootShell.q("$DIR/ipv6")} && test ! -e ${RootShell.q("$DIR/guard")}",
            "printf '%s\\n' ${RootShell.q(id)} > ${RootShell.q("$DIR/release.new")}",
            "mv -f ${RootShell.q("$DIR/release.new")} ${RootShell.q("$DIR/release")}",
            "(flock -x -w 8 9) 9>${RootShell.q("$DIR/lock")}",
            timeoutMs = 12000,
        )
        if (!r.ok) return false
        token = null
        return true
    }

    fun awaitIdleForRoot(): Boolean {
        // Devices that have never used the autonomous watchdog need no lock.
        val absent = RootShell.run("test ! -d ${RootShell.q(DIR)}", timeoutMs = 5000)
        if (absent.ok) return true
        return awaitPrevious() && noPendingRecords()
    }

    fun noPendingRecords(): Boolean = RootShell.run(
        "test ! -e ${RootShell.q("$DIR/ipv4")} && test ! -e ${RootShell.q("$DIR/ipv6")} && test ! -e ${RootShell.q("$DIR/guard")}",
        timeoutMs = 5000,
    ).ok

    fun armed() = token != null
}
