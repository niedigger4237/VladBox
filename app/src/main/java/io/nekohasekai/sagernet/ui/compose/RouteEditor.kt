package io.nekohasekai.sagernet.ui.compose

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.RouteSettingsActivity
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.AppListActivity

fun installRouteEditor(activity: RouteSettingsActivity, host: ComposeView) {
    host.setContent {
        HubTheme {
            val revision by activity.editorRevision.collectAsState()
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                    Row(Modifier.height(56.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { activity.onBackPressedDispatcher.onBackPressed() }) { Icon(painterResource(R.drawable.ic_navigation_close), stringResource(android.R.string.cancel)) }
                        Text(stringResource(R.string.cag_route), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (DataStore.editingId != 0L) TextButton(onClick = { activity.editorDelete() }) { Text("Delete") }
                        IconButton(onClick = { activity.editorSave() }) { Text("✓", style = MaterialTheme.typography.headlineSmall) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RouteField("Rule name", DataStore.routeName) { DataStore.routeName = it }
                        var custom by remember { mutableStateOf(DataStore.serverConfig) }
                        OutlinedTextField(custom, { custom = it; DataStore.serverConfig = it },
                            Modifier.fillMaxWidth(), label = { Text("Custom configuration") })
                        AdvancedRouteConditions(custom) { custom = it; DataStore.serverConfig = it }
                        Text(stringResource(R.string.cag_route), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                        key(revision) {
                            OutlinedButton(onClick = { activity.selectAppList.launch(Intent(activity, AppListActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Applications" + " · " + DataStore.routePackages.lines().count { it.isNotBlank() })
                            }
                            RouteField("Domain names", DataStore.routeDomain, "Domains, suffixes, regular expressions or geosite selectors") { DataStore.routeDomain = it }
                            Button(onClick = { activity.showInstalledSrs() }, modifier = Modifier.fillMaxWidth()) { Text("Select SRS rule sets") }
                            RouteField("Destination IP addresses", DataStore.routeIP, "IP addresses, CIDR ranges or geoip selectors") { DataStore.routeIP = it }
                            RouteField("Destination ports", DataStore.routePort, "Remote service ports or port ranges") { DataStore.routePort = it }
                            RouteField("Source IP addresses", DataStore.routeSource, "Client IP addresses or CIDR ranges") { DataStore.routeSource = it }
                            RouteField("Source ports", DataStore.routeSourcePort, "Client ports or port ranges") { DataStore.routeSourcePort = it }
                            RouteChoice("Transport protocol", arrayOf("Any", "TCP", "UDP", "ICMP (VPN / ROOT TUN)"),
                                arrayOf("", "tcp", "udp", "icmp"), DataStore.routeNetwork) {
                                DataStore.routeNetwork = it; activity.refreshEditor()
                            }
                            RouteField("Protocol", DataStore.routeProtocol, "Application-layer protocol") { DataStore.routeProtocol = it }
                            RouteChoice("Outbound action", activity.resources.getStringArray(R.array.outbound_entry), activity.resources.getStringArray(R.array.outbound_value), DataStore.routeOutbound.toString()) {
                                if (it == "3") activity.selectProfileForAdd.launch(Intent(activity, ProfileSelectActivity::class.java))
                                else { it.toIntOrNull()?.let { value -> DataStore.routeOutbound = value }; activity.refreshEditor() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun RouteField(label: String, initial: String, hint: String? = null, change: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    OutlinedTextField(value, { value = it; change(it) }, Modifier.fillMaxWidth(), label = { Text(label) }, supportingText = { hint?.let { Text(it) } })
}

@Composable private fun RouteChoice(label: String, entries: Array<String>, values: Array<String>, selected: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(entries.getOrNull(values.indexOf(selected)) ?: selected) }
            DropdownMenu(expanded, { expanded = false }) {
                entries.forEachIndexed { index, entry ->
                    DropdownMenuItem(text = { Text(entry) }, onClick = { expanded = false; values.getOrNull(index)?.let(change) })
                }
            }
        }
    }
}

@Composable private fun AdvancedRouteConditions(config: String, change: (String) -> Unit) {
    var draft by remember { mutableStateOf<io.nekohasekai.sagernet.fmt.RouteAdvancedConditions.Fields?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val active = io.nekohasekai.sagernet.fmt.RouteAdvancedConditions.hasConditions(config)
    OutlinedButton(onClick = {
        try {
            draft = io.nekohasekai.sagernet.fmt.RouteAdvancedConditions.read(config)
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Invalid custom configuration"
        }
    }, modifier = Modifier.fillMaxWidth()) { Text(if (active) "Advanced conditions · configured" else "Advanced conditions") }
    if (draft == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    draft?.let { fields ->
        AlertDialog(onDismissRequest = { draft = null; error = null },
            title = { Text("Advanced conditions") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RouteChoice("IP version", arrayOf("Any", "IPv4", "IPv6"), arrayOf("", "4", "6"), fields.ipVersion) {
                        draft = fields.copy(ipVersion = it)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Private destination IP", Modifier.weight(1f))
                        Switch(fields.destinationPrivate, { draft = fields.copy(destinationPrivate = it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Private source IP", Modifier.weight(1f))
                        Switch(fields.sourcePrivate, { draft = fields.copy(sourcePrivate = it) })
                    }
                    OutlinedTextField(fields.packageRegex, { draft = fields.copy(packageRegex = it) },
                        label = { Text("Package name regex") },
                        supportingText = { Text("One expression per line. Ordinary VPN only; the rule is paused in ROOT modes.") })
                    Text("Advanced traffic conditions do not generate DNS rules. Use DNS Rules for separate DNS policy.",
                        style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        change(io.nekohasekai.sagernet.fmt.RouteAdvancedConditions.update(config, fields))
                        draft = null; error = null
                    } catch (e: Exception) { error = e.message ?: "Invalid condition" }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { draft = null; error = null }) { Text("Cancel") } })
    }
}
