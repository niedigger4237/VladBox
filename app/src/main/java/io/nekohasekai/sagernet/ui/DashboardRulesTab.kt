package io.nekohasekai.sagernet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class DashboardRule(val type: String, val payload: String, val target: String, val order: Int = 0)

@Composable
internal fun DashboardRulesTab(connected: Boolean, rules: List<DashboardRule> = emptyList(), status: String? = null, searchOpen: Boolean = false, onSearchChange: (Boolean) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var detail by remember(connected, rules) { mutableStateOf<DashboardRule?>(null) }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(searchOpen) { if (!searchOpen) query = "" }
    detail?.let { rule ->
        AlertDialog(onDismissRequest = { detail = null },
            title = { Text("#${rule.order} · ${ruleLabel(rule)}") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Type: " + rule.type)
                    Text("Conditions: " + rule.payload.ifBlank { "No conditions reported" })
                    Text("Action: " + ruleAction(rule.target))
                    Text("Core action: " + rule.target.ifBlank { "—" })
                }
            }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } })
    }
    val filtered = remember(rules, query) {
        rules.filter { "${it.order} ${ruleLabel(it)} ${it.type} ${it.payload} ${ruleAction(it.target)} ${it.target}".contains(query, ignoreCase = true) }
    }
    Card(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = .45f))) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Routing rules" + if (rules.isNotEmpty()) " (${rules.size})" else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { onSearchChange(!searchOpen) }) {
                    Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_search), contentDescription = "Search rules")
                }
            }
            if (searchOpen) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Search rules") })
            }
            if (filtered.isEmpty()) {
                Text(if (!connected) "Service is not running" else if (rules.isEmpty()) status ?: "No routing rules returned" else "No matching rules",
                    Modifier.padding(vertical = 12.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Text("Core routing rules · evaluation order", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              itemsIndexed(filtered, key = { index, rule -> if (rule.order > 0) rule.order else -index - 1 }) { _, rule ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { detail = rule }, border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = .55f)), shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuleTypeIcon(ruleLabel(rule))
                        Column(Modifier.weight(1f)) {
                            Text((if (rule.order > 0) "#${rule.order} · " else "") + ruleLabel(rule), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rule.payload.ifBlank { if (ruleLabel(rule) == "Final") "All remaining traffic" else "—" }, style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_arrow_forward), contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        val targetColor = when (DashboardRulePolicy.action(rule.target).kind) {
                            DashboardRulePolicy.Kind.DIRECT -> Color(0xFF81D99A)
                            DashboardRulePolicy.Kind.BLOCK -> colors.error
                            DashboardRulePolicy.Kind.OTHER -> colors.onSurfaceVariant
                            else -> colors.primary
                        }
                        Surface(shape = RoundedCornerShape(20.dp), color = targetColor.copy(alpha = .15f)) {
                            Text(ruleAction(rule.target), Modifier.padding(horizontal = 7.dp, vertical = 3.dp).widthIn(max = 100.dp), color = targetColor,
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
              }
            }
        }
    }
}

@Composable
private fun RuleTypeIcon(type: String) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val normalized = type.uppercase(java.util.Locale.ROOT)
    val symbol = when {
        normalized.contains("IP") -> io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_lan
        normalized.contains("PROTOCOL") || normalized.contains("PORT") -> io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_dns
        normalized.contains("RULE SET") -> io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_folder
        normalized.contains("INBOUND") -> io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_shield
        else -> null
    }
    if (symbol != null) {
        Icon(androidx.compose.ui.res.painterResource(symbol), contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
    } else if (normalized == "MATCH" || normalized == "FINAL") {
        Text("{}", Modifier.width(24.dp), color = tint, style = MaterialTheme.typography.titleMedium)
    } else {
        Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_public), contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
    }
}


@Composable
internal fun DashboardLiveRules(connected: Boolean, searchOpen: Boolean = false, onSearchChange: (Boolean) -> Unit = {},
    service: () -> io.nekohasekai.sagernet.aidl.ISagerNetService?) {
    var rules by remember(connected) { mutableStateOf(emptyList<DashboardRule>()) }
    var status by remember(connected) { mutableStateOf<String?>("Loading…") }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        var retryMs = 5000L
        while (true) {
            try {
                rules = dashboardSessionRead(service) {
                    val response = dashboardGet("rules")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        // Go encodes an empty nil slice as JSON null.
                        check(response.has("rules")) { "Missing rules snapshot" }
                        val array = response.optJSONArray("rules")
                        if (array == null) {
                            check(response.isNull("rules")) { "Invalid rules snapshot" }
                            emptyList()
                        } else (0 until array.length()).mapNotNull { index ->
                            val item = array.optJSONObject(index) ?: return@mapNotNull null
                            DashboardRule(item.optString("type"), item.optString("payload"), item.optString("proxy"), index + 1)
                        }
                    }
                }
                status = null
                retryMs = 30000L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never leave old rules visible after a possible session change.
                rules = emptyList()
                status = "Rules unavailable from local API"
                retryMs = (retryMs * 2).coerceIn(5000L, 30000L)
            }
            kotlinx.coroutines.delay(retryMs)
        }
    }
    DashboardRulesTab(connected, rules, status, searchOpen, onSearchChange)
}

private fun ruleLabel(rule: DashboardRule): String = DashboardRulePolicy.label(rule.type, rule.payload)

private fun ruleAction(target: String): String = DashboardRulePolicy.action(target).label
