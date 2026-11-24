package io.nekohasekai.sagernet.fmt.tuic

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseTuic(url: String): TuicBean {
    // https://github.com/daeuniverse/dae/discussions/182
    val link = url.replace("tuic://", "https://").toHttpUrlOrNull() ?: error(
        "invalid tuic link $url"
    )
    return TuicBean().apply {
        protocolVersion = 5

        name = link.fragment
        serverAddress = link.host
        serverPort = link.port

        val rawUser = link.username
        val rawPass = link.password

        if (rawUser.contains(":")) {
            val parts = rawUser.split(":", limit = 2)
            uuid = parts[0]
            token = parts.getOrElse(1) { "" }
        } else {
            uuid = rawUser
            token = rawPass
        }

        link.queryParameter("sni")?.let {
            sni = it
        }
        link.queryParameter("congestion_control")?.let {
            congestionController = it
        }
        link.queryParameter("udp_relay_mode")?.let {
            udpRelayMode = it
        }
        link.queryParameter("alpn")?.let {
            alpn = it
        }
        link.queryParameter("allow_insecure")?.let {
            if (it == "1" || it.equals("true", ignoreCase = true)) allowInsecure = true
        }
        link.queryParameter("pinSHA256")?.takeIf { it.isNotBlank() }?.let {
            quicTlsOptions = org.json.JSONObject().put("certificate_public_key_sha256", org.json.JSONArray(listOf(it))).toString()
        }
        link.queryParameter("zero_rtt_handshake")?.let { reduceRTT = it == "1" || it.equals("true", true) }
        link.queryParameter("udp_over_stream")?.let { tuicUdpOverStream = it == "1" || it.equals("true", true) }
        link.queryParameter("heartbeat")?.let { tuicHeartbeat = it }
        link.queryParameter("disable_sni")?.let {
            if (it == "1" || it.equals("true", ignoreCase = true)) disableSNI = true
        }
    }
}

fun TuicBean.toUri(): String {
    val builder = linkBuilder().username(uuid).password(token).host(serverAddress).port(serverPort)

    builder.addQueryParameter("congestion_control", congestionController)
    builder.addQueryParameter("udp_relay_mode", udpRelayMode)

    if (sni.isNotBlank()) builder.addQueryParameter("sni", sni)
    if (alpn.isNotBlank()) builder.addQueryParameter("alpn", alpn)
    if (allowInsecure) builder.addQueryParameter("allow_insecure", "1")
    org.json.JSONObject(quicTlsOptions.ifBlank { "{}" }).optJSONArray("certificate_public_key_sha256")?.let { pins ->
        if (pins.length() == 1) builder.addQueryParameter("pinSHA256", pins.getString(0))
    }
    if (reduceRTT) builder.addQueryParameter("zero_rtt_handshake", "1")
    if (tuicUdpOverStream) builder.addQueryParameter("udp_over_stream", "1")
    if (tuicHeartbeat.isNotBlank()) builder.addQueryParameter("heartbeat", tuicHeartbeat)
    if (disableSNI) builder.addQueryParameter("disable_sni", "1")
    if (name.isNotBlank()) builder.encodedFragment(name.urlSafe())

    return builder.toLink("tuic")
}

fun buildSingBoxOutboundTuicBean(bean: TuicBean): SingBoxOptions.Outbound_TUICOptions {
    if (bean.protocolVersion == 4) throw Exception("TUIC v4 is no longer supported")
    return SingBoxOptions.Outbound_TUICOptions().apply {
        type = "tuic"
        server = bean.serverAddress
            io.nekohasekai.sagernet.fmt.v2ray.applyClientDialer(bean.outboundDialer, "", _hack_config_map)
        server_port = bean.serverPort
        uuid = bean.uuid
        password = bean.token
        congestion_control = bean.congestionController
        when (bean.udpRelayMode) {
            "quic", "native" -> udp_relay_mode = bean.udpRelayMode
        }
        zero_rtt_handshake = bean.reduceRTT
        bean.tuicHeartbeat.trim().takeIf { it.isNotEmpty() }?.let {
            _hack_config_map["heartbeat"] = it
        }
        bean.tuicNetwork.trim().takeIf { it.isNotEmpty() }?.let {
            require(it in listOf("tcp", "udp")) { "Invalid TUIC network" }
            _hack_config_map["network"] = it
        }
        bean.tuicIdleTimeout.trim().takeIf { it.isNotEmpty() }?.let {
            _hack_config_map["idle_timeout"] = it
        }
        bean.tuicKeepAlive.trim().takeIf { it.isNotEmpty() }?.let {
            _hack_config_map["keep_alive_period"] = it
        }
        bean.tuicStreamWindow.trim().takeIf { it.isNotEmpty() }?.let {
            _hack_config_map["stream_receive_window"] = it
        }
        bean.tuicConnectionWindow.trim().takeIf { it.isNotEmpty() }?.let {
            _hack_config_map["connection_receive_window"] = it
        }
        bean.tuicMaxStreams.trim().takeIf { it.isNotEmpty() }?.let {
            val n = it.toIntOrNull() ?: error("Invalid max_concurrent_streams")
            require(n > 0)
            _hack_config_map["max_concurrent_streams"] = n
        }
        bean.tuicInitialPacket.trim().takeIf { it.isNotEmpty() }?.let {
            val n = it.toIntOrNull() ?: error("Invalid initial_packet_size")
            require(n > 0)
            _hack_config_map["initial_packet_size"] = n
        }
        if (bean.tuicUdpOverStream) {
            require(bean.udpRelayMode != "quic") { "UDP over stream requires native relay mode" }
            _hack_config_map["udp_over_stream"] = true
        }
        if (bean.tuicDisablePathMtu) _hack_config_map["disable_path_mtu_discovery"] = true
        tls = SingBoxOptions.OutboundTLSOptions().apply {
            if (bean.sni.isNotBlank()) {
                server_name = bean.sni
            }
            if (bean.alpn.isNotBlank()) {
                alpn = bean.alpn.listByLineOrComma()
            }
            if (bean.caText.isNotBlank()) {
                certificate = bean.caText
            }
            disable_sni = bean.disableSNI
            insecure = bean.allowInsecure || DataStore.globalAllowInsecure
            enabled = true
                io.nekohasekai.sagernet.fmt.applyQuicTlsOptions(bean.quicTlsOptions, _hack_config_map)
        }
    }
}
