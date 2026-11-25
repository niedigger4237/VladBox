package io.nekohasekai.sagernet.bg.proto

import android.net.Uri
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal const val LATENCY_TEST_TIMEOUT_MS = 5000
internal const val LATENCY_REQUEST_TIMEOUT_MS = 20000L
internal const val GROUP_LATENCY_TEST_TIMEOUT_MS = 10000
private const val GROUP_LATENCY_REQUEST_TIMEOUT_MS = 32000L

internal class ClashHttpException(val status: Int, message: String) : IllegalStateException(message)

internal fun io.nekohasekai.sagernet.database.ProxyEntity.isRawCoreConfig(): Boolean =
    type == io.nekohasekai.sagernet.database.ProxyEntity.TYPE_CONFIG && configBean?.type == 0

internal suspend fun clashDelay(tag: String = TAG_PROXY, url: String = DataStore.connectionTestURL): Int {
    require(url.startsWith("https://", true) || url.startsWith("http://", true)) { "Invalid connection test URL" }
    val result = kotlinx.coroutines.withTimeoutOrNull(LATENCY_REQUEST_TIMEOUT_MS) {
        clashRequest("proxies/" + Uri.encode(tag) + "/delay?timeout=" + LATENCY_TEST_TIMEOUT_MS + "&url=" + Uri.encode(url))
    } ?: throw java.net.SocketTimeoutException("Clash API latency request timed out")
    val delay = result.optInt("delay", -1)
    check(delay > 0) { result.optString("message").ifBlank { "No valid latency returned" } }
    return delay
}

/** Native group API returns a map of successful outbound tags to milliseconds.
 * GLOBAL in sing-box is often synthetic; only fall back on an unsupported/missing endpoint.
 */
internal suspend fun clashGroupDelay(group: String, tags: List<String>, url: String = DataStore.connectionTestURL): Map<String, Int> {
    require(url.startsWith("https://", true) || url.startsWith("http://", true)) { "Invalid connection test URL" }
    val distinct = tags.distinct()
    if (distinct.isEmpty()) return emptyMap()
    try {
        val result = kotlinx.coroutines.withTimeoutOrNull(GROUP_LATENCY_REQUEST_TIMEOUT_MS) {
            clashRequest("group/" + Uri.encode(group) + "/delay?timeout=" + GROUP_LATENCY_TEST_TIMEOUT_MS + "&url=" + Uri.encode(url),
                readTimeoutMs = GROUP_LATENCY_TEST_TIMEOUT_MS + 3000)
        } ?: throw java.net.SocketTimeoutException("Clash group latency request timed out")
        return distinct.mapNotNull { tag -> result.optInt(tag, -1).takeIf { it > 0 }?.let { tag to it } }.toMap()
    } catch (e: ClashHttpException) {
        if (e.status !in listOf(404, 405, 501)) throw e
    }
    // Some cores expose a synthetic GLOBAL in /proxies but no matching real group.
    // Limit concurrency and preserve cancellation; never retry real group timeouts.
    return kotlinx.coroutines.coroutineScope {
        val results = linkedMapOf<String, Int>()
        for (chunk in distinct.chunked(5)) {
            val batch = chunk.map { tag ->
                async {
                    try { tag to clashDelay(tag, url) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
            }.awaitAll()
            batch.filterNotNull().forEach { (tag, delay) -> results[tag] = delay }
        }
        results
    }
}

internal suspend fun activeProfileDelay(expectedProfile: Long = DataStore.currentProfile, url: String = DataStore.connectionTestURL): Int {
    check(DataStore.serviceState.connected && expectedProfile > 0 && DataStore.currentProfile == expectedProfile) { "Active profile changed or disconnected" }
    val delay = clashDelay(url = url)
    check(DataStore.serviceState.connected && DataStore.currentProfile == expectedProfile) { "Active profile changed during test" }
    return delay
}

// Group latency fallback uses batches of five. Reserve one additional
// worker for a foreground dashboard read, and bound the pending queue.
private val dashboardExecutor = ThreadPoolExecutor(
    6, 6, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(24),
    { task -> Thread(task, "clash-api").apply { isDaemon = true } },
).apply { allowCoreThreadTimeOut(true) }

private data class RootApiHint(val mode: String, val profile: Long, val expires: Long)
@Volatile private var rootApiHint: RootApiHint? = null

private class DashboardRequestHandle {
    val connection = AtomicReference<HttpURLConnection?>()
    val process = AtomicReference<Process?>()
    @Volatile var cancelled = false

    fun cancel() {
        cancelled = true
        process.getAndSet(null)?.destroy()
        connection.getAndSet(null)?.let { conn ->
            // Do not enqueue cleanup behind the blocked request workers.
            Thread({ runCatching { conn.disconnect() } }, "clash-api-close")
                .apply { isDaemon = true }.start()
        }
    }

    fun checkActive() {
        if (cancelled) throw CancellationException("Dashboard request cancelled")
    }
}

internal suspend fun clashRequest(path: String, method: String = "GET", body: JSONObject? = null,
    readTimeoutMs: Int = LATENCY_TEST_TIMEOUT_MS + 3000): JSONObject =
    suspendCancellableCoroutine { continuation ->
        val handle = DashboardRequestHandle()
        val task = FutureTask<Unit> {
            try {
                handle.checkActive()
                val rootMode = io.nekohasekai.sagernet.database.DataStore.serviceMode in listOf(
                    io.nekohasekai.sagernet.Key.MODE_ROOT_TUN, io.nekohasekai.sagernet.Key.MODE_ROOT_TPROXY,
                    io.nekohasekai.sagernet.Key.MODE_ROOT_TUN2SOCKS, io.nekohasekai.sagernet.Key.MODE_ROOT_BPF2SOCKS)
                val mode = DataStore.serviceMode
                val profile = DataStore.currentProfile
                val hint = rootApiHint
                val useRoot = rootMode && hint?.mode == mode && hint.profile == profile &&
                    android.os.SystemClock.elapsedRealtime() < hint.expires
                val result = if (useRoot) {
                    dashboardRoot(path, method, body, handle, readTimeoutMs)
                } else try {
                    dashboardDirect(path, method, body, handle, readTimeoutMs)
                } catch (e: java.io.IOException) {
                    handle.checkActive()
                    // A failed read after PUT may still mean the core applied
                    // the mutation. Never blindly replay it through root.
                    if (!rootMode || method != "GET") throw e
                    dashboardRoot(path, method, body, handle, readTimeoutMs).also {
                        handle.checkActive()
                        rootApiHint = RootApiHint(mode, profile, android.os.SystemClock.elapsedRealtime() + 15000L)
                    }
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
        continuation.invokeOnCancellation {
            task.cancel(true)
            dashboardExecutor.remove(task)
            handle.cancel()
        }
        try {
            dashboardExecutor.execute(task)
        } catch (e: RejectedExecutionException) {
            if (continuation.isActive) continuation.resumeWithException(
                IllegalStateException("Local API request queue is full"))
        }
    }

private fun dashboardDirect(path: String, method: String, body: JSONObject?, handle: DashboardRequestHandle, readTimeoutMs: Int): JSONObject {
    val connection = URL("http://127.0.0.1:9090/" + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    handle.connection.set(connection)
    try {
        handle.checkActive()
        connection.connectTimeout = 1500
        connection.readTimeout = readTimeoutMs
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
            throw ClashHttpException(code, if (message.isBlank()) "Clash API HTTP $code" else "Clash API HTTP $code: $message")
        }
        return if (response.isBlank()) JSONObject() else JSONObject(response)
    } finally {
        handle.connection.compareAndSet(connection, null)
        connection.disconnect()
    }
}

private fun dashboardRoot(path: String, method: String, body: JSONObject?, handle: DashboardRequestHandle, readTimeoutMs: Int): JSONObject {
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
    val seconds = (readTimeoutMs + 999) / 1000 + 2
    val command = "if command -v curl >/dev/null 2>&1; then curl -sS --max-time $seconds -i -H " +
        q("Authorization: Bearer " + token) + " -X " + method + payload + " " + url +
        "; else printf '%s' " + q(request) + " | nc -w $seconds 127.0.0.1 9090; fi"
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
            throw ClashHttpException(status ?: -1, if (message.isBlank()) "Root API HTTP ${status ?: "unknown"}" else "Root API HTTP $status: $message")
        }
        return if (content.isBlank()) JSONObject() else JSONObject(content)
    } finally {
        handle.process.compareAndSet(process, null)
        process.destroy()
    }
}

