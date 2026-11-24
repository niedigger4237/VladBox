package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.nekohasekai.sagernet.database.DataStore

@Composable
fun SimpleProxyScreen(shadowsocks: Boolean, onSave: () -> Unit, onClose: () -> Unit, menuItems: List<Pair<String, () -> Unit>>) {
    val store = DataStore.profileCacheStore
    var revision by remember { mutableStateOf(0) }
    var section by rememberSaveable { mutableStateOf("") }
    val version = remember(revision) { if (shadowsocks) 2 else DataStore.serverProtocolInt }
    val name = remember(revision) { if (shadowsocks) store.getString("name", "").orEmpty() else DataStore.profileName }
    val address = remember(revision) { if (shadowsocks) store.getString("serverAddress", "").orEmpty() else DataStore.serverAddress }
    val port = remember(revision) { if (shadowsocks) store.getString("serverPort", "").orEmpty() else DataStore.serverPort.toString() }
    val method = remember(revision) { store.getString("method", "").orEmpty() }
    val password = remember(revision) { if (shadowsocks) store.getString("password", "").orEmpty() else DataStore.serverPassword }
    val dialerValid = runCatching { io.nekohasekai.sagernet.fmt.v2ray.applyClientDialer(store.getString("outboundDialer","").orEmpty(), store.getString("outboundNetwork","").orEmpty(), mutableMapOf()) }.isSuccess
    val valid = dialerValid && address.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } == true && (!shadowsocks || (method.isNotBlank() && (method == "none" || password.isNotBlank())))
    val title = if (shadowsocks) "Shadowsocks" else when(version) { 0 -> "SOCKS4"; 1 -> "SOCKS4A"; else -> "SOCKS5" }
    fun write(key: String, value: String) { store.putString(key,value); revision++ }
    androidx.activity.compose.BackHandler(section.isNotEmpty()) { section = "" }
    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = section.ifEmpty { title }, onNavigation = onClose, onBack = { if (section.isNotEmpty()) section = "" else onClose() }, extraActions = {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = onSave, enabled = valid) { Text("Save") }
            Box {
                IconButton(onClick = { expanded = true }) { Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_baseline_more_vert_24), "More options") }
                DropdownMenu(expanded, { expanded = false }) { menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) } }
            }
        })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section.isEmpty()) {
                val sections = listOf("General" to "Server and protocol", "Authentication" to (if (shadowsocks) method else "Credentials"), "Advanced" to (if (shadowsocks) "Plugin and UDP" else "UDP options"), "Connection" to "Network and dialer")
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
            }
            if (section == "General") item {
                SimpleSection("Server") {
                    SimpleField("Profile name",name) { if (shadowsocks) write("name",it) else { DataStore.profileName = it; revision++ } }
                    SimpleField("Server address",address) { if (shadowsocks) write("serverAddress",it) else { DataStore.serverAddress = it; revision++ } }
                    if (shadowsocks) SimpleField("Server port",port) { write("serverPort",it) }
                    else {
                        var draft by rememberSaveable { mutableStateOf(port) }
                        SimpleField("Server port",draft) { draft = it; DataStore.serverPort = it.toIntOrNull() ?: 0; revision++ }
                    }
                    if (!shadowsocks) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("SOCKS4","SOCKS4A","SOCKS5").forEachIndexed { i, label ->
                            FilterChip(selected = version == i, onClick = { DataStore.serverProtocolInt = i; revision++ }, label = { Text(label) })
                        }
                    }
                }
            }
            if (section == "Authentication") item {
                SimpleSection("Authentication") {
                    if (shadowsocks) {
                        val methods = listOf("none","aes-128-gcm","aes-192-gcm","aes-256-gcm","chacha20-ietf-poly1305","xchacha20-ietf-poly1305","2022-blake3-aes-128-gcm","2022-blake3-aes-256-gcm","2022-blake3-chacha20-poly1305","aes-128-ctr","aes-192-ctr","aes-256-ctr","aes-128-cfb","aes-192-cfb","aes-256-cfb","rc4-md5","chacha20-ietf","xchacha20")
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(method.ifBlank { "Select encryption method" }) }
                            DropdownMenu(expanded, { expanded = false }) { methods.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { write("method",m); expanded = false }) } }
                        }
                    } else SimpleField(if (version == 2) "Username" else "User ID",DataStore.serverUsername) { DataStore.serverUsername = it; revision++ }
                    if (shadowsocks || version == 2) SimpleField("Password",password,true) { if (shadowsocks) write("password",it) else { DataStore.serverPassword = it; revision++ } }
                }
            }
            if (section == "Connection") item {
                SimpleSection("Connection") {
                    if (shadowsocks || version == 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "TCP + UDP", "tcp" to "TCP", "udp" to "UDP").forEach { (value,label) ->
                            FilterChip(selected = store.getString("outboundNetwork","").orEmpty() == value, onClick = { write("outboundNetwork",value) }, label = { Text(label) })
                        }
                    } else Text("TCP only")
                    VlessDialer(store.getString("outboundDialer","").orEmpty()) { write("outboundDialer",it) }
                    if (!dialerValid) Text("Invalid connection options", color = MaterialTheme.colorScheme.error)
                }
            }
            if (section == "Advanced") item {
                SimpleSection("Advanced") {
                    if (shadowsocks) {
                        SimpleField("Plugin",store.getString("pluginName","").orEmpty()) { write("pluginName",it) }
                        SimpleField("Plugin options",store.getString("pluginConfig","").orEmpty()) { write("pluginConfig",it) }
                    }
                    if (shadowsocks || version == 2) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("UDP over TCP", Modifier.weight(1f))
                        Switch(store.getBoolean("sUoT",false), { store.putBoolean("sUoT",it); revision++ })
                    } else Text("SOCKS4 and SOCKS4A support TCP only.")
                }
            }
        }
    }
}
@Composable private fun SimpleSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); content() } }
}
@Composable private fun SimpleField(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
