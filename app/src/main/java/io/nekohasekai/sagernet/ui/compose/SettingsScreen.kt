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
import kotlinx.coroutines.flow.StateFlow

fun installSettingsScreen(host: ComposeView, screen: PreferenceScreen, revision: StateFlow<Int>, changed: () -> Unit) {
    host.setContent {
        HubTheme {
            val rev by revision.collectAsState()
            var category by remember { mutableStateOf<Int?>(null) }
            var query by remember { mutableStateOf("") }
            val groups = remember(rev) { (0 until screen.preferenceCount).mapNotNull { screen.getPreference(it) as? PreferenceCategory } }
            val mode = screen.findPreference<ListPreference>("serviceMode")
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                if (category != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { category = null }) { Text("‹") }
                        Text(groups.getOrNull(category ?: -1)?.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge)
                    }
                    androidx.activity.compose.BackHandler { category = null }
                } else {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Search settings") }, singleLine = true)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (category == null && query.isBlank()) {
                        if (mode != null) item { SettingTile(mode, changed, true) }
                        items(groups.size) { index ->
                            val group = groups[index]
                            Card(Modifier.fillMaxWidth().clickable { category = index }, shape = RoundedCornerShape(22.dp)) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                            Icon(painterResource(R.drawable.ic_action_settings), null)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(group.title.toString(), style = MaterialTheme.typography.titleMedium)
                                        Text((0 until group.preferenceCount).map { group.getPreference(it) }.filter { it.isVisible }.take(3).joinToString(" · ") { it.title.toString() }, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("›", style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    } else {
                        val visible = groups.filterIndexed { index, _ -> category == null || category == index }.flatMap { group ->
                            (0 until group.preferenceCount).map { group.getPreference(it) }
                        }.filter { it.isVisible && (query.isBlank() || it.title.toString().contains(query, true) || it.key.orEmpty().contains(query, true) || it.summary.toString().contains(query, true)) }
                        items(visible, key = { it.key }) { pref -> SettingTile(pref, changed) }
                    }
                }
            }
        }
    }
}

@Composable private fun SettingTile(pref: Preference, changed: () -> Unit, featured: Boolean = false) {
    var expanded by remember(pref.key) { mutableStateOf(false) }
    var edit by remember(pref.key) { mutableStateOf(false) }
    var extra by remember(pref.key) { mutableStateOf(false) }
    var draft by remember(pref.key) { mutableStateOf("") }
    var concurrent by remember(pref.key) { mutableStateOf("") }
    val data = io.nekohasekai.sagernet.database.DataStore
    if (edit || extra) {
        val port = pref.key in setOf("mixedPort", "socksPort", "rootTproxyPort", "bpfBridgePort")
        val valid = when {
            extra && pref.key == "mtu" -> (draft.toIntOrNull() ?: 0) in 1000..10000
            extra && pref.key == "logLevel" -> (draft.toIntOrNull() ?: 0) > 0
            port -> (draft.toIntOrNull() ?: 0) in 1..65535
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
                else -> { pref.performClick(); changed() }
            }
        }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pref.title.toString(), style = MaterialTheme.typography.titleMedium)
                val summary = pref.summary?.toString().orEmpty()
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pref is TwoStatePreference) {
                Switch(pref.isChecked, { value -> if (pref.callChangeListener(value)) { pref.isChecked = value; changed() } }, enabled = pref.isEnabled)
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
                            DropdownMenuItem(text = { Text(entry.toString()) }, onClick = {
                                expanded = false
                                val value = pref.entryValues?.getOrNull(index)?.toString()
                                if (value != null && pref.callChangeListener(value)) { pref.value = value; changed() }
                            })
                        }
                    }
                }
            }
        }
    }
}
