package io.nekohasekai.sagernet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal suspend fun dashboardGet(path: String): JSONObject = dashboardRequest(path)

internal suspend fun dashboardRequest(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
    kotlinx.coroutines.withTimeoutOrNull(io.nekohasekai.sagernet.bg.proto.ClashRequestPolicy.CONTROL_DEADLINE_MS) {
        io.nekohasekai.sagernet.bg.proto.clashRequest(path, method, body, readTimeoutMs = 4000)
    } ?: throw java.net.SocketTimeoutException("Local API request timed out")

// Capture the same binder/session for the entire read; do not publish a
// snapshot obtained from a core that restarted while the request was running.
internal suspend fun <T> dashboardSessionRead(
    service: () -> io.nekohasekai.sagernet.aidl.ISagerNetService?,
    block: suspend () -> T
): T {
    val binder = service() ?: error("Service unavailable")
    suspend fun session() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { binder.selectorSessionId }
    val expected = session()
    check(expected != 0L) { "Service not connected" }
    val result = block()
    check(service() === binder && session() == expected) { "Service session changed" }
    return result
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
internal fun DashboardLiveTab(tab: Int, connected: Boolean, searching: Boolean = false, onSearchChange: (Boolean) -> Unit = {},
    service: () -> io.nekohasekai.sagernet.aidl.ISagerNetService?) {
    var entries by remember(tab, connected) { mutableStateOf(emptyList<DashboardEntry>()) }
    var error by remember(tab, connected) { mutableStateOf<String?>(null) }
    var loaded by remember(tab, connected) { mutableStateOf(false) }
    var query by remember(tab) { mutableStateOf("") }
    LaunchedEffect(tab, connected) {
        if (!connected) return@LaunchedEffect
        val normalMs = if (tab == 1) 2000L else 5000L
        var retryMs = normalMs
        while (true) {
            try {
                entries = dashboardSessionRead(service) {
                    val response = dashboardGet(listOf("", "connections", "proxies", "rules")[tab])
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { dashboardEntries(tab, response) }
                }
                retryMs = normalMs
                error = null
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entries = emptyList()
                error = "Local API unavailable"
                retryMs = (retryMs * 2).coerceAtMost(30000L)
            }
            delay(retryMs)
        }
    }
    var selected by remember(tab) { mutableStateOf<DashboardEntry?>(null) }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.title) }, text = { Text(item.address + "\nDownload: " + item.download + "\nUpload: " + item.upload + "\nRoute: " + item.detail) }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }) }
    var routeFilter by remember(tab) { mutableStateOf("All") }
    var filterMenu by remember { mutableStateOf(false) }
    LaunchedEffect(searching) { if (!searching) query = "" }
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Active connections" + if (loaded && error == null) " (" + entries.size + ")" else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box {
                    IconButton(onClick = { filterMenu = true }) {
                        Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_filter_alt), contentDescription = "Filter connections", tint = if (routeFilter == "All") colors.onSurface else colors.primary)
                    }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        listOf("All", "Proxy", "Direct", "Other").forEach { option ->
                            DropdownMenuItem(text = { Text(if (routeFilter == option) "✓  $option" else option) }, onClick = { routeFilter = option; filterMenu = false })
                        }
                        DropdownMenuItem(text = { Text("Search") }, onClick = { onSearchChange(!searching); filterMenu = false })
                    }
                }
            }
            if (searching) OutlinedTextField(value = query, onValueChange = { query = it },
                placeholder = { Text("Search connections") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!connected || error != null || !loaded || entries.isEmpty()) {
                Text(if (!connected) "Service is not running" else error ?: if (!loaded) "Loading…" else "No connections",
                    color = colors.onSurfaceVariant)
            }
            val filtered = entries.filter { item ->
                val direct = item.detail.equals("direct", true) || item.detail.equals("bypass", true)
                val proxy = item.detail.equals("proxy", true)
                val matchesRoute = when (routeFilter) { "Direct" -> direct; "Proxy" -> proxy; "Other" -> !direct && !proxy; else -> true }
                matchesRoute && (item.title + " " + item.subtitle + " " + item.detail).contains(query, ignoreCase = true)
            }
            if (entries.isNotEmpty() && filtered.isEmpty()) Text("No matching connections", color = colors.onSurfaceVariant)
            filtered.forEach { item ->
                val direct = item.detail.equals("direct", true) || item.detail.equals("bypass", true)
                val proxy = item.detail.equals("proxy", true)
                val tint = if (direct) androidx.compose.ui.graphics.Color(0xFF81D99A) else colors.primary
                OutlinedCard(Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = .55f)), shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(Modifier.size(32.dp), shape = androidx.compose.foundation.shape.CircleShape, color = colors.secondaryContainer) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) { DashboardBrandIcon(item.title) { Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_public), contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.onSecondaryContainer) } }
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
                        IconButton(onClick = { selected = item }, modifier = Modifier.size(32.dp)) { Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_more_vert), contentDescription = "Connection details") }
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

