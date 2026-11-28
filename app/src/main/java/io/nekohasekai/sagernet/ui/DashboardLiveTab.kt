package io.nekohasekai.sagernet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

private data class DashboardEntry(
    val id: String,
    val title: String,
    val address: String,
    val source: String,
    val network: String,
    val process: String,
    val start: String,
    val chains: List<String>,
    val rule: String,
    val route: DashboardConnectionPolicy.Route,
    val download: Long,
    val upload: Long
) {
    val detail get() = chains.asReversed().joinToString(" → ").ifBlank { rule }
    val searchText get() = listOf(title, address, source, network, process, detail, rule).joinToString(" ")
}

private fun JSONObject.string(key: String): String =
    if (isNull(key)) "" else optString(key).takeUnless { it == "null" }.orEmpty()

private fun dashboardEntries(data: JSONObject, types: Map<String, String>): List<DashboardEntry> {
    val array = data.optJSONArray("connections") ?: return emptyList()
    val entries = ArrayList<DashboardEntry>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val metadata = item.optJSONObject("metadata") ?: JSONObject()
        val host = metadata.string("host").ifBlank { metadata.string("destinationIP") }
        val chains = item.optJSONArray("chains")?.let { tags ->
            (0 until tags.length()).mapNotNull { index -> tags.optString(index).takeIf { it.isNotBlank() && it != "null" } }
        }.orEmpty()
        val address = DashboardConnectionPolicy.address(metadata.string("destinationIP").ifBlank { host }, metadata.string("destinationPort"))
        val source = DashboardConnectionPolicy.address(metadata.string("sourceIP"), metadata.string("sourcePort"))
        val start = item.string("start")
        val id = item.string("id").ifBlank { "legacy:$i:$source:$address:$start" }
        entries.add(DashboardEntry(id, host.ifBlank { "Unknown destination" }, address, source,
            metadata.string("network"), metadata.string("processPath"), start, chains, item.string("rule"),
            DashboardConnectionPolicy.route(chains, types), item.optLong("download").coerceAtLeast(0),
            item.optLong("upload").coerceAtLeast(0)))
    }
    return entries.distinctBy { it.id }.sortedWith(compareBy<DashboardEntry> { it.start }.thenBy { it.id })
}

private fun dashboardOutboundTypes(response: JSONObject): Map<String, String> {
    val proxies = response.optJSONObject("proxies") ?: return emptyMap()
    return proxies.keys().asSequence().mapNotNull { tag ->
        proxies.optJSONObject(tag)?.string("type")?.takeIf { it.isNotBlank() }?.let { tag to it }
    }.toMap()
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
        val normalMs = 2000L
        var retryMs = normalMs
        var types = emptyMap<String, String>()
        var typesSession = 0L
        var typesAt = Long.MIN_VALUE
        while (true) {
            try {
                entries = dashboardSessionRead(service) {
                    val binder = service() ?: error("Service unavailable")
                    val session = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { binder.selectorSessionId }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (session != typesSession) {
                        types = emptyMap()
                        typesSession = session
                        typesAt = Long.MIN_VALUE
                    }
                    if (typesAt == Long.MIN_VALUE || now - typesAt >= 30_000L) {
                        try {
                            val response = dashboardGet("proxies")
                            types = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { dashboardOutboundTypes(response) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { types = emptyMap() }
                        typesAt = now
                    }
                    val response = dashboardGet("connections")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { dashboardEntries(response, types) }
                }
                retryMs = normalMs
                error = null
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entries = emptyList()
                types = emptyMap()
                typesAt = Long.MIN_VALUE
                error = "Local API unavailable"
                retryMs = (retryMs * 2).coerceAtMost(30000L)
            }
            delay(retryMs)
        }
    }
    var selectedId by remember(tab, connected) { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId }
    LaunchedEffect(selectedId, selected) {
        if (selectedId != null && selected == null) selectedId = null
    }
    selected?.let { item ->
        AlertDialog(onDismissRequest = { selectedId = null }, title = { Text(item.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())) {
                    Text("Destination: " + item.address)
                    if (item.source.isNotBlank()) Text("Source: " + item.source)
                    if (item.network.isNotBlank()) Text("Network: " + item.network.uppercase(java.util.Locale.ROOT))
                    if (item.process.isNotBlank()) Text("Process: " + item.process)
                    Text("Download: " + dashboardBytes(item.download))
                    Text("Upload: " + dashboardBytes(item.upload))
                    Text("Route: " + item.detail)
                    if (item.rule.isNotBlank()) Text("Rule: " + item.rule)
                    if (item.start.isNotBlank()) Text("Start: " + item.start)
                }
            }, confirmButton = { TextButton(onClick = { selectedId = null }) { Text("Close") } })
    }
    var routeFilter by remember(tab) { mutableStateOf("All") }
    var filterMenu by remember { mutableStateOf(false) }
    LaunchedEffect(searching) { if (!searching) query = "" }
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            val filtered = remember(entries, routeFilter, query) {
                entries.filter { item ->
                    (routeFilter == "All" || item.route.label == routeFilter) && item.searchText.contains(query, ignoreCase = true)
                }
            }
            if (entries.isNotEmpty() && filtered.isEmpty()) Text("No matching connections", color = colors.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              items(filtered, key = { it.id }) { item ->
                val direct = item.route == DashboardConnectionPolicy.Route.DIRECT
                val proxy = item.route == DashboardConnectionPolicy.Route.PROXY
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
                            Text("↓ " + dashboardBytes(item.download), style = MaterialTheme.typography.labelSmall)
                            Text("↑ " + dashboardBytes(item.upload), style = MaterialTheme.typography.labelSmall)
                        }
                        Surface(shape = RoundedCornerShape(20.dp), color = tint.copy(alpha = .18f)) {
                            Text(if (direct) "Direct" else if (proxy) "Proxy" else item.chains.firstOrNull().orEmpty().ifBlank { "Other" },
                                Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = tint,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        IconButton(onClick = { selectedId = item.id }, modifier = Modifier.size(32.dp)) { Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_more_vert), contentDescription = "Connection details") }
                    }
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

