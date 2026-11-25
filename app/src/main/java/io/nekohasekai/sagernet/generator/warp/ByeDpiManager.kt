package io.nekohasekai.sagernet.generator.warp

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Temporary loopback process. Not an app-private socket: do not expose
 * this adapter to production registration until access isolation is implemented.
 * All operations are blocking and must run on IO.
 */
internal class ByeDpiManager(context: Context) {
    private val executable = File(context.applicationInfo.nativeLibraryDir, "libciadpi.so")
    private val loopback = InetAddress.getByName("127.0.0.1")

    fun open(strategy: DpiStrategySelector.Strategy): WarpDpiSession {
        if (!executable.isFile || !executable.canExecute())
            throw IOException("ByeDPI Android executable unavailable")
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val address = InetSocketAddress(loopback, port)
        val args = listOf(executable.absolutePath, "-i", "127.0.0.1",
            "-p", port.toString(), "-U", "-c", "8") + strategy.arguments
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val session = Session(process, address)
        try {
            Thread {
                try { process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) != -1) { }
                } } catch (_: IOException) { }
            }.apply { isDaemon = true; start() }
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!process.isAlive) throw IOException("ByeDPI exited during startup")
                try {
                    Socket().use { socket ->
                        socket.connect(address, 100)
                        socket.soTimeout = 200
                        socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                        val input = socket.getInputStream()
                        if (input.read() == 5 && input.read() == 0 && process.isAlive)
                            return session
                    }
                } catch (_: IOException) { }
                Thread.sleep(50)
            }
            throw IOException("ByeDPI SOCKS startup timed out")
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    private class Session(private val process: Process, address: InetSocketAddress) : WarpDpiSession {
        override val proxy = Proxy(Proxy.Type.SOCKS, address)
        @Synchronized override fun close() {
            process.destroy()
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
    }
}
