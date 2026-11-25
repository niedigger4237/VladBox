package io.nekohasekai.sagernet.generator.warp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object DpiStrategyCatalog {
    private fun preset(id: String, args: String) =
        DpiStrategySelector.Strategy(id, args.split(' ').filter { it.isNotEmpty() })

    val strategies = listOf(
        preset("none", ""),
        preset("s1", "--split 1+s"),
        preset("d1", "--disorder 1+s"),
        preset("tlsrec", "--tlsrec 1+s"),
        preset("sd", "--split 1+s --disorder 3+s"),
        preset("dtr", "--disorder 1 --tlsrec 1+s"),
        preset("multi", "--split 1 --split 3+s --tlsrec 1+s"),
        preset("oob", "--oob 1+s"),
        preset("doob", "--disoob 1+s"),
        preset("oobtr", "--oob 3 --tlsrec 3+s"),
        preset("f8", "--fake -1 --ttl 8"),
        preset("f5", "--fake -1 --ttl 5"),
        preset("f10tr", "--fake -1 --ttl 10 --tlsrec 1+s"),
        preset("df", "--disorder 1 --fake -1 --ttl 8"),
        preset("auto1", "--disorder 1 --auto=torst --tlsrec 1+s"),
        preset("auto2", "--fake -1 --ttl 10 --auto=ssl_err --fake -1 --ttl 5"),
        preset("md5", "--fake -1 --md5sig"),
        preset("md5oob", "--oob 1 --fake -1 --md5sig")
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences("warp_dpi", Context.MODE_PRIVATE)

    data class Custom(val id: String, val name: String, val args: String, val enabled: Boolean = true)
    data class Settings(val timeout: Int = 6, val custom: List<Custom> = emptyList())

    fun arguments(raw: String): List<String>? {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 16) return null
        val options = setOf("--split", "--disorder", "--tlsrec", "--oob", "--disoob", "--fake", "--ttl")
        val flags = setOf("--md5sig", "--auto=torst", "--auto=ssl_err")
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i++]
            if (token in flags) continue
            if (token !in options || i == tokens.size || !tokens[i++].matches(Regex("[0-9+\\-s]+"))) return null
        }
        return tokens
    }

    fun settings(context: Context): Settings {
        val p = prefs(context)
        val custom = runCatching {
            val array = JSONArray(p.getString("custom", "[]") ?: "[]")
            (0 until array.length()).mapNotNull { i ->
                val item = array.getJSONObject(i)
                val args = item.getString("args")
                if (arguments(args) == null) null else Custom(item.getString("id"), item.getString("name"), args, item.optBoolean("enabled", true))
            }
        }.getOrDefault(emptyList())
        return Settings(p.getInt("timeout", 6).coerceIn(1, 120), custom)
    }

    fun save(context: Context, settings: Settings) {
        require(settings.timeout in 1..120 && settings.custom.all { arguments(it.args) != null })
        val array = JSONArray()
        settings.custom.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("args", it.args).put("enabled", it.enabled)) }
        prefs(context).edit().putInt("timeout", settings.timeout).putString("custom", array.toString()).apply()
    }

    fun available(settings: Settings) = strategies + settings.custom.filter { it.enabled }.mapNotNull {
        arguments(it.args)?.let { args -> DpiStrategySelector.Strategy(it.id, args) }
    }

    fun preferred(context: Context): String? =
        prefs(context).getString("strategy", null)?.takeIf { id -> available(settings(context)).any { it.id == id } }

    fun remember(context: Context, match: DpiStrategySelector.Match) {
        prefs(context).edit().putString("strategy", match.strategy.id).apply()
    }
}
