package io.nekohasekai.sagernet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
internal fun DashboardRulesTab(connected: Boolean, rules: List<DashboardRule> = emptyList(), status: String? = null, searchOpen: Boolean = false, onSearchChange: (Boolean) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<DashboardRule?>(null) }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(searchOpen) { if (!searchOpen) query = "" }
    detail?.let { rule -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(ruleLabel(rule)) }, text = { Text(rule.payload + "\n\n" + rule.target) }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } }) }
    val filtered = rules.filter { "${ruleLabel(it)} ${it.type} ${it.payload} ${it.target}".contains(query, ignoreCase = true) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            filtered.forEach { rule ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { detail = rule }, border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = .55f)), shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant.copy(alpha = .35f))) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuleTypeIcon(ruleLabel(rule))
                        Column(Modifier.weight(1f)) {
                            Text(ruleLabel(rule), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rule.payload.ifBlank { if (ruleLabel(rule) == "Final") "All remaining traffic" else "—" }, style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_dash_symbol_arrow_forward), contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        val targetColor = when (rule.target.lowercase(java.util.Locale.ROOT)) {
                            "direct", "bypass", "route(bypass)", "route(direct)" -> Color(0xFF81D99A)
                            "block", "reject" -> colors.error
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
internal fun DashboardLiveRules(connected: Boolean, searchOpen: Boolean = false, onSearchChange: (Boolean) -> Unit = {}) {
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
    DashboardRulesTab(connected, rules, status, searchOpen, onSearchChange)
}

private fun ruleLabel(rule: DashboardRule): String {
    val type = rule.type.lowercase(java.util.Locale.ROOT).replace("-", "").replace("_", "")
    val payload = rule.payload.lowercase(java.util.Locale.ROOT)
    return when (type) {
        "match", "final" -> "Final"
        "domain" -> "Domain"
        "domainsuffix" -> "Domain suffix"
        "domainkeyword" -> "Domain keyword"
        "domainregex" -> "Domain regex"
        "ipcidr", "ipcidr6" -> "IP CIDR"
        "geoip" -> "GeoIP"
        "geosite" -> "Geosite"
        "ruleset" -> "Rule set"
        "default" -> when {
            payload.startsWith("inbound=") -> "Inbound"
            payload.startsWith("protocol=") -> "Protocol"
            payload.startsWith("port=") -> "Destination port"
            payload.startsWith("ip_is_private=") -> "Private IP / Rule set"
            payload.startsWith("source_ip_cidr=") -> "Source / Destination IP"
            payload.startsWith("rule_set=") || payload.startsWith("rule_set:") -> "Rule set"
            payload.startsWith("domain_suffix=") || payload.startsWith("domain_suffix:") -> "Domain suffix"
            payload.startsWith("domain_keyword=") || payload.startsWith("domain_keyword:") -> "Domain keyword"
            payload.startsWith("domain_regex=") || payload.startsWith("domain_regex:") -> "Domain regex"
            payload.startsWith("domain=") || payload.startsWith("domain:") -> "Domain"
            payload.startsWith("ip_cidr=") || payload.startsWith("ip_cidr:") -> "IP CIDR"
            payload.startsWith("process_name=") || payload.startsWith("package_name=") -> "Application"
            else -> "Routing rule"
        }
        else -> rule.type.ifBlank { "Routing rule" }
    }
}

private fun ruleAction(target: String): String = when (target.lowercase(java.util.Locale.ROOT)) {
    "route(bypass)", "route(direct)", "bypass", "direct" -> "Direct"
    "reject", "block" -> "Block"
    "hijack-dns" -> "DNS Hijack"
    "sniff" -> "Sniff"
    "route(proxy)", "proxy" -> "Proxy"
    else -> target.ifBlank { "—" }
}
