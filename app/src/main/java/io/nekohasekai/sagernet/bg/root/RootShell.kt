package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.ktx.Logs
import java.io.File

/** Minimal `su` helper. Every call is a separate `su -c` so no state is shared. */
object RootShell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    fun run(vararg script: String, timeoutMs: Long = 20_000): Result {
        val cmd = script.joinToString("\n")
        var process: Process? = null
        return try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            process = p
            val out = StringBuffer()
            val err = StringBuffer()
            val t1 = Thread {
                runCatching { p.inputStream.bufferedReader().useLines { lines -> lines.forEach { out.appendLine(it) } } }
            }.apply { isDaemon = true }
            val t2 = Thread {
                runCatching { p.errorStream.bufferedReader().useLines { lines -> lines.forEach { err.appendLine(it) } } }
            }.apply { isDaemon = true }
            t1.start(); t2.start()
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return Result(-1, out.toString(), "timeout")
            }
            t1.join(1000); t2.join(1000)
            // Never treat a partial stdout payload as a successful response.
            if (t1.isAlive || t2.isAlive) return Result(-1, "", "output timeout")
            Result(p.exitValue(), out.toString().trim(), err.toString().trim())
        } catch (_: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            Result(-1, "", "interrupted")
        } catch (e: Exception) {
            Logs.w(e)
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    enum class Access { GRANTED, UNAVAILABLE, UNKNOWN }

    /** Distinguish confirmed lack of usable su from a delayed root-manager prompt or transient I/O error. */
    fun probeAccess(): Access {
        val result = run("id -u", timeoutMs = 30000)
        if (result.ok && result.out.trim() == "0") return Access.GRANTED
        if (result.ok && result.out.trim().toIntOrNull() != null) return Access.UNAVAILABLE
        val diagnostic = (result.err + "\n" + result.out).lowercase(java.util.Locale.ROOT)
        if (result.code == -1) {
            // ProcessBuilder reports ENOENT when su is not accessible in this app's namespace.
            if ("error=2," in diagnostic || "enoent" in diagnostic || "error=13," in diagnostic)
                return Access.UNAVAILABLE
            return Access.UNKNOWN
        }
        if (result.code == 126 || result.code == 127 ||
            listOf("permission denied", "access denied", "root access denied", "not allowed",
                "not authorized", "su: denied", "request rejected").any { it in diagnostic })
            return Access.UNAVAILABLE
        // A silent nonzero exit may be an authorization rejection, but not proof.
        return Access.UNKNOWN
    }

    fun isAvailable(): Boolean = run("id -u").let { it.ok && it.out.trim() == "0" }

    /** Single-quote a value for a shell command. */
    fun q(v: String) = "'" + v.replace("'", "'\\''") + "'"

    fun q(f: File) = q(f.absolutePath)
}
