package io.nekohasekai.sagernet.bg

import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.SagerNet
import java.net.InetAddress
import java.net.NetworkInterface

/** One immutable address plan shared by the generated core config and Android's VPN builder. */
data class TunAddressPlan(
    val ipv4: String,
    val dns4: String,
    val ipv6: String,
    val ipv4Prefix: Int = 28,
    val ipv6Prefix: Int = 126,
) {
    val cidr4 get() = "$ipv4/$ipv4Prefix"
    val cidr6 get() = "$ipv6/$ipv6Prefix"

    companion object {
        fun legacy() = TunAddressPlan("172.19.0.1", "172.19.0.2", "fdfe:dcba:9876::1", 30)

        private data class Prefix(val address: InetAddress, val bits: Int)

        private fun overlaps(a: Prefix, b: Prefix): Boolean {
            val x = a.address.address
            val y = b.address.address
            if (x.size != y.size) return false
            var remaining = minOf(a.bits, b.bits)
            for (i in x.indices) {
                if (remaining <= 0) return true
                val mask = (0xff shl (8 - minOf(remaining, 8))) and 0xff
                if (((x[i].toInt() xor y[i].toInt()) and mask) != 0) return false
                remaining -= 8
            }
            return true
        }

        /** Observe connected networks and non-default routes without requiring root. */
        @Suppress("DEPRECATION")
        fun select(includeIpv6: Boolean): TunAddressPlan {
            val occupied = mutableListOf<Prefix>()
            val cm = SagerNet.connectivity
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) continue
                val link = cm.getLinkProperties(network) ?: continue
                link.linkAddresses.forEach { occupied += Prefix(it.address, it.prefixLength) }
                link.routes.forEach {
                    val dest = it.destination
                    if (dest.prefixLength > 0) occupied += Prefix(dest.address, dest.prefixLength)
                }
            }
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    iface.interfaceAddresses.forEach {
                        val bits = it.networkPrefixLength.toInt()
                        if (bits > 0) occupied += Prefix(it.address, bits)
                    }
                }
            }
            fun free(ip: String, bits: Int): Boolean {
                val candidate = Prefix(InetAddress.getByName(ip), bits)
                return occupied.none { overlaps(candidate, it) }
            }
            val bases = sequenceOf("172.19.0") +
                (0..255).asSequence().map { "10.253.$it" } +
                (16..31).asSequence().flatMap { second ->
                    (0..255).asSequence().map { third -> "172.$second.$third" }
                }
            val v4 = bases.firstOrNull { free("$it.1", 28) }
                ?: error("No non-overlapping IPv4 TUN subnet found")
            val v6 = if (includeIpv6) {
                (sequenceOf("fdfe:dcba:9876") +
                    (1..255).asSequence().map { "fdfe:dcba:" + it.toString(16) })
                    .map { "$it::1" }.firstOrNull { free(it, 126) }
                    ?: error("No non-overlapping IPv6 TUN subnet found")
            } else "fdfe:dcba:9876::1"
            return TunAddressPlan("$v4.1", "$v4.2", v6)
        }
    }
}
