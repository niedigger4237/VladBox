package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports Xray client outbounds into editable managed profiles, not an Xray
 * runtime. Inbounds, DNS and route policy are intentionally managed by the app.
 * Never flatten a proxy chain or silently downgrade authentication.
 */
object XrayJsonImport {
    private val supported = setOf("vless", "vmess", "trojan")
    private val helpers = setOf("freedom", "blackhole", "dns")
    

    fun recognizes(value: Any?): Boolean = when (value) {
        is JSONObject -> (value.optString("protocol").isNotBlank() && !value.has("type") &&
            !value.has("server") && !value.has("remote_addr")) ||
            (value.optJSONArray("outbounds")?.let { a -> (0 until a.length()).any { recognizes(a.opt(it)) } } == true)
        is JSONArray -> (0 until value.length()).any { recognizes(value.opt(it)) }
        else -> false
    }

    private fun objects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).map { array.getJSONObject(it) }

    private fun obj(value: JSONObject, key: String): JSONObject =
        if (value.has(key) && !value.isNull(key)) value.getJSONObject(key) else JSONObject()

    private fun active(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL, false -> false
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        is JSONObject -> value.keys().asSequence().any { active(value.opt(it)) }
        is JSONArray -> value.length() != 0
        else -> true
    }

    private fun checkKeys(value: JSONObject, allowed: Set<String>, context: String) {
        val unsupported = value.keys().asSequence().filter { it !in allowed && active(value.opt(it)) }.toList()
        require(unsupported.isEmpty()) { "Unsupported Xray $context: " + unsupported.joinToString() }
    }

    // Display metadata is independent of the raw outbound tag used for chains.
    private fun displayName(value: JSONObject): String =
        listOf("remarks", "name").firstNotNullOfOrNull { key ->
            (value.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private fun meaningfulTag(value: JSONObject): String =
        value.optString("tag").trim().takeUnless {
            it.matches(Regex("(?i)^(?:proxy|outbound|vless|vmess|trojan)(?:[-_ ]?\\d+)?$"))
        }.orEmpty()

    private fun required(value: JSONObject, key: String): String =
        value.optString(key).takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing Xray $key")

    private fun stringList(value: JSONObject, key: String): String = when (val v = value.opt(key)) {
        null, JSONObject.NULL -> ""
        is String -> v
        is JSONArray -> (0 until v.length()).joinToString(",") { v.getString(it) }
        else -> throw IllegalArgumentException("Invalid Xray $key")
    }

    /** Hop order is physical ingress to exit, matching ChainBean.proxies. */
    data class ImportedChain(val name: String, val hops: List<AbstractBean>)

    private fun dependency(outbound: JSONObject): String {
        val legacy = obj(outbound, "proxySettings")
        val sock = obj(obj(outbound, "streamSettings"), "sockopt")
        val tag = legacy.optString("tag")
        val dialer = sock.optString("dialerProxy")
        checkKeys(legacy, setOf("tag", "transportLayer"), "proxySettings")
        require(!active(legacy) || (tag.isNotBlank() && legacy.optBoolean("transportLayer"))) {
            "Legacy Xray proxySettings requires transportLayer=true for equivalent conversion"
        }
        require(tag.isBlank() || dialer.isBlank()) { "Conflicting Xray proxySettings and dialerProxy" }
        return dialer.ifBlank { tag }
    }

    fun parse(value: Any, fileName: String = "", onChains: ((List<ImportedChain>) -> Unit)? = null): List<AbstractBean> {
        val outbounds = when (value) {
            is JSONObject -> if (value.has("outbounds")) objects(value.getJSONArray("outbounds")) else listOf(value)
            is JSONArray -> {
                val entries = objects(value)
                if (entries.all { it.has("protocol") }) entries
                else {
                    val chains = mutableListOf<ImportedChain>()
                    val beans = entries.flatMap { parse(it, fileName, if (onChains == null) null else { c -> chains.addAll(c); Unit }) }
                    if (chains.isNotEmpty()) onChains?.invoke(chains)
                    return beans
                }
            }
            else -> throw IllegalArgumentException("Expected Xray JSON object")
        }
        val configName = (value as? JSONObject)?.takeIf { it.has("outbounds") }?.let(::displayName).orEmpty()
        val sourceName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
            .replace(Regex("(?i)\\.json$"), "")
        val proxies = outbounds.filter { it.optString("protocol") !in helpers }
        require(proxies.isNotEmpty()) { "No supported Xray client outbounds" }
        val tagged = linkedMapOf<String, JSONObject>()
        for (outbound in outbounds) {
            val tag = outbound.optString("tag")
            if (tag.isNotBlank()) require(tagged.put(tag, outbound) == null) { "Duplicate Xray outbound tag: $tag" }
            if (outbound.optString("protocol") in helpers) {
                require(dependency(outbound).isBlank()) { "Chained Xray helper outbound is unsupported" }
            }
        }
        val dependencies = proxies.map { dependency(it) }
        require(dependencies.none { it.isNotBlank() } || onChains != null) {
            "Xray chains must be imported from a file or clipboard; subscription chains are not supported yet"
        }
        // Validate every dependency before creating any profiles.
        val byTag = proxies.mapIndexedNotNull { i, outbound ->
            outbound.optString("tag").takeIf { it.isNotBlank() }?.let { it to i }
        }.toMap()
        fun hopOrder(index: Int, seen: Set<Int> = emptySet()): List<Int> {
            require(index !in seen) { "Circular Xray proxy chain" }
            require(seen.size < 32) { "Xray proxy chain exceeds 32 hops" }
            val dep = dependencies[index]
            if (dep.isBlank()) return listOf(index)
            val previous = byTag[dep] ?: throw IllegalArgumentException("Missing or unsupported Xray chain hop: $dep")
            return hopOrder(previous, seen + index) + index
        }
        val orders = proxies.indices.map { hopOrder(it) }
        val beans = proxies.mapIndexed { index, outbound ->
            val protocol = outbound.optString("protocol")
            require(protocol in supported) { "Unsupported Xray outbound protocol: $protocol" }
            val stream = obj(outbound, "streamSettings")
            // The validated dependency is represented by ChainBean, not a raw tag.
            val sock = obj(stream, "sockopt")
            if (dependencies[index].isNotBlank() && active(sock.opt("tcpFastOpen"))) {
                throw IllegalArgumentException("Xray chained tcpFastOpen has no equivalent on an inner dialer")
            }
            require(!obj(outbound, "mux").optBoolean("enabled")) { "Xray Mux.Cool is not equivalent to sing-box multiplex" }
            checkKeys(outbound, setOf("protocol", "tag", "settings", "streamSettings", "mux", "proxySettings", "remarks", "name"), "outbound")
            val settings = outbound.getJSONObject("settings")
            val arrayKey = if (protocol == "trojan") "servers" else "vnext"
            val flat = settings.has("address")
            val server = if (flat) {
                require(!active(settings.opt(arrayKey))) { "Ambiguous Xray server settings" }
                settings
            } else {
                checkKeys(settings, setOf(arrayKey), "settings")
                val entries = objects(settings.getJSONArray(arrayKey))
                require(entries.size == 1) { "Multiple endpoints require explicit Xray balancing conversion" }
                entries.single()
            }
            val user = if (flat || protocol == "trojan") server else {
                checkKeys(server, setOf("address", "port", "users"), "server")
                val users = objects(server.getJSONArray("users"))
                require(users.size == 1) { "Multiple users require explicit Xray balancing conversion" }
                users.single()
            }
            checkKeys(user, setOf("address", "port", "id", "password", "level", "email", "flow", "encryption", "security", "alterId"), "account")
            val address = required(server, "address")
            val port = server.opt("port")?.toString()?.toIntOrNull()
            require(port != null && port in 1..65535) { "Invalid Xray server port" }
            val bean: StandardV2RayBean = if (protocol == "trojan") {
                require(user.optString("flow").isEmpty()) { "Trojan flow is unsupported" }
                TrojanBean().apply { password = required(user, "password") }
            } else {
                VMessBean().apply {
                    uuid = required(user, "id")
                    alterId = if (protocol == "vless") -1 else user.optInt("alterId", 0)
                    require(protocol == "vless" || alterId >= 0) { "Invalid VMess alterId" }
                    if (protocol == "vless") {
                        require(user.optInt("alterId", 0) == 0) { "Unexpected VLESS alterId" }
                        val flow = user.optString("flow")
                        require(flow in setOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")) { "Unsupported VLESS flow" }
                        encryption = flow.removeSuffix("-udp443")
                        vlessEncryption = user.optString("encryption", "none")
                    } else encryption = user.optString("security", "auto")
                }
            }
            bean.serverAddress = address
            bean.serverPort = port
            val tagName = meaningfulTag(outbound)
            val endpointName = "${protocol.uppercase(java.util.Locale.ROOT)} — $address"
            val parentName = configName.ifBlank { sourceName }
            bean.name = displayName(outbound).ifBlank {
                if (parentName.isNotBlank()) {
                    if (proxies.size == 1) parentName
                    else "$parentName — ${tagName.ifBlank { endpointName }}"
                } else tagName.ifBlank { endpointName }
            }
            bean.initializeDefaultValues()
            val localStream = JSONObject(stream.toString())
            localStream.optJSONObject("sockopt")?.remove("dialerProxy")
            readStream(bean, localStream)
            bean.initializeDefaultValues()
            if (bean.type == "xhttp" && dependencies[index].isNotBlank()) {
                val extra = XhttpExtra.toSingBox(bean.xhttpExtra, bean.xhttpMode, bean.realityPubKey.isNotBlank())
                val download = extra["download"] as? Map<*, *>
                require(download?.get("detour") == null) { "Independent XHTTP download detour requires explicit conversion" }
            }
            bean
        }
        val chainNames = mutableSetOf<String>()
        val chains = orders.mapIndexedNotNull { index, order ->
            if (order.size < 2) null else {
                val name = beans[index].name + " (Chain)"
                var unique = name
                var suffix = 2
                while (!chainNames.add(unique)) unique = "$name (${suffix++})"
                ImportedChain(unique, order.map { beans[it] })
            }
        }
        if (chains.isNotEmpty()) {
            // Label inner components explicitly: selecting them individually omits their chain.
            for (i in orders.indices) if (orders[i].size > 1) beans[i].name += " (Chain hop)"
            onChains?.invoke(chains)
        }
        return beans
    }

    private fun readStream(bean: StandardV2RayBean, stream: JSONObject) {
        val network = stream.optString("network").ifBlank { stream.optString("method", "tcp") }
        val security = stream.optString("security", "none")
        val tls = when (security) {
            "none", "" -> { bean.security = "none"; JSONObject() }
            "tls", "reality" -> {
                bean.security = "tls"
                obj(stream, if (security == "tls") "tlsSettings" else "realitySettings")
            }
            else -> throw IllegalArgumentException("Unsupported Xray stream security: $security")
        }
        if (security == "tls") {
            checkKeys(tls, setOf("serverName", "fingerprint", "allowInsecure", "alpn", "minVersion", "maxVersion", "cipherSuites", "curvePreferences"), "TLS")
            bean.sni = tls.optString("serverName")
            bean.utlsFingerprint = tls.optString("fingerprint").takeUnless { it == "none" } ?: ""
            bean.allowInsecure = tls.optBoolean("allowInsecure")
            bean.alpn = stringList(tls, "alpn")
            bean.tlsMinVersion = tls.optString("minVersion")
            bean.tlsMaxVersion = tls.optString("maxVersion")
            bean.tlsCipherSuites = tls.optString("cipherSuites").replace(':', ',')
            bean.tlsCurves = stringList(tls, "curvePreferences")
        } else if (security == "reality") {
            checkKeys(tls, setOf("serverName", "fingerprint", "publicKey", "password", "shortId", "mldsa65Verify", "pqv", "show", "spiderX", "supportX25519MLKEM768", "support_x25519mlkem768", "support-x25519mlkem768"), "REALITY")
            bean.sni = tls.optString("serverName")
            bean.utlsFingerprint = tls.optString("fingerprint", "chrome").ifBlank { "chrome" }
            require(bean.utlsFingerprint != "none") { "REALITY requires a uTLS fingerprint" }
            bean.realityPubKey = tls.optString("password").ifBlank { required(tls, "publicKey") }
            bean.realityShortId = tls.optString("shortId")
            bean.realityMldsa65Verify = validateRealityMldsa65(tls.optString("mldsa65Verify", tls.optString("pqv")))
            val hybrid = listOf("supportX25519MLKEM768", "support_x25519mlkem768", "support-x25519mlkem768")
                .firstNotNullOfOrNull { if (tls.has(it)) tls.opt(it)?.toString() else null }
            bean.realityHybridMode = parseRealityHybridMode(hybrid)
        }
        val sock = obj(stream, "sockopt")
        // Ignore Xray-specific address selection and connection racing.
        // The app's global DNS and sing-box dialer remain authoritative.
        checkKeys(sock, setOf("tcpFastOpen", "tcpKeepAliveInterval", "tcpKeepAliveIdle", "domainStrategy", "happyEyeballs"), "socket options")
        val dialer = JSONObject()
        if (sock.has("tcpFastOpen")) {
            val fastOpen = sock.opt("tcpFastOpen")
            require(fastOpen is Boolean) { "Unsupported Xray tcpFastOpen value" }
            dialer.put("tcp_fast_open", fastOpen)
        }
        // Xray uses seconds here; negative values have special meanings.
        for ((from, to) in listOf("tcpKeepAliveInterval" to "tcp_keep_alive_interval", "tcpKeepAliveIdle" to "tcp_keep_alive")) {
            if (sock.has(from)) {
                val seconds = sock.getLong(from)
                require(seconds >= 0) { "Unsupported Xray $from value" }
                if (seconds > 0) dialer.put(to, "${seconds}s")
            }
        }
        if (dialer.length() > 0) bean.outboundDialer = dialer.toString()
        val transportKey = when (network) {
            "raw", "tcp" -> if (stream.has("rawSettings")) "rawSettings" else "tcpSettings"
            "ws" -> "wsSettings"
            "httpupgrade" -> "httpupgradeSettings"
            "grpc" -> "grpcSettings"
            "xhttp", "splithttp" -> if (stream.has("xhttpSettings")) "xhttpSettings" else "splithttpSettings"
            "kcp", "mkcp" -> "kcpSettings"
            else -> throw IllegalArgumentException("Unsupported Xray transport: $network")
        }
        checkKeys(stream, setOf("network", "method", "security", "tlsSettings", "realitySettings", "sockopt", transportKey), "stream settings")
        val t = obj(stream, transportKey)
        bean.type = when (network) { "raw" -> "tcp"; "splithttp" -> "xhttp"; "mkcp" -> "kcp"; else -> network }
        when (bean.type) {
            "tcp" -> {
                checkKeys(t, setOf("header"), "RAW")
                val header = obj(t, "header")
                require(header.optString("type", "none") == "none") { "Xray RAW HTTP header requires separate conversion" }
            }
            "ws", "httpupgrade" -> {
                checkKeys(t, setOf("host", "path", "headers"), "HTTP transport")
                bean.host = t.optString("host")
                bean.path = t.optString("path", "/")
                val headers = obj(t, "headers")
                val lines = mutableListOf<String>()
                for (key in headers.keys()) {
                    val value = headers.getString(key)
                    require(!key.contains(':') && !key.contains('\n') && !value.contains('\n') && !value.contains('\r')) { "Invalid HTTP header" }
                    if (key.equals("host", true)) {
                        require(bean.type == "ws") { "HTTPUpgrade Host must be specified separately" }
                        if (bean.host.isBlank()) bean.host = value
                    } else lines.add("$key: $value")
                }
                bean.transportHeaders = lines.joinToString("\n")
                require(bean.type != "httpupgrade" || !bean.path.contains("ed=")) { "HTTPUpgrade early data is unsupported" }
            }
            "grpc" -> {
                checkKeys(t, setOf("serviceName", "multiMode", "idle_timeout", "health_check_timeout", "permit_without_stream"), "gRPC")
                require(!t.optBoolean("multiMode")) { "Xray gRPC multiMode is unsupported" }
                bean.path = t.optString("serviceName")
                bean.grpcIdleTimeout = t.optInt("idle_timeout")
                bean.grpcPingTimeout = t.optInt("health_check_timeout")
                bean.grpcPermitWithoutStream = t.optBoolean("permit_without_stream")
            }
            "xhttp" -> {
                bean.host = t.optString("host")
                bean.path = t.optString("path", "/")
                bean.xhttpMode = t.optString("mode", "auto").ifBlank { "auto" }
                val extra = JSONObject((if (t.has("extra") && !t.isNull("extra")) t.getJSONObject("extra") else t).toString())
                for (key in listOf("host", "path", "mode", "extra")) extra.remove(key)
                bean.xhttpExtra = extra.toString()
                XhttpExtra.toSingBox(bean.xhttpExtra, bean.xhttpMode, security == "reality")
            }
            "kcp" -> {
                checkKeys(t, setOf("mtu", "tti", "uplinkCapacity", "downlinkCapacity", "congestion", "readBufferSize", "writeBufferSize", "header", "seed"), "mKCP")
                bean.kcpMtu = t.optString("mtu")
                bean.kcpTti = t.optString("tti")
                bean.kcpUp = t.optString("uplinkCapacity")
                bean.kcpDown = t.optString("downlinkCapacity")
                bean.kcpRead = t.optString("readBufferSize")
                bean.kcpWrite = t.optString("writeBufferSize")
                bean.kcpCongestion = t.optBoolean("congestion")
                bean.kcpHeader = obj(t, "header").optString("type")
                bean.kcpSeed = t.optString("seed")
            }
        }
    }
}
