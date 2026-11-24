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
fun TrojanGoProfileScreen(onSave: () -> Unit, onClose: () -> Unit, menuItems: List<Pair<String, () -> Unit>>) {
    var revision by remember { mutableStateOf(0) }
    var section by rememberSaveable { mutableStateOf("") }
    val valid = remember(revision) {
        DataStore.serverAddress.isNotBlank() && DataStore.serverPort in 1..65535 &&
        DataStore.serverNetwork in listOf("original","ws") && DataStore.serverPassword.isNotEmpty() &&
        (DataStore.serverNetwork != "ws" || DataStore.serverPath.startsWith("/")) &&
        (DataStore.serverEncryption == "none" || (DataStore.serverEncryption == "ss" && DataStore.serverMethod in listOf("aes-128-gcm","aes-256-gcm","chacha20-ietf-poly1305") && DataStore.serverPassword1.isNotEmpty()))
    }
    androidx.activity.compose.BackHandler(section.isNotEmpty()) { section = "" }
    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = section.ifEmpty { "Trojan-Go" }, onNavigation = onClose, onBack = { if (section.isNotEmpty()) section = "" else onClose() }, extraActions = {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = onSave, enabled = valid) { Text("Save") }
            Box {
                IconButton(onClick = { expanded = true }) { Text("⋮") }
                DropdownMenu(expanded, { expanded = false }) { menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) } }
            }
        })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section.isEmpty()) {
                val sections = listOf("General" to "Server and transport", "Transport" to "TCP and WebSocket", "Security" to "TLS and encryption", "Advanced" to "Engine information")
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
                            SimpleField("Password",DataStore.serverPassword,true) { DataStore.serverPassword = it; revision++ }
                        }
                        "Transport" -> {
                            ProfileChoices(listOf("original" to "TCP","ws" to "WebSocket"),DataStore.serverNetwork) { DataStore.serverNetwork = it; revision++ }
                            if (DataStore.serverNetwork == "ws") {
                                SimpleField("Host",DataStore.serverHost) { DataStore.serverHost = it; revision++ }
                                SimpleField("Path",DataStore.serverPath) { DataStore.serverPath = it; revision++ }
                            }
                        }
                        "Security" -> {
                            SimpleField("Server name (SNI)",DataStore.serverSNI) { DataStore.serverSNI = it; revision++ }
                            SimpleToggle("Allow insecure",DataStore.serverAllowInsecure) { DataStore.serverAllowInsecure = it; revision++ }
                            if (DataStore.serverAllowInsecure) Text("TLS certificate verification is disabled.",color = MaterialTheme.colorScheme.error)
                            ProfileChoices(listOf("none" to "None","ss" to "Shadowsocks"),DataStore.serverEncryption) {
                                DataStore.serverEncryption = it
                                if (it == "ss" && DataStore.serverMethod !in listOf("aes-128-gcm","aes-256-gcm","chacha20-ietf-poly1305")) DataStore.serverMethod = "aes-128-gcm"
                                revision++
                            }
                            if (DataStore.serverEncryption == "ss") {
                                ProfileChoices(listOf("aes-128-gcm","aes-256-gcm","chacha20-ietf-poly1305").map { it to it },DataStore.serverMethod) { DataStore.serverMethod = it; revision++ }
                                SimpleField("Shadowsocks password",DataStore.serverPassword1,true) { DataStore.serverPassword1 = it; revision++ }
                            }
                        }
                        "Advanced" -> {
                            Text("Trojan-Go uses an external engine. Native sing-box dialer options are not available.",style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!valid) Text("Check server and connection settings.",color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
