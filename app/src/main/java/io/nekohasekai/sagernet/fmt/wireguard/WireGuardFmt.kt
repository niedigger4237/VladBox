package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

fun genReserved(anyStr: String): String {
    try {
        val list = anyStr.listByLineOrComma()
        val ba = ByteArray(3)
        if (list.size == 3) {
            list.forEachIndexed { index, s ->
                val i = s
                    .replace("[", "")
                    .replace("]", "")
                    .replace(" ", "")
                    .toIntOrNull() ?: return anyStr
                ba[index] = i.toByte()
            }
            return Util.b64EncodeOneLine(ba)
        } else {
            return anyStr
        }
    } catch (e: Exception) {
        return anyStr
    }
}

/** "1,2,3" / "[1, 2, 3]" or the base64 form -> three bytes */
fun reservedBytes(text: String): List<Int>? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val nums = t.listByLineOrComma().mapNotNull { it.replace("[", "").replace("]", "").trim().toIntOrNull() }
    if (nums.size == 3 && nums.all { it in 0..255 }) return nums
    return try {
        val ba = android.util.Base64.decode(t, android.util.Base64.DEFAULT)
        if (ba.size == 3) ba.map { it.toInt() and 0xff } else null
    } catch (e: Exception) { null }
}

/** "10.0.0.2" -> "10.0.0.2/32", "fd00::2" -> "fd00::2/128"; prefixes are kept. */
private fun String.asPrefix(): String {
    val v = trim()
    if (v.contains("/")) return v
    return if (v.contains(":")) "$v/128" else "$v/32"
}

/**
 * sing-box >= 1.13 removed the WireGuard outbound: WireGuard (and AmneziaWG, which is
 * the same endpoint with an `amnezia` block) is an endpoint now.
 *
 * Note: sing-box-extended's endpoint has no `reserved` (Cloudflare WARP) support.
 */
fun buildSingBoxEndpointWireguardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions {
    return SingBoxOptions.Endpoint_WireGuardOptions().apply {
        type = "wireguard"
        address = bean.localAddress.listByLineOrComma().map { it.asPrefix() }
        private_key = bean.privateKey
        mtu = bean.mtu
        if (bean.system) system = true
        if (bean.workers > 0) workers = bean.workers
        bean.udpTimeout.trim().takeIf { it.isNotEmpty() }?.let { udp_timeout = it }
        peers = listOf(SingBoxOptions.Endpoint_WireGuardPeer().apply {
            address = bean.serverAddress
            port = bean.serverPort
            public_key = bean.peerPublicKey
            if (bean.peerPreSharedKey.isNotBlank()) pre_shared_key = bean.peerPreSharedKey
            allowed_ips = listOf("0.0.0.0/0", "::/0")
            if (bean.persistentKeepalive > 0) persistent_keepalive_interval = bean.persistentKeepalive
        })
        if (!bean.isAmnezia) {
            if (bean.wgDialer.isNotBlank()) {
                val dialer = org.json.JSONObject(bean.wgDialer)
                val allowed = setOf("bind_interface", "inet4_bind_address", "inet6_bind_address",
                    "bind_address_no_port", "protect_path", "routing_mark", "reuse_addr", "netns",
                    "connect_timeout", "tcp_fast_open", "tcp_multi_path", "disable_tcp_keep_alive",
                    "tcp_keep_alive", "tcp_keep_alive_interval", "udp_fragment", "domain_resolver",
                    "network_strategy", "network_type", "fallback_network_type", "fallback_delay")
                fun native(value: Any): Any? = when (value) {
                    org.json.JSONObject.NULL -> null
                    is org.json.JSONObject -> value.keys().asSequence().associateWith { native(value.get(it)) }
                    is org.json.JSONArray -> (0 until value.length()).map { native(value.get(it)) }
                    else -> value
                }
                for (key in dialer.keys()) {
                    require(key in allowed) { "Unsupported WireGuard dialer field: $key" }
                    native(dialer.get(key))?.let { _hack_config_map[key] = it }
                }
            }
            require(bean.wgListenPort in 0..65535) { "Invalid WireGuard listen port" }
            require(bean.wgUdpNatMax >= 0 && bean.wgBuffers >= 0) { "Invalid WireGuard resource limits" }
            if (bean.system && bean.wgInterfaceName.isNotBlank()) _hack_config_map["name"] = bean.wgInterfaceName.trim()
            if (bean.wgListenPort > 0) _hack_config_map["listen_port"] = bean.wgListenPort
            val natModes = setOf("endpoint_independent", "address_dependent", "address_and_port_dependent")
            listOf("udp_mapping" to bean.wgUdpMapping, "udp_filtering" to bean.wgUdpFiltering).forEach { (key, value) ->
                if (value.isNotBlank()) {
                    require(value in natModes) { "Invalid WireGuard NAT behavior" }
                    _hack_config_map[key] = value
                }
            }
            if (bean.wgUdpNatMax > 0) _hack_config_map["udp_nat_max"] = bean.wgUdpNatMax
            if (bean.wgBuffers > 0) _hack_config_map["preallocated_buffers_per_pool"] = bean.wgBuffers
            if (bean.wgDisablePauses) _hack_config_map["disable_pauses"] = true
            peers.first().allowed_ips = bean.wgAllowedIps.listByLineOrComma().filter { it.isNotBlank() }.map { it.asPrefix() }.also {
                require(it.isNotEmpty()) { "WireGuard Allowed IPs required" }
            }
            if (bean.wgExtraPeers.isNotBlank()) {
                val extra = org.json.JSONArray(bean.wgExtraPeers)
                peers = peers + (0 until extra.length()).map { index ->
                    val peer = extra.getJSONObject(index)
                    SingBoxOptions.Endpoint_WireGuardPeer().apply {
                        address = peer.optString("address")
                        port = peer.optInt("port", 0).also { require(it in 0..65535) }
                        public_key = peer.getString("public_key").also { require(it.isNotBlank()) }
                        pre_shared_key = peer.optString("pre_shared_key")
                        val ips = peer.getJSONArray("allowed_ips")
                        allowed_ips = (0 until ips.length()).map { ips.getString(it).asPrefix() }.also { require(it.isNotEmpty()) }
                        persistent_keepalive_interval = peer.optInt("persistent_keepalive_interval", 0).also { require(it in 0..65535) }
                    }
                }
            }
        }
        if (bean.isAmnezia) {
            amnezia = SingBoxOptions.Endpoint_WireGuardAmnezia().apply {
                if (bean.awgJc > 0) jc = bean.awgJc
                if (bean.awgJmin > 0) jmin = bean.awgJmin
                if (bean.awgJmax > 0) jmax = bean.awgJmax
                if (bean.awgS1 > 0) s1 = bean.awgS1
                if (bean.awgS2 > 0) s2 = bean.awgS2
                if (bean.awgS3 > 0) s3 = bean.awgS3
                if (bean.awgS4 > 0) s4 = bean.awgS4
                bean.awgH1.trim().takeIf { it.isNotEmpty() }?.let { h1 = it }
                bean.awgH2.trim().takeIf { it.isNotEmpty() }?.let { h2 = it }
                bean.awgH3.trim().takeIf { it.isNotEmpty() }?.let { h3 = it }
                bean.awgH4.trim().takeIf { it.isNotEmpty() }?.let { h4 = it }
                bean.awgI1.trim().takeIf { it.isNotEmpty() }?.let { i1 = it }
                bean.awgI2.trim().takeIf { it.isNotEmpty() }?.let { i2 = it }
                bean.awgI3.trim().takeIf { it.isNotEmpty() }?.let { i3 = it }
                bean.awgI4.trim().takeIf { it.isNotEmpty() }?.let { i4 = it }
                bean.awgI5.trim().takeIf { it.isNotEmpty() }?.let { i5 = it }
                bean.awgHpk.trim().takeIf { it.isNotEmpty() }?.let { header_protection_key = it }
                bean.awgCpa.trim().takeIf { it.isNotEmpty() }?.let { content_padding_addition = it }
                bean.awgRekeyAfter.trim().takeIf { it.isNotEmpty() }?.let { rekey_after_time = it }
                bean.awgRekeyTimeout.trim().takeIf { it.isNotEmpty() }?.let { rekey_timeout = it }
                bean.awgRejectAfter.trim().takeIf { it.isNotEmpty() }?.let { reject_after_time = it }
                bean.awgKeepaliveTimeout.trim().takeIf { it.isNotEmpty() }?.let { keepalive_timeout = it }
                bean.awgMaxHandshake.trim().takeIf { it.isNotEmpty() }?.let { max_handshake_attempts = it }
                if (bean.awgRandomTrailers) random_trailers = true
                if (bean.awgDisableCookies) disable_cookies = true
            }
        }
    }
}
