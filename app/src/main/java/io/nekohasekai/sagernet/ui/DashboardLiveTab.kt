package io.nekohasekai.sagernet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import java.net.Proxy

internal suspend fun dashboardGet(path: String): JSONObject = dashboardRequest(path)

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

internal suspend fun dashboardRequest(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
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
        connection.readTimeout = 8000
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer " + io.nekohasekai.sagernet.bg.proto.ClashApiSecret.value)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        check(connection.responseCode in 200..299) { "HTTP " + connection.responseCode }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
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
    val command = "if command -v curl >/dev/null 2>&1; then curl -fsS --max-time 10 -i -H " +
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
        check(status != null && status in 200..299) { "Root API HTTP error" }
        val content = response.substringAfter("\r\n\r\n", response.substringAfter("\n\n", ""))
        return if (content.isBlank()) JSONObject() else JSONObject(content)
    } finally {
        handle.process.compareAndSet(process, null)
        process.destroy()
    }
}

private data class DashboardEntry(val title: String, val subtitle: String, val detail: String)

private fun dashboardEntries(tab: Int, data: JSONObject): List<DashboardEntry> {
    val entries = mutableListOf<DashboardEntry>()
    when (tab) {
        1 -> {
            val array = data.optJSONArray("connections") ?: return entries
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val metadata = item.optJSONObject("metadata") ?: JSONObject()
                val host = metadata.optString("host").ifBlank { metadata.optString("destinationIP") }
                val port = metadata.optString("destinationPort")
                val chains = item.optJSONArray("chains")
                val route = if (chains != null && chains.length() > 0) chains.optString(0) else item.optString("rule")
                entries.add(DashboardEntry(host + if (port.isBlank()) "" else ":" + port,
                    "↓ " + (item.optLong("download").toString() + " B") +
                    "  ↑ " + (item.optLong("upload").toString() + " B"), route))
            }
        }
        2 -> {
            val proxies = data.optJSONObject("proxies") ?: return entries
            for (name in proxies.keys()) {
                val item = proxies.optJSONObject(name) ?: continue
                val history = item.optJSONArray("history")
                val latency = if (history != null && history.length() > 0) history.optJSONObject(history.length()-1)?.optInt("delay") ?: 0 else 0
                entries.add(DashboardEntry(name, item.optString("type") + item.optString("now").let { if (it.isBlank()) "" else " · " + it }, if (latency > 0) "$latency ms" else "—"))
            }
        }
        3 -> {
            val array = data.optJSONArray("rules") ?: return entries
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                entries.add(DashboardEntry(item.optString("payload"), item.optString("type"), item.optString("proxy")))
            }
        }
    }
    return entries
}

@Composable
internal fun DashboardLiveTab(tab: Int, connected: Boolean) {
    var entries by remember(tab, connected) { mutableStateOf(emptyList<DashboardEntry>()) }
    var error by remember(tab, connected) { mutableStateOf<String?>(null) }
    var loaded by remember(tab, connected) { mutableStateOf(false) }
    var query by remember(tab) { mutableStateOf("") }
    LaunchedEffect(tab, connected) {
        if (!connected) return@LaunchedEffect
        while (true) {
            try {
                entries = dashboardEntries(tab, dashboardGet(listOf("", "connections", "proxies", "rules")[tab]))
                error = null
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entries = emptyList()
                error = "Local API unavailable"
            }
            delay(if (tab == 1) 2000 else 5000)
        }
    }
    Text(listOf("", "Active connections", "Proxies", "Routing rules")[tab] + if (loaded && error == null) " (" + entries.size + ")" else "", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (!connected || error != null || !loaded || entries.isEmpty()) {
        Text(if (!connected) "Service is not running" else error ?: if (!loaded) "Loading…" else "No entries", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    entries.filter { (it.title + " " + it.subtitle + " " + it.detail).contains(query, ignoreCase = true) }.forEach { item ->
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(item.detail, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
