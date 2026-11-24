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
                WgDialer(read("wgDialer")) { write("wgDialer",it) }
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

@Composable private fun WgDialer(raw: String, change: (String)->Unit) {
    var advanced by remember { mutableStateOf(false) }
    val json = remember(raw) { runCatching { org.json.JSONObject(raw.ifBlank { "{}" }) }.getOrNull() }
    fun update(key: String, value: Any?) {
        if (json == null) return
        val copy = org.json.JSONObject(json.toString())
        if (value == null) copy.remove(key) else copy.put(key,value)
        change(copy.toString())
    }
    if (json == null) {
        Text("Invalid dialer JSON. Correct the JSON below.",color = MaterialTheme.colorScheme.error)
    } else {
        val fields = linkedMapOf(
            "bind_interface" to "Bind interface",
            "inet4_bind_address" to "IPv4 bind address",
            "inet6_bind_address" to "IPv6 bind address",
            "protect_path" to "Protect path",
            "routing_mark" to "Routing mark",
            "netns" to "Network namespace",
            "connect_timeout" to "Connect timeout",
            "tcp_keep_alive" to "TCP keepalive",
            "tcp_keep_alive_interval" to "TCP keepalive interval",
            "fallback_delay" to "Fallback delay"
        )
        for ((key,label) in fields) {
            WgText(label,json.opt(key)?.toString().orEmpty()) { update(key,it.takeIf { v -> v.isNotBlank() }) }
        }
        for ((key,label) in linkedMapOf(
            "bind_address_no_port" to "Bind address without port",
            "reuse_addr" to "Reuse address",
            "tcp_fast_open" to "TCP Fast Open",
            "tcp_multi_path" to "Multipath TCP",
            "disable_tcp_keep_alive" to "Disable TCP keepalive",
            "udp_fragment" to "UDP fragmentation"
        )) {
            WgChoice(label,json.opt(key)?.toString().orEmpty(),listOf("" to "Default","true" to "Enabled","false" to "Disabled")) {
                update(key,it.takeIf { v -> v.isNotBlank() }?.toBooleanStrict())
            }
        }
        WgText("Network strategy",json.optString("network_strategy")) { update("network_strategy",it.takeIf { v -> v.isNotBlank() }) }
        for ((key,label) in listOf("network_type" to "Network types","fallback_network_type" to "Fallback network types")) {
            val values = json.optJSONArray(key)
            val selected = if (values != null) (0 until values.length()).map { values.optString(it) }.toSet() else setOf(json.optString(key)).filter { it.isNotBlank() }.toSet()
            Text(label,style = MaterialTheme.typography.titleSmall)
            for (type in listOf("wifi","cellular","ethernet","other")) {
                WgToggle(type,type in selected) { enabled ->
                    val next = if (enabled) selected + type else selected - type
                    update(key,if (next.isEmpty()) null else org.json.JSONArray(next.toList()))
                }
            }
        }
        var resolver by remember(raw) { mutableStateOf(json.opt("domain_resolver")?.toString().orEmpty()) }
        WgText("Domain resolver (tag or JSON)",resolver) { resolver = it }
        OutlinedButton(onClick = {
            runCatching {
                val value = resolver.trim()
                update("domain_resolver",if (value.isBlank()) null else if (value.startsWith("{")) org.json.JSONObject(value) else value)
            }
        },modifier = Modifier.fillMaxWidth()) { Text("Apply domain resolver") }
    }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide extra JSON" else "Show extra JSON") }
    if (advanced || json == null) WgText("Dialer extra JSON",raw,change = change)
}
