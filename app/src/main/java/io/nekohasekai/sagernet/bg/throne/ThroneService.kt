package io.nekohasekai.sagernet.bg.throne

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.IThroneCallback
import io.nekohasekai.sagernet.aidl.IThroneService
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.throneproj.mobile.Instance
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.StartOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Hosts Throne's core in its own process (android:process=":throne"). */
class ThroneService : Service() {

    private var instance: Instance? = null
    private var networkKey: Any? = null

    private val binder = object : IThroneService.Stub() {

        override fun start(config: String, cb: IThroneCallback): String {
            return try {
                closeInstance()
                ThroneRuntime.callback = cb
                ThroneRuntime.ensureSetup(application)
                val options = StartOptions().apply {
                    coreConfig = config
                    needXray = false
                    xrayConfig = ""
                    xrayOutboundDNSStrategy = ""
                }
                val inst = Mobile.newInstance(ThronePlatform(cb), options)
                instance = inst
                inst.start()
                ""
            } catch (e: Throwable) {
                Log.w("Throne", "start failed", e)
                closeInstance()
                e.message ?: e.toString()
            }
        }

        override fun close() = closeInstance()
        override fun pause() { instance?.pause() }
        override fun wake() { instance?.wake() }
        override fun resetNetwork() { instance?.resetNetwork() }
        override fun selectOutbound(group: String, tag: String): Boolean = try {
            instance?.selectOutbound(group, tag)
            true
        } catch (_: Throwable) {
            false
        }

        override fun queryStats(tag: String, direction: String): Long = try {
            instance?.queryOutboundStats(tag, direction) ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    @Synchronized
    private fun closeInstance() {
        val inst = instance ?: return
        instance = null
        try {
            inst.close()
        } catch (e: Throwable) {
            Log.w("Throne", "close failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // this process has no main-process init: track the underlying network for the DNS resolver
        networkKey = Any().also { key ->
            CoroutineScope(Dispatchers.Default).launch {
                DefaultNetworkListener.start(key) { SagerNet.underlyingNetwork = it }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        closeInstance()
        return false
    }

    override fun onDestroy() {
        closeInstance()
        networkKey?.let { key -> CoroutineScope(Dispatchers.Default).launch { DefaultNetworkListener.stop(key) } }
        super.onDestroy()
        // a clean slate for the next start: the Go runtime is not meant to be re-initialised
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
