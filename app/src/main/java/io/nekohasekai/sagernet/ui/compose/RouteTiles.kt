package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R

data class RouteProfileTile(val id: String, val name: String, val active: Boolean, val outbound: String, val count: Int)

fun installRouteProfiles(host: ComposeView, profiles: List<RouteProfileTile>, create: () -> Unit,
    select: (String) -> Unit, edit: () -> Unit, rename: (String) -> Unit, delete: (String) -> Unit) {
    host.setContent {
        HubTheme {
            val colors = MaterialTheme.colorScheme
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = create, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("+ New profile") }
                profiles.forEach { profile ->
                    key(profile.id) {
                        var menu by remember { mutableStateOf(false) }
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor =
                            if (profile.active) lerp(colors.surface, colors.primary, .20f) else lerp(colors.surface, colors.onSurface, .07f))) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(16.dp), color = colors.secondaryContainer) {
                                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                            Icon(painterResource(R.drawable.ic_hardware_router), null, tint = colors.onSecondaryContainer)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(profile.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                        if (profile.active) Text("✓ Active", color = colors.primary, style = MaterialTheme.typography.labelMedium)
                                    }
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_baseline_more_vert_24), "More") }
                                        DropdownMenu(menu, { menu = false }) {
                                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename(profile.id) })
                                            if (profile.id != "default") DropdownMenuItem(text = { Text("Delete", color = colors.error) }, onClick = { menu = false; delete(profile.id) })
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = colors.surface.copy(alpha = .75f)) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(stringResource(R.string.route_outbound), style = MaterialTheme.typography.labelSmall)
                                            Text(profile.outbound, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = colors.surface.copy(alpha = .75f)) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("Rules", style = MaterialTheme.typography.labelSmall)
                                            Text(profile.count.toString(), fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                                if (profile.active) Button(onClick = edit, modifier = Modifier.fillMaxWidth()) { Text("Edit rules") }
                                else OutlinedButton(onClick = { select(profile.id) }, modifier = Modifier.fillMaxWidth()) { Text("Select") }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun installRouteSettingsTiles(host: ComposeView, outbound: String, dns: String,
    onOutbound: () -> Unit, onDns: () -> Unit, onAssets: () -> Unit) {
    host.setContent {
        HubTheme {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RouteSettingTile(Modifier.weight(1f), stringResource(R.string.route_outbound), outbound, R.drawable.ic_hardware_router, onOutbound)
                    RouteSettingTile(Modifier.weight(1f), stringResource(R.string.route_dns_tunnel), dns, R.drawable.ic_hardware_router, onDns)
                }
                RouteSettingTile(Modifier.fillMaxWidth(), "Assets", "", R.drawable.ic_baseline_layers_24, onAssets)
            }
        }
    }
}

@Composable private fun RouteSettingTile(modifier: Modifier, title: String, value: String, icon: Int, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(onClick = action, modifier = modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(icon), null, tint = colors.onSecondaryContainer)
            Text(title, color = colors.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
            if (value.isNotEmpty()) Text(value, color = colors.onSecondaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
    }
}

@Composable fun RuleTile(name: String, summary: String, outbound: String, enabled: Boolean, toggle: (Boolean) -> Unit, edit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        outbound.equals("direct", true) -> androidx.compose.ui.graphics.Color(0xFF42865A)
        outbound.equals("block", true) -> colors.error
        else -> colors.primary
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = lerp(colors.surface, colors.onSurface, .07f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = .14f)) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_baseline_layers_24), null, tint = tint)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(enabled, toggle)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = tint.copy(alpha = .14f)) {
                    Text(outbound, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = colors.onSurface, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = edit) { Icon(painterResource(R.drawable.ic_image_edit), stringResource(R.string.edit)) }
            }
        }
    }
}
