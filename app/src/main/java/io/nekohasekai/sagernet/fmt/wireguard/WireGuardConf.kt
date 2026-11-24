package io.nekohasekai.sagernet.fmt.wireguard

import org.json.JSONArray
import org.json.JSONObject

/** Standard WireGuard INI format, deliberately separate from AmneziaWG. */
object WireGuardConf {
    fun parse(text: String): WireGuardBean {
        val iface = linkedMapOf<String, String>()
        val peers = mutableListOf<MutableMap<String, String>>()
        var section: MutableMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim().removePrefix("\uFEFF")
            if (line.isBlank()) continue
            when (line.lowercase()) {
                "[interface]" -> { section = iface; continue }
                "[peer]" -> { section = linkedMapOf(); peers.add(section); continue }
            }
            require('=' in line && section != null) { "Invalid WireGuard configuration" }
            val key = line.substringBefore('=').trim().lowercase()
            val value = line.substringAfter('=').trim()
            val target = requireNotNull(section)
            val allowed = if (target === iface) setOf("privatekey","address","mtu","listenport")
                else setOf("publickey","presharedkey","endpoint","allowedips","persistentkeepalive")
            require(key in allowed) { "Unsupported WireGuard option: $key" }
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
        return WireGuardBean().apply {
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
        }
    }

    fun export(bean: WireGuardBean): String {
        require(!bean.isAmnezia) { "AmneziaWG is not supported by this exporter" }
        fun clean(value: String): String = value.also { require('\n' !in it && '\r' !in it) }
        fun list(value: String) = value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${clean(bean.privateKey)}")
            appendLine("Address = ${list(bean.localAddress)}")
            appendLine("MTU = ${bean.mtu}")
            if (bean.wgListenPort > 0) appendLine("ListenPort = ${bean.wgListenPort}")
            fun addPeer(host: String, port: Int, key: String, psk: String, ips: String, keepalive: Int) {
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = ${clean(key)}")
                if (psk.isNotBlank()) appendLine("PresharedKey = ${clean(psk)}")
                if (host.isNotBlank() && port > 0) appendLine("Endpoint = ${clean(if (':' in host && !host.startsWith("[")) "[$host]" else host)}:$port")
                appendLine("AllowedIPs = ${list(ips)}")
                if (keepalive > 0) appendLine("PersistentKeepalive = $keepalive")
            }
            addPeer(bean.serverAddress,bean.serverPort,bean.peerPublicKey,bean.peerPreSharedKey,bean.wgAllowedIps,bean.persistentKeepalive)
            val extra = JSONArray(bean.wgExtraPeers.ifBlank { "[]" })
            for (i in 0 until extra.length()) {
                val p = extra.getJSONObject(i)
                val ips = p.getJSONArray("allowed_ips")
                addPeer(p.optString("address"),p.optInt("port"),p.getString("public_key"),p.optString("pre_shared_key"),
                    (0 until ips.length()).joinToString(", ") { ips.getString(it) },p.optInt("persistent_keepalive_interval"))
            }
        }
    }
}
