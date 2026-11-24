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
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    androidx.activity.compose.BackHandler(enabled = section.isNotEmpty()) { section = "" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (section.isEmpty()) {
            items(6) { index ->
                val names = listOf("General","Interface","Peers","UDP & NAT","Dialer settings","Import / Export")
                val descriptions = listOf("Profile name and protocol","Address, keys and MTU","Endpoints and allowed IPs","Mapping, filtering and limits","Connection and network options","WireGuard configuration files")
                Card(onClick = { section = names[index] }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(names[index],style = MaterialTheme.typography.titleLarge)
                            Text(descriptions[index],style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("›",style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { section = "" }) { Text("‹ Back") }
                    Text(section,style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        if (section == "General") item {
            WgCard("General") {
                WgText("Profile name",read("name")) { write("name",it) }
                OutlinedButton(onClick = onLegacy, modifier = Modifier.fillMaxWidth()) { Text("AmneziaWG / legacy editor") }
            }
        }
        if (section == "Interface") item {
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
        if (section == "Peers") item {
            WgCard("Peers") {
                WgText("Endpoint address",read("serverAddress")) { write("serverAddress",it) }
                WgText("Endpoint port",read("serverPort")) { write("serverPort",it) }
                WgText("Public key",read("peerPublicKey")) { write("peerPublicKey",it) }
                WgText("Pre-shared key",read("peerPreSharedKey"),true) { write("peerPreSharedKey",it) }
                WgText("Allowed IPs",read("wgAllowedIps")) { write("wgAllowedIps",it) }
                WgText("Persistent keepalive",read("persistentKeepalive")) { write("persistentKeepalive",it) }
                WgText("Reserved (compatibility only)",read("reserved")) { write("reserved",it) }
                WgPeers(read("wgExtraPeers")) { write("wgExtraPeers",it) }
            }
        }
        if (section == "UDP & NAT") item {
            WgCard("UDP & NAT") {
                WgText("UDP timeout",read("udpTimeout")) { write("udpTimeout",it) }
                WgChoice("UDP mapping",read("wgUdpMapping"),natModes) { write("wgUdpMapping",it) }
                WgChoice("UDP filtering",read("wgUdpFiltering"),natModes) { write("wgUdpFiltering",it) }
                WgText("UDP NAT max",read("wgUdpNatMax")) { write("wgUdpNatMax",it) }
                WgText("Preallocated buffers per pool",read("wgBuffers")) { write("wgBuffers",it) }
                WgToggle("Disable pauses",checked("wgDisablePauses")) { toggle("wgDisablePauses",it) }
            }
        }
        if (section == "Dialer settings") item {
            WgCard("Dialer settings") {
                WgDialer(read("wgDialer")) { write("wgDialer",it) }
            }
        }
        if (section == "Import / Export") item {
            WgCard("Import / Export") {
                FilledTonalButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import .conf") }
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export .conf") }
            }
        }
        if (section.isEmpty()) item {
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

@Composable private fun WgPeers(raw: String, change: (String)->Unit) {
    val peers = remember(raw) { runCatching { org.json.JSONArray(raw.ifBlank { "[]" }) }.getOrNull() }
    var editing by remember { mutableStateOf<Int?>(null) }
    var removing by remember { mutableStateOf<Int?>(null) }
    if (peers == null) {
        Text("Invalid additional peers JSON",color = MaterialTheme.colorScheme.error)
        WgText("Peers JSON",raw,change = change)
        return
    }
    for (index in 0 until peers.length()) {
        val peer = peers.optJSONObject(index)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp),verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Peer ${index + 2}",style = MaterialTheme.typography.titleMedium)
                Text(peer?.optString("address").orEmpty().ifBlank { "No endpoint" } + ":" + (peer?.optInt("port") ?: 0))
                Text("Allowed IPs: " + (peer?.optJSONArray("allowed_ips")?.toString() ?: "—"),style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editing = index }) { Text("Edit") }
                    TextButton(onClick = { removing = index }) { Text("Remove",color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    FilledTonalButton(onClick = { editing = peers.length() },modifier = Modifier.fillMaxWidth()) { Text("Add peer") }
    removing?.let { index ->
        AlertDialog(onDismissRequest = { removing = null },title = { Text("Remove peer?") },
            text = { Text("Peer ${index + 2} will be removed from this profile.") },
            confirmButton = { TextButton(onClick = {
                val copy = org.json.JSONArray(peers.toString())
                copy.remove(index)
                change(copy.toString())
                removing = null
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
    editing?.let { index ->
        val existing = peers.optJSONObject(index)
        key(index) {
            var address by remember { mutableStateOf(existing?.optString("address").orEmpty()) }
            var port by remember { mutableStateOf(existing?.optString("port") ?: "0") }
            var publicKey by remember { mutableStateOf(existing?.optString("public_key").orEmpty()) }
            var psk by remember { mutableStateOf(existing?.optString("pre_shared_key").orEmpty()) }
            var ips by remember { mutableStateOf(existing?.optJSONArray("allowed_ips")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: "0.0.0.0/0, ::/0") }
            var keepalive by remember { mutableStateOf(existing?.optString("persistent_keepalive_interval") ?: "0") }
            val routes = ips.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            val valid = publicKey.isNotBlank() && routes.isNotEmpty() && port.toIntOrNull()?.let { it in 0..65535 } == true && keepalive.toIntOrNull()?.let { it in 0..65535 } == true
            AlertDialog(onDismissRequest = { editing = null },
                title = { Text(if (existing == null) "Add peer" else "Edit peer") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 440.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { WgText("Endpoint address",address) { address = it } }
                        item { WgText("Endpoint port",port) { port = it } }
                        item { WgText("Public key",publicKey) { publicKey = it } }
                        item { WgText("Pre-shared key",psk,true) { psk = it } }
                        item { WgText("Allowed IPs",ips) { ips = it } }
                        item { WgText("Persistent keepalive",keepalive) { keepalive = it } }
                    }
                },
                confirmButton = { TextButton(enabled = valid,onClick = {
                    val obj = org.json.JSONObject(existing?.toString() ?: "{}")
                    obj.put("address",address.trim()).put("port",port.toInt()).put("public_key",publicKey.trim())
                        .put("pre_shared_key",psk.trim()).put("allowed_ips",org.json.JSONArray(routes))
                        .put("persistent_keepalive_interval",keepalive.toInt())
                    val copy = org.json.JSONArray(peers.toString())
                    copy.put(index,obj)
                    change(copy.toString())
                    editing = null
                }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
        }
    }
}
