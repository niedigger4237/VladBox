package io.nekohasekai.sagernet.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import kotlinx.coroutines.delay
import io.nekohasekai.sagernet.bg.root.ServiceControl
import io.nekohasekai.sagernet.bg.root.RootDaemon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var serviceState by remember { mutableStateOf(DataStore.serviceState) }
    var rootMode by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            serviceState = DataStore.serviceState
            rootMode = DataStore.serviceMode in listOf(Key.MODE_ROOT_TUN, Key.MODE_ROOT_TPROXY,
                Key.MODE_ROOT_TUN2SOCKS, Key.MODE_ROOT_BPF2SOCKS)
            delay(500)
        }
    }
    var daemonPhase by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rootMode, serviceState) {
        daemonPhase = null
        if (rootMode && serviceState == BaseService.State.Connected) {
            while (true) {
                daemonPhase = withContext(Dispatchers.IO) { RootDaemon.phase() }
                delay(3000)
            }
        }
    }
    fun enabled(key: String): Boolean {
        revision
        return store.getBoolean(key, false)
    }
    val master = enabled("scEnabled")
    var page by rememberSaveable { mutableStateOf("home") }
    BackHandler(enabled = page != "home") { page = "home" }
    val pageTitle = when (page) {
        "schedule" -> stringResource(R.string.sc_schedule)
        "wifi" -> "Wi-Fi"
        "keyguard" -> stringResource(R.string.sc_keyguard)
        else -> stringResource(R.string.service_control)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(pageTitle) },
            navigationIcon = { IconButton(onClick = { if (page == "home") close() else page = "home" }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } })
    }) { padding ->
        key(page) {
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page == "home" && rootMode && serviceState.started) ControlCard {
                Text("Supervisor status", style = MaterialTheme.typography.titleLarge)
                Text(when (daemonPhase) {
                    "running" -> "Traffic active"
                    "paused" -> "Traffic paused • automation remains available"
                    "stopped" -> "Traffic stopped • waiting for a configured event if supervisor is active"
                    "failed" -> "Supervisor reported a failure"
                    "starting", "validating", "acquiring", "applying-rules" -> "Starting traffic"
                    "stopping" -> "Stopping traffic"
                    else -> "Supervisor status unavailable"
                }, style = MaterialTheme.typography.bodyMedium)
            }
            if (page == "home" && rootMode && serviceState.started) ControlCard {
                Text(stringResource(R.string.need_reload), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(
                    enabled = serviceState == BaseService.State.Connected,
                    onClick = {
                        if (DataStore.serviceState == BaseService.State.Connected &&
                            DataStore.serviceMode in listOf(Key.MODE_ROOT_TUN, Key.MODE_ROOT_TPROXY,
                                Key.MODE_ROOT_TUN2SOCKS, Key.MODE_ROOT_BPF2SOCKS)) {
                            SagerNet.reloadService()
                            serviceState = BaseService.State.Connecting
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.apply)) }
            }
            if (page == "home") {
            ControlCard {
                ControlSwitch(stringResource(R.string.sc_enable), master, true, stringResource(R.string.sc_enable_sum)) {
                    store.putBoolean("scEnabled", it); revision++
                }
            }
            Text("Automation", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            ControlDestination(Icons.Default.Schedule, stringResource(R.string.sc_schedule), "Start and stop at scheduled times", master && enabled("scSchedule")) { page = "schedule" }
            ControlDestination(Icons.Default.Wifi, "Wi-Fi", "Connection and disconnection rules", master && enabled("scWifi")) { page = "wifi" }
            if (Build.VERSION.SDK_INT >= 33) ControlDestination(Icons.Default.Lock, stringResource(R.string.sc_keyguard), "Lock and unlock rules", master && enabled("scKeyguard")) { page = "keyguard" }
            }
            if (page == "schedule") ControlCard {
                Text(stringResource(R.string.sc_schedule), style = MaterialTheme.typography.titleLarge)
                ControlSwitch(stringResource(R.string.sc_schedule_enable), enabled("scSchedule"), master) {
                    store.putBoolean("scSchedule", it); revision++
                }
                val active = master && enabled("scSchedule")
                DailyScheduleField("scStartCron", stringResource(R.string.sc_start_cron), active) { revision++ }
                HorizontalDivider()
                DailyScheduleField("scStopCron", stringResource(R.string.sc_stop_cron), active) { revision++ }
                Text(stringResource(R.string.sc_cron_sum), style = MaterialTheme.typography.bodySmall)
            }
            if (page == "wifi") ControlCard {
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
                        ControlField("${key}_ssids", "SSID", active, false) { revision++ }
                        ControlField("${key}_bssids", "BSSID", active, false) { revision++ }
                        Text(stringResource(R.string.sc_ids_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (page == "keyguard" && Build.VERSION.SDK_INT >= 33) ControlCard {
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
private fun ControlField(key: String, title: String, enabled: Boolean, cron: Boolean, onSaved: () -> Unit = {}) {
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
                store.putString(key, draft.trim()); value = draft.trim(); editing = false; onSaved()
            }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlDestination(icon: ImageVector, title: String, description: String, active: Boolean, open: () -> Unit) {
    Card(onClick = open, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium)
                Text(if (active) "Enabled" else "Disabled", style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyScheduleField(key: String, title: String, enabled: Boolean, onSaved: () -> Unit) {
    val store = DataStore.configurationStore
    var value by remember(key) { mutableStateOf(store.getString(key).orEmpty()) }
    val fields = value.trim().split(Regex("\\s+"))
    val minute = fields.getOrNull(0)?.toIntOrNull()
    val hour = fields.getOrNull(1)?.toIntOrNull()
    val daily = fields.size == 5 && fields.drop(2).all { it == "*" } &&
        minute != null && minute in 0..59 && hour != null && hour in 0..23
    var picking by rememberSaveable { mutableStateOf(false) }
    val time = if (daily) java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute) else null
    Text(title, style = MaterialTheme.typography.titleMedium)
    FilledTonalButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(time?.let { "Every day · $it" } ?: if (value.isBlank()) "Choose daily time" else "Replace custom schedule with daily time")
    }
    androidx.compose.runtime.key(value) {
        ControlField(key, "Advanced Cron", enabled, true) {
            value = store.getString(key).orEmpty()
            onSaved()
        }
    }
    if (picking) {
        val state = rememberTimePickerState(initialHour = if (daily) hour!! else 8,
            initialMinute = if (daily) minute!! else 0, is24Hour = true)
        AlertDialog(onDismissRequest = { picking = false },
            title = { Text(title) },
            text = { TimeInput(state = state) },
            confirmButton = { TextButton(onClick = {
                store.putString(key, "${state.minute} ${state.hour} * * *")
                value = store.getString(key).orEmpty()
                picking = false
                onSaved()
            }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
