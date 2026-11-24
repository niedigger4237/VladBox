package io.nekohasekai.sagernet.fmt.v2ray

import android.text.TextUtils
import com.google.gson.Gson
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.utils.NGUtil
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

data class VmessQRCode(
    var v: String = "",
    var ps: String = "",
    var add: String = "",
    var port: String = "",
    var id: String = "",
    var aid: String = "0",
    var scy: String = "",
    var net: String = "",
    var type: String = "",
    var host: String = "",
    var path: String = "",
    var tls: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fp: String = "",
    var pinSHA256: String = "",
    var pbk: String = "",
    var sid: String = "",
    var mode: String = "",
    var extra: String = "",
    var packetEncoding: String = "",
    var seed: String = "",
    var network: String = "",
    var ed: Int = 0,
    var eh: String = "",
)

fun StandardV2RayBean.isTLS(): Boolean {
    return security == "tls"
}

fun StandardV2RayBean.setTLS(boolean: Boolean) {
    security = if (boolean) "tls" else ""
}

fun parseV2Ray(link: String): StandardV2RayBean {
    if (link.startsWith("vless://", ignoreCase = true)) {
        val url = ("https://" + link.substringAfter("://")).toHttpUrl()
        require(url.username.isNotBlank() && url.password.isEmpty()) { "Invalid VLESS user info" }
        return VMessBean().apply { alterId = -1; parseDuckSoft(url) }
    }
    // Try parse stupid formats first

    if (!link.contains("?")) {
        try {
            return parseV2RayN(link)
        } catch (e: Exception) {
            Logs.i("try v2rayN: " + e.readableMessage)
        }
    }

    try {
        return tryResolveVmess4Kitsunebi(link)
    } catch (e: Exception) {
        Logs.i("try Kitsunebi: " + e.readableMessage)
    }

    // "std" format

    val bean = VMessBean().apply { if (link.startsWith("vless://")) alterId = -1 }
    val url = link.replace("vmess://", "https://").replace("vless://", "https://").toHttpUrl()

    if (url.password.isNotBlank()) {
        // https://github.com/v2fly/v2fly-github-io/issues/26 (rarely use)
        bean.serverAddress = url.host
        bean.serverPort = url.port
        bean.name = url.fragment

        var protocol = url.username
        bean.type = protocol
        bean.alterId = url.password.substringAfterLast('-').toInt()
        bean.uuid = url.password.substringBeforeLast('-')

        if (protocol.endsWith("+tls")) {
            bean.security = "tls"
            protocol = protocol.substring(0, protocol.length - 4)

            url.queryParameter("tlsServerName")?.let {
                if (it.isNotBlank()) {
                    bean.sni = it
                }
            }
        }

        when (protocol) {
//            "tcp" -> {
//                url.queryParameter("type")?.let { type ->
//                    if (type == "http") {
//                        bean.headerType = "http"
//                        url.queryParameter("host")?.let {
//                            bean.host = it
//                        }
//                    }
//                }
//            }
            "http" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it.split("|").joinToString(",")
                }
            }

            "ws" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it
                }
            }

            "grpc" -> {
                url.queryParameter("serviceName")?.let {
                    bean.path = it
                }
            }

            "httpupgrade" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it
                }
            }

            "xhttp" -> bean.parseXhttpQuery(url)
        }
    } else {
        // also vless format
        bean.parseDuckSoft(url)
    }

    return bean
}

// https://github.com/XTLS/Xray-core/issues/91
fun StandardV2RayBean.parseDuckSoft(url: HttpUrl) {
    serverAddress = url.host
    serverPort = url.port
    name = url.fragment

    if (this is TrojanBean) {
        password = url.username
    } else {
        uuid = url.username
    }

    // not ducksoft fmt path
    if (url.pathSegments.size > 1 || url.pathSegments[0].isNotBlank()) {
        path = url.pathSegments.joinToString("/")
    }

    type = url.queryParameter("type") ?: "tcp"
    if (type == "h2" || url.queryParameter("headerType") == "http") type = "http"

    security = url.queryParameter("security")
    if (security.isNullOrBlank()) {
        security = if (this is TrojanBean) "tls" else "none"
    }

    when (security) {
        "tls", "reality" -> {
            security = "tls"
            url.queryParameter("allowInsecure")?.let {
                allowInsecure = it == "1" || it == "true"
            }
            url.queryParameter("sni")?.let {
                sni = it
            }
            url.queryParameter("host")?.let {
                if (sni.isNullOrBlank()) sni = it
            }
            url.queryParameter("alpn")?.let {
                alpn = it
            }
            url.queryParameter("cert")?.let {
                certificates = it
            }
            url.queryParameter("pbk")?.let {
                realityPubKey = it
            }
            url.queryParameter("sid")?.let {
                realityShortId = it
            }
        }
    }

    when (type) {
        "http" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
        }

        "ws" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
            url.queryParameter("ed")?.let { ed ->
                wsMaxEarlyData = ed.toIntOrNull()?.takeIf { it >= 0 } ?: 0

                url.queryParameter("eh")?.let {
                    earlyDataHeaderName = it
                }
            }
        }

        "grpc" -> {
            url.queryParameter("serviceName")?.let {
                path = it
            }
        }

        "httpupgrade" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
        }

        "xhttp" -> parseXhttpQuery(url)
    }

    if (isVLESS) {
        url.queryParameter("encryption")?.let { vlessEncryption = it }
        if (type == "kcp" || type == "mkcp") {
            type = "kcp"
            url.queryParameter("headerType")?.let { kcpHeader = it }
            url.queryParameter("seed")?.let { kcpSeed = it }
        }
    }

    url.queryParameter("network")?.let { vlessNetwork = it }

    url.queryParameter("pinSHA256")?.let { tlsPubKeySha256 = it }

    // maybe from matsuri vmess exoprt
    if (this is VMessBean && !isVLESS) {
        url.queryParameter("encryption")?.let {
            encryption = it
        }
    }

    url.queryParameter("packetEncoding")?.let {
        when (it) {
            "packet", "packetaddr" -> packetEncoding = 1
            "xudp" -> packetEncoding = 2
        }
    }

    url.queryParameter("flow")?.let {
        if (isVLESS) {
            encryption = it.removeSuffix("-udp443")
        }
    }

    url.queryParameter("fp")?.let {
        utlsFingerprint = it
    }
}

// 不确定是谁的格式
private fun tryResolveVmess4Kitsunebi(server: String): VMessBean {
    // vmess://YXV0bzo1YWY1ZDBlYy02ZWEwLTNjNDMtOTNkYi1jYTMwMDg1MDNiZGJAMTgzLjIzMi41Ni4xNjE6MTIwMg
    // ?remarks=*%F0%9F%87%AF%F0%9F%87%B5JP%20-355%20TG@moon365free&obfsParam=%7B%22Host%22:%22183.232.56.161%22%7D&path=/v2ray&obfs=websocket&alterId=0

    var result = server.replace("vmess://", "")
    val indexSplit = result.indexOf("?")
    if (indexSplit > 0) {
        result = result.substring(0, indexSplit)
    }
    result = NGUtil.decode(result)

    val arr1 = result.split('@')
    if (arr1.count() != 2) {
        throw IllegalStateException("invalid kitsunebi format")
    }
    val arr21 = arr1[0].split(':')
    val arr22 = arr1[1].split(':')
    if (arr21.count() != 2) {
        throw IllegalStateException("invalid kitsunebi format")
    }

    return VMessBean().apply {
        serverAddress = arr22[0]
        serverPort = NGUtil.parseInt(arr22[1])
        uuid = arr21[1]
        encryption = arr21[0]
        if (indexSplit < 0) return@apply

        val url = ("https://localhost/path?" + server.substringAfter("?")).toHttpUrl()
        url.queryParameter("remarks")?.apply { name = this }
        url.queryParameter("alterId")?.apply { alterId = this.toInt() }
        url.queryParameter("path")?.apply { path = this }
        url.queryParameter("tls")?.apply { security = "tls" }
        url.queryParameter("allowInsecure")
            ?.apply { if (this == "1" || this == "true") allowInsecure = true }
        url.queryParameter("obfs")?.apply {
            type = this.replace("websocket", "ws").replace("none", "tcp")
            if (type == "ws") {
                url.queryParameter("obfsParam")?.apply {
                    if (this.startsWith("{")) {
                        host = JSONObject(this).getStr("Host")
                    } else if (security == "tls") {
                        sni = this
                    }
                }
            }
        }
    }
}

// SagerNet's
// Do not support some format and then throw exception
fun parseV2RayN(link: String): VMessBean {
    val result = link.substringAfter("vmess://").decodeBase64UrlSafe()
    if (result.contains("= vmess")) {
        return parseCsvVMess(result)
    }
    val bean = VMessBean()
    val vmessQRCode = Gson().fromJson(result, VmessQRCode::class.java)

    // Although VmessQRCode fields are non null, looks like Gson may still create null fields
    if (TextUtils.isEmpty(vmessQRCode.add)
        || TextUtils.isEmpty(vmessQRCode.port)
        || TextUtils.isEmpty(vmessQRCode.id)
        || TextUtils.isEmpty(vmessQRCode.net)
    ) {
        throw Exception("invalid VmessQRCode")
    }

    bean.name = vmessQRCode.ps
    bean.serverAddress = vmessQRCode.add
    bean.serverPort = vmessQRCode.port.toIntOrNull()
    bean.encryption = vmessQRCode.scy
    bean.uuid = vmessQRCode.id
    bean.alterId = vmessQRCode.aid.toIntOrNull()
    bean.type = vmessQRCode.net
    bean.host = vmessQRCode.host
    bean.path = vmessQRCode.path
    val headerType = vmessQRCode.type
    bean.tlsPubKeySha256 = vmessQRCode.pinSHA256.orEmpty()
    bean.realityPubKey = vmessQRCode.pbk.orEmpty()
    bean.realityShortId = vmessQRCode.sid.orEmpty()
    bean.xhttpMode = vmessQRCode.mode.orEmpty()
    bean.xhttpExtra = vmessQRCode.extra.orEmpty()
    bean.vlessNetwork = vmessQRCode.network.orEmpty()
    bean.wsMaxEarlyData = vmessQRCode.ed.coerceAtLeast(0)
    bean.earlyDataHeaderName = vmessQRCode.eh.orEmpty()
    bean.packetEncoding = when (vmessQRCode.packetEncoding) { "packet", "packetaddr" -> 1; "xudp" -> 2; else -> 0 }
    if (bean.type == "kcp") { bean.kcpHeader = headerType.orEmpty(); bean.kcpSeed = vmessQRCode.seed.orEmpty() }

    when (bean.type) {
        "", "tcp", "raw" -> {
            if (headerType == "http") {
                bean.type = "http"
            }
        }
    }
    when (vmessQRCode.tls) {
        "tls", "reality" -> {
            bean.security = "tls"
            bean.sni = vmessQRCode.sni
            if (bean.sni.isNullOrBlank()) bean.sni = bean.host
            bean.alpn = vmessQRCode.alpn
            bean.utlsFingerprint = vmessQRCode.fp
        }
    }

    return bean
}

private fun parseCsvVMess(csv: String): VMessBean {

    val args = csv.split(",")

    val bean = VMessBean()

    bean.serverAddress = args[1]
    bean.serverPort = args[2].toInt()
    bean.encryption = args[3]
    bean.uuid = args[4].replace("\"", "")

    args.subList(5, args.size).forEach {

        when {
            it == "over-tls=true" -> bean.security = "tls"
            it.startsWith("tls-host=") -> bean.host = it.substringAfter("=")
            it.startsWith("obfs=") -> bean.type = it.substringAfter("=")
            it.startsWith("obfs-path=") || it.contains("Host:") -> {
                runCatching {
                    bean.path = it.substringAfter("obfs-path=\"").substringBefore("\"obfs")
                }
                runCatching {
                    bean.host = it.substringAfter("Host:").substringBefore("[")
                }

            }

        }

    }

    return bean

}

fun VMessBean.toV2rayN(): String {
    val bean = this
    return "vmess://" + VmessQRCode().apply {
        v = "2"
        ps = bean.name
        add = bean.serverAddress
        port = bean.serverPort.toString()
        id = bean.uuid
        aid = bean.alterId.toString()
        net = bean.type
        host = bean.host
        path = bean.path

        when (net) {
            "http" -> {
                if (!isTLS()) {
                    type = "http"
                    net = "tcp"
                }
            }
        }

        if (isTLS()) {
            tls = "tls"
            if (bean.realityPubKey.isNotBlank()) {
                tls = "reality"
            }
        }

        scy = bean.encryption
        sni = bean.sni
        alpn = bean.alpn.replace("\n", ",")
        fp = bean.utlsFingerprint
        if (bean.isTLS()) pinSHA256 = bean.tlsPubKeySha256
        if (bean.isTLS() && bean.realityPubKey.isNotBlank()) { pbk = bean.realityPubKey; sid = bean.realityShortId }
        if (net == "xhttp") { mode = bean.xhttpMode; extra = bean.xhttpExtra }
        if (net == "kcp") { type = bean.kcpHeader; seed = bean.kcpSeed }
        if (net == "ws") { ed = bean.wsMaxEarlyData; eh = bean.earlyDataHeaderName }
        network = bean.vlessNetwork
        packetEncoding = when (bean.packetEncoding) { 1 -> "packetaddr"; 2 -> "xudp"; else -> "" }
    }.let {
        NGUtil.encode(Gson().toJson(it))
    }
}

fun StandardV2RayBean.toUriVMessVLESSTrojan(isTrojan: Boolean): String {
    // VMess
    if (this is VMessBean && !isVLESS) {
        return toV2rayN()
    }

    // VLESS & Trojan (ducksoft fmt)
    val builder = linkBuilder()
        .username(if (this is TrojanBean) password else uuid)
        .host(serverAddress)
        .port(serverPort)
        .addQueryParameter("type", type)

    if (isVLESS) {
        builder.addQueryParameter("encryption", vlessEncryption.ifBlank { "none" })
        if (encryption.isNotBlank() && encryption !in listOf("auto", "none")) builder.addQueryParameter("flow", encryption)
    }

    if (vlessNetwork.isNotBlank()) builder.addQueryParameter("network", vlessNetwork)

    when (type) {
        "tcp" -> {}
        "ws", "http", "httpupgrade" -> {
            if (host.isNotBlank()) {
                builder.addQueryParameter("host", host)
            }
            if (path.isNotBlank()) {
                builder.addQueryParameter("path", path)
            }
            if (type == "ws") {
                if (wsMaxEarlyData > 0) {
                    builder.addQueryParameter("ed", "$wsMaxEarlyData")
                    if (earlyDataHeaderName.isNotBlank()) {
                        builder.addQueryParameter("eh", earlyDataHeaderName)
                    }
                }
            } else if (type == "http" && !isTLS()) {
                builder.setQueryParameter("type", "tcp")
                builder.addQueryParameter("headerType", "http")
            }
        }

        "kcp" -> {
            if (isVLESS) {
                if (kcpHeader.isNotBlank()) builder.addQueryParameter("headerType", kcpHeader)
                if (kcpSeed.isNotBlank()) builder.addQueryParameter("seed", kcpSeed)
            }
        }
        "grpc" -> {
            if (path.isNotBlank()) {
                builder.setQueryParameter("serviceName", path)
            }
        }

        "xhttp" -> {
            if (host.isNotBlank()) builder.addQueryParameter("host", host)
            if (path.isNotBlank()) builder.addQueryParameter("path", path)
            if (xhttpMode.isNotBlank() && xhttpMode != "auto") {
                builder.addQueryParameter("mode", xhttpMode)
            }
            if (xhttpExtra.isNotBlank()) builder.addQueryParameter("extra", xhttpExtra)
        }
    }

    if (security.isNotBlank() && security != "none") {
        builder.addQueryParameter("security", security)
        when (security) {
            "tls" -> {
                if (sni.isNotBlank()) {
                    builder.addQueryParameter("sni", sni)
                }
                if (alpn.isNotBlank()) {
                    builder.addQueryParameter("alpn", alpn.replace("\n", ","))
                }
                if (certificates.isNotBlank()) {
                    builder.addQueryParameter("cert", certificates)
                }
                if (tlsPubKeySha256.isNotBlank()) builder.addQueryParameter("pinSHA256", tlsPubKeySha256)
                if (allowInsecure) {
                    builder.addQueryParameter("allowInsecure", "1")
                }
                if (utlsFingerprint.isNotBlank()) {
                    builder.addQueryParameter("fp", utlsFingerprint)
                }
                if (realityPubKey.isNotBlank()) {
                    builder.setQueryParameter("security", "reality")
                    builder.addQueryParameter("pbk", realityPubKey)
                    builder.addQueryParameter("sid", realityShortId)
                }
            }
        }
    }

    when (packetEncoding) {
        1 -> {
            builder.addQueryParameter("packetEncoding", "packetaddr")
        }

        2 -> {
            builder.addQueryParameter("packetEncoding", "xudp")
        }
    }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink(if (isTrojan) "trojan" else "vless")
}

fun buildSingBoxOutboundStreamSettings(bean: StandardV2RayBean): V2RayTransportOptions? {
    when (bean.type) {
        "tcp" -> {
            return null
        }

        "ws" -> {
            return V2RayTransportOptions_WebsocketOptions().apply {
                type = "ws"
                headers = mutableMapOf()
                headers.putAll(bean.parseTransportHeaders())

                if (bean.host.isNotBlank()) {
                    headers["Host"] = bean.host
                }

                if (bean.path.contains("?ed=")) {
                    path = bean.path.substringBefore("?ed=")
                    max_early_data = bean.path.substringAfter("?ed=").toIntOrNull() ?: 2048
                    early_data_header_name = "Sec-WebSocket-Protocol"
                } else {
                    path = bean.path.takeIf { it.isNotBlank() } ?: "/"
                }

                if (bean.wsMaxEarlyData > 0) {
                    max_early_data = bean.wsMaxEarlyData
                }

                if (bean.earlyDataHeaderName.isNotBlank()) {
                    early_data_header_name = bean.earlyDataHeaderName
                }
            }
        }

        "http" -> {
            return V2RayTransportOptions_HTTPOptions().apply {
                type = "http"
                bean.httpIdleTimeout.trim().takeIf { it.isNotEmpty() }?.let { _hack_config_map["idle_timeout"] = it }
                bean.httpPingTimeout.trim().takeIf { it.isNotEmpty() }?.let { _hack_config_map["ping_timeout"] = it }
                if (bean.httpMethod.isNotBlank()) method = bean.httpMethod
                else if (!bean.isTLS()) method = "GET" // v2ray tcp header
                bean.parseTransportHeaders().takeIf { it.isNotEmpty() }?.let { headers = it }
                if (bean.host.isNotBlank()) {
                    host = bean.host.split(",")
                }
                path = bean.path.takeIf { it.isNotBlank() } ?: "/"
            }
        }

        "quic" -> {
            return V2RayTransportOptions().apply {
                type = "quic"
            }
        }

        "grpc" -> {
            return V2RayTransportOptions_GRPCOptions().apply {
                type = "grpc"
                service_name = bean.path
                if (bean.grpcIdleTimeout > 0) _hack_config_map["idle_timeout"] = "${bean.grpcIdleTimeout}s"
                if (bean.grpcPingTimeout > 0) _hack_config_map["ping_timeout"] = "${bean.grpcPingTimeout}s"
                if (bean.grpcPermitWithoutStream) _hack_config_map["permit_without_stream"] = true
            }
        }

        "httpupgrade" -> {
            return V2RayTransportOptions_HTTPUpgradeOptions().apply {
                type = "httpupgrade"
                host = bean.host
                path = bean.path
                bean.parseTransportHeaders().takeIf { it.isNotEmpty() }?.let { headers = it }
            }
        }

        "kcp" -> {
            return V2RayTransportOptions().apply {
                type = "kcp"
                bean.kcpMtu.trim().takeIf { it.isNotEmpty() }?.let { val n = it.toLongOrNull() ?: error("Invalid KCP mtu"); require(n in 1L..4294967295L); _hack_config_map["mtu"] = n }
                bean.kcpTti.trim().takeIf { it.isNotEmpty() }?.let { val n = it.toLongOrNull() ?: error("Invalid KCP tti"); require(n in 1L..4294967295L); _hack_config_map["tti"] = n }
                bean.kcpUp.trim().takeIf { it.isNotEmpty() }?.let { val n = it.toLongOrNull() ?: error("Invalid KCP uplink_capacity"); require(n in 1L..4294967295L); _hack_config_map["uplink_capacity"] = n }
                bean.kcpDown.trim().takeIf { it.isNotEmpty() }?.let { val n = it.toLongOrNull() ?: error("Invalid KCP downlink_capacity"); require(n in 1L..4294967295L); _hack_config_map["downlink_capacity"] = n }
                bean.kcpRead.trim().takeIf { it.isNotEmpty() }?.let { val n = it.toLongOrNull() ?: error("Invalid KCP read_buffer_size"); require(n in 1L..4294967295L); _hack_config_map["read_buffer_size"] = n }
                bean.kcpWrite.trim().takeIf { it.isNotEmpty() }?.let { val n = it.toLongOrNull() ?: error("Invalid KCP write_buffer_size"); require(n in 1L..4294967295L); _hack_config_map["write_buffer_size"] = n }
                bean.kcpHeader.trim().takeIf { it.isNotEmpty() }?.let { _hack_config_map["header_type"] = it }
                bean.kcpSeed.trim().takeIf { it.isNotEmpty() }?.let { _hack_config_map["seed"] = it }
                if (bean.kcpCongestion) _hack_config_map["congestion"] = true
            }
        }

        "xhttp" -> {
            return V2RayTransportOptions_XHTTPOptions().apply {
                type = "xhttp"
                if (bean.host.isNotBlank()) host = bean.host
                path = bean.path.takeIf { it.isNotBlank() } ?: "/"
                mode = bean.xhttpMode.takeIf { it.isNotBlank() } ?: "auto"
                XhttpExtra.toSingBox(bean.xhttpExtra, mode).forEach { (k, v) ->
                    _hack_config_map[k] = v
                }
            }
        }
    }

    error("Unsupported transport: ${bean.type}")
}

/** "Name: value" lines -> header map */
fun StandardV2RayBean.parseTransportHeaders(): MutableMap<String, String> {
    val map = linkedMapOf<String, String>()
    transportHeaders.lines().forEach { line ->
        val i = line.indexOf(':')
        if (i > 0) map[line.substring(0, i).trim()] = line.substring(i + 1).trim()
    }
    return map
}

fun buildSingBoxOutboundTLS(bean: StandardV2RayBean): OutboundTLSOptions? {
    if (bean.security != "tls") return null
    return OutboundTLSOptions().apply {
        enabled = true
        insecure = bean.allowInsecure || DataStore.globalAllowInsecure
        if (bean.sni.isNotBlank()) server_name = bean.sni
        if (bean.alpn.isNotBlank()) alpn = bean.alpn.listByLineOrComma()
        if (bean.certificates.isNotBlank()) certificate = bean.certificates
        bean.tlsEngine.trim().takeIf { it.isNotEmpty() }?.let {
            require(it == "go") { "Only the Go TLS engine is supported on Android" }
            _hack_config_map["engine"] = it
        }
        require(bean.certificates.isBlank() || bean.tlsCertificatePath.isBlank()) { "Choose certificate text or path" }
        bean.tlsCertificatePath.takeIf { it.isNotBlank() }?.let { _hack_config_map["certificate_path"] = it }
        val versions = listOf("1.0", "1.1", "1.2", "1.3")
        val min = bean.tlsMinVersion.trim()
        val max = bean.tlsMaxVersion.trim()
        require(min.isEmpty() || min in versions) { "Invalid minimum TLS version" }
        require(max.isEmpty() || max in versions) { "Invalid maximum TLS version" }
        require(min.isEmpty() || max.isEmpty() || versions.indexOf(min) <= versions.indexOf(max)) { "Invalid TLS version range" }
        if (min.isNotEmpty()) _hack_config_map["min_version"] = min
        if (max.isNotEmpty()) _hack_config_map["max_version"] = max
        bean.tlsCurves.takeIf { it.isNotBlank() }?.let { _hack_config_map["curve_preferences"] = it.listByLineOrComma() }
        bean.tlsHandshakeTimeout.takeIf { it.isNotBlank() }?.let { _hack_config_map["handshake_timeout"] = it }
        require(bean.tlsClientCertificate.isBlank() || bean.tlsClientCertificatePath.isBlank()) { "Choose client certificate text or path" }
        require(bean.tlsClientKey.isBlank() || bean.tlsClientKeyPath.isBlank()) { "Choose client key text or path" }
        require((bean.tlsClientCertificate.isNotBlank() || bean.tlsClientCertificatePath.isNotBlank()) ==
            (bean.tlsClientKey.isNotBlank() || bean.tlsClientKeyPath.isNotBlank())) { "Client certificate and key must be configured together" }
        bean.tlsClientCertificatePath.takeIf { it.isNotBlank() }?.let { _hack_config_map["client_certificate_path"] = it }
        bean.tlsClientKeyPath.takeIf { it.isNotBlank() }?.let { _hack_config_map["client_key_path"] = it }
        if (bean.tlsClientCertificate.isNotBlank()) {
            _hack_config_map["client_certificate"] = bean.tlsClientCertificate.lines()
        }
        if (bean.tlsClientKey.isNotBlank()) _hack_config_map["client_key"] = bean.tlsClientKey.lines()
        if (bean.tlsDisableSni) disable_sni = true
        if (bean.tlsPubKeySha256.isNotBlank()) certificate_public_key_sha256 = bean.tlsPubKeySha256.listByLineOrComma()
        if (bean.tlsCipherSuites.isNotBlank()) cipher_suites = bean.tlsCipherSuites.listByLineOrComma()
        if (bean.tlsFragment) {
            fragment = true
            if (bean.tlsFragmentFallbackDelay.isNotBlank()) fragment_fallback_delay = bean.tlsFragmentFallbackDelay
        }
        if (bean.tlsRecordFragment) record_fragment = true
        val spoof = bean.tlsSpoof.trim()
        val spoofMethod = bean.tlsSpoofMethod.trim()
        if (spoof.isNotEmpty() || spoofMethod.isNotEmpty()) {
            require(bean.realityPubKey.isBlank()) { "TLS spoof is unsupported with Reality" }
            require(spoof.isNotEmpty() && !bean.tlsDisableSni) { "TLS spoof requires SNI and a spoof server name" }
            require(!spoof.equals(bean.sni.ifBlank { bean.serverAddress }, ignoreCase = true)) { "TLS spoof must differ from server name" }
            require(spoofMethod.isEmpty() || spoofMethod in listOf("wrong-sequence", "wrong-checksum", "wrong-ack", "wrong-md5", "wrong-timestamp")) { "Invalid TLS spoof method" }
            _hack_config_map["spoof"] = spoof
            if (spoofMethod.isNotEmpty()) _hack_config_map["spoof_method"] = spoofMethod
        }
        if (bean.tlsKernelTx) _hack_config_map["kernel_tx"] = true
        if (bean.tlsKernelRx) _hack_config_map["kernel_rx"] = true
        var fp = bean.utlsFingerprint
        if (bean.realityPubKey.isNotBlank()) {
            require(!bean.tlsDisableSni) { "Reality requires SNI" }
            reality = OutboundRealityOptions().apply {
                enabled = true
                public_key = bean.realityPubKey
                short_id = bean.realityShortId
                if (bean.realityHybrid) _hack_config_map["support_x25519mlkem768"] = true
            }
            if (fp.isNullOrBlank()) fp = "chrome"
        }
        if (fp.isNotBlank()) {
            utls = OutboundUTLSOptions().apply {
                enabled = true
                fingerprint = fp
            }
        }
        if (bean.enableECH) {
            ech = OutboundECHOptions().apply {
                enabled = true
                if (bean.echConfig.isNotBlank()) {
                    config = bean.echConfig.lines()
                }
                require(bean.echConfig.isBlank() || bean.echConfigPath.isBlank()) { "Choose ECH config text or path" }
                bean.echConfigPath.takeIf { it.isNotBlank() }?.let { _hack_config_map["config_path"] = it }
                bean.echQueryName.takeIf { it.isNotBlank() }?.let { _hack_config_map["query_server_name"] = it }
            }
        }
    }
}

fun applyClientDialer(rawDialer: String, network: String, options: MutableMap<String, Any>) {
    val dialer = org.json.JSONObject(rawDialer.ifBlank { "{}" })
    val allowed = setOf("bind_interface","inet4_bind_address","inet6_bind_address","bind_address_no_port","protect_path","routing_mark","reuse_addr","netns","connect_timeout","tcp_fast_open","tcp_multi_path","disable_tcp_keep_alive","tcp_keep_alive","tcp_keep_alive_interval","udp_fragment","network_strategy","network_type","fallback_network_type","fallback_delay")
    require(!dialer.has("network_strategy") || listOf("bind_interface","inet4_bind_address","inet6_bind_address").none { dialer.optString(it).isNotBlank() }) { "Network strategy conflicts with interface or address binding" }
    fun native(value: Any?): Any? = when (value) {
        null, org.json.JSONObject.NULL -> null
        is org.json.JSONArray -> (0 until value.length()).map { native(value.get(it)) }
        else -> value
    }
    for (key in dialer.keys()) {
        require(key in allowed) { "Unsupported outbound dialer option: $key" }
        val value = dialer.get(key)
        if (key == "routing_mark") {
            val raw = value.toString()
            val mark = if (raw.startsWith("0x",true)) raw.substring(2).toLong(16) else raw.toLong()
            require(mark in 0L..4294967295L) { "Invalid routing mark" }
            options[key] = mark
        } else native(value)?.let { options[key] = it }
    }
    network.trim().takeIf { it.isNotEmpty() }?.let {
        require(it in listOf("tcp", "udp")) { "Invalid outbound network" }
        options["network"] = it
    }
}

fun buildSingBoxOutboundStandardV2RayBean(bean: StandardV2RayBean): Outbound {
    when (bean) {
        is HttpBean -> {
            return Outbound_HTTPOptions().apply {
                type = "http"
                applyClientDialer(bean.outboundDialer, "", _hack_config_map)
                server = bean.serverAddress
                server_port = bean.serverPort
                username = bean.username
                password = bean.password
                bean.httpPath.takeIf { it.isNotBlank() }?.let { _hack_config_map["path"] = it }
                bean.httpHeaders.takeIf { it.isNotBlank() }?.let {
                    val headers = com.google.gson.JsonParser.parseString(it).asJsonObject
                    _hack_config_map["headers"] = headers
                }
                tls = buildSingBoxOutboundTLS(bean)
            }
        }

        is VMessBean -> {
            if (bean.isVLESS) return Outbound_VLESSOptions().apply {
                type = "vless"
                applyClientDialer(bean.outboundDialer, bean.vlessNetwork, _hack_config_map)
                server = bean.serverAddress
                server_port = bean.serverPort
                uuid = bean.uuid
                bean.vlessEncryption.trim().takeIf { it.isNotEmpty() && it != "none" }?.let { _hack_config_map["encryption"] = it }
                if (bean.encryption.isNotBlank() && bean.encryption != "auto" && bean.encryption != "none") {
                    flow = bean.encryption
                }
                when (bean.packetEncoding) {
                    0 -> packet_encoding = ""
                    1 -> packet_encoding = "packetaddr"
                    2 -> packet_encoding = "xudp"
                }
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
            return Outbound_VMessOptions().apply {
                type = "vmess"
                applyStandardDialer(bean, _hack_config_map)
                server = bean.serverAddress
                server_port = bean.serverPort
                uuid = bean.uuid
                alter_id = bean.alterId
                if (bean.globalPadding) global_padding = true
                if (bean.authenticatedLength) authenticated_length = true
                security = bean.encryption.takeIf { it.isNotBlank() } ?: "auto"
                when (bean.packetEncoding) {
                    0 -> packet_encoding = ""
                    1 -> packet_encoding = "packetaddr"
                    2 -> packet_encoding = "xudp"
                }
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
        }

        is TrojanBean -> {
            return Outbound_TrojanOptions().apply {
                type = "trojan"
                applyStandardDialer(bean, _hack_config_map)
                server = bean.serverAddress
                server_port = bean.serverPort
                password = bean.password
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
        }

        else -> throw IllegalStateException("can't reach")
    }
}

private fun StandardV2RayBean.parseXhttpQuery(url: okhttp3.HttpUrl) {
    url.queryParameter("host")?.let { host = it }
    url.queryParameter("path")?.let { path = it }
    url.queryParameter("mode")?.let { xhttpMode = it }
    url.queryParameter("extra")?.let { xhttpExtra = it }
}
