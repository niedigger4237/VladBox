package io.nekohasekai.sagernet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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

internal data class DashboardRule(val type: String, val payload: String, val target: String)

@Composable
internal fun DashboardRulesTab(connected: Boolean, rules: List<DashboardRule> = emptyList(), status: String? = null) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val filtered = rules.filter { "${it.type} ${it.payload} ${it.target}".contains(query, ignoreCase = true) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Routing rules" + if (rules.isNotEmpty()) " (${rules.size})" else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                    val tint = colors.onSurface
                    Canvas(Modifier.size(22.dp)) {
                        drawCircle(tint, radius = size.width * .28f, center = Offset(size.width * .4f, size.height * .4f), style = Stroke(2.dp.toPx()))
                        drawLine(tint, Offset(size.width * .61f, size.height * .61f), Offset(size.width * .87f, size.height * .87f), 2.dp.toPx())
                    }
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
            filtered.forEach { rule ->
                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RuleTypeIcon(rule.type)
                        Column(Modifier.weight(1f)) {
                            Text(rule.type, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rule.payload.ifBlank { "*" }, style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("→", color = colors.onSurfaceVariant)
                        val targetColor = when (rule.target.lowercase(java.util.Locale.ROOT)) {
                            "direct", "bypass" -> Color(0xFF81D99A)
                            "block", "reject" -> colors.error
                            else -> colors.primary
                        }
                        Text(rule.target, Modifier.widthIn(max = 100.dp), color = targetColor,
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    if (normalized == "MATCH" || normalized == "FINAL") {
        Text("{}", Modifier.width(24.dp), color = tint, style = MaterialTheme.typography.titleMedium)
    } else {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 1.5.dp.toPx()
            if (normalized.contains("CIDR") || normalized.startsWith("IP")) {
                drawRoundRect(tint, topLeft = Offset(size.width * .1f, size.height * .22f),
                    size = androidx.compose.ui.geometry.Size(size.width * .8f, size.height * .56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(stroke))
                for (i in 0..2) drawCircle(tint, stroke, Offset(size.width * (.3f + i * .2f), size.height * .5f))
            } else {
                drawCircle(tint, radius = size.width * .42f, style = Stroke(stroke))
                drawOval(tint, topLeft = Offset(size.width * .29f, size.height * .08f),
                    size = androidx.compose.ui.geometry.Size(size.width * .42f, size.height * .84f), style = Stroke(stroke))
                drawLine(tint, Offset(size.width * .09f, size.height * .5f), Offset(size.width * .91f, size.height * .5f), stroke)
            }
        }
    }
}


@Composable
internal fun DashboardLiveRules(connected: Boolean) {
    var rules by remember(connected) { mutableStateOf(emptyList<DashboardRule>()) }
    var status by remember(connected) { mutableStateOf<String?>("Loading…") }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        while (true) {
            try {
                val array = dashboardGet("rules").getJSONArray("rules")
                rules = (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    DashboardRule(item.optString("type"), item.optString("payload"), item.optString("proxy"))
                }
                status = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                rules = emptyList()
                status = "Rules unavailable from local API"
            }
            kotlinx.coroutines.delay(5000)
        }
    }
    DashboardRulesTab(connected, rules, status)
}
