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
    val dns6 get() = InetAddress.getByAddress(InetAddress.getByName(ipv6).address.apply {
        this[lastIndex] = (this[lastIndex].toInt() + 1).toByte()
    }).hostAddress!!

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

        const val DEFAULT_IPV4_SUBNET = "172.19.0.0/28"
        const val DEFAULT_IPV6_SUBNET = "fdfe:dcba:9876::/126"

        // Strict numeric literals only. No DNS lookups for user-supplied hostnames.
        private fun parseSubnet(text: String, v6: Boolean): Prefix {
            val parts = text.trim().split('/')
            require(parts.size == 2) { "Use a network CIDR, including prefix length" }
            val ip = parts[0]
            val bits = parts[1].toIntOrNull() ?: error("Invalid subnet prefix")
            require(bits in (if (v6) 64..126 else 24..30)) {
                if (v6) "IPv6 prefix must be /64 through /126" else "IPv4 prefix must be /24 through /30"
            }
            val address = if (v6) {
                require(ip.contains(':') && ip.matches(Regex("[0-9a-fA-F:]+"))) { "Invalid IPv6 literal" }
                InetAddress.getByName(ip).also { require(it.address.size == 16) { "Invalid IPv6 address" } }
            } else {
                val octets = ip.split('.')
                require(octets.size == 4 && octets.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) {
                    "Invalid IPv4 literal"
                }
                InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
            }
            val privateRanges = if (v6) listOf("fd00::" to 8) else
                listOf("10.0.0.0" to 8, "172.16.0.0" to 12, "192.168.0.0" to 16)
            require(privateRanges.any { (base, length) ->
                bits >= length && overlaps(Prefix(address, bits), Prefix(InetAddress.getByName(base), length))
            }) { "Use a private subnet (RFC1918 IPv4 or fd00::/8 IPv6)" }
            val bytes = address.address
            for (i in bytes.indices) {
                val keep = (bits - i * 8).coerceIn(0, 8)
                val hostMask = 0xff ushr keep
                require((bytes[i].toInt() and hostMask) == 0) { "Enter the network address, not a host address" }
            }
            return Prefix(address, bits)
        }

        fun validationError(text: String, v6: Boolean): String? =
            runCatching { parseSubnet(text, v6) }.exceptionOrNull()?.let { it.message ?: "Invalid CIDR" }

        private fun host(prefix: Prefix, number: Int): String =
            InetAddress.getByAddress(prefix.address.address.apply { this[lastIndex] = (this[lastIndex].toInt() or number).toByte() }).hostAddress!!

        /** Observe connected networks and non-default routes without requiring root. */
        @Suppress("DEPRECATION")
        private fun occupiedPrefixes(): List<Prefix> {
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
            return occupied
        }

        /** Pure address derivation, also used by JVM unit tests. */
        internal fun fromManualSubnets(ipv4Subnet: String, ipv6Subnet: String, includeIpv6: Boolean): TunAddressPlan {
            val v4 = parseSubnet(ipv4Subnet, false)
            val v6 = parseSubnet(if (includeIpv6) ipv6Subnet else DEFAULT_IPV6_SUBNET, true)
            return TunAddressPlan(host(v4, 1), host(v4, 2), host(v6, 1), v4.bits, v6.bits)
        }

        fun manual(ipv4Subnet: String, ipv6Subnet: String, includeIpv6: Boolean): TunAddressPlan {
            val plan = fromManualSubnets(ipv4Subnet, ipv6Subnet, includeIpv6)
            val v4 = Prefix(InetAddress.getByName(plan.ipv4), plan.ipv4Prefix)
            val v6 = Prefix(InetAddress.getByName(plan.ipv6), plan.ipv6Prefix)
            val occupied = occupiedPrefixes()
            require(occupied.none { overlaps(v4, it) }) { "Manual IPv4 TUN subnet overlaps a connected network: $ipv4Subnet" }
            require(!includeIpv6 || occupied.none { overlaps(v6, it) }) { "Manual IPv6 TUN subnet overlaps a connected network: $ipv6Subnet" }
            return plan
        }

        fun select(includeIpv6: Boolean): TunAddressPlan {
            val occupied = occupiedPrefixes()
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
