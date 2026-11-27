package io.nekohasekai.sagernet.fmt.naive

import io.nekohasekai.sagernet.ktx.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseNaive(link: String): NaiveBean {
    val proto = link.substringAfter("+").substringBefore(":")
    val url = ("https://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid NaiveProxy link")
    return NaiveBean().also {
        it.proto = proto
    }.apply {
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        sni = url.queryParameter("sni")
        certificates = url.queryParameter("cert")
        // HttpUrl.queryParameter already decodes percent escapes. A second
        // decode corrupts literal '+' and '%' in header values.
        extraHeaders = url.queryParameter("extra-headers")?.replace("\r\n", "\n")
        insecureConcurrency = url.queryParameter("insecure-concurrency")?.toIntOrNull()
        name = url.fragment
        initializeDefaultValues()
    }
}

fun NaiveBean.toUri(proxyOnly: Boolean = false): String {
    val builder = linkBuilder().host(if (proxyOnly) finalAddress else serverAddress).port(if (proxyOnly) finalPort else serverPort)
    if (username.isNotBlank()) {
        builder.username(username)
        if (password.isNotBlank()) {
            builder.password(password)
        }
    }
    if (!proxyOnly) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (certificates.isNotBlank()) {
            builder.addQueryParameter("cert", certificates)
        }
        if (extraHeaders.isNotBlank()) {
            builder.addQueryParameter("extra-headers", extraHeaders)
        }
        if (name.isNotBlank()) {
            builder.encodedFragment(name.urlSafe())
        }
        if (insecureConcurrency > 0) {
            builder.addQueryParameter("insecure-concurrency", "$insecureConcurrency")
        }
    }
    return builder.toLink(if (proxyOnly) proto else "naive+$proto", false)
}

/** Prepare native Cronet options without mutating the saved profile.
 * Selected only by builds explicitly compiled with native NaiveProxy. */
internal fun buildNativeNaiveOutbound(bean: NaiveBean): moe.matsuri.nb4a.SingBoxOptions.Outbound =
    moe.matsuri.nb4a.SingBoxOptions.Outbound().apply {
        require(bean.serverAddress.isNotBlank() && bean.serverPort in 1..65535) { "Invalid NaiveProxy server" }
        require(bean.proto in listOf("https", "quic")) { "Invalid NaiveProxy transport" }
        require(bean.insecureConcurrency >= 0) { "Invalid NaiveProxy concurrency" }
        type = "naive"
        _hack_config_map["server"] = bean.serverAddress
        _hack_config_map["server_port"] = bean.serverPort
        _hack_config_map["username"] = bean.username
        _hack_config_map["password"] = bean.password
        if (bean.proto == "quic") _hack_config_map["quic"] = true
        if (bean.insecureConcurrency > 0) _hack_config_map["insecure_concurrency"] = bean.insecureConcurrency
        _hack_config_map.putAll(NaiveMemoryPolicy.options(bean.proto, bean.memoryProfile))
        val headers = linkedMapOf<String, List<String>>()
        for (line in bean.extraHeaders.lineSequence().filter { it.isNotBlank() }) {
            val colon = line.indexOf(':')
            require(colon > 0) { "Invalid NaiveProxy HTTP header" }
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            require(name.matches(Regex("[!#$%&'*+.^_\u0060|~0-9A-Za-z-]+")) && value.none { it.isISOControl() && it != '\t' }) {
                "Invalid NaiveProxy HTTP header"
            }
            // Cronet's native outbound takes only the first value for a name.
            require(headers.keys.none { it.equals(name, ignoreCase = true) }) { "Duplicate NaiveProxy HTTP header" }
            headers[name] = listOf(value)
        }
        if (headers.isNotEmpty()) _hack_config_map["extra_headers"] = headers
        val tls = linkedMapOf<String, Any>("enabled" to true)
        if (bean.sni.isNotBlank()) tls["server_name"] = bean.sni
        if (bean.certificates.isNotBlank()) tls["certificate"] = bean.certificates.lines()
        _hack_config_map["tls"] = tls
        if (bean.sUoT) _hack_config_map["udp_over_tcp"] = mapOf("enabled" to true)
    }
