package io.nekohasekai.sagernet.fmt.wireguard

import org.json.JSONArray
import org.json.JSONObject

/** WireGuard and AmneziaWG INI import/export. */
object WireGuardConf {
    data class ImportResult(val bean: WireGuardBean, val warnings: List<String>)
    private val awgInts = mapOf("jc" to "awgJc","jmin" to "awgJmin","jmax" to "awgJmax","s1" to "awgS1","s2" to "awgS2","s3" to "awgS3","s4" to "awgS4")
    private val awgStrings = mapOf("h1" to "awgH1","h2" to "awgH2","h3" to "awgH3","h4" to "awgH4","i1" to "awgI1","i2" to "awgI2","i3" to "awgI3","i4" to "awgI4","i5" to "awgI5","hpk" to "awgHpk","cpa" to "awgCpa","header_protection_key" to "awgHpk","content_padding_addition" to "awgCpa","rekey_after_time" to "awgRekeyAfter","rekey_timeout" to "awgRekeyTimeout","reject_after_time" to "awgRejectAfter","keepalive_timeout" to "awgKeepaliveTimeout","max_handshake_attempts" to "awgMaxHandshake")
    private val awgBools = mapOf("random_trailers" to "awgRandomTrailers","disable_cookies" to "awgDisableCookies")
    fun parse(text: String): WireGuardBean = parseWithWarnings(text).bean
    fun parseWithWarnings(text: String): ImportResult {
        val warnings = mutableListOf<String>()
        val iface = linkedMapOf<String, String>()
        val peers = mutableListOf<MutableMap<String, String>>()
        var section: MutableMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim().removePrefix("\uFEFF")
            if (line.isBlank() || line.startsWith(";")) continue
            when (line.lowercase()) {
                "[interface]" -> { section = iface; continue }
                "[peer]" -> { section = linkedMapOf(); peers.add(section); continue }
            }
            require('=' in line && section != null) { "Invalid WireGuard configuration" }
            val key = line.substringBefore('=').trim().lowercase()
            val value = line.substringAfter('=').trim()
            val target = requireNotNull(section)
            val allowed = if (target === iface) setOf("privatekey","address","mtu","listenport","reserved") + awgInts.keys + awgStrings.keys + awgBools.keys
                else setOf("publickey","presharedkey","endpoint","allowedips","persistentkeepalive")
            if (key !in allowed) {
                warnings.add(if (key == "dns") "DNS from file was not applied; global DNS is unchanged." else "Ignored unsupported option: $key")
                continue
            }
            target[key] = if (key in setOf("address","allowedips") && target.containsKey(key)) target[key] + ", " + value else value
        }
        require(peers.isNotEmpty()) { "At least one peer is required" }
        fun required(map: Map<String,String>, key: String) = map[key]?.takeIf { it.isNotBlank() } ?: error("Missing $key")
        fun number(map: Map<String,String>, key: String, default: Int, range: IntRange): Int =
            (map[key]?.toInt() ?: default).also { require(it in range) { "Invalid $key" } }
        fun peer(map: Map<String,String>): JSONObject {
            val endpoint = map["endpoint"].orEmpty()
            val host = if (endpoint.isBlank()) "" else endpoint.substringBeforeLast(':').removeSurrounding("[","]")
            val port = if (endpoint.isBlank()) 0 else endpoint.substringAfterLast(':').toInt().also { require(it in 1..65535 && host.isNotBlank()) }
            return JSONObject().put("address",host).put("port",port)
                .put("public_key",required(map,"publickey")).put("pre_shared_key",map["presharedkey"].orEmpty())
                .put("allowed_ips",JSONArray(required(map,"allowedips").split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                .put("persistent_keepalive_interval",number(map,"persistentkeepalive",0,0..65535))
        }
        val first = peer(peers.first())
        val bean = WireGuardBean().apply {
            initializeDefaultValues()
            privateKey = required(iface,"privatekey")
            localAddress = required(iface,"address")
            mtu = number(iface,"mtu",1280,1..65535)
            wgListenPort = number(iface,"listenport",0,0..65535)
            serverAddress = first.getString("address")
            serverPort = first.getInt("port")
            peerPublicKey = first.getString("public_key")
            peerPreSharedKey = first.getString("pre_shared_key")
            val ips = first.getJSONArray("allowed_ips")
            wgAllowedIps = (0 until ips.length()).joinToString(", ") { ips.getString(it) }
            persistentKeepalive = first.getInt("persistent_keepalive_interval")
            wgExtraPeers = JSONArray(peers.drop(1).map { peer(it) }).toString()
            reserved = iface["reserved"].orEmpty()
            if (reserved.isNotBlank()) warnings.add("Reserved is stored but unsupported by the current core.")
            for ((key, field) in awgInts) iface[key]?.let { WireGuardBean::class.java.getField(field).set(this,it.toInt().also { n -> require(n >= 0) }) }
            for ((key, field) in awgStrings) iface[key]?.let { WireGuardBean::class.java.getField(field).set(this,it) }
            for ((key, field) in awgBools) iface[key]?.let {
                val value = when (it.lowercase()) { "true","1" -> true; "false","0" -> false; else -> error("Invalid $key") }
                WireGuardBean::class.java.getField(field).set(this,value)
            }
            if (isAmnezia) {
                require(peers.size == 1) { "Multiple AmneziaWG peers are not supported by the current editor" }
                require(wgAllowedIps.split(',').map { it.trim() }.toSet() == setOf("0.0.0.0/0","::/0")) { "AmneziaWG custom AllowedIPs are not supported by the current generator" }
                if (wgListenPort > 0) warnings.add("AmneziaWG ListenPort is not applied by the current generator.")
            }
        }
        return ImportResult(bean,warnings.distinct())
    }

    fun export(bean: WireGuardBean): String {
        require(bean.privateKey.isNotBlank() && bean.localAddress.isNotBlank() && bean.peerPublicKey.isNotBlank()) { "Required WireGuard fields are missing" }
        fun clean(value: String): String = value.trim().also { require('\n' !in it && '\r' !in it) }
        fun list(value: String) = value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${clean(bean.privateKey)}")
            appendLine("Address = ${list(bean.localAddress)}")
            appendLine("MTU = ${bean.mtu}")
            if (!bean.isAmnezia && bean.wgListenPort > 0) appendLine("ListenPort = ${bean.wgListenPort}")
            if (bean.reserved.isNotBlank()) appendLine("Reserved = ${clean(bean.reserved)}")
            if (bean.isAmnezia) {
                for ((key, field) in awgInts) {
                    val value = WireGuardBean::class.java.getField(field).get(bean) as Int
                    // Match the endpoint generator: zero means the optional field is unset.
                    if (value > 0) appendLine("${key.replaceFirstChar { it.uppercaseChar() }} = $value")
                }
                for ((key, field) in awgStrings.entries.distinctBy { it.value }) {
                    val value = WireGuardBean::class.java.getField(field).get(bean) as String
                    if (value.isNotBlank()) appendLine("${key.replaceFirstChar { it.uppercaseChar() }} = ${clean(value)}")
                }
                for ((key, field) in awgBools) {
                    if (WireGuardBean::class.java.getField(field).get(bean) == true) appendLine("$key = true")
                }
            }
            fun addPeer(host: String, port: Int, key: String, psk: String, ips: String, keepalive: Int) {
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = ${clean(key)}")
                if (psk.isNotBlank()) appendLine("PresharedKey = ${clean(psk)}")
                if (host.isNotBlank() && port > 0) appendLine("Endpoint = ${clean(if (':' in host && !host.startsWith("[")) "[$host]" else host)}:$port")
                appendLine("AllowedIPs = ${list(ips)}")
                if (keepalive > 0) appendLine("PersistentKeepalive = $keepalive")
            }
            addPeer(bean.serverAddress,bean.serverPort,bean.peerPublicKey,bean.peerPreSharedKey,if (bean.isAmnezia) "0.0.0.0/0, ::/0" else bean.wgAllowedIps,bean.persistentKeepalive)
            val extra = JSONArray(if (bean.isAmnezia) "[]" else bean.wgExtraPeers.ifBlank { "[]" })
            for (i in 0 until extra.length()) {
                val p = extra.getJSONObject(i)
                val ips = p.getJSONArray("allowed_ips")
                addPeer(p.optString("address"),p.optInt("port"),p.getString("public_key"),p.optString("pre_shared_key"),
                    (0 until ips.length()).joinToString(", ") { ips.getString(it) },p.optInt("persistent_keepalive_interval"))
            }
        }
    }
}
