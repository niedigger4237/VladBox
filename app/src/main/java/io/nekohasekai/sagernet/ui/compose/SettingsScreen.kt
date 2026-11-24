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
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (featured) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = pref.isEnabled) {
            if (pref is ListPreference && pref.javaClass.simpleName == "SimpleMenuPreference") expanded = true
            else { pref.performClick(); changed() }
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
