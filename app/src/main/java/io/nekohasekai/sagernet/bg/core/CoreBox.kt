package io.nekohasekai.sagernet.bg.core

import libcore.Libcore

/** What the service needs from the running sing-box,. */
interface CoreBox {
    fun start()
    fun close()
    fun setAsMain()
    fun sleep()
    fun wake()
    fun selectOutbound(tag: String): Boolean
    /** latency in ms, throws when the test fails */
    fun urlTest(url: String, timeoutMs: Int): Int
    /** bytes of an outbound tag since the last query of that direction ("uplink" / "downlink") */
    fun queryStats(tag: String, direction: String): Long
    fun enableStats(tags: String)
    fun resetConnections()
}

/** The in-process sing-box-extended core (libcore.aar). */
class LibcoreBox(val raw: libcore.BoxInstance) : CoreBox {
    override fun start() = raw.start()
    override fun close() = raw.close()
    override fun setAsMain() = raw.setAsMain()
    override fun sleep() = raw.sleep()
    override fun wake() = raw.wake()
    override fun selectOutbound(tag: String): Boolean = raw.selectOutbound(tag)
    override fun urlTest(url: String, timeoutMs: Int): Int = Libcore.urlTest(raw, url, timeoutMs)
    override fun queryStats(tag: String, direction: String): Long = raw.queryStats(tag, direction)
    override fun enableStats(tags: String) = raw.setV2rayStats(tags)
    override fun resetConnections() = Libcore.resetAllConnections(true)
    fun refreshNetwork() = raw.refreshNetwork()
}

/** resets the connections of whichever core runs the current profile */
fun resetCoreConnections() {
    val proxy = io.nekohasekai.sagernet.database.DataStore.baseService?.data?.proxy
    if (proxy != null && proxy.isBoxInitialized()) proxy.box.resetConnections()
    else Libcore.resetAllConnections(true)
}
