package io.nekohasekai.sagernet.bg.throne

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.IThroneCallback
import io.nekohasekai.sagernet.aidl.IThroneService
import io.nekohasekai.sagernet.bg.core.CoreBox
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import libcore.Libcore
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Throne's core as seen from :bg: a binder to the :throne process, which owns the only copy of its Go runtime. */
class ThroneBox private constructor(private val config: String) : CoreBox {

    private lateinit var service: IThroneService
    private var connection: ServiceConnection? = null

    private val callback = object : IThroneCallback.Stub() {
        override fun openTun(): ParcelFileDescriptor {
            val vpn = DataStore.vpnService ?: throw IllegalStateException("no VpnService")
            return ParcelFileDescriptor.fromFd(vpn.startVpn("", ""))
        }

        override fun protect(fd: ParcelFileDescriptor): Boolean = DataStore.vpnService?.protect(fd.fd) ?: false

        override fun log(level: Int, message: String) {
            if (DataStore.logLevel == 0) return
            val name = when (level) {
                0 -> "Panic"
                1 -> "Fatal"
                2 -> "Error"
                3 -> "Warning"
                5 -> "Debug"
                6 -> "Trace"
                else -> "Info"
            }
            Libcore.nekoLogPrintln("[$name] $message")
        }
    }

    private fun bind() {
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IThroneService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Logs.w("throne process disconnected")
            }
        }
        val app = SagerNet.application
        if (!app.bindService(Intent(app, ThroneService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            throw IllegalStateException("can't bind the Throne service")
        }
        connection = conn
        if (!latch.await(15, TimeUnit.SECONDS)) throw IllegalStateException("Throne process did not start")
    }

    override fun start() {
        bind()
        val err = service.start(config, callback)
        if (err.isNotEmpty()) throw IllegalStateException(err)
    }

    override fun close() {
        try {
            if (::service.isInitialized) service.close()
        } catch (_: Exception) {
        }
        connection?.let { SagerNet.application.unbindService(it) }
        connection = null
    }

    override fun setAsMain() {}
    override fun sleep() = service.pause()
    override fun wake() = service.wake()

    override fun selectOutbound(tag: String) {
        service.selectOutbound("proxy", tag)
    }

    // through the mixed inbound, like the extended core's own probe client
    override fun urlTest(url: String, timeoutMs: Int): Int {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", DataStore.mixedPort))
        val started = System.nanoTime()
        val conn = URL(url).openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        try {
            val code = conn.responseCode
            if (code !in 200..399) error("http $code")
        } finally {
            conn.disconnect()
        }
        return ((System.nanoTime() - started) / 1_000_000).toInt()
    }

    override fun queryStats(tag: String, direction: String): Long = service.queryStats(tag, direction)
    override fun enableStats(tags: String) {}
    override fun resetConnections() = service.resetNetwork()

    companion object {
        fun create(config: String) = ThroneBox(config)
    }
}
