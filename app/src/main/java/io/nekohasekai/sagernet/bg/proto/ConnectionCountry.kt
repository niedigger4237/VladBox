package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Country seen by an HTTPS probe routed through the running local mixed proxy.
 * Never fall back to a direct connection or infer a country from the profile name.
 */
internal object ConnectionCountry {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val validCountries = Locale.getISOCountries().toSet()

    suspend fun lookup(port: Int = DataStore.mixedPort): String? =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url("https://www.cloudflare.com/cdn-cgi/trace")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            val call = client.newBuilder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .build().newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onResponse(call: Call, response: Response) {
                    val country = response.use {
                        if (!it.isSuccessful) null else runCatching {
                            it.body?.source()?.let { source ->
                                // Trace responses are small; do not buffer unlimited data.
                                source.request(16385L)
                                if (source.buffer.size > 16384L) null
                                else source.readUtf8().lineSequence()
                                    .firstOrNull { line -> line.startsWith("loc=") }
                                    ?.substringAfter("=")?.trim()?.uppercase(Locale.ROOT)
                                    ?.takeIf { code -> code in validCountries }
                            }
                        }.getOrNull()
                    }
                    if (continuation.isActive) continuation.resume(country)
                }
            })
        }

    fun flag(code: String): String = if (code in validCountries)
        code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("") else ""

    fun name(code: String): String = Locale("", code).getDisplayCountry(Locale.getDefault())
}
