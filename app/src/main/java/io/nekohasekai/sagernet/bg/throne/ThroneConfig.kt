package io.nekohasekai.sagernet.bg.throne

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.ktx.Logs

/**
 * Throne's sing-box fork differs a little from sing-box-extended; this rewrites VladBox's generated config
 * for it. Used for the in-process engine and for the standalone root runner.
 */
object ThroneConfig {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private const val GEOSITE_URL = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-%s.srs"
    private const val GEOIP_URL = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-%s.srs"

    /**
     * @param tun true for the in-process VPN engine (the platform opens the tun)
     * @param cachePath file for the cache_file (remote rule-sets are kept there), absolute or relative to the core's dir
     */
    fun adapt(json: String, tun: Boolean, cachePath: String, uiDir: String? = null): String = try {
        val root = JsonParser.parseString(json).asJsonObject

        // endpoints: wireguard `amnezia` block is called `amnezia_wg` in Throne's fork
        root.getAsJsonArray("endpoints")?.forEach { e ->
            val o = e.asJsonObject
            if (o.get("type")?.asString == "wireguard" && o.has("amnezia")) {
                o.add("amnezia_wg", o.remove("amnezia"))
            }
        }

        // geoip:/geosite: local rule-sets are VladBox's own database format: use the .srs sets of sing-geo*
        root.getAsJsonObject("route")?.getAsJsonArray("rule_set")?.forEach { e ->
            val o = e.asJsonObject
            val path = o.get("path")?.asString ?: return@forEach
            val url = when {
                path.startsWith("geosite:") -> GEOSITE_URL.format(path.removePrefix("geosite:"))
                path.startsWith("geoip:") -> GEOIP_URL.format(path.removePrefix("geoip:"))
                else -> return@forEach
            }
            o.addProperty("type", "remote")
            o.remove("path")
            o.addProperty("format", "binary")
            o.addProperty("url", url)
            o.addProperty("download_detour", "direct")
            o.addProperty("update_interval", "7d")
        }

        // tun: the platform opens the device, sing-box needs auto_route to build its route ranges
        if (tun) root.getAsJsonArray("inbounds")?.forEach { e ->
            val o = e.asJsonObject
            if (o.get("type")?.asString == "tun") o.addProperty("auto_route", true)
        }

        val experimental = root.getAsJsonObject("experimental") ?: JsonObject().also { root.add("experimental", it) }
        if (!experimental.has("cache_file")) {
            experimental.add("cache_file", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("path", cachePath)
            })
        }
        experimental.getAsJsonObject("clash_api")?.let { api ->
            if (uiDir != null) api.addProperty("external_ui", uiDir) else api.remove("external_ui")
        }

        gson.toJson(root)
    } catch (e: Exception) {
        Logs.w("throne config adapt failed: $e")
        json
    }
}
