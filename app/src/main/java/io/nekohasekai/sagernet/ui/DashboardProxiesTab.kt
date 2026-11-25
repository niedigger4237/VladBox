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
                            Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_lan), contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.primary)
                            Text(group?.name ?: "No proxy groups", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(group?.let { if (it.type.equals("URLTest", true)) "Automatic server selection" else "Proxy group" }
                            ?: if (connected) "Proxy data unavailable" else "Service is not running",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (group != null) {
                        Column(horizontalAlignment = Alignment.End) {
                        Box {
                            TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text((if (group.type.equals("URLTest", true)) "Auto" else group.type) + " ▾") }
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
                    Text("↻", style = MaterialTheme.typography.headlineSmall)
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
        Text(if (delay != null && delay > 0) (if (showLightning) "ϟ " else "") + "$delay ms" else "—", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

private fun proxyFlag(countryCode: String?): String {
    val code = countryCode?.uppercase(java.util.Locale.ROOT) ?: return ""
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
    return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}


@Composable
internal fun DashboardLiveProxies(connected: Boolean) {
    var groups by remember(connected) { mutableStateOf(emptyList<DashboardProxyGroup>()) }
    var error by remember(connected) { mutableStateOf<String?>(null) }
    val measured = remember(connected) { mutableStateMapOf<String, Int>() }
    var busy by remember(connected) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        val profile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val id = io.nekohasekai.sagernet.database.DataStore.currentProfile
            if (id > 0) io.nekohasekai.sagernet.database.SagerDatabase.proxyDao.getById(id) else null
        }
        val proxies = dashboardGet("proxies").getJSONObject("proxies")
        groups = proxies.keys().asSequence().mapNotNull { name ->
            val item = proxies.optJSONObject(name) ?: return@mapNotNull null
            val all = item.optJSONArray("all") ?: return@mapNotNull null
            val nodes = (0 until all.length()).map { index ->
                val tag = all.getString(index)
                val history = proxies.optJSONObject(tag)?.optJSONArray("history")
                val delay = history?.optJSONObject(history.length() - 1)?.optInt("delay")?.takeIf { it > 0 }
                val isMain = tag == io.nekohasekai.sagernet.fmt.TAG_PROXY
                val label = if (isMain) profile?.displayName()?.takeIf { it.isNotBlank() } else null
                val protocol = if (isMain) profile?.displayType() else proxies.optJSONObject(tag)?.optString("type")?.takeIf { it.isNotBlank() }
                DashboardProxyNode(tag, measured[tag] ?: delay, displayName = label ?: tag, protocol = protocol)
            }
            DashboardProxyGroup(name, item.optString("type"), item.optString("now"), nodes)
        }.toList()
    }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        while (true) {
            if (!busy) {
                try { refresh(); error = null }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { groups = emptyList(); error = "Local API unavailable" }
            }
            kotlinx.coroutines.delay(5000)
        }
    }
    fun action(block: suspend () -> Unit) {
        if (busy || !connected) return
        busy = true
        scope.launch {
            try { block(); refresh(); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message?.takeIf { it.startsWith("Delay test failed for ") } ?: "Proxy operation failed" }
            finally { busy = false }
        }
    }
    DashboardProxiesTab(connected, groups,
        onSelect = if (busy) null else { group, node ->
            action { dashboardRequest("proxies/" + android.net.Uri.encode(group), "PUT", org.json.JSONObject().put("name", node)) }
        },
        onTest = if (busy) null else { group ->
            action {
                val nodes = groups.firstOrNull { it.name == group }?.nodes.orEmpty()
                val failed = mutableListOf<String>()
                for (node in nodes) {
                    try {
                        val result = dashboardGet("proxies/" + android.net.Uri.encode(node.name) + "/delay?timeout=5000&url=" +
                            android.net.Uri.encode("https://www.gstatic.com/generate_204"))
                        val ms = result.optInt("delay")
                        if (ms > 0) measured[node.name] = ms else { measured.remove(node.name); failed.add(node.name) }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { measured.remove(node.name); failed.add(node.name) }
                }
                if (failed.isNotEmpty()) {
                    groups = groups.map { g -> g.copy(nodes = g.nodes.map { n -> if (n.name in failed) n.copy(delay = null) else n }) }
                    error("Delay test failed for " + failed.size + " server(s)")
                }
            }
        })
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
