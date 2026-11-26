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

    private val baseKeys = setOf(
        "headers", "x_padding_bytes", "no_grpc_header", "no_sse_header",
        "sc_max_each_post_bytes", "sc_min_posts_interval_ms", "sc_max_buffered_posts",
        "sc_stream_up_server_secs", "server_max_header_bytes", "xmux", "x_padding_obfs_mode",
        "x_padding_key", "x_padding_header", "x_padding_placement", "x_padding_method",
        "uplink_http_method", "session_placement", "session_key", "seq_placement", "seq_key",
        "uplink_data_placement", "uplink_data_key", "uplink_chunk_size", "session_id_table",
        "session_id_length", "domain_strategy", "trusted_x_forwarded_for", "congestion_controller", "cwnd"
    )
    private val xmuxKeys = setOf("max_concurrency", "max_connections", "c_max_reuse_times",
        "h_max_request_times", "h_max_reusable_secs", "h_keep_alive_period")
    private val rangeKeys = setOf("x_padding_bytes", "sc_max_each_post_bytes", "sc_min_posts_interval_ms",
        "sc_stream_up_server_secs", "uplink_chunk_size", "session_id_length")
    private val xmuxRangeKeys = xmuxKeys - "h_keep_alive_period"

    fun resolveMode(mode: String, reality: Boolean, separateDownload: Boolean): String =
        when (mode.ifBlank { "auto" }) {
            "auto" -> if (!reality) "packet-up" else if (separateDownload) "stream-up" else "stream-one"
            "packet-up", "stream-up", "stream-one" -> mode
            else -> throw IllegalArgumentException("Invalid XHTTP mode")
        }

    private fun range(value: Any?, name: String): Pair<Long, Long> {
        fun number(v: Any?): Long = v?.toString()?.toLongOrNull()
            ?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }
            ?: throw IllegalArgumentException("Invalid XHTTP range: $name")
        val result = when (value) {
            is Map<*, *> -> {
                require(value.keys == setOf("from", "to")) { "Invalid XHTTP range: $name" }
                number(value["from"]) to number(value["to"])
            }
            else -> {
                val parts = value.toString().split('-')
                require(parts.size in 1..2) { "Invalid XHTTP range: $name" }
                number(parts.first()) to number(parts.last())
            }
        }
        require(result.first <= result.second) { "Reversed XHTTP range: $name" }
        return result
    }

    private fun normalizeRange(value: Any?, name: String): String {
        val (from, to) = range(value, name)
        return if (from == to) from.toString() else "$from-$to"
    }

    private fun normalizeBase(out: MutableMap<String, Any>, mode: String) {
        for (key in rangeKeys) if (out.containsKey(key)) out[key] = normalizeRange(out[key], key)
        // Xray treats a zero/unset padding range as the default; the pinned core rejects zero.
        if (out["x_padding_bytes"] == null || out["x_padding_bytes"] == "0") out["x_padding_bytes"] = "100-1000"
        require(range(out["x_padding_bytes"], "x_padding_bytes").first > 0) { "XHTTP padding must be positive" }
        val xmux = out["xmux"]
        if (xmux != null) {
            require(xmux is Map<*, *>) { "Invalid XMUX object" }
            val normalized = linkedMapOf<String, Any>()
            for ((k, v) in xmux) {
                require(k is String && k in xmuxKeys && v != null) { "Unsupported XMUX parameter: $k" }
                normalized[k] = if (k in xmuxRangeKeys) normalizeRange(v, k) else
                    (v.toString().toLongOrNull()?.takeIf { it >= -1 } ?: throw IllegalArgumentException("Invalid XMUX keep alive period"))
            }
            fun positive(key: String) = normalized[key]?.let { range(it, key).second > 0 } ?: false
            require(!positive("max_connections") || !positive("max_concurrency")) { "XMUX max_connections conflicts with max_concurrency" }
            out["xmux"] = normalized
        }
        require(mode == "packet-up" || !out["uplink_http_method"].toString().equals("GET", true)) { "XHTTP GET requires packet-up mode" }
        require(mode == "packet-up" || out["uplink_data_placement"] !in listOf("cookie", "header")) { "XHTTP cookie/header uplink requires packet-up mode" }
        val headers = out["headers"]
        if (headers != null) {
            require(headers is Map<*, *> && headers.all { (k, v) -> k is String && v is String && !k.equals("host", true) }) { "XHTTP headers must be strings and Host must be configured separately" }
        }
    }

    fun snake(key: String): String {
        special[key]?.let { return it }
        return key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }

    private fun convert(v: Any?, keepKeys: Boolean): Any? = when (v) {
        is JSONObject -> {
            val m = LinkedHashMap<String, Any?>()
            for (k in v.keys()) {
                if (!keepKeys && k == "downloadSettings") continue
                val key = if (keepKeys) k else snake(k)
                val converted = convert(v.get(k), keepKeys || key == "headers")
                require(!m.containsKey(key) || m[key] == converted) { "Conflicting XHTTP aliases: $key" }
                m[key] = converted
            }
            m
        }

        is JSONArray -> (0 until v.length()).map { convert(v.get(it), keepKeys) }
        JSONObject.NULL -> null
        else -> v
    }

    private fun convertDownload(source: JSONObject): Map<String, Any> {
        val network = source.optString("network", source.optString("method", "xhttp"))
        require(network in setOf("xhttp", "splithttp")) { "XHTTP download requires XHTTP transport" }
        val allowed = setOf("address", "port", "network", "method", "security", "xhttpSettings", "splithttpSettings", "tlsSettings", "realitySettings")
        require(source.keys().asSequence().all { it in allowed }) { "Unsupported Xray download stream option; manual conversion required" }
        val settings = source.optJSONObject("xhttpSettings") ?: source.optJSONObject("splithttpSettings") ?: JSONObject()
        val result = LinkedHashMap<String, Any>()
        val extra = settings.optJSONObject("extra")
        // Xray extra overrides transport options, but outer host/path remain authoritative.
        val direct = JSONObject((extra ?: settings).toString())
        if (extra != null) {
            direct.remove("host")
            direct.remove("path")
            for (key in listOf("host", "path")) if (settings.has(key)) direct.put(key, settings.get(key))
        }
        direct.remove("extra")
        direct.remove("mode")
        require(!direct.has("downloadSettings") && !direct.has("download")) { "Nested XHTTP download is unsupported" }
        @Suppress("UNCHECKED_CAST")
        val converted = convert(direct, false) as Map<String, Any?>
        converted.forEach { (k, v) -> if (v != null) result[k] = v }
        require(result.keys.all { it in baseKeys || it in setOf("host", "path") }) { "Unsupported XHTTP download transport parameter" }
        source.optString("address").takeIf { it.isNotBlank() }?.let { result["server"] = it }
        if (source.has("port")) {
            val port = source.getInt("port")
            require(port in 1..65535) { "Invalid XHTTP download port" }
            result["server_port"] = port
        }
        val security = source.optString("security", "none")
        require(security in setOf("none", "tls", "reality")) { "Unsupported XHTTP download security" }
        if (security != "none") {
            val tls = linkedMapOf<String, Any>("enabled" to true)
            val opts = source.optJSONObject(if (security == "reality") "realitySettings" else "tlsSettings") ?: JSONObject()
            val supported = if (security == "reality") setOf("serverName", "fingerprint", "publicKey", "password", "shortId", "mldsa65Verify", "pqv",
                "supportX25519MLKEM768", "support-x25519mlkem768", "support_x25519mlkem768", "spiderX", "show")
                else setOf("serverName", "fingerprint", "allowInsecure", "alpn", "minVersion", "maxVersion", "disableSNI")
            require(opts.keys().asSequence().all { it in supported }) { "Unsupported XHTTP download TLS parameter; manual conversion required" }
            opts.optString("serverName").takeIf { it.isNotBlank() }?.let { tls["server_name"] = it }
            opts.optString("fingerprint").takeIf { it.isNotBlank() && it != "none" }?.let { tls["utls"] = mapOf("enabled" to true, "fingerprint" to it) }
            if (security == "tls") {
                if (opts.has("allowInsecure")) tls["insecure"] = opts.getBoolean("allowInsecure")
                if (opts.has("disableSNI")) tls["disable_sni"] = opts.getBoolean("disableSNI")
                for ((from, to) in listOf("minVersion" to "min_version", "maxVersion" to "max_version"))
                    opts.optString(from).takeIf { it.isNotBlank() }?.let { tls[to] = it }
                opts.optJSONArray("alpn")?.let { arr -> tls["alpn"] = (0 until arr.length()).map { arr.getString(it) } }
            } else {
                val key = opts.optString("publicKey", opts.optString("password"))
                require(key.isNotBlank()) { "Missing XHTTP download Reality public key" }
                val mode = parseRealityHybridMode(
                    listOf("supportX25519MLKEM768", "support-x25519mlkem768", "support_x25519mlkem768")
                        .firstNotNullOfOrNull { name -> if (opts.has(name)) opts.opt(name)?.toString() else null }
                )
                if (!tls.containsKey("utls")) tls["utls"] = mapOf("enabled" to true, "fingerprint" to "chrome")
                tls["reality"] = linkedMapOf<String, Any>(
                    "enabled" to true, "public_key" to key, "short_id" to opts.optString("shortId"),
                    "support_x25519mlkem768" to (mode != "disabled")
                ).apply {
                    if (mode == "enabled") put("require_x25519mlkem768", true)
                    validateRealityMldsa65(opts.optString("mldsa65Verify", opts.optString("pqv"))).takeIf { it.isNotEmpty() }?.let { put("mldsa65_verify", it) }
                }
            }
            result["tls"] = tls
        }
        return result
    }

    /** Export shared Xray-compatible spelling, retaining sing-box-only extensions.
     * Native-only download detour/TLS settings cannot be mapped losslessly. */
    fun toShareExtra(extra: String?, mode: String, reality: Boolean): String {
        if (extra.isNullOrBlank()) return ""
        val original = JSONObject(extra)
        // Preserve untouched Xray-style download objects including diagnostic fields.
        if (original.has("downloadSettings")) return extra
        val normalized = toSingBox(extra, mode, reality)
        fun camel(key: String): String = special.entries.firstOrNull { it.value == key }?.key
            ?: key.split('_').let { parts -> parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } } }
        val nativeOnly = setOf("domain_strategy", "trusted_x_forwarded_for", "congestion_controller", "cwnd")
        fun transport(values: Map<*, *>): JSONObject = JSONObject().apply {
            for ((k, v) in values) {
                if (k !is String || k == "download") continue
                if (k == "xmux" && v is Map<*, *>) {
                    put("xmux", JSONObject().apply { for ((key, value) in v) if (key is String) put(camel(key), value) })
                } else put(if (k in nativeOnly) k else camel(k), JSONObject.wrap(v))
            }
        }
        val out = transport(normalized)
        val d = normalized["download"] as? Map<*, *> ?: return out.toString()
        val tls = d["tls"] as? Map<*, *>
        val utls = tls?.get("utls") as? Map<*, *>
        val pq = tls?.get("reality") as? Map<*, *>
        val supportedTls = setOf("enabled", "server_name", "insecure", "alpn", "min_version", "max_version", "disable_sni", "utls", "reality")
        val supportedReality = setOf("enabled", "public_key", "short_id", "support_x25519mlkem768", "require_x25519mlkem768", "mldsa65_verify")
        if (d["detour"] != null || tls?.keys?.any { it !in supportedTls } == true ||
            utls?.keys?.any { it !in setOf("enabled", "fingerprint") } == true ||
            pq?.keys?.any { it !in supportedReality } == true) {
            out.put("download", JSONObject(d))
            return out.toString()
        }
        val stream = JSONObject().put("address", d["server"]).put("port", d["server_port"]).put("network", "xhttp")
        val security = if (tls?.get("enabled") != true) "none" else if (pq?.get("enabled") == true) "reality" else "tls"
        stream.put("security", security)
        val settings = transport(d.filterKeys { it !in setOf("server", "server_port", "tls", "detour") })
        stream.put("xhttpSettings", settings)
        if (security != "none" && tls != null) {
            val opts = JSONObject()
            tls["server_name"]?.let { opts.put("serverName", it) }
            if (utls?.get("enabled") == true) utls["fingerprint"]?.let { opts.put("fingerprint", it) }
            if (security == "reality" && pq != null) {
                opts.put("publicKey", pq["public_key"]).put("shortId", pq["short_id"])
                pq["mldsa65_verify"]?.let { opts.put("mldsa65Verify", it) }
                if (pq["require_x25519mlkem768"] == true) opts.put("supportX25519MLKEM768", true)
                else if (pq["support_x25519mlkem768"] == false) opts.put("supportX25519MLKEM768", false)
                stream.put("realitySettings", opts)
            } else {
                for ((key, name) in listOf("insecure" to "allowInsecure", "alpn" to "alpn", "min_version" to "minVersion", "max_version" to "maxVersion", "disable_sni" to "disableSNI"))
                    tls[key]?.let { opts.put(name, JSONObject.wrap(it)) }
                stream.put("tlsSettings", opts)
            }
        }
        out.put("downloadSettings", stream)
        return out.toString()
    }

    fun toSingBox(extra: String?, mode: String = "auto", reality: Boolean = false): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        if (!extra.isNullOrBlank()) {
            val source = try { JSONObject(extra) } catch (e: Exception) { throw IllegalArgumentException("Invalid XHTTP extra JSON", e) }
            @Suppress("UNCHECKED_CAST")
            (convert(source, false) as Map<String, Any?>).forEach { (k, v) -> if (v != null) out[k] = v }
            if (source.has("downloadSettings") && !source.isNull("downloadSettings")) {
                require(!out.containsKey("download")) { "Duplicate XHTTP download configuration" }
                out["download"] = convertDownload(source.getJSONObject("downloadSettings"))
            }
        }
        for (key in listOf("mode", "type", "host", "path")) out.remove(key)
        require(out.keys.all { it in baseKeys || it == "download" }) {
            "Unsupported XHTTP extra parameter: " + out.keys.filter { it !in baseKeys && it != "download" }.joinToString()
        }
        val effective = resolveMode(mode, reality, out.containsKey("download"))
        require(effective != "stream-one" || !out.containsKey("download")) { "XHTTP download is unavailable in stream-one mode" }
        normalizeBase(out, effective)
        if (out.containsKey("download")) {
            val raw = out["download"]
            require(raw is Map<*, *>) { "Invalid XHTTP download object" }
            val download = linkedMapOf<String, Any>()
            for ((key, value) in raw) {
                require(key is String && value != null && (key in baseKeys || key in setOf("server", "server_port", "host", "path", "tls", "detour"))) { "Unsupported XHTTP download parameter: $key" }
                download[key] = value
            }
            require((download["server"] as? String)?.isNotBlank() == true) { "XHTTP download server is required" }
            val port = download["server_port"]?.toString()?.toIntOrNull()
            require(port != null && port in 1..65535) { "XHTTP download port is required" }
            download["server_port"] = port
            normalizeBase(download, effective)
            out["download"] = download
        }
        return out
    }
}
