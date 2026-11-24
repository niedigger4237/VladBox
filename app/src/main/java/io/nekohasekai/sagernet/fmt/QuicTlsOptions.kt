package io.nekohasekai.sagernet.fmt

import org.json.JSONObject

fun applyQuicTlsOptions(raw: String, options: MutableMap<String, Any>) {
    val json = JSONObject(raw.ifBlank { "{}" })
    val allowed = setOf("min_version", "max_version", "certificate_public_key_sha256", "certificate_path", "client_certificate", "client_certificate_path", "client_key", "client_key_path", "handshake_timeout", "curve_preferences")
    fun present(key: String) = json.has(key) && json.optString(key).isNotBlank()
    require(!(present("client_certificate") && present("client_certificate_path")))
    require(!(present("client_key") && present("client_key_path")))
    require((present("client_certificate") || present("client_certificate_path")) == (present("client_key") || present("client_key_path"))) { "Client certificate and key must be supplied together" }
    for (key in json.keys()) {
        require(key in allowed) { "Unsupported QUIC TLS option: $key" }
        val value = json.get(key)
        if (key == "min_version" || key == "max_version") require(value == "1.3") { "QUIC requires TLS 1.3" }
        if (value is org.json.JSONArray) options[key] = (0 until value.length()).map { value.getString(it) }
        else if (value is String && value.isNotBlank()) options[key] = value
        else require(value is String) { "Invalid TLS option: $key" }
    }
}
