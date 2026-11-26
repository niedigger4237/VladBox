package io.nekohasekai.sagernet.bg.root

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs

/**
 * Observe Android connectivity and tethering notifications. Broadcasts are only wakeup hints;
 * routing decisions always use a fresh, verified root snapshot, never broadcast extras.
 */
internal class TetherNetworkMonitor(private val changed: (String) -> Unit) {
    private val context = SagerNet.application.applicationContext
    private val cm = SagerNet.connectivity
    private var callbackRegistered = false
    private var receiverRegistered = false
    @Volatile private var closed = false
    private val lastLinks = HashMap<Network, LinkProperties>()

    private fun notifyChange(reason: String) {
        if (!closed) changed(reason)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = notifyChange("network available")
        override fun onLost(network: Network) {
            synchronized(lastLinks) { lastLinks.remove(network) }
            notifyChange("network lost")
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Android may deliver identical snapshots repeatedly. Keep full
            // LinkProperties equality, including addresses, routes and DNS.
            val changed = synchronized(lastLinks) {
                if (closed || lastLinks[network] == linkProperties) false
                else {
                    // The callback delivers a LinkProperties snapshot. Keep it private
                    // and read-only; the copy constructor is not public Android SDK API.
                    lastLinks[network] = linkProperties
                    true
                }
            }
            if (changed) notifyChange("link properties")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            notifyChange(intent.action ?: "broadcast")
        }
    }

    /** Register without requesting a new network or changing the system upstream. */
    @Suppress("DEPRECATION")
    fun start() {
        if (closed) return
        try {
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, callback)
            callbackRegistered = true
        } catch (e: Exception) {
            Logs.w(e)
        }
        try {
            val filter = IntentFilter().apply {
                addAction("android.net.conn.TETHER_STATE_CHANGED")
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
                addAction("android.hardware.usb.action.USB_STATE")
                addAction("android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            Logs.w(e)
        }
        Logs.i("VPN tether monitor: callback=$callbackRegistered broadcast=$receiverRegistered")
    }

    fun close() {
        closed = true
        synchronized(lastLinks) { lastLinks.clear() }
        if (callbackRegistered) {
            try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { Logs.w(e) }
            callbackRegistered = false
        }
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) { Logs.w(e) }
            receiverRegistered = false
        }
    }
}
