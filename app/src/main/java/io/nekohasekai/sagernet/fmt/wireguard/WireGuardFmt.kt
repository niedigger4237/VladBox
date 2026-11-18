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
        // AWG adds padding/junk to the outer packets: a 1420 MTU black-holes full-size TCP segments
        mtu = if (bean.isAmnezia) minOf(bean.mtu, 1280) else bean.mtu
        peers = listOf(SingBoxOptions.Endpoint_WireGuardPeer().apply {
            address = bean.serverAddress
            port = bean.serverPort
            public_key = bean.peerPublicKey
            if (bean.peerPreSharedKey.isNotBlank()) pre_shared_key = bean.peerPreSharedKey
            allowed_ips = listOf("0.0.0.0/0", "::/0")
            persistent_keepalive_interval = 25
        })
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
