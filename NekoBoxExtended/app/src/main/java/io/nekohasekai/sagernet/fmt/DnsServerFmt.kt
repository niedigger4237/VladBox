package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions

/**
 * Converts the user facing DNS address notation (the one used by the settings
 * screen since the legacy sing-box DNS format) into the sing-box >= 1.12 server
 * object, which is the only one accepted by sing-box 1.14.
 *
 * Supported: local, dhcp://auto|<iface>, [udp://|tcp://|tls://|https://|quic://|h3://]host[:port][/path]
 *
 * @param resolver tag of the DNS server used to resolve a domain host, null for none.
 */
fun buildDnsServer(address: String, tag: String, resolver: String?): DNSServerOptions {
    val raw = address.trim()
    return DNSServerOptions().apply {
        this.tag = tag
        if (raw == "local") {
            type = "local"
            return@apply
        }

        val scheme = if (raw.contains("://")) raw.substringBefore("://").lowercase() else "udp"
        var rest = if (raw.contains("://")) raw.substringAfter("://") else raw

        if (scheme == "dhcp") {
            type = "dhcp"
            if (rest.isNotEmpty() && rest != "auto") interface_ = rest
            return@apply
        }

        type = when (scheme) {
            "udp", "tcp", "tls", "https", "quic", "h3" -> scheme
            "http3" -> "h3"
            "dot" -> "tls"
            "doh" -> "https"
            "doq" -> "quic"
            else -> throw IllegalArgumentException("Unsupported DNS server: $address")
        }

        var urlPath = ""
        val slash = rest.indexOf('/')
        if (slash >= 0) {
            urlPath = rest.substring(slash)
            rest = rest.substring(0, slash)
        }

        val host: String
        var port: Int? = null
        if (rest.startsWith("[")) { // [v6]:port
            host = rest.substringAfter("[").substringBefore("]")
            rest.substringAfter("]", "").removePrefix(":").toIntOrNull()?.let { port = it }
        } else if (rest.count { it == ':' } == 1) { // host:port
            host = rest.substringBefore(":")
            port = rest.substringAfter(":").toIntOrNull()
        } else { // domain / ipv4 / bare ipv6
            host = rest
        }

        server = host
        if (port != null) server_port = port
        if ((type == "https" || type == "h3") && urlPath.isNotEmpty() && urlPath != "/dns-query") {
            path = urlPath
        }
        if (resolver != null && !host.isIpAddress()) domain_resolver = resolver
    }
}
