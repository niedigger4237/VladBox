package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.preference.*
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.root.RootShell.Access
import androidx.compose.ui.res.stringResource

fun installSettingsScreen(host: ComposeView, screen: PreferenceScreen, model: SettingsViewModel, changed: () -> Unit) {
    host.setContent {
        HubTheme {
            val state by model.state.collectAsState()
            val category = state.category
            val query = state.query
            val mode = screen.findPreference<ListPreference>("serviceMode")
            val modeValue = remember(state.revision) { mode?.value ?: "vpn" }
            fun isShown(pref: Preference) = pref.isVisible && settingVisible(pref.key, modeValue, state.rootAccess)
            val groups = remember(state.revision, state.rootAccess) {
                (0 until screen.preferenceCount).mapNotNull { screen.getPreference(it) as? PreferenceCategory }
                    .filter { group -> (0 until group.preferenceCount).any { isShown(group.getPreference(it)) } }
            }
            LaunchedEffect(category, groups) {
                if (category != null && groups.none { it.title.toString() == category }) model.select(null)
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                if (category != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { model.select(null) }) { Text("‹") }
                        Text(groups.firstOrNull { it.title.toString() == category }?.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    OutlinedTextField(query, { model.search(it) }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Search settings") }, singleLine = true)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (category == null && query.isBlank()) {
                        if (mode != null) item { SettingTile(mode, changed, true, state.revision, state.rootAccess, state.checkingRoot) }
                        items(groups.size) { index ->
                            val group = groups[index]
                            Card(Modifier.fillMaxWidth().clickable { model.select(group.title.toString()) }, shape = RoundedCornerShape(22.dp)) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                            Icon(painterResource(R.drawable.ic_action_settings), null)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(group.title.toString(), style = MaterialTheme.typography.titleMedium)
                                        Text((0 until group.preferenceCount).map { group.getPreference(it) }.filter { isShown(it) }.take(3).joinToString(" · ") { it.title.toString() }, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("›", style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    } else {
                        val visible = groups.filter { category == null || category == it.title.toString() }.flatMap { group ->
                            (0 until group.preferenceCount).map { group.getPreference(it) }
                        }.filter { isShown(it) && (query.isBlank() || it.title.toString().contains(query, true) || it.key.orEmpty().contains(query, true) || it.summary.toString().contains(query, true)) }
                        items(visible, key = { it.key }) { pref -> SettingTile(pref, changed, revision = state.revision, rootAccess = state.rootAccess, checkingRoot = state.checkingRoot) }
                    }
                }
            }
        }
    }
}

@Composable private fun SettingTile(
    pref: Preference, changed: () -> Unit, featured: Boolean = false, revision: Int = 0,
    rootAccess: Access = Access.UNKNOWN, checkingRoot: Boolean = false,
) {
    var expanded by remember(pref.key) { mutableStateOf(false) }
    var edit by remember(pref.key) { mutableStateOf(false) }
    var extra by remember(pref.key) { mutableStateOf(false) }
    var draft by remember(pref.key) { mutableStateOf("") }
    var concurrent by remember(pref.key) { mutableStateOf("") }
    val checked = remember(revision, pref.key) { (pref as? TwoStatePreference)?.isChecked ?: false }
    val data = io.nekohasekai.sagernet.database.DataStore
    if (edit && pref.key == "rootTunBypassRuleSets") {
        val available = remember {
            io.nekohasekai.sagernet.SagerNet.application.externalAssets.listFiles().orEmpty()
                .filter { it.isFile && it.length() > 0 && it.name.matches(Regex("geoip-[A-Za-z0-9._!-]+\\.srs")) }
                .map { "geoip:" + it.name.removePrefix("geoip-").removeSuffix(".srs") }.sorted()
        }
        var selected by remember { mutableStateOf(draft.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.toSet()) }
        AlertDialog(onDismissRequest = { edit = false }, title = { Text(pref.title.toString()) },
            text = {
                Column {
                    Text(androidx.compose.ui.res.stringResource(R.string.root_tun_bypass_rulesets_sum))
                    if (available.isEmpty()) Text(androidx.compose.ui.res.stringResource(R.string.root_tun_bypass_empty))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items((available + selected).distinct().sorted()) { tag ->
                            val enabled = tag in available
                            Row(Modifier.fillMaxWidth().clickable(enabled = enabled || tag in selected) {
                                selected = if (tag in selected) selected - tag else selected + tag
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(tag in selected, onCheckedChange = { checked ->
                                    selected = if (checked) selected + tag else selected - tag
                                }, enabled = enabled || tag in selected)
                                Text(tag + if (enabled) "" else " (missing)")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = selected.all { it in available }, onClick = {
                    val value = selected.sorted().joinToString("\n")
                    if (pref.callChangeListener(value)) (pref as EditTextPreference).text = value
                    edit = false; changed()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } })
    } else if (edit || extra) {
        val port = pref.key in setOf("mixedPort", "socksPort", "rootTproxyPort", "bpfBridgePort")
        val valid = when {
            extra && pref.key == "mtu" -> (draft.toIntOrNull() ?: 0) in 1000..10000
            extra && pref.key == "logLevel" -> (draft.toIntOrNull() ?: 0) > 0
            port -> (draft.toIntOrNull() ?: 0) in 1..65535
            pref.key == "rootTunInterfaces" -> io.nekohasekai.sagernet.bg.root.RootDaemon.validTunInterfaces(draft)
            pref.key == "rootIgnoredInterfaces" -> io.nekohasekai.sagernet.bg.root.RootDaemon.validIgnoredInterfaces(draft)
            else -> true
        }
        AlertDialog(onDismissRequest = { edit = false; extra = false },
            title = { Text(if (extra && pref.key == "logLevel") "Log buffer size (KB)" else pref.title.toString()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, { draft = it }, isError = !valid, label = { Text(pref.title.toString()) })
                    if (pref.key == "connectionTestURL") OutlinedTextField(concurrent, { concurrent = it }, label = { Text("Concurrent tests") })
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    if (extra && pref.key == "logLevel") {
                        data.logBufSize = draft.toInt()
                        pref.callChangeListener((pref as ListPreference).value)
                    } else if (pref.callChangeListener(draft)) {
                        when (pref) {
                            is EditTextPreference -> pref.text = draft
                            is ListPreference -> pref.value = draft
                        }
                        if (pref.key == "connectionTestURL") data.connectionTestConcurrent = concurrent.toIntOrNull()?.takeIf { it > 0 } ?: 5
                    }
                    edit = false; extra = false; changed()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { edit = false; extra = false }) { Text("Cancel") } })
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (featured) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = pref.isEnabled) {
            when (pref) {
                is ListPreference -> expanded = true
                is EditTextPreference -> {
                    draft = pref.text.orEmpty()
                    concurrent = data.connectionTestConcurrent.toString()
                    edit = true
                }
                is TwoStatePreference -> { if (pref.callChangeListener(!checked)) { pref.isChecked = !checked; changed() } }
                else -> { pref.performClick(); changed() }
            }
        }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pref.title.toString(), style = MaterialTheme.typography.titleMedium)
                val summary = pref.summary?.toString().orEmpty()
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pref.key == "serviceMode" && (checkingRoot || rootAccess == Access.UNKNOWN)) {
                    Text(stringResource(if (checkingRoot) R.string.root_access_checking else R.string.root_access_unknown),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (pref is TwoStatePreference) {
                Switch(checked, { value -> if (pref.callChangeListener(value)) { pref.isChecked = value; changed() } }, enabled = pref.isEnabled)
            } else {
                Box {
                    Text("›", style = MaterialTheme.typography.headlineSmall)
                    if (pref is ListPreference) DropdownMenu(expanded, { expanded = false }) {
                        if (pref.key == "mtu" || pref.key == "logLevel") DropdownMenuItem(
                            text = { Text(if (pref.key == "mtu") "Custom MTU" else "Log buffer size") },
                            onClick = {
                                expanded = false
                                draft = if (pref.key == "mtu") pref.value.orEmpty() else data.logBufSize.takeIf { it > 0 }?.toString() ?: "50"
                                extra = true
                            })
                        pref.entries.orEmpty().forEachIndexed { index, entry ->
                            val value = pref.entryValues?.getOrNull(index)?.toString()
                            val rootMode = pref.key == "serviceMode" && value?.startsWith("root_") == true
                            if (!rootMode || rootAccess != Access.UNAVAILABLE) {
                                DropdownMenuItem(text = { Text(entry.toString()) },
                                    enabled = !rootMode || rootAccess == Access.GRANTED,
                                    onClick = {
                                        expanded = false
                                        if (value != null && pref.callChangeListener(value)) { pref.value = value; changed() }
                                    })
                            }
                        }
                    }
                }
            }
        }
    }
}
