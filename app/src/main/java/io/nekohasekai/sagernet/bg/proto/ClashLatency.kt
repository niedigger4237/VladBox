package io.nekohasekai.sagernet.bg.proto

import android.net.Uri
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal const val LATENCY_TEST_TIMEOUT_MS = 5000
internal const val LATENCY_REQUEST_TIMEOUT_MS = 20000L

internal fun io.nekohasekai.sagernet.database.ProxyEntity.isRawCoreConfig(): Boolean =
    type == io.nekohasekai.sagernet.database.ProxyEntity.TYPE_CONFIG && configBean?.type == 0

internal suspend fun clashDelay(tag: String = TAG_PROXY, url: String = DataStore.connectionTestURL): Int {
    require(url.startsWith("https://", true) || url.startsWith("http://", true)) { "Invalid connection test URL" }
    val result = kotlinx.coroutines.withTimeout(LATENCY_REQUEST_TIMEOUT_MS) {
        clashRequest("proxies/" + Uri.encode(tag) + "/delay?timeout=" + LATENCY_TEST_TIMEOUT_MS + "&url=" + Uri.encode(url))
    }
    val delay = result.optInt("delay", -1)
    check(delay > 0) { result.optString("message").ifBlank { "No valid latency returned" } }
    return delay
}

internal suspend fun activeProfileDelay(expectedProfile: Long = DataStore.currentProfile, url: String = DataStore.connectionTestURL): Int {
    check(DataStore.serviceState.connected && expectedProfile > 0 && DataStore.currentProfile == expectedProfile) { "Active profile changed or disconnected" }
    val delay = clashDelay(url = url)
    check(DataStore.serviceState.connected && DataStore.currentProfile == expectedProfile) { "Active profile changed during test" }
    return delay
}

private val dashboardExecutor = Executors.newCachedThreadPool()

private class DashboardRequestHandle {
    val connection = AtomicReference<HttpURLConnection?>()
    val process = AtomicReference<Process?>()
    @Volatile var cancelled = false

    fun cancel() {
        cancelled = true
        process.getAndSet(null)?.destroy()
        connection.getAndSet(null)?.let { conn ->
            dashboardExecutor.execute { conn.disconnect() }
        }
    }

    fun checkActive() {
        if (cancelled) throw CancellationException("Dashboard request cancelled")
    }
}

internal suspend fun clashRequest(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
    suspendCancellableCoroutine { continuation ->
        val handle = DashboardRequestHandle()
        continuation.invokeOnCancellation { handle.cancel() }
        dashboardExecutor.execute {
            try {
                handle.checkActive()
                val rootMode = io.nekohasekai.sagernet.database.DataStore.serviceMode in listOf(
                    io.nekohasekai.sagernet.Key.MODE_ROOT_TUN, io.nekohasekai.sagernet.Key.MODE_ROOT_TPROXY,
                    io.nekohasekai.sagernet.Key.MODE_ROOT_TUN2SOCKS, io.nekohasekai.sagernet.Key.MODE_ROOT_BPF2SOCKS)
                val result = try {
                    dashboardDirect(path, method, body, handle)
                } catch (e: java.io.IOException) {
                    handle.checkActive()
                    if (!rootMode) throw e
                    dashboardRoot(path, method, body, handle)
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }

private fun dashboardDirect(path: String, method: String, body: JSONObject?, handle: DashboardRequestHandle): JSONObject {
    val connection = URL("http://127.0.0.1:9090/" + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    handle.connection.set(connection)
    try {
        handle.checkActive()
        connection.connectTimeout = 1500
        connection.readTimeout = LATENCY_TEST_TIMEOUT_MS + 3000
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer " + io.nekohasekai.sagernet.bg.proto.ClashApiSecret.value)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(response).optString("message") }.getOrNull().orEmpty()
            error(if (message.isBlank()) "Clash API HTTP $code" else "Clash API HTTP $code: $message")
        }
        return if (response.isBlank()) JSONObject() else JSONObject(response)
    } finally {
        handle.connection.compareAndSet(connection, null)
        connection.disconnect()
    }
}

private fun dashboardRoot(path: String, method: String, body: JSONObject?, handle: DashboardRequestHandle): JSONObject {
    require(method in listOf("GET", "PUT"))
    fun q(value: String): String = io.nekohasekai.sagernet.bg.root.RootShell.q(value)
    val url = q("http://127.0.0.1:9090/" + path)
    val payload = body?.let { " -H " + q("Content-Type: application/json") + " --data-binary " + q(it.toString()) }.orEmpty()
    val token = io.nekohasekai.sagernet.bg.proto.ClashApiSecret.value
    val data = body?.toString().orEmpty()
    val request = method + " /" + path + " HTTP/1.0\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + token +
        "\r\nConnection: close\r\n" +
        (if (body != null) "Content-Type: application/json\r\nContent-Length: " + data.toByteArray(Charsets.UTF_8).size + "\r\n" else "") +
        "\r\n" + data
    val command = "if command -v curl >/dev/null 2>&1; then curl -sS --max-time 10 -i -H " +
        q("Authorization: Bearer " + token) + " -X " + method + payload + " " + url +
        "; else printf '%s' " + q(request) + " | nc -w 10 127.0.0.1 9090; fi"
    handle.checkActive()
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    handle.process.set(process)
    try {
        handle.checkActive()
        val response = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Root API request failed" }
        val status = response.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull()
        val content = response.substringAfter("\r\n\r\n", response.substringAfter("\n\n", ""))
        if (status == null || status !in 200..299) {
            val message = runCatching { JSONObject(content).optString("message") }.getOrNull().orEmpty()
            error(if (message.isBlank()) "Root API HTTP ${status ?: "unknown"}" else "Root API HTTP $status: $message")
        }
        return if (content.isBlank()) JSONObject() else JSONObject(content)
    } finally {
        handle.process.compareAndSet(process, null)
        process.destroy()
    }
}

