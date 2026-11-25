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

private data class DashboardEntry(val title: String, val subtitle: String, val detail: String, val address: String = "", val download: String = "", val upload: String = "")

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
                val ip = metadata.optString("destinationIP").ifBlank { host }
                val address = (if (ip.contains(':')) "[$ip]" else ip) + if (port.isBlank()) "" else ":$port"
                entries.add(DashboardEntry(host.ifBlank { "Unknown destination" }, address, route,
                    address, dashboardBytes(item.optLong("download")), dashboardBytes(item.optLong("upload"))))
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
    var selected by remember(tab) { mutableStateOf<DashboardEntry?>(null) }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.title) }, text = { Text(item.address + "\nDownload: " + item.download + "\nUpload: " + item.upload + "\nRoute: " + item.detail) }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }) }
    var searching by remember(tab) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Active connections" + if (loaded && error == null) " (" + entries.size + ")" else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                        drawCircle(colors.onSurface, size.width * .28f, androidx.compose.ui.geometry.Offset(size.width * .4f, size.height * .4f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        drawLine(colors.onSurface, androidx.compose.ui.geometry.Offset(size.width * .61f, size.height * .61f), androidx.compose.ui.geometry.Offset(size.width * .87f, size.height * .87f), 2.dp.toPx())
                    }
                }
            }
            if (searching) OutlinedTextField(value = query, onValueChange = { query = it },
                placeholder = { Text("Search connections") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!connected || error != null || !loaded || entries.isEmpty()) {
                Text(if (!connected) "Service is not running" else error ?: if (!loaded) "Loading…" else "No connections",
                    color = colors.onSurfaceVariant)
            }
            entries.filter { (it.title + " " + it.subtitle + " " + it.detail).contains(query, ignoreCase = true) }.forEach { item ->
                val direct = item.detail.equals("direct", true) || item.detail.equals("bypass", true)
                val proxy = item.detail.equals("proxy", true)
                val tint = if (direct) androidx.compose.ui.graphics.Color(0xFF81D99A) else colors.primary
                OutlinedCard(Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = .55f)), shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(Modifier.size(30.dp), shape = androidx.compose.foundation.shape.CircleShape, color = colors.secondaryContainer) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) { DashboardServiceIcon(item.title) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(item.address, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Column {
                            Text("↓ " + item.download, style = MaterialTheme.typography.labelSmall)
                            Text("↑ " + item.upload, style = MaterialTheme.typography.labelSmall)
                        }
                        Surface(shape = RoundedCornerShape(20.dp), color = tint.copy(alpha = .18f)) {
                            Text(if (direct) "Direct" else if (proxy) "Proxy" else item.detail.ifBlank { "—" },
                                Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = tint,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        IconButton(onClick = { selected = item }, modifier = Modifier.size(32.dp)) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
        }
    }
}

private fun dashboardBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return if (index == 0) "${value.toLong()} B" else
        String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[index])
}

@Composable
private fun DashboardServiceIcon(host: String) {
    val domain = host.lowercase(java.util.Locale.ROOT).trimEnd('.')
    fun matches(vararg domains: String) = domains.any { domain == it || domain.endsWith("." + it) }
    val kind = when {
        matches("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com") -> 1
        matches("google.com", "googleapis.com", "gstatic.com", "googleusercontent.com") -> 2
        matches("telegram.org", "t.me", "telegram.me") -> 3
        matches("discord.com", "discord.gg", "discordapp.com", "discordapp.net") -> 4
        matches("github.com", "githubusercontent.com", "githubassets.com") -> 5
        matches("spotify.com", "scdn.co", "spotifycdn.com") -> 6
        matches("cloudflare.com", "cloudflare-dns.com", "cloudflare.net") -> 7
        else -> 0
    }
    val tint = MaterialTheme.colorScheme.onSecondaryContainer
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val s = size.width / 24f
        val stroke = 1.6.dp.toPx()
        fun pt(x: Float,y: Float) = androidx.compose.ui.geometry.Offset(x*s,y*s)
        fun line(x: Float,y: Float,a: Float,b: Float) = drawLine(tint,pt(x,y),pt(a,b),stroke,cap=androidx.compose.ui.graphics.StrokeCap.Round)
        fun outline() = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
        when(kind) {
            1 -> {
                drawRoundRect(tint,pt(2f,5f),androidx.compose.ui.geometry.Size(20*s,14*s),androidx.compose.ui.geometry.CornerRadius(4*s),style=outline())
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(10*s,8*s); lineTo(16*s,12*s); lineTo(10*s,16*s); close() }
                drawPath(p,tint)
            }
            2 -> { drawArc(tint,40f,285f,false,pt(3f,3f),androidx.compose.ui.geometry.Size(18*s,18*s),style=outline()); line(12f,12f,21f,12f); line(21f,12f,21f,15f) }
            3 -> { line(2f,10f,22f,3f); line(22f,3f,17f,21f); line(17f,21f,10f,15f); line(10f,15f,22f,3f); line(2f,10f,10f,15f) }
            4 -> { drawRoundRect(tint,pt(3f,6f),androidx.compose.ui.geometry.Size(18*s,12*s),androidx.compose.ui.geometry.CornerRadius(5*s),style=outline()); drawCircle(tint,1.5*s,pt(9f,12f)); drawCircle(tint,1.5*s,pt(15f,12f)); line(6f,18f,4f,20f); line(18f,18f,20f,20f) }
            5 -> { drawCircle(tint,9*s,pt(12f,12f),style=outline()); line(8f,8f,7f,5f); line(16f,8f,17f,5f); drawRoundRect(tint,pt(7f,8f),androidx.compose.ui.geometry.Size(10*s,8*s),androidx.compose.ui.geometry.CornerRadius(3*s)); line(12f,16f,12f,21f) }
            6 -> { drawCircle(tint,10*s,pt(12f,12f),style=outline()); for(i in 0..2) drawArc(tint,205f,125f,false,pt(5f+i,9f+i*3f),androidx.compose.ui.geometry.Size((14f-2f*i)*s,7*s),style=outline()) }
            7 -> { drawArc(tint,180f,180f,false,pt(5f,4f),androidx.compose.ui.geometry.Size(14*s,14*s),style=outline()); line(5f,11f,2f,15f); line(2f,15f,4f,18f); line(4f,18f,22f,18f); line(22f,18f,22f,14f); line(22f,14f,19f,11f) }
            else -> { drawCircle(tint,10*s,pt(12f,12f),style=outline()); drawOval(tint,pt(7f,2f),androidx.compose.ui.geometry.Size(10*s,20*s),style=outline()); line(2f,12f,22f,12f) }
        }
    }
}
