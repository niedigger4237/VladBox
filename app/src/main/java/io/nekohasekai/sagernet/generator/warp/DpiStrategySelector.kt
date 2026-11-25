package io.nekohasekai.sagernet.generator.warp

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit
import android.os.SystemClock

/** Engine adapter must restrict destinations and close its process on close(). */
interface WarpDpiSession : java.io.Closeable {
    val proxy: Proxy
}

object DpiStrategySelector {
    data class Strategy(val id: String, val arguments: List<String>)
    data class Match(val strategy: Strategy, val latencyMs: Long)

    /** Blocking operation; run on IO. Session ownership never escapes this method. */
    fun <T> withWorkingStrategy(
        strategies: List<Strategy>,
        preferredId: String?,
        open: (Strategy) -> WarpDpiSession,
        cancelled: () -> Boolean,
        progress: (Int, Int, Strategy) -> Unit,
        use: (Match, WarpDpiSession) -> T
    ): T {
        val ordered = strategies.distinctBy { it.id }.sortedBy { it.id != preferredId }
        for ((index, strategy) in ordered.withIndex()) {
            if (cancelled()) throw java.util.concurrent.CancellationException()
            progress(index + 1, ordered.size, strategy)
            val session = try { open(strategy) } catch (e: java.io.IOException) { continue }
            session.use {
                val latency = try { probe(it.proxy) } catch (e: java.io.IOException) { null }
                if (cancelled()) throw java.util.concurrent.CancellationException()
                if (latency != null) return use(Match(strategy, latency), it)
            }
        }
        error("No working ByeDPI strategy for Cloudflare API")
    }

    private fun probe(proxy: Proxy): Long? {
        require(proxy.type() == Proxy.Type.SOCKS)
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val start = SystemClock.elapsedRealtime()
        try {
            val request = Request.Builder()
                .url("https://api.cloudflareclient.com/v0i1909051800/reg")
                .get().build()
            return client.newCall(request).execute().use { response ->
                if (response.code in 200..499 && response.code != 429)
                    SystemClock.elapsedRealtime() - start else null
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
