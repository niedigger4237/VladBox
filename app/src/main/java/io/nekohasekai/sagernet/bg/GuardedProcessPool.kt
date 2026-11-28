package io.nekohasekai.sagernet.bg

import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.MainThread
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.LogRedactor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import libcore.Libcore
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

class GuardedProcessPool(private val onFatal: suspend (IOException) -> Unit) : CoroutineScope {
    companion object {
        private val pid by lazy {
            Class.forName("java.lang.ProcessManager\$ProcessImpl").getDeclaredField("pid")
                .apply { isAccessible = true }
        }
    }

    private inner class Guard(
        private val cmd: List<String>,
        private val env: Map<String, String> = mapOf()
    ) {
        private lateinit var process: Process

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            val redactor = LogRedactor.Stream()
            input.bufferedReader().use { reader ->
                reader.forEachLine { logger(redactor.redact(it)) }
            }
        } catch (_: IOException) {
        }    // ignore

        fun start() {
            process = ProcessBuilder(cmd).directory(SagerNet.application.noBackupFilesDir).apply {
                environment().putAll(env)
            }.start()
        }

        suspend fun looper(onRestartCallback: (suspend () -> Unit)?) {
            var running = true
            var restarted = false
            val cmdName = File(cmd.first()).nameWithoutExtension
            // A waiter must be able to publish the exit even after cancellation.
            val exitChannel = Channel<Int>(capacity = 1)
            try {
                while (true) {
                    val child = process
                    thread(name = "stderr-$cmdName", isDaemon = true) {
                        streamLogger(child.errorStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                    }
                    thread(name = "stdout-$cmdName", isDaemon = true) {
                        streamLogger(child.inputStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                    }
                    // Waiting for exit must not depend on stdout reaching EOF:
                    // descendants may still hold the pipe open.
                    thread(name = "wait-$cmdName", isDaemon = true) {
                        exitChannel.trySend(child.waitFor())
                    }
                    val startTime = SystemClock.elapsedRealtime()
                    if (restarted) onRestartCallback?.invoke()
                    val exitCode = exitChannel.receive()
                    running = false
                    when {
                        SystemClock.elapsedRealtime() - startTime < 1000 -> throw IOException(
                            "$cmdName exits too fast (exit code: $exitCode)"
                        )

                        exitCode == 128 + OsConstants.SIGKILL -> Logs.w("$cmdName was killed")
                        else -> Logs.w(IOException("$cmdName unexpectedly exits with code $exitCode"))
                    }
                    Logs.i("restart process: $cmdName (last exit code: $exitCode)")
                    ensureActive()
                    start()
                    running = true
                    restarted = true
                }
            } catch (e: IOException) {
                Logs.w("error occurred. stop guard: $cmdName")
                // The notification belongs to this pool, not a replacement session.
                this@GuardedProcessPool.launch { onFatal(e) }
            } finally {
                if (running) withContext(NonCancellable) {  // clean-up cannot be cancelled
                    if (Build.VERSION.SDK_INT < 24) {
                        try {
                            Os.kill(pid.get(process) as Int, OsConstants.SIGTERM)
                        } catch (e: ErrnoException) {
                            if (e.errno != OsConstants.ESRCH) Logs.w(e)
                        } catch (e: ReflectiveOperationException) {
                            Logs.w(e)
                        }
                        if (withTimeoutOrNull(500) { exitChannel.receive() } != null) return@withContext
                    }
                    process.destroy()                       // kill the process
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (withTimeoutOrNull(1000) { exitChannel.receive() } != null) return@withContext
                        process.destroyForcibly()           // Force to kill the process if it's still alive
                    }
                    exitChannel.receive()
                }                                           // otherwise process already exited, nothing to be done
            }
        }
    }

    override val coroutineContext = Dispatchers.Main.immediate + SupervisorJob()
    var processCount = 0

    @MainThread
    fun start(
        cmd: List<String>,
        env: MutableMap<String, String> = mutableMapOf(),
        onRestartCallback: (suspend () -> Unit)? = null
    ) {
        coroutineContext.ensureActive()
        Logs.i("start process: ${File(cmd.first()).nameWithoutExtension}")
        Guard(cmd, env).apply {
            start() // if start fails, IOException will be thrown directly
            // Enter the guard before cancellation can strand the newly spawned child.
            launch(start = CoroutineStart.UNDISPATCHED) { looper(onRestartCallback) }
        }
        processCount += 1
    }

    @MainThread
    fun close(scope: CoroutineScope) {
        cancel()
        coroutineContext[Job]!!.also { job -> scope.launch { job.cancelAndJoin() } }
    }
}
