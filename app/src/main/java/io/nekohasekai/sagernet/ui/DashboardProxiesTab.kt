package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class DashboardProxyNode(val name: String, val delay: Int? = null, val countryCode: String? = null, val displayName: String = name, val protocol: String? = null)
internal data class DashboardProxyGroup(val name: String, val type: String, val selected: String, val nodes: List<DashboardProxyNode>)

@Composable
internal fun DashboardProxiesTab(
    connected: Boolean,
    groups: List<DashboardProxyGroup> = emptyList(),
    onSelect: ((String, String) -> Unit)? = null,
    onTest: ((String) -> Unit)? = null
) {
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val group = groups.firstOrNull { it.name == selectedGroup } ?: groups.firstOrNull()
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Proxy groups", style = MaterialTheme.typography.titleMedium)
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = .55f)), colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_public), contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.primary)
                            Text(group?.name ?: "No proxy groups", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(group?.let { when { it.type.equals("URLTest", true) -> "Automatic server selection"; it.type.equals("Fallback", true) -> "Automatic failover"; else -> "Proxy group" } }
                            ?: if (connected) "Proxy data unavailable" else "Service is not running",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (group != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text((if (group.type.equals("URLTest", true) || group.type.equals("Fallback", true)) "Auto" else group.type) + " ▾") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                groups.forEach { item ->
                                    DropdownMenuItem(text = { Text(item.name) }, onClick = { selectedGroup = item.name; expanded = false })
                                }
                            }
                        }
                        ProxyDelay(group.nodes.firstOrNull { it.name == group.selected }?.delay, showLightning = true)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(group?.let { "Servers (" + it.name + ")" } ?: "Servers", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { group?.let { onTest?.invoke(it.name) } }, enabled = connected && group != null && onTest != null) {
                    Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_refresh), contentDescription = "Test server latency")
                }
            }
            if (group == null || group.nodes.isEmpty()) {
                Text("No servers available", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column {
                        group.nodes.forEachIndexed { index, node ->
                            val active = node.name == group.selected
                            Surface(color = if (active) colors.secondaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                                border = if (active) BorderStroke(1.dp, colors.primary.copy(alpha = .5f)) else null) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp)
                                    .selectable(selected = active, enabled = connected && onSelect != null && group.type.equals("Selector", true),
                                        role = Role.RadioButton, onClick = { onSelect?.invoke(group.name, node.name) })
                                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                                        drawCircle(if (active) colors.primary else colors.outline, radius = size.minDimension / 2 - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                                        if (active) drawCircle(colors.primary, radius = size.minDimension / 4)
                                    }
                                    val flag = proxyFlag(node.countryCode)
                                    if (flag.isNotEmpty() && !node.displayName.startsWith(flag)) Text(flag)
                                    Column(Modifier.weight(1f)) {
                                        Text(node.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        node.protocol?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                                    }
                                    ProxyDelay(node.delay)
                                }
                            }
                            if (index < group.nodes.lastIndex) Divider(color = colors.outlineVariant.copy(alpha = .5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyDelay(delay: Int?, showLightning: Boolean = false) {
    val tint = when {
        delay == null || delay <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        delay < 100 -> Color(0xFF81D99A)
        delay < 200 -> Color(0xFFE5B85C)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(color = tint.copy(alpha = .18f), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.defaultMinSize(minWidth = if (showLightning) 64.dp else 52.dp, minHeight = 24.dp).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
            if (showLightning && delay != null && delay > 0) Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_bolt), contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
            Text(if (delay != null && delay > 0) "$delay ms" else "—", color = tint, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, softWrap = false)
        }
    }
}

private fun proxyFlag(countryCode: String?): String {
    val code = countryCode?.uppercase(java.util.Locale.ROOT) ?: return ""
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
    return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}


@Composable
internal fun DashboardLiveProxies(connected: Boolean, service: () -> io.nekohasekai.sagernet.aidl.ISagerNetService?) {
    var groups by remember(connected) { mutableStateOf(emptyList<DashboardProxyGroup>()) }
    var error by remember(connected) { mutableStateOf<String?>(null) }
    var busy by remember(connected) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val refreshLock = remember { kotlinx.coroutines.sync.Mutex() }
    var displayedSessionId by remember(connected) { mutableStateOf(0L) }
    var cachedProfileId by remember(connected) { mutableStateOf(Long.MIN_VALUE) }
    var cachedProfile by remember(connected) { mutableStateOf<io.nekohasekai.sagernet.database.ProxyEntity?>(null) }
    suspend fun refresh() {
        refreshLock.lock()
        try {
        val binder = service() ?: error("Service unavailable")
        val sessionId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { binder.selectorSessionId }
        check(sessionId != 0L) { "Service not connected" }
        val id = io.nekohasekai.sagernet.database.DataStore.currentProfile
        if (cachedProfileId != id) {
            cachedProfile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (id > 0) io.nekohasekai.sagernet.database.SagerDatabase.proxyDao.getById(id) else null
            }
            cachedProfileId = id
        }
        val profile = cachedProfile
        val proxies = dashboardGet("proxies").getJSONObject("proxies")
        val refreshedGroups = proxies.keys().asSequence().mapNotNull { name ->
            val item = proxies.optJSONObject(name) ?: return@mapNotNull null
            val all = item.optJSONArray("all") ?: return@mapNotNull null
            val nodes = (0 until all.length()).map { index ->
                val tag = all.getString(index)
                val history = proxies.optJSONObject(tag)?.optJSONArray("history")
                val delay = history?.optJSONObject(history.length() - 1)?.optInt("delay")?.takeIf { it > 0 }
                val isMain = tag == io.nekohasekai.sagernet.fmt.TAG_PROXY
                val label = if (isMain) profile?.displayName()?.takeIf { it.isNotBlank() } else null
                val protocol = if (isMain) profile?.displayType() else proxies.optJSONObject(tag)?.optString("type")?.takeIf { it.isNotBlank() }
                val nodeData = proxies.optJSONObject(tag)
                val country = sequenceOf(nodeData?.optString("countryCode"), nodeData?.optString("country"))
                    .filterNotNull().map { it.uppercase(java.util.Locale.ROOT) }.firstOrNull { it in java.util.Locale.getISOCountries() }
                DashboardProxyNode(tag, delay, countryCode = country, displayName = label ?: tag, protocol = protocol)
            }
            DashboardProxyGroup(name, item.optString("type"), item.optString("now"), nodes)
        }.toList()
        val confirmedSession = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { binder.selectorSessionId }
        check(confirmedSession == sessionId) { "Service session changed" }
        displayedSessionId = sessionId
        groups = refreshedGroups
        } finally { refreshLock.unlock() }
    }
    LaunchedEffect(connected, busy) {
        // A user operation owns the refresh until it completes; cancel the
        // polling read instead of stacking it behind the operation.
        if (!connected || busy) return@LaunchedEffect
        var retryMs = 5000L
        while (true) {
            try { refresh(); error = null; retryMs = 5000L }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                displayedSessionId = 0L
                groups = emptyList()
                error = "Local API unavailable; refresh failed"
                retryMs = (retryMs * 2).coerceAtMost(30000L)
            }
            kotlinx.coroutines.delay(retryMs)
        }
    }
    fun action(refreshAfter: Boolean = true, block: suspend () -> Unit) {
        if (busy || !connected) return
        busy = true
        scope.launch {
            try { block(); if (refreshAfter) refresh(); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message?.takeIf { it.startsWith("Delay test failed for ") } ?: "Proxy operation failed" }
            finally { busy = false }
        }
    }
    DashboardProxiesTab(connected, groups,
        onSelect = if (busy || displayedSessionId == 0L) null else { group, node ->
            val sessionId = displayedSessionId
            action(refreshAfter = false) {
                val binder = service() ?: error("Service unavailable")
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    binder.selectManagedOutbound(sessionId, group, node)
                }
                when (result) {
                    0 -> {
                        // Custom/raw selector groups have no application profile mapping.
                        check(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            binder.selectorSessionId == sessionId
                        }) { "Service session changed" }
                        dashboardRequest("proxies/" + android.net.Uri.encode(group), "PUT", org.json.JSONObject().put("name", node))
                        refresh()
                    }
                    1 -> refresh()
                    2 -> Unit // Reconnection recreates this tab with the new core state.
                    else -> {
                        displayedSessionId = 0L
                        refresh()
                        error("Selector rejected")
                    }
                }
            }
        },
        onTest = if (busy || displayedSessionId == 0L) null else { group ->
            val sessionId = displayedSessionId
            action(refreshAfter = false) {
                val binder = service() ?: error("Service unavailable")
                suspend fun verifySession() {
                    check(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        binder.selectorSessionId == sessionId
                    }) { "Service session changed" }
                }
                verifySession()
                val nodes = groups.firstOrNull { it.name == group }?.nodes.orEmpty()
                val tags = nodes.map { it.name }.distinct()
                val results = io.nekohasekai.sagernet.bg.proto.clashGroupDelay(group, tags)
                verifySession()
                // Refresh real history/selection from the core, then apply this test's result.
                refresh()
                verifySession()
                check(displayedSessionId == sessionId) { "Service session changed" }
                groups = groups.map { g -> g.copy(nodes = g.nodes.map { n ->
                    if (n.name in tags) n.copy(delay = results[n.name]) else n
                }) }
                val failed = tags.count { it !in results }
                if (failed > 0) error("Delay test failed for $failed server(s)")
            }
        })
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
