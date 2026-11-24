package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.database.DataStore

@Composable
fun WireGuardProfileScreen(onPeers: () -> Unit, onImport: () -> Unit, onExport: () -> Unit, onLegacy: () -> Unit, onSave: () -> Unit) {
    val store = DataStore.profileCacheStore
    var revision by remember { mutableStateOf(0) }
    fun read(key: String): String { revision; return store.getString(key, "") ?: "" }
    fun write(key: String, value: String) { store.putString(key,value); revision++ }
    fun checked(key: String): Boolean { revision; return store.getBoolean(key,false) }
    fun toggle(key: String, value: Boolean) { store.putBoolean(key,value); revision++ }
    val natModes = listOf("" to "Default", "endpoint_independent" to "Endpoint independent", "address_dependent" to "Address dependent", "address_and_port_dependent" to "Address and port dependent")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            WgCard("General") {
                WgText("Profile name",read("name")) { write("name",it) }
                OutlinedButton(onClick = onLegacy, modifier = Modifier.fillMaxWidth()) { Text("AmneziaWG / legacy editor") }
            }
        }
        item {
            WgCard("Interface") {
                WgText("Local address",read("localAddress")) { write("localAddress",it) }
                WgText("Private key",read("privateKey"),true) { write("privateKey",it) }
                WgToggle("System interface",checked("system")) { toggle("system",it) }
                if (checked("system")) WgText("Interface name",read("wgInterfaceName")) { write("wgInterfaceName",it) }
                WgText("Listen port",read("wgListenPort")) { write("wgListenPort",it) }
                WgText("MTU",read("mtu")) { write("mtu",it) }
                WgText("Workers",read("workers")) { write("workers",it) }
            }
        }
        item {
            WgCard("Peers") {
                WgText("Endpoint address",read("serverAddress")) { write("serverAddress",it) }
                WgText("Endpoint port",read("serverPort")) { write("serverPort",it) }
                WgText("Public key",read("peerPublicKey")) { write("peerPublicKey",it) }
                WgText("Pre-shared key",read("peerPreSharedKey"),true) { write("peerPreSharedKey",it) }
                WgText("Allowed IPs",read("wgAllowedIps")) { write("wgAllowedIps",it) }
                WgText("Persistent keepalive",read("persistentKeepalive")) { write("persistentKeepalive",it) }
                WgText("Reserved (compatibility only)",read("reserved")) { write("reserved",it) }
                FilledTonalButton(onClick = onPeers, modifier = Modifier.fillMaxWidth()) { Text("Manage additional peers") }
            }
        }
        item {
            WgCard("UDP & NAT") {
                WgText("UDP timeout",read("udpTimeout")) { write("udpTimeout",it) }
                WgChoice("UDP mapping",read("wgUdpMapping"),natModes) { write("wgUdpMapping",it) }
                WgChoice("UDP filtering",read("wgUdpFiltering"),natModes) { write("wgUdpFiltering",it) }
                WgText("UDP NAT max",read("wgUdpNatMax")) { write("wgUdpNatMax",it) }
                WgText("Preallocated buffers per pool",read("wgBuffers")) { write("wgBuffers",it) }
                WgToggle("Disable pauses",checked("wgDisablePauses")) { toggle("wgDisablePauses",it) }
            }
        }
        item {
            WgCard("Dialer settings") {
                WgText("Dialer extra JSON",read("wgDialer")) { write("wgDialer",it) }
                Text("Individual dialer fields are available in the legacy editor.",style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            WgCard("Import / Export") {
                FilledTonalButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import .conf") }
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export .conf") }
            }
        }
        item {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = read("privateKey").isNotBlank() && read("peerPublicKey").isNotBlank() && read("localAddress").isNotBlank() && read("serverPort").toIntOrNull()?.let { it in 0..65535 } == true) { Text("Save profile") }
        }
    }
}

@Composable private fun WgCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title,style = MaterialTheme.typography.titleLarge,color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
@Composable private fun WgText(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value,change,Modifier.fillMaxWidth(),label = { Text(label) },visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
@Composable private fun WgToggle(label: String,value: Boolean,change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f)); Switch(value,change)
    }
}
@Composable private fun WgChoice(label: String,value: String,options: List<Pair<String,String>>,change: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true },modifier = Modifier.fillMaxWidth()) { Text(label + ": " + (options.firstOrNull { it.first == value }?.second ?: value)) }
        DropdownMenu(expanded,{ expanded = false }) {
            options.forEach { (key,title) -> DropdownMenuItem(text = { Text(title) },onClick = { change(key); expanded = false }) }
        }
    }
}
