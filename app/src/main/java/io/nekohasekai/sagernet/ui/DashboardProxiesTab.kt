package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class DashboardProxyNode(val name: String, val delay: Int? = null, val countryCode: String? = null, val displayName: String = name, val protocol: String? = null, val historyKey: String = "", val failed: Boolean = false)
internal data class DashboardProxyGroup(val name: String, val type: String, val selected: String, val nodes: List<DashboardProxyNode>, val synthetic: Boolean = false) {
    val label: String get() = if (synthetic) "All outbounds" else name
}

@Composable
internal fun DashboardProxiesTab(
    connected: Boolean,
    groups: List<DashboardProxyGroup> = emptyList(),
    onSelect: ((String, String) -> Unit)? = null,
    onTest: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val group = groups.firstOrNull { it.name == selectedGroup } ?: groups.firstOrNull()
    val colors = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Proxy groups", style = MaterialTheme.typography.titleMedium)
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = .55f)), colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_public), contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.primary)
                            Text(group?.label ?: "No proxy groups", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(group?.let { when { it.synthetic -> "Read-only outbound overview"; it.type.equals("URLTest", true) -> "Automatic server selection"; it.type.equals("Fallback", true) -> "Automatic failover"; else -> "Proxy group" } }
                            ?: if (connected) "Proxy data unavailable" else "Service is not running",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (group != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text((if (group.synthetic) "All" else if (group.type.equals("URLTest", true) || group.type.equals("Fallback", true)) "Auto" else group.type) + " ▾") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                groups.forEach { item ->
                                    DropdownMenuItem(text = { Text(item.label) }, onClick = { selectedGroup = item.name; expanded = false })
                                }
                            }
                        }
                        ProxyDelay(group.nodes.firstOrNull { it.name == group.selected }?.delay, showLightning = true,
                            failed = group.nodes.firstOrNull { it.name == group.selected }?.failed == true)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(group?.let { "Servers (" + it.label + ")" } ?: "Servers", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { group?.let { onTest?.invoke(it.name) } }, enabled = connected && group != null && group.nodes.isNotEmpty() && onTest != null) {
                    Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_refresh), contentDescription = "Test server latency")
                }
            }
            if (group == null || group.nodes.isEmpty()) {
                Text("No servers available", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedCard(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(group.nodes, key = { _, node -> node.name }) { index, node ->
                            val active = node.name == group.selected
                            Surface(color = if (active) colors.secondaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                                border = if (active) BorderStroke(1.dp, colors.primary.copy(alpha = .5f)) else null) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp)
                                    .selectable(selected = active, enabled = connected && onSelect != null && !group.synthetic && group.type.equals("Selector", true),
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
                                    ProxyDelay(node.delay, failed = node.failed)
                                }
                            }
                            if (index < group.nodes.lastIndex) HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyDelay(delay: Int?, showLightning: Boolean = false, failed: Boolean = false) {
    val tint = when {
        failed -> MaterialTheme.colorScheme.error
        delay == null || delay <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        delay < 100 -> Color(0xFF81D99A)
        delay < 200 -> Color(0xFFE5B85C)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(color = tint.copy(alpha = .18f), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.defaultMinSize(minWidth = if (showLightning) 64.dp else 52.dp, minHeight = 24.dp).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
            if (showLightning && delay != null && delay > 0) Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_bolt), contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
            Text(if (failed) "Failed" else if (delay != null && delay > 0) "$delay ms" else "—", color = tint, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, softWrap = false)
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
    var metadataSession by remember(connected) { mutableStateOf(0L) }
    var metadata by remember(connected) { mutableStateOf(org.json.JSONObject()) }
    var outcomes by remember(connected) { mutableStateOf(emptyMap<String, DashboardProxyPolicy.Outcome>()) }
    suspend fun refresh() {
        refreshLock.lock()
        try {
        val binder = service() ?: error("Service unavailable")
        val sessionId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { binder.selectorSessionId }
        check(sessionId != 0L) { "Service not connected" }
        if (metadataSession != sessionId) {
            val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                binder.getDashboardProfiles(sessionId)
            }
            check(snapshot.isNotBlank()) { "Service session changed" }
            metadata = org.json.JSONObject(snapshot)
            metadataSession = sessionId
            outcomes = emptyMap()
        }
        val profiles = metadata.optJSONObject("profiles")
        val managedSelector = metadata.optBoolean("managedSelector")
        val proxies = dashboardGet("proxies").getJSONObject("proxies")
        val refreshedGroups = proxies.keys().asSequence().mapNotNull { name ->
            val item = proxies.optJSONObject(name) ?: return@mapNotNull null
            val all = item.optJSONArray("all") ?: return@mapNotNull null
            val nodes = (0 until all.length()).map { index ->
                val tag = all.getString(index)
                val nodeData = proxies.optJSONObject(tag)
                val history = nodeData?.optJSONArray("history")
                val last = history?.optJSONObject(history.length() - 1)
                val delay = last?.optInt("delay")?.takeIf { it > 0 }
                val key = last?.toString().orEmpty()
                val profile = profiles?.optJSONObject(tag)
                val label = profile?.optString("name")?.takeIf { it.isNotBlank() }
                val protocol = profile?.optString("protocol")?.takeIf { it.isNotBlank() }
                    ?: nodeData?.optString("type")?.takeIf { it.isNotBlank() }
                val country = sequenceOf(nodeData?.optString("countryCode"), nodeData?.optString("country"))
                    .filterNotNull().map { it.uppercase(java.util.Locale.ROOT) }.firstOrNull { it in java.util.Locale.getISOCountries() }
                val outcome = outcomes[tag]?.takeIf { it.historyKey == key }
                DashboardProxyNode(tag, if (outcome != null) outcome.delay else delay, countryCode = country,
                    displayName = label ?: tag, protocol = protocol, historyKey = key,
                    failed = outcome != null && outcome.delay == null)
            }
            DashboardProxyGroup(name, item.optString("type"), item.optString("now"), nodes.distinctBy { it.name },
                synthetic = name == "GLOBAL" && item.optString("type").equals("Fallback", true))
        }.sortedBy { DashboardProxyPolicy.priority(it.name, it.synthetic, managedSelector) }.toList()
        val confirmedSession = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { binder.selectorSessionId }
        check(service() === binder && confirmedSession == sessionId) { "Service session changed" }
        outcomes = outcomes.filter { (tag, outcome) ->
            refreshedGroups.any { group -> group.nodes.any { it.name == tag && it.historyKey == outcome.historyKey } }
        }
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
                metadataSession = 0L
                outcomes = emptyMap()
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
    Column(Modifier.fillMaxSize()) {
    DashboardProxiesTab(connected, groups, modifier = Modifier.weight(1f),
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
                    2 -> {
                        displayedSessionId = 0L
                        groups = emptyList()
                        outcomes = emptyMap()
                    } // Wait for the new ROOT/VPN session; never show an optimistic selection.
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
                    check(service() === binder && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        binder.selectorSessionId == sessionId
                    }) { "Service session changed" }
                }
                verifySession()
                val testedGroup = groups.firstOrNull { it.name == group } ?: error("Proxy group unavailable")
                val tags = testedGroup.nodes.map { it.name }.distinct()
                val results = if (testedGroup.synthetic) {
                    // GLOBAL is an overview, not a real group endpoint in sing-box.
                    io.nekohasekai.sagernet.bg.proto.clashOutboundDelays(tags, verifySession = { verifySession() })
                } else {
                    io.nekohasekai.sagernet.bg.proto.clashGroupDelay(group, tags)
                }
                verifySession()
                // Refresh real history/selection from the core, then apply this test's result.
                refresh()
                verifySession()
                check(displayedSessionId == sessionId) { "Service session changed" }
                val byTag = groups.flatMap { it.nodes }.associateBy { it.name }
                outcomes = outcomes + tags.mapNotNull { tag ->
                    byTag[tag]?.let { tag to DashboardProxyPolicy.Outcome(results[tag], it.historyKey) }
                }.toMap()
                groups = groups.map { g -> g.copy(nodes = g.nodes.map { n ->
                    if (n.name in tags) n.copy(delay = results[n.name], failed = results[n.name] == null) else n
                }) }
                val failed = tags.count { it !in results }
                if (failed > 0) error("Delay test failed for $failed server(s)")
            }
        })
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
