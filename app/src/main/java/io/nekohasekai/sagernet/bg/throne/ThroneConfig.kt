package io.nekohasekai.sagernet.bg.throne

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Throne's sing-box fork differs a little from sing-box-extended; this rewrites VladBox's generated config
 * for it. Used for the in-process engine and for the standalone root runner.
 */
object ThroneConfig {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private val GEOSITE_URLS = listOf(
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-%s.srs",
        "https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-%s.srs",
        "https://fastly.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-%s.srs",
        "https://gcore.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-%s.srs",
    )
    private val GEOIP_URLS = listOf(
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-%s.srs",
        "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-%s.srs",
        "https://fastly.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-%s.srs",
        "https://gcore.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-%s.srs",
    )

    private fun download(urls: List<String>, name: String, target: File): Boolean {
        for (u in urls) {
            try {
                val conn = URL(u.format(name)).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                try {
                    if (conn.responseCode != 200) continue
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size < 8 || String(bytes, 0, 3) != "SRS") continue
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    tmp.writeBytes(bytes)
                    return tmp.renameTo(target)
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun prepareRuleSets(root: JsonObject, route: JsonObject, dir: File) {
        val sets = route.getAsJsonArray("rule_set") ?: return
        dir.mkdirs()
        val missing = HashSet<String>()
        val jobs = ArrayList<Pair<String, Callable<Boolean>>>()
        val pool = Executors.newFixedThreadPool(6)
        try {
            for (e in sets) {
                val o = e.asJsonObject
                val tag = o.get("tag")?.asString ?: continue
                val path = o.get("path")?.asString ?: continue
                val (urls, name) = when {
                    path.startsWith("geosite:") -> GEOSITE_URLS to path.removePrefix("geosite:")
                    path.startsWith("geoip:") -> GEOIP_URLS to path.removePrefix("geoip:")
                    else -> continue
                }
                val file = File(dir, path.replace(':', '-') + ".srs")
                // cached copy is used as is; an update is tried but a failure keeps the old file
                val cached = file.isFile && file.length() > 0
                val stale = !cached || System.currentTimeMillis() - file.lastModified() > 7L * 24 * 3600 * 1000
                if (stale) jobs.add(tag to Callable { download(urls, name, file) || cached })
                o.addProperty("format", "binary")
                o.addProperty("path", file.absolutePath)
                if (!cached && !stale) missing.add(tag)
            }
            val futures = jobs.map { (tag, c) -> tag to pool.submit(c) }
            for ((tag, f) in futures) {
                val ok = try { f.get(25, TimeUnit.SECONDS) } catch (_: Exception) { false }
                if (!ok) missing.add(tag)
            }
        } finally {
            pool.shutdownNow()
        }
        if (missing.isEmpty()) return
        Logs.w("Throne: rule-sets unavailable, dropping rules that use them: $missing")
        val kept = JsonArray()
        sets.forEach { if (it.asJsonObject.get("tag")?.asString !in missing) kept.add(it) }
        route.add("rule_set", kept)
        fun uses(rule: JsonObject): Boolean {
            val rs = rule.get("rule_set")
            if (rs != null) {
                if (rs.isJsonArray) { if (rs.asJsonArray.any { it.asString in missing }) return true }
                else if (rs.asString in missing) return true
            }
            return rule.getAsJsonArray("rules")?.any { uses(it.asJsonObject) } == true
        }
        fun filter(rules: JsonArray?): JsonArray? {
            if (rules == null) return null
            val out = JsonArray()
            rules.forEach { if (!uses(it.asJsonObject)) out.add(it) }
            return out
        }
        filter(route.getAsJsonArray("rules"))?.let { route.add("rules", it) }
        root.getAsJsonObject("dns")?.let { dns -> filter(dns.getAsJsonArray("rules"))?.let { dns.add("rules", it) } }
    }

    /**
     * @param tun true for the in-process VPN engine (the platform opens the tun)
     * @param cachePath file for the cache_file (remote rule-sets are kept there), absolute or relative to the core's dir
     */
    fun adapt(json: String, tun: Boolean, cachePath: String, uiDir: String? = null, ruleSetDir: File): String = try {
        val root = JsonParser.parseString(json).asJsonObject

        // endpoints: wireguard `amnezia` block is called `amnezia_wg` in Throne's fork
        root.getAsJsonArray("endpoints")?.forEach { e ->
            val o = e.asJsonObject
            if (o.get("type")?.asString == "wireguard" && o.has("amnezia")) {
                o.add("amnezia_wg", o.remove("amnezia"))
            }
        }

        // geoip:/geosite: rule-sets are VladBox's own database format. Throne's core needs .srs files:
        // fetch them (with mirrors) before the start; a set that can't be had is dropped together with the rules
        // using it, so that an unreachable host never keeps the whole core from starting
        root.getAsJsonObject("route")?.let { route -> prepareRuleSets(root, route, ruleSetDir) }

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
