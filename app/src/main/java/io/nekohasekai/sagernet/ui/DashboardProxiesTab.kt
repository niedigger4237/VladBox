package io.nekohasekai.sagernet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class DashboardProxyNode(val name: String, val delay: Int? = null)
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
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Proxy groups", style = MaterialTheme.typography.titleMedium)
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🌐  " + (group?.name ?: "No proxy groups"), style = MaterialTheme.typography.titleSmall)
                        Text(group?.let { if (it.type.equals("URLTest", true)) "Automatic server selection" else it.type }
                            ?: if (connected) "Proxy data unavailable" else "Service is not running",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (group != null) {
                        Box {
                            TextButton(onClick = { expanded = true }) { Text(group.type + " ▾") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                groups.forEach { item ->
                                    DropdownMenuItem(text = { Text(item.name) }, onClick = { selectedGroup = item.name; expanded = false })
                                }
                            }
                        }
                        ProxyDelay(group.nodes.firstOrNull { it.name == group.selected }?.delay)
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
                            Surface(color = if (active) colors.secondaryContainer else colors.surface,
                                shape = RoundedCornerShape(10.dp),
                                border = if (active) BorderStroke(1.dp, colors.primary.copy(alpha = .5f)) else null) {
                                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = active, enabled = connected && onSelect != null,
                                        onClick = { onSelect?.invoke(group.name, node.name) })
                                    Text(node.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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
private fun ProxyDelay(delay: Int?) {
    val tint = when {
        delay == null || delay <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        delay < 100 -> Color(0xFF81D99A)
        delay < 200 -> Color(0xFFE5B85C)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(color = tint.copy(alpha = .18f), shape = RoundedCornerShape(20.dp)) {
        Text(if (delay != null && delay > 0) "$delay ms" else "—", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = tint, style = MaterialTheme.typography.labelMedium)
    }
}
