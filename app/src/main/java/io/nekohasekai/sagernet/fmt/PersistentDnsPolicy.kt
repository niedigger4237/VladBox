package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import java.io.File
import java.security.MessageDigest

/** Hash effective DNS and static egress configuration, never write credentials to metadata. */
internal object PersistentDnsPolicy {
    fun directory(): File = File(SagerNet.application.noBackupFilesDir, "nf-dns-cache-v1")

    private val staticTypes = setOf("direct", "socks", "http", "vless", "vmess", "trojan",
        "shadowsocks", "shadowtls", "hysteria", "hysteria2", "tuic", "wireguard", "ssh", "anytls")

    private fun canonical(value: JsonElement): JsonElement = when {
        value.isJsonObject -> JsonObject().apply {
            value.asJsonObject.entrySet().sortedBy { it.key }.forEach { add(it.key, canonical(it.value)) }
        }
        value.isJsonArray -> JsonArray().apply { value.asJsonArray.forEach { add(canonical(it)) } }
        else -> value
    }

    fun scope(config: String, proxyId: Long, routeProfile: String, generation: String): String? {
        val root = JsonParser.parseString(config).asJsonObject
        val experimental = root.get("experimental")
        if (experimental?.isJsonObject == true && experimental.asJsonObject.has("cache_file")) return null
        val dns = root.get("dns")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (listOf("disable_cache", "disable_expire").any { dns.get(it)?.let { v -> v.isJsonPrimitive && v.asString == "true" } == true }) return null
        for (key in listOf("outbounds", "endpoints")) {
            val entries = root.get(key) ?: continue
            if (!entries.isJsonArray || entries.asJsonArray.any {
                !it.isJsonObject || it.asJsonObject.get("type")?.asString !in staticTypes
            }) return null
        }
        val context = JsonObject().apply {
            addProperty("schema", "nf-dns-cache-v1")
            addProperty("proxy", proxyId)
            addProperty("routingProfile", routeProfile)
            addProperty("clearGeneration", generation)
            for (key in listOf("dns", "outbounds", "endpoints", "route", "services", "network_namespace", "certificate")) {
                root.get(key)?.let { add(key, canonical(it)) }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical(context).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
