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
        return try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            val t2 = Thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            t1.start(); t2.start()
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return Result(-1, out.toString(), "timeout")
            }
            t1.join(1000); t2.join(1000)
            Result(p.exitValue(), out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            Logs.w(e)
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    fun isAvailable(): Boolean = run("id -u").let { it.ok && it.out.trim() == "0" }

    /** Single-quote a value for a shell command. */
    fun q(v: String) = "'" + v.replace("'", "'\\''") + "'"

    fun q(f: File) = q(f.absolutePath)
}
