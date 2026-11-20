package io.nekohasekai.sagernet.bg.throne

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.core.CoreBox
import io.nekohasekai.sagernet.database.DataStore
import io.throneproj.mobile.Instance
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.StartOptions
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** The in-process Throne core (ThroneCore.aar) behind the service. */
class ThroneBox(private val inst: Instance) : CoreBox {

    override fun start() = inst.start()
    override fun close() { inst.close() }
    override fun setAsMain() {}
    override fun sleep() = inst.pause()
    override fun wake() = inst.wake()

    override fun selectOutbound(tag: String) {
        inst.selectOutbound("proxy", tag)
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

    override fun queryStats(tag: String, direction: String): Long = inst.queryOutboundStats(tag, direction)
    override fun enableStats(tags: String) {}
    override fun resetConnections() = inst.resetNetwork()

    companion object {
        fun create(config: String): ThroneBox {
            ThroneRuntime.ensureSetup(SagerNet.application)
            val options = StartOptions().apply {
                coreConfig = config
                needXray = false
                xrayConfig = ""
                xrayOutboundDNSStrategy = ""
            }
            return ThroneBox(Mobile.newInstance(ThroneRuntime.platform, options))
        }
    }
}
