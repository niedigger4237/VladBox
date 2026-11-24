package io.nekohasekai.sagernet.fmt.v2ray

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the Xray-style XHTTP `extra` object (camelCase, as found in share links)
 * into the snake_case keys understood by sing-box-extended's `xhttp` transport.
 * Snake_case input is passed through untouched.
 */
object XhttpExtra {

    private val special = mapOf(
        "noGRPCHeader" to "no_grpc_header",
        "noSSEHeader" to "no_sse_header",
        "uplinkHTTPMethod" to "uplink_http_method",
        "sessionIDPlacement" to "session_placement",
        "sessionIDKey" to "session_key",
        "sessionIDTable" to "session_id_table",
        "sessionIDLength" to "session_id_length",
    )

    // these keep their inner keys verbatim (HTTP header names, ...)
    private val verbatim = setOf("headers")

    // not representable 1:1 (different structure in sing-box), ignored
    private val unsupported = setOf("downloadSettings")

    fun snake(key: String): String {
        special[key]?.let { return it }
        return key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }

    private fun convert(v: Any?, keepKeys: Boolean): Any? = when (v) {
        is JSONObject -> {
            val m = LinkedHashMap<String, Any?>()
            for (k in v.keys()) {
                if (!keepKeys && k in unsupported) continue
                m[if (keepKeys) k else snake(k)] = convert(v.get(k), !keepKeys && k in verbatim)
            }
            m
        }

        is JSONArray -> (0 until v.length()).map { convert(v.get(it), keepKeys) }
        JSONObject.NULL -> null
        else -> v
    }

    fun toSingBox(extra: String?, mode: String = "auto"): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        if (!extra.isNullOrBlank()) {
            try {
                @Suppress("UNCHECKED_CAST")
                (convert(JSONObject(extra), false) as Map<String, Any?>).forEach { (k, v) ->
                    if (v != null) out[k] = v
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid XHTTP extra JSON", e)
            }
        }
        // sing-box-extended refuses an xhttp transport without x_padding_bytes
        if (!out.containsKey("x_padding_bytes")) out["x_padding_bytes"] = "100-1000"
        out.remove("mode") // set explicitly from the bean
        out.remove("type") // transport type is fixed by the generator
        out.remove("host") // host is configured separately in the profile
        out.remove("path") // path is configured separately in the profile
        require(mode in setOf("auto", "packet-up", "stream-up", "stream-one")) { "Invalid XHTTP mode" }
        require(mode != "stream-one" || !out.containsKey("download")) { "XHTTP download is unavailable in stream-one mode" }
        require(mode == "packet-up" || !out["uplink_http_method"].toString().equals("GET", true)) { "XHTTP GET requires packet-up mode" }
        require(mode == "packet-up" || out["uplink_data_placement"] !in listOf("cookie", "header")) { "XHTTP cookie/header uplink requires packet-up mode" }
        val headers = out["headers"] as? Map<*, *>
        require(headers?.keys?.none { it.toString().equals("host", true) } != false) { "Configure XHTTP Host separately" }
        val xmux = out["xmux"] as? Map<*, *>
        fun positive(value: Any?): Boolean = value?.toString()?.substringAfterLast('-')?.toLongOrNull()?.let { it > 0 } ?: false
        require(!positive(xmux?.get("max_connections")) || !positive(xmux?.get("max_concurrency"))) { "XMUX max_connections conflicts with max_concurrency" }
        return out
    }
}
