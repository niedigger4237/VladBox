package io.nekohasekai.sagernet.fmt.trojan_go

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.Protocols
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

fun parseTrojanGo(server: String): TrojanGoBean {
    val link = server.replace("trojan-go://", "https://").toHttpUrlOrNull() ?: error("Invalid Trojan-Go link")
    return TrojanGoBean().apply {
        serverAddress = link.host
        serverPort = link.port
        password = link.username
        link.queryParameter("sni")?.let {
            sni = it
        }
        link.queryParameter("type")?.let { lType ->
            type = lType

            when (type) {
                "ws" -> {
                    link.queryParameter("host")?.let {
                        host = it
                    }
                    link.queryParameter("path")?.let {
                        path = it
                    }
                }
                else -> {
                }
            }
        }
        link.queryParameter("encryption")?.let {
            encryption = it
        }
        link.queryParameter("plugin")?.let {
            plugin = it
        }
        link.queryParameter("allowInsecure")?.let { allowInsecure = it == "1" || it.equals("true", true) }
        link.fragment.takeIf { !it.isNullOrBlank() }?.let {
            name = it
        }
        initializeDefaultValues()
    }
}

fun TrojanGoBean.toUri(): String {
    val builder = linkBuilder().username(password).host(serverAddress).port(serverPort)
    if (sni.isNotBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (type.isNotBlank() && type != "original") {
        builder.addQueryParameter("type", type)

        when (type) {
            "ws" -> {
                if (host.isNotBlank()) {
                    builder.addQueryParameter("host", host)
                }
                if (path.isNotBlank()) {
                    builder.addQueryParameter("path", path)
                }
            }
        }
    }
    if (encryption.isNotBlank() && encryption != "none") {
        builder.addQueryParameter("encryption", encryption)
    }
    if (allowInsecure) builder.addQueryParameter("allowInsecure", "1")
    if (plugin.isNotBlank()) {
        builder.addQueryParameter("plugin", plugin)
    }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink("trojan-go")
}

fun TrojanGoBean.buildTrojanGoConfig(port: Int): String {
    return JSONObject().apply {
        put("run_type", "client")
        put("local_addr", LOCALHOST)
        put("local_port", port)
        put("remote_addr", finalAddress)
        put("remote_port", finalPort)
        put("password", JSONArray().apply {
            put(password)
        })
        put("log_level", if (DataStore.logLevel > 0) 0 else 2)
//        if (Protocols.shouldEnableMux("trojan-go")) put("mux", JSONObject().apply {
//            put("enabled", true)
//            put("concurrency", DataStore.muxConcurrency)
//        })
        put("tcp", JSONObject().apply {
            put("prefer_ipv4", DataStore.ipv6Mode <= IPv6Mode.ENABLE)
        })

        when (type) {
            "original" -> {
            }
            "ws" -> put("websocket", JSONObject().apply {
                put("enabled", true)
                put("host", host)
                put("path", path)
            })
        }

        if (sni.isBlank() && finalAddress == LOCALHOST && !serverAddress.isIpAddress()) {
            sni = serverAddress
        }

        put("ssl", JSONObject().apply {
            if (sni.isNotBlank()) put("sni", sni)
            if (allowInsecure) put("verify", false)
        })

        when {
            encryption == "none" -> {
            }
            encryption.startsWith("ss;") -> put("shadowsocks", JSONObject().apply {
                put("enabled", true)
                put("method", encryption.substringAfter(";").substringBefore(":"))
                put("password", encryption.substringAfter(":"))
            })
        }
    }.toStringPretty()
}

fun JSONObject.parseTrojanGo(): TrojanGoBean {
    val source = this
    return TrojanGoBean().applyDefaultValues().apply {
        serverAddress = source.getString("remote_addr")
        serverPort = source.optInt("remote_port", 443)
        require(serverAddress.isNotBlank() && serverPort in 1..65535) { "Invalid Trojan-Go server" }
        password = when (val pass = source.get("password")) {
            is String -> pass
            is JSONArray -> pass.getString(0)
            else -> error("Invalid Trojan-Go password")
        }
        require(password.isNotEmpty()) { "Missing Trojan-Go password" }
        source.optJSONObject("ssl")?.let { ssl ->
            sni = ssl.optString("sni", sni)
            allowInsecure = !ssl.optBoolean("verify", true)
        }
        source.optJSONObject("websocket")?.let { ws ->
            if (ws.optBoolean("enabled", false)) {
                type = "ws"
                host = ws.optString("host", host)
                path = ws.optString("path", path)
            }
        }
        source.optJSONObject("shadowsocks")?.let { ss ->
            if (ss.optBoolean("enabled", false)) {
                val method = ss.getString("method")
                val secret = ss.getString("password")
                require(method in listOf("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305") && secret.isNotEmpty()) {
                    "Unsupported Trojan-Go Shadowsocks encryption"
                }
                encryption = "ss;$method:$secret"
            }
        }
        require(source.optJSONObject("mux")?.optBoolean("enabled", false) != true) {
            "Trojan-Go JSON with enabled mux cannot be imported without losing mux settings"
        }
        initializeDefaultValues()
    }
}
