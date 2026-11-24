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
                        IconButton(onClick = { activity.onBackPressed() }) { Icon(painterResource(R.drawable.ic_navigation_close), stringResource(android.R.string.cancel)) }
                        Text(stringResource(R.string.cag_route), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (DataStore.editingId != 0L) TextButton(onClick = { activity.editorDelete() }) { Text("Delete") }
                        IconButton(onClick = { activity.editorSave() }) { Text("✓", style = MaterialTheme.typography.headlineSmall) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RouteField(stringResource(R.string.route_name), DataStore.routeName) { DataStore.routeName = it }
                        RouteField(stringResource(R.string.custom_config), DataStore.serverConfig) { DataStore.serverConfig = it }
                        Text(stringResource(R.string.cag_route), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                        key(revision) {
                            OutlinedButton(onClick = { activity.selectAppList.launch(Intent(activity, AppListActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.apps) + " · " + DataStore.routePackages.lines().count { it.isNotBlank() })
                            }
                            RouteField("domain", DataStore.routeDomain) { DataStore.routeDomain = it }
                            Button(onClick = { activity.showInstalledSrs() }, modifier = Modifier.fillMaxWidth()) { Text("Choose installed / online SRS") }
                            RouteField("dst ip", DataStore.routeIP) { DataStore.routeIP = it }
                            RouteField("dst port", DataStore.routePort) { DataStore.routePort = it }
                            RouteField("src ip", DataStore.routeSource) { DataStore.routeSource = it }
                            RouteField("src port", DataStore.routeSourcePort) { DataStore.routeSourcePort = it }
                            RouteChoice("network", activity.resources.getStringArray(R.array.route_protocol_entry), activity.resources.getStringArray(R.array.route_protocol_value), DataStore.routeNetwork) {
                                DataStore.routeNetwork = it; activity.refreshEditor()
                            }
                            RouteField("protocol", DataStore.routeProtocol) { DataStore.routeProtocol = it }
                            RouteChoice("outbound", activity.resources.getStringArray(R.array.outbound_entry), activity.resources.getStringArray(R.array.outbound_value), DataStore.routeOutbound.toString()) {
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

@Composable private fun RouteField(label: String, initial: String, change: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    OutlinedTextField(value, { value = it; change(it) }, Modifier.fillMaxWidth(), label = { Text(label) })
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
