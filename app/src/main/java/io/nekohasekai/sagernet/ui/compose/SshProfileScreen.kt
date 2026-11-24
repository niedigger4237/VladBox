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
fun SshProfileScreen(onSave: () -> Unit, onClose: () -> Unit, menuItems: List<Pair<String, () -> Unit>>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = DataStore.profileCacheStore
    var revision by remember { mutableStateOf(0) }
    var section by rememberSaveable { mutableStateOf("") }
    fun read(key: String) = store.getString(key,"").orEmpty()
    fun write(key: String,value: String) { store.putString(key,value); revision++ }
    var importError by remember { mutableStateOf("") }
    val importKey = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(65537)
                    var size = 0
                    while (size < buffer.size) {
                        val count = stream.read(buffer, size, buffer.size - size)
                        if (count <= 0) break
                        size += count
                    }
                    require(size in 1..65536) { "Invalid key file size" }
                    String(buffer, 0, size, Charsets.UTF_8)
                } ?: error("Cannot read key")
            }.onSuccess { key ->
                if (key.contains("PRIVATE KEY-----")) {
                    DataStore.serverPrivateKey = key
                    write("privateKeyPath","")
                    importError = ""
                } else importError = "Unsupported private key file"
            }.onFailure { importError = "Unable to read private key" }
        }
    }
    val auth = remember(revision) { DataStore.serverAuthType }
    val valid = remember(revision) {
        DataStore.serverAddress.isNotBlank() && DataStore.serverPort in 1..65535 &&
        DataStore.serverUsername.isNotBlank() && auth in 0..2 &&
        (auth != 2 || (DataStore.serverPrivateKey.isNotBlank() xor read("privateKeyPath").isNotBlank())) &&
        runCatching { io.nekohasekai.sagernet.fmt.v2ray.applyClientDialer(read("outboundDialer"),"",mutableMapOf()) }.isSuccess
    }
    androidx.activity.compose.BackHandler(section.isNotEmpty()) { section = "" }
    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = section.ifEmpty { "SSH" }, onNavigation = onClose, onBack = { if (section.isNotEmpty()) section = "" else onClose() }, extraActions = {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = onSave, enabled = valid) { Text("Save") }
            Box {
                IconButton(onClick = { expanded = true }) { Text("⋮") }
                DropdownMenu(expanded, { expanded = false }) { menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) } }
            }
        })
        LazyColumn(Modifier.weight(1f),contentPadding = PaddingValues(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section.isEmpty()) {
                val sections = listOf("General" to "Server and username","Authentication" to "Password or private key","Security" to "Host key and SSH algorithms","Advanced" to "Dialer options")
                items(sections.size) { i ->
                    val (label,detail) = sections[i]
                    Card(onClick = { section = label },modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(20.dp)) {
                            Column(Modifier.weight(1f)) { Text(label,style = MaterialTheme.typography.titleLarge); Text(detail) }
                            Text("›",style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                item { Button(onClick = onSave,enabled = valid,modifier = Modifier.fillMaxWidth()) { Text("Save profile") } }
            } else item {
                // Read revision inside the section so cache-backed controls refresh after edits.
                val current = revision
                key(section) {
                    SimpleSection(section) {
                        when(section) {
                            "General" -> {
                                SimpleField("Profile name",DataStore.profileName) { DataStore.profileName = it; revision++ }
                                SimpleField("Server address",DataStore.serverAddress) { DataStore.serverAddress = it; revision++ }
                                var port by rememberSaveable { mutableStateOf(DataStore.serverPort.toString()) }
                                SimpleField("Server port",port) { port = it; DataStore.serverPort = it.toIntOrNull() ?: 0; revision++ }
                                SimpleField("Username",DataStore.serverUsername) { DataStore.serverUsername = it; revision++ }
                            }
                            "Authentication" -> {
                                ProfileChoices(listOf("0" to "None","1" to "Password","2" to "Private key"),auth.toString()) { DataStore.serverAuthType = it.toInt(); revision++ }
                                if (auth == 1) SimpleField("Password",DataStore.serverPassword,true) { DataStore.serverPassword = it; revision++ }
                                if (auth == 2) {
                                    SimpleField("Private key (PEM)",DataStore.serverPrivateKey,true) { DataStore.serverPrivateKey = it; revision++ }
                                    OutlinedButton(onClick = { importKey.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import private key from file") }
                                    if (importError.isNotEmpty()) Text(importError,color = MaterialTheme.colorScheme.error)
                                    SimpleField("Private key path",read("privateKeyPath")) { write("privateKeyPath",it) }
                                    SimpleField("Private key passphrase",DataStore.serverPassword1,true) { DataStore.serverPassword1 = it; revision++ }
                                }
                            }
                            "Security" -> {
                                SimpleField("Server host key",DataStore.serverCertificates) { DataStore.serverCertificates = it; revision++ }
                                Text("Set a trusted server host key to verify the SSH server identity.",style = MaterialTheme.typography.bodySmall)
                                Text("Leave algorithm lists empty to use core defaults. Separate custom algorithms with commas.",style = MaterialTheme.typography.bodySmall)
                                SimpleField("Client version",read("clientVersion")) { write("clientVersion",it) }
                                SshAlgorithms("Host key algorithms",read("hostKeyAlgorithms"),listOf("ssh-ed25519","ecdsa-sha2-nistp256","ecdsa-sha2-nistp384","ecdsa-sha2-nistp521","rsa-sha2-256","rsa-sha2-512")) { write("hostKeyAlgorithms",it) }
                                SshAlgorithms("Cipher algorithms",read("cipherAlgorithms"),listOf("aes128-ctr","aes192-ctr","aes256-ctr","aes128-gcm@openssh.com","aes256-gcm@openssh.com","chacha20-poly1305@openssh.com")) { write("cipherAlgorithms",it) }
                                SshAlgorithms("MAC algorithms",read("macAlgorithms"),listOf("hmac-sha2-256","hmac-sha2-512","hmac-sha2-256-etm@openssh.com","hmac-sha2-512-etm@openssh.com")) { write("macAlgorithms",it) }
                                SshAlgorithms("Key exchange algorithms",read("kexAlgorithms"),listOf("curve25519-sha256","curve25519-sha256@libssh.org","ecdh-sha2-nistp256","ecdh-sha2-nistp384","ecdh-sha2-nistp521","diffie-hellman-group14-sha256")) { write("kexAlgorithms",it) }
                            }
                            "Advanced" -> VlessDialer(read("outboundDialer")) { write("outboundDialer",it) }
                        }
                        if (!valid) Text("Check required fields and authentication settings.",color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SshAlgorithms(label: String, value: String, choices: List<String>, change: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    Text(label, style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(if (selected.isEmpty()) "Core default" else "${selected.size} selected")
    }
    if (expanded) {
        choices.forEach { algorithm ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = algorithm in selected, onCheckedChange = { checked ->
                    change((if (checked) selected + algorithm else selected.filterNot { it == algorithm }).distinct().joinToString(","))
                })
                Text(algorithm, Modifier.weight(1f))
            }
        }
        SimpleField("Custom ordered list",value,change = change)
        TextButton(onClick = { change("") }) { Text("Reset to core default") }
    }
}
