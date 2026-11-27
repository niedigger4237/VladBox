package io.nekohasekai.sagernet.fmt.trojan_go

import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions

/** Only the standard Trojan wire protocol can use sing-box's Trojan outbound.
 * The extra Shadowsocks layer and reserved plugin options retain the legacy engine. */
internal fun canUseNativeTrojan(transport: String?, encryption: String?, plugin: String?): Boolean =
    transport in listOf("original", "ws") && encryption == "none" && plugin.isNullOrBlank()

internal fun TrojanGoBean.canUseNativeTrojan(): Boolean =
    canUseNativeTrojan(type, encryption, plugin)

internal fun buildNativeTrojanGoOutbound(bean: TrojanGoBean): SingBoxOptions.Outbound =
    SingBoxOptions.Outbound().apply {
        require(bean.canUseNativeTrojan()) { "Trojan-Go profile requires the external engine" }
        require(bean.serverAddress.isNotBlank() && bean.serverPort in 1..65535) { "Invalid Trojan-Go server" }
        require(bean.password.isNotEmpty()) { "Missing Trojan-Go password" }
        type = "trojan"
        _hack_config_map["server"] = bean.serverAddress
        _hack_config_map["server_port"] = bean.serverPort
        _hack_config_map["password"] = bean.password
        val tls = linkedMapOf<String, Any>("enabled" to true)
        if (bean.sni.isNotBlank()) tls["server_name"] = bean.sni
        if (bean.allowInsecure) tls["insecure"] = true
        _hack_config_map["tls"] = tls
        if (bean.type == "ws") {
            require(bean.path.startsWith("/")) { "Invalid Trojan-Go WebSocket path" }
            val host = bean.host.ifBlank { bean.serverAddress.wrapIPV6Host() }
            _hack_config_map["transport"] = mapOf(
                "type" to "ws",
                "path" to bean.path,
                "headers" to mapOf("Host" to host, "Origin" to "https://$host")
            )
        }
    }
