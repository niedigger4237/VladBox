package io.nekohasekai.sagernet.bg.throne

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import io.nekohasekai.sagernet.SagerNet
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.aidl.IThroneCallback
import android.util.Log
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.throneproj.mobile.ConnectionOwner
import io.throneproj.mobile.InterfaceUpdateListener
import io.throneproj.mobile.LocalDNSTransport
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.NetworkInterfaceIterator
import io.throneproj.mobile.PlatformInterface
import io.throneproj.mobile.StringIterator
import io.throneproj.mobile.TunOptions
import io.throneproj.mobile.WIFIState
import kotlinx.coroutines.runBlocking
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.WeakHashMap
import io.throneproj.mobile.NetworkInterface as CoreNetworkInterface
import io.throneproj.mobile.Notification as CoreNotification

/** Android side of Throne's core, adapted from ThroneForAndroid's NativeInterface to VladBox's VpnService. */
class ThronePlatform(private val callback: IThroneCallback) : PlatformInterface {

    override fun localDNSTransport(): LocalDNSTransport? = try {
        ThroneResolver
    } catch (_: Throwable) {
        null
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        // the VpnService lives in :bg: hand it a duplicate of the socket
        ParcelFileDescriptor.fromFd(fd).use { callback.protect(it) }
    }

    // The tun is configured by VpnService.startVpn (addresses, routes, per-app lists, MTU come from VladBox's settings).
    override fun openTun(options: TunOptions): Int {
        val pfd = callback.openTun() ?: error("android: no tun")
        return pfd.detachFd()
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("android: connection owner lookup requires API 29")
        }
        val uid = SagerNet.connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        return ConnectionOwner().apply {
            userId = uid
            setAndroidPackageNames(packageNamesOf(uid).toStringIterator())
        }
    }

    override fun packageNamesByUid(uid: Int): StringIterator = packageNamesOf(uid).toStringIterator()

    private fun packageNamesOf(uid: Int): List<String> {
        if (uid <= 1000) return listOf("android")
        return SagerNet.application.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        runBlocking {
            DefaultNetworkListener.start(listener) { network ->
                checkDefaultInterfaceUpdate(listener, network)
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        runBlocking {
            DefaultNetworkListener.stop(listener)
        }
    }

    private class IfaceReportState {
        var name: String? = null
        var index: Int = Int.MIN_VALUE
        var network: Network? = null
        var isExpensive: Boolean = false
    }

    private val ifaceReportStates = Collections.synchronizedMap(WeakHashMap<InterfaceUpdateListener, IfaceReportState>())

    private fun reportState(listener: InterfaceUpdateListener): IfaceReportState =
        synchronized(ifaceReportStates) { ifaceReportStates.getOrPut(listener) { IfaceReportState() } }

    private fun clearInterface(listener: InterfaceUpdateListener, state: IfaceReportState) {
        state.name = null
        state.index = Int.MIN_VALUE
        state.network = null
        state.isExpensive = false
        listener.updateDefaultInterface("", -1, false, false)
    }

    private fun checkDefaultInterfaceUpdate(listener: InterfaceUpdateListener, network: Network?) {
        val state = reportState(listener)
        if (network == null) {
            clearInterface(listener, state)
            return
        }
        repeat(10) {
            val linkProperties = SagerNet.connectivity.getLinkProperties(network)
            if (linkProperties == null) {
                Thread.sleep(100)
                return@repeat
            }
            val interfaceIndex = try {
                NetworkInterface.getByName(linkProperties.interfaceName).index
            } catch (e: Exception) {
                Thread.sleep(100)
                return@repeat
            }
            val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
            val isExpensive = capabilities?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } ?: false
            val same = state.name == linkProperties.interfaceName && state.index == interfaceIndex &&
                state.network == network
            if (same && state.isExpensive == isExpensive) return
            state.name = linkProperties.interfaceName
            state.index = interfaceIndex
            state.network = network
            state.isExpensive = isExpensive
            listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, isExpensive, false)
            return
        }
        Log.w("Throne", "checkDefaultInterfaceUpdate exhausted retries for $network")
        clearInterface(listener, state)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        @Suppress("DEPRECATION") val networks = SagerNet.connectivity.allNetworks
        val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val interfaces = mutableListOf<CoreNetworkInterface>()
        for (network in networks) {
            val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: continue
            val networkCapabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: continue
            val networkInterface = networkInterfaces.find { it.name == linkProperties.interfaceName } ?: continue
            val boxInterface = CoreNetworkInterface()
            boxInterface.name = linkProperties.interfaceName
            boxInterface.index = networkInterface.index
            runCatching { boxInterface.mtu = networkInterface.mtu }
                .onFailure { Log.w("Throne", "failed to get mtu for interface ${boxInterface.name}: $it") }
            boxInterface.addresses = networkInterface.interfaceAddresses.map { it.toPrefix() }.toStringIterator()
            boxInterface.dnsServer = linkProperties.dnsServers.mapNotNull { it.hostAddress }.toStringIterator()
            boxInterface.gateway = linkProperties.routes.filter { it.isDefaultRoute }
                .mapNotNull { it.gateway?.hostAddress }.toStringIterator()
            boxInterface.type = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Mobile.InterfaceTypeWIFI
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Mobile.InterfaceTypeCellular
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Mobile.InterfaceTypeEthernet
                else -> Mobile.InterfaceTypeOther
            }
            var flags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (networkInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (networkInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            boxInterface.flags = flags
            boxInterface.metered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return object : NetworkInterfaceIterator {
            private val it = interfaces.iterator()
            override fun hasNext(): Boolean = it.hasNext()
            override fun next(): CoreNetworkInterface = it.next()
        }
    }

    // never throw from callbacks whose generated Java method declares no exception
    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {}

    override fun sendNotification(notification: CoreNotification) {}

    override fun cancelNotification(identifier: String, typeID: Int) {}
}

private fun Iterable<String>.toStringIterator(): StringIterator {
    val values = toList()
    return object : StringIterator {
        private val it = values.iterator()
        override fun hasNext(): Boolean = it.hasNext()
        override fun next(): String = it.next()
        override fun len(): Int = values.size
    }
}

private fun InterfaceAddress.toPrefix(): String {
    return if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/${networkPrefixLength}"
    } else {
        "${address.hostAddress}/${networkPrefixLength}"
    }
}
