package io.nekohasekai.sagernet.ui

internal object DashboardConnectionPolicy {
    enum class Route(val label: String) { DIRECT("Direct"), PROXY("Proxy"), OTHER("Other") }
    private val proxyTypes = setOf("ss", "shadowsocks", "socks", "socks4", "socks5", "http", "https",
        "vmess", "vless", "trojan", "trojango", "hysteria", "hysteria2", "tuic", "wireguard",
        "amneziawg", "ssh", "shadowtls", "anytls", "naive", "mieru", "snell", "tor")
    fun route(chains: List<String>, types: Map<String, String>): Route {
        val leaf = chains.firstOrNull() ?: return Route.OTHER
        val type = types[leaf].orEmpty().lowercase(java.util.Locale.ROOT).replace("-", "").replace("_", "")
        if (type == "direct" || (type.isBlank() && leaf in setOf("direct", "bypass"))) return Route.DIRECT
        if (type in setOf("reject", "block", "dns")) return Route.OTHER
        if (type in proxyTypes || chains.contains("proxy")) return Route.PROXY
        return Route.OTHER
    }

    fun address(host: String, port: String): String {
        if (host.isBlank()) return ""
        if (port.isBlank() || port == "0") return host
        return (if (host.contains(':') && !host.startsWith("[")) "[$host]" else host) + ":" + port
    }
}
