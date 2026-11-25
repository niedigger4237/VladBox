package io.nekohasekai.sagernet.generator.warp

import android.content.Context

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

    fun preferred(context: Context): String? =
        prefs(context).getString("strategy", null)?.takeIf { id -> strategies.any { it.id == id } }

    fun remember(context: Context, match: DpiStrategySelector.Match) {
        require(strategies.any { it == match.strategy })
        prefs(context).edit().putString("strategy", match.strategy.id).apply()
    }
}
