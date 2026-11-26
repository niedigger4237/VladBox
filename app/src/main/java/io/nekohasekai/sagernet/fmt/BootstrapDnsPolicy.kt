package io.nekohasekai.sagernet.fmt

import java.net.InetAddress

/** Bootstrap must be independently dialable: only literal IP endpoints, no DNS
 * dependency, no proxy detour, no insecure TLS option. */
internal object BootstrapDnsPolicy {
    data class Server(val type: String, val host: String, val port: Int? = null,
        val path: String? = null, val tlsName: String? = null)

    private val providers = mapOf(
        "cloudflare" to ("1.1.1.1" to "cloudflare-dns.com"),
        "quad9" to ("9.9.9.9" to "dns.quad9.net"),
        "google" to ("8.8.8.8" to "dns.google")
    )

    fun select(preset: String, custom: String, tlsName: String): Server? {
        if (preset == "system") return null
        if (preset == "custom") return parse(custom, tlsName)
        val provider = providers[preset.substringBefore('_')] ?: error("Unknown Bootstrap DNS preset")
        return when (preset.substringAfter('_', "udp")) {
            "udp" -> Server("udp", provider.first)
            "doh" -> Server("https", provider.first, tlsName = provider.second)
            "dot" -> Server("tls", provider.first, tlsName = provider.second)
            else -> error("Unknown Bootstrap DNS transport")
        }
    }

    fun validTlsName(raw: String): Boolean {
        val name = raw.trim()
        return name.isEmpty() || name.length <= 253 && name.split('.').all {
            it.length in 1..63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
        }
    }

    private fun literalIp(host: String): Boolean {
        if (':' in host) {
            // Only numeric IPv6 grammar reaches InetAddress; never query a hostname.
            return host.matches(Regex("[0-9A-Fa-f:.]+")) &&
                runCatching { InetAddress.getByName(host) }.isSuccess
        }
        val parts = host.split('.')
        return parts.size == 4 && parts.all {
            it.matches(Regex("0|[1-9][0-9]{0,2}")) && (it.toIntOrNull() ?: -1) in 0..255
        }
    }

    fun parse(address: String, tlsName: String): Server {
        val raw = address.trim()
        require(raw.isNotEmpty() && raw.none { it.isWhitespace() || it == '@' || it == '#' || it == '?' || it == '\\' }) {
            "Bootstrap DNS requires an IP address without credentials, query or fragment"
        }
        val type = raw.substringBefore("://", "udp").lowercase()
        require(type in setOf("udp", "tcp", "tls", "https")) { "Unsupported Bootstrap DNS transport" }
        val rest = raw.substringAfter("://", raw)
        val authority = rest.substringBefore('/')
        val path = if ('/' in rest) "/" + rest.substringAfter('/') else null
        require(path == null || type == "https") { "Only Bootstrap DoH supports a path" }

        val host: String
        val portString: String?
        if (authority.startsWith('[')) {
            val match = Regex("^\\[([^]]+)\\](?::([0-9]+))?$").matchEntire(authority)
                ?: error("Invalid bracketed Bootstrap DNS address")
            host = match.groupValues[1]
            require(':' in host) { "Brackets require an IPv6 literal" }
            portString = match.groups[2]?.value
        } else if (authority.count { it == ':' } == 1) {
            host = authority.substringBefore(':')
            portString = authority.substringAfter(':')
        } else {
            host = authority
            portString = null
        }
        require(literalIp(host)) { "Bootstrap DNS requires a literal IPv4 or IPv6 address" }
        val port = portString?.let {
            require(it.matches(Regex("[0-9]{1,5}"))) { "Invalid Bootstrap DNS port" }
            it.toInt().also { value -> require(value in 1..65535) { "Invalid Bootstrap DNS port" } }
        }
        require(validTlsName(tlsName)) { "Invalid Bootstrap DNS TLS server name" }
        val name = tlsName.trim().takeIf { it.isNotEmpty() && type in setOf("tls", "https") }
        return Server(type, host, port, path?.takeUnless { it == "/dns-query" }, name)
    }
}
