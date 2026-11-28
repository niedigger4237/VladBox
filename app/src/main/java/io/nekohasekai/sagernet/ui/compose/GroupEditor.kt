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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ktx.USER_AGENT
import okhttp3.HttpUrl.Companion.toHttpUrl

fun installGroupEditor(activity: GroupSettingsActivity, host: ComposeView) {
    host.setContent {
        HubTheme {
            val revision by activity.editorRevision.collectAsState()
            var name by remember { mutableStateOf(DataStore.groupName) }
            var type by remember { mutableStateOf(DataStore.groupType) }
            var order by remember { mutableStateOf(DataStore.groupOrder) }
            var selector by remember { mutableStateOf(DataStore.groupIsSelector) }
            var link by remember { mutableStateOf(DataStore.subscriptionLink) }
            var resolve by remember { mutableStateOf(DataStore.subscriptionForceResolve) }
            var dedup by remember { mutableStateOf(DataStore.subscriptionDeduplication) }
            var connected by remember { mutableStateOf(DataStore.subscriptionUpdateWhenConnectedOnly) }
            var agent by remember { mutableStateOf(DataStore.subscriptionUserAgent) }
            var auto by remember { mutableStateOf(DataStore.subscriptionAutoUpdate) }
            var delay by remember { mutableStateOf(DataStore.subscriptionAutoUpdateDelay.toString()) }
            val validDelay = !auto || (delay.toIntOrNull() ?: 0) >= 15
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { activity.onBackPressedDispatcher.onBackPressed() }) {
                            Icon(painterResource(R.drawable.ic_navigation_close), stringResource(android.R.string.cancel))
                        }
                        Text(stringResource(R.string.group_settings), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (DataStore.editingId != 0L) {
                            IconButton(onClick = { activity.editorDelete() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_action_delete),
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(onClick = { activity.editorSave() }, enabled = type != GroupType.SUBSCRIPTION || validDelay) {
                            Text("✓", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EditorField(R.string.group_name, name) { name = it; DataStore.groupName = it }
                        EditorChoice(R.string.group_type, activity.resources.getStringArray(R.array.group_types), activity.resources.getStringArray(R.array.int_array_2), type) { type = it; DataStore.groupType = it }
                        EditorChoice(R.string.group_order, activity.resources.getStringArray(R.array.group_orders), activity.resources.getStringArray(R.array.int_array_3), order) { order = it; DataStore.groupOrder = it }
                        EditorSwitch(R.string.use_selector, selector) { selector = it; DataStore.groupIsSelector = it }
                        key(revision) {
                            val entries = activity.resources.getStringArray(R.array.front_proxy_entry)
                            val values = activity.resources.getStringArray(R.array.front_proxy_value)
                            EditorChoice(R.string.front_proxy, entries, values, DataStore.frontProxyTmp) {
                                if (it == 3) activity.selectProfileForAddFront.launch(Intent(activity, ProfileSelectActivity::class.java))
                                else { DataStore.frontProxyTmp = it; activity.refreshEditor() }
                            }
                            EditorChoice(R.string.landing_proxy, entries, values, DataStore.landingProxyTmp) {
                                if (it == 3) activity.selectProfileForAddLanding.launch(Intent(activity, ProfileSelectActivity::class.java))
                                else { DataStore.landingProxyTmp = it; activity.refreshEditor() }
                            }
                        }
                        if (type == GroupType.SUBSCRIPTION) {
                            Text(stringResource(R.string.subscription_settings), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                            val warning = if (link.isBlank() || link.startsWith("content:")) null else runCatching {
                                if (link.contains("\n")) "Unexpected new line"
                                else if (link.toHttpUrl().scheme == "http") activity.getString(R.string.cleartext_http_warning) else null
                            }.getOrElse { it.localizedMessage ?: "Invalid URL" }
                            OutlinedTextField(link, { link = it; DataStore.subscriptionLink = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.group_subscription_link)) }, isError = warning != null, supportingText = { warning?.let { Text(it) } })
                            EditorSwitch(R.string.force_resolve, resolve, R.string.force_resolve_sum) { resolve = it; DataStore.subscriptionForceResolve = it }
                            EditorSwitch(R.string.deduplication, dedup, R.string.deduplication_sum) { dedup = it; DataStore.subscriptionDeduplication = it }
                            Text(stringResource(R.string.update_settings), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                            EditorSwitch(R.string.update_when_connected_only, connected, R.string.update_when_connected_only_sum) { connected = it; DataStore.subscriptionUpdateWhenConnectedOnly = it }
                            OutlinedTextField(agent, { agent = it; DataStore.subscriptionUserAgent = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.subscription_user_agent)) }, placeholder = { Text(USER_AGENT) })
                            EditorSwitch(R.string.auto_update, auto) { auto = it; DataStore.subscriptionAutoUpdate = it }
                            OutlinedTextField(delay, { delay = it; it.toIntOrNull()?.takeIf { n -> n >= 15 }?.let { n -> DataStore.subscriptionAutoUpdateDelay = n } }, Modifier.fillMaxWidth(), enabled = auto, isError = !validDelay, label = { Text(stringResource(R.string.auto_update_delay)) }, supportingText = { if (!validDelay) Text("≥ 15") }, singleLine = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun EditorField(label: Int, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true)
}

@Composable private fun EditorSwitch(label: Int, checked: Boolean, summary: Int? = null, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label))
            summary?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked, change)
    }
}

@Composable private fun EditorChoice(label: Int, entries: Array<String>, values: Array<String>, selected: Int, change: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(entries.getOrNull(values.indexOf(selected.toString())) ?: selected.toString())
            }
            DropdownMenu(expanded, { expanded = false }) {
                entries.forEachIndexed { index, entry ->
                    DropdownMenuItem(text = { Text(entry) }, onClick = {
                        expanded = false
                        values.getOrNull(index)?.toIntOrNull()?.let(change)
                    })
                }
            }
        }
    }
}
