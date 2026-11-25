package io.nekohasekai.sagernet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import java.net.Proxy

internal suspend fun dashboardGet(path: String): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL("http://127.0.0.1:9090/" + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    try {
        connection.connectTimeout = 1500
        connection.readTimeout = 2000
        connection.instanceFollowRedirects = false
        check(connection.responseCode == 200) { "HTTP " + connection.responseCode }
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
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
