package io.nekohasekai.sagernet.ui

import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.root.ServiceControl
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.HubTheme

class ServiceControlActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setContent { HubTheme { ServiceControlScreen { finish() } } }
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceControlScreen(close: () -> Unit) {
    val store = DataStore.configurationStore
    var revision by remember { mutableStateOf(0) }
    fun enabled(key: String): Boolean {
        revision
        return store.getBoolean(key, false)
    }
    val master = enabled("scEnabled")
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.service_control)) },
            navigationIcon = { TextButton(onClick = close) { Text("‹", style = MaterialTheme.typography.headlineMedium) } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ControlCard {
                ControlSwitch(stringResource(R.string.sc_enable), master, true, stringResource(R.string.sc_enable_sum)) {
                    store.putBoolean("scEnabled", it); revision++
                }
            }
            ControlCard {
                Text(stringResource(R.string.sc_schedule), style = MaterialTheme.typography.titleLarge)
                ControlSwitch(stringResource(R.string.sc_schedule_enable), enabled("scSchedule"), master) {
                    store.putBoolean("scSchedule", it); revision++
                }
                val active = master && enabled("scSchedule")
                ControlField("scStartCron", stringResource(R.string.sc_start_cron), active, true)
                ControlField("scStopCron", stringResource(R.string.sc_stop_cron), active, true)
                Text(stringResource(R.string.sc_cron_sum), style = MaterialTheme.typography.bodySmall)
            }
            ControlCard {
                Text("Wi-Fi", style = MaterialTheme.typography.titleLarge)
                ControlSwitch(stringResource(R.string.sc_wifi_enable), enabled("scWifi"), master, stringResource(R.string.sc_wifi_sum)) {
                    store.putBoolean("scWifi", it); revision++
                }
                val active = master && enabled("scWifi")
                val rules = listOf(
                    Triple("connectStart", "connectStop", R.string.sc_wifi_connect_start),
                    Triple("connectStop", "connectStart", R.string.sc_wifi_connect_stop),
                    Triple("disconnectStart", "disconnectStop", R.string.sc_wifi_disconnect_start),
                    Triple("disconnectStop", "disconnectStart", R.string.sc_wifi_disconnect_stop)
                )
                rules.forEach { (name, opposite, title) ->
                    HorizontalDivider()
                    val key = "scWifi_$name"
                    ControlSwitch(stringResource(title), enabled(key), active) {
                        if (it) store.putBoolean("scWifi_$opposite", false)
                        store.putBoolean(key, it); revision++
                    }
                    if (enabled(key)) {
                        ControlField("${key}_ssids", "SSID", active, false)
                        ControlField("${key}_bssids", "BSSID", active, false)
                        Text(stringResource(R.string.sc_ids_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) ControlCard {
                Text(stringResource(R.string.sc_keyguard), style = MaterialTheme.typography.titleLarge)
                ControlSwitch(stringResource(R.string.sc_keyguard_enable), enabled("scKeyguard"), master, stringResource(R.string.sc_keyguard_sum)) {
                    store.putBoolean("scKeyguard", it); revision++
                }
                val active = master && enabled("scKeyguard")
                listOf(
                    Triple("scLockStart", "scLockStop", R.string.sc_lock_start),
                    Triple("scLockStop", "scLockStart", R.string.sc_lock_stop),
                    Triple("scUnlockStart", "scUnlockStop", R.string.sc_unlock_start),
                    Triple("scUnlockStop", "scUnlockStart", R.string.sc_unlock_stop)
                ).forEach { (key, opposite, title) ->
                    ControlSwitch(stringResource(title), enabled(key), active) {
                        if (it) store.putBoolean(opposite, false)
                        store.putBoolean(key, it); revision++
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun ControlSwitch(title: String, checked: Boolean, enabled: Boolean, summary: String = "", change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}

@Composable
private fun ControlField(key: String, title: String, enabled: Boolean, cron: Boolean) {
    val store = DataStore.configurationStore
    var value by remember(key) { mutableStateOf(store.getString(key) ?: "") }
    var draft by remember(key) { mutableStateOf(value) }
    var editing by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { draft = value; editing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(if (value.isBlank()) title else "$title: $value")
    }
    if (editing) {
        val valid = !cron || draft.isBlank() || ServiceControl.validCron(draft)
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(title) },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, isError = !valid,
                singleLine = cron, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = valid, onClick = {
                store.putString(key, draft.trim()); value = draft.trim(); editing = false
            }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
