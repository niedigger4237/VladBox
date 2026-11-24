package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.database.DataStore

@Composable
fun NaiveProfileScreen(onSave: () -> Unit, onClose: () -> Unit, menuItems: List<Pair<String, () -> Unit>>) {
    var revision by remember { mutableStateOf(0) }
    var section by rememberSaveable { mutableStateOf("") }
    val valid = remember(revision) {
        DataStore.serverAddress.isNotBlank() && DataStore.serverPort in 1..65535 &&
        DataStore.serverProtocol in listOf("https","quic") && DataStore.serverInsecureConcurrency >= 0
    }
    androidx.activity.compose.BackHandler(section.isNotEmpty()) { section = "" }
    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = section.ifEmpty { "NaiveProxy" }, onNavigation = onClose, onBack = { if (section.isNotEmpty()) section = "" else onClose() }, extraActions = {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = onSave, enabled = valid) { Text("Save") }
            Box {
                IconButton(onClick = { expanded = true }) { Text("⋮") }
                DropdownMenu(expanded, { expanded = false }) { menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) } }
            }
        })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section.isEmpty()) {
                val sections = listOf("General" to "Server and transport", "Authentication" to "Username and password", "Security" to "Server name and certificate", "Advanced" to "Headers and connection options")
                items(sections.size) { i ->
                    val (label, detail) = sections[i]
                    Card(onClick = { section = label }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(20.dp)) {
                            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.titleLarge); Text(detail) }
                            Text("›", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                item { Button(onClick = onSave, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Save profile") } }
            } else item {
                val current = revision
                SimpleSection(section) {
                    when(section) {
                        "General" -> {
                            SimpleField("Profile name",DataStore.profileName) { DataStore.profileName = it; revision++ }
                            SimpleField("Server address",DataStore.serverAddress) { DataStore.serverAddress = it; revision++ }
                            var port by rememberSaveable { mutableStateOf(DataStore.serverPort.toString()) }
                            SimpleField("Server port",port) { port = it; DataStore.serverPort = it.toIntOrNull() ?: 0; revision++ }
                            ProfileChoices(listOf("https" to "HTTPS","quic" to "QUIC"),DataStore.serverProtocol) { DataStore.serverProtocol = it; revision++ }
                        }
                        "Authentication" -> {
                            SimpleField("Username",DataStore.serverUsername) { DataStore.serverUsername = it; revision++ }
                            SimpleField("Password",DataStore.serverPassword,true) { DataStore.serverPassword = it; revision++ }
                        }
                        "Security" -> {
                            SimpleField("Server name (SNI)",DataStore.serverSNI) { DataStore.serverSNI = it; revision++ }
                            SimpleField("CA certificate (PEM)",DataStore.serverCertificates) { DataStore.serverCertificates = it; revision++ }
                            Text("Custom certificates are stored in the profile but not applied by the current external NaiveProxy configuration.",style = MaterialTheme.typography.bodySmall)
                        }
                        "Advanced" -> {
                            SimpleField("Extra HTTP headers",DataStore.serverHeaders) { DataStore.serverHeaders = it; revision++ }
                            var concurrency by rememberSaveable { mutableStateOf(DataStore.serverInsecureConcurrency.toString()) }
                            SimpleField("Insecure concurrency",concurrency) { concurrency = it; DataStore.serverInsecureConcurrency = it.toIntOrNull() ?: -1; revision++ }
                            SimpleToggle("UDP over TCP",DataStore.profileCacheStore.getBoolean("sUoT",false)) { DataStore.profileCacheStore.putBoolean("sUoT",it); revision++ }
                            Text("Native sing-box dialer options are not available for the external NaiveProxy engine.",style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!valid) Text("Check server and connection settings.",color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
