package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.blankAsNull
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun buildSingBoxOutboundAnyTLSBean(bean: AnyTLSBean): SingBoxOptions.Outbound_AnyTLSOptions {
    return SingBoxOptions.Outbound_AnyTLSOptions().apply {
        type = "anytls"
        require(!org.json.JSONObject(bean.outboundDialer.ifBlank { "{}" }).optBoolean("tcp_fast_open")) { "AnyTLS does not support TCP Fast Open" }
        io.nekohasekai.sagernet.fmt.v2ray.applyClientDialer(bean.outboundDialer, "", _hack_config_map)
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password
        bean.idleSessionCheckInterval.blankAsNull()?.let { _hack_config_map["idle_session_check_interval"] = it }
        bean.idleSessionTimeout.blankAsNull()?.let { _hack_config_map["idle_session_timeout"] = it }
        bean.minIdleSession.blankAsNull()?.let {
            val count = it.toIntOrNull() ?: error("Invalid minimum idle sessions")
            require(count >= 0) { "Minimum idle sessions must be non-negative" }
            _hack_config_map["min_idle_session"] = count
        }
        bean.clientMetadata.blankAsNull()?.let { _hack_config_map["client_metadata"] = it }

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            val extra = org.json.JSONObject(bean.tlsExtras.ifBlank { "{}" })
            val min = extra.optString("min_version")
            val max = extra.optString("max_version")
            val versions = listOf("1.0","1.1","1.2","1.3")
            require(min.isBlank() || min in versions)
            require(max.isBlank() || max in versions)
            require(min.isBlank() || max.isBlank() || versions.indexOf(min) <= versions.indexOf(max))
            for (key in listOf("min_version","max_version","handshake_timeout","certificate_path","client_certificate_path","client_key_path")) {
                extra.optString(key).takeIf { it.isNotBlank() }?.let { _hack_config_map[key] = it }
            }
            for (key in listOf("certificate_public_key_sha256","curve_preferences","cipher_suites")) {
                extra.optString(key).takeIf { it.isNotBlank() }?.let { _hack_config_map[key] = it.listByLineOrComma() }
            }
            for (key in listOf("client_certificate","client_key")) {
                extra.optString(key).takeIf { it.isNotBlank() }?.let { _hack_config_map[key] = it.lines() }
            }
            require(bean.certificates.isBlank() || extra.optString("certificate_path").isBlank())
            require(extra.optString("client_certificate").isBlank() || extra.optString("client_certificate_path").isBlank())
            require(extra.optString("client_key").isBlank() || extra.optString("client_key_path").isBlank())
            require(listOf("client_certificate","client_certificate_path").any { extra.optString(it).isNotBlank() } == listOf("client_key","client_key_path").any { extra.optString(it).isNotBlank() })
            server_name = bean.sni.blankAsNull()
            insecure = bean.allowInsecure || DataStore.globalAllowInsecure
            alpn = bean.alpn.blankAsNull()?.listByLineOrComma()
            bean.certificates.blankAsNull()?.let {
                certificate = it
            }
            bean.utlsFingerprint.blankAsNull()?.let {
                utls = SingBoxOptions.OutboundUTLSOptions().apply {
                    enabled = true
                    fingerprint = it
                }
            }
            val realityKey = extra.optString("reality_public_key")
            if (realityKey.isNotBlank()) {
                require(bean.sni.isNotBlank()) { "Reality requires SNI" }
                require(bean.echConfig.isBlank() && extra.optString("ech_config_path").isBlank()) { "Choose Reality or ECH" }
                reality = SingBoxOptions.OutboundRealityOptions().apply {
                    enabled = true
                    public_key = realityKey
                    short_id = extra.optString("reality_short_id")
                }
                if (bean.utlsFingerprint.isBlank()) utls = SingBoxOptions.OutboundUTLSOptions().apply { enabled = true; fingerprint = "chrome" }
            }
            if (extra.optBoolean("fragment")) { fragment = true; extra.optString("fragment_fallback_delay").takeIf { it.isNotBlank() }?.let { fragment_fallback_delay = it } }
            if (extra.optBoolean("record_fragment")) record_fragment = true
            bean.echConfig.blankAsNull()?.let {
                // In new version, some complex options will be deprecated, so we just do this.
                ech = SingBoxOptions.OutboundECHOptions().apply {
                    enabled = true
                    config = it.lines()
                }
            }
            val echPath = extra.optString("ech_config_path")
            val echQuery = extra.optString("ech_query_server_name")
            require(bean.echConfig.isBlank() || echPath.isBlank())
            if (echPath.isNotBlank() || echQuery.isNotBlank()) {
                require(realityKey.isBlank())
                ech = (ech ?: SingBoxOptions.OutboundECHOptions()).apply {
                    enabled = true
                    if (echPath.isNotBlank()) _hack_config_map["config_path"] = echPath
                    if (echQuery.isNotBlank()) _hack_config_map["query_server_name"] = echQuery
                }
            }
        }
    }
}

fun AnyTLSBean.toUri(): String {
    val builder = linkBuilder()
        .host(serverAddress)
        .port(serverPort)
        .username(password)
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (!sni.isNullOrBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (!utlsFingerprint.isNullOrBlank()) {
        builder.addQueryParameter("fp", utlsFingerprint)
    }
    if (!alpn.isNullOrBlank()) builder.addQueryParameter("alpn", alpn)
    val extra = org.json.JSONObject(tlsExtras.orEmpty().ifBlank { "{}" })
    for ((key, parameter) in listOf("reality_public_key" to "pbk", "reality_short_id" to "sid", "certificate_public_key_sha256" to "pinSHA256")) {
        extra.optString(key).takeIf { it.isNotBlank() }?.let { builder.addQueryParameter(parameter,it) }
    }
    return builder.toLink("anytls")
}

fun parseAnytls(url: String): AnyTLSBean {
    // https://github.com/anytls/anytls-go/blob/main/docs/uri_scheme.md
    val link = url.replace("anytls://", "https://").toHttpUrlOrNull() ?: error(
        "invalid anytls link $url"
    )
    return AnyTLSBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment
        password = link.username
        sni = link.queryParameter("sni") ?: ""
        alpn = link.queryParameter("alpn") ?: ""
        val extra = org.json.JSONObject()
        for ((key, parameter) in listOf("reality_public_key" to "pbk", "reality_short_id" to "sid", "certificate_public_key_sha256" to "pinSHA256")) {
            link.queryParameter(parameter)?.takeIf { it.isNotBlank() }?.let { extra.put(key,it) }
        }
        tlsExtras = extra.toString()
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it.equals("true", ignoreCase = true)
        }
        link.queryParameter("fp")?.let {
            utlsFingerprint = it
        }
    }
}
