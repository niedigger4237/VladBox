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
fun TlsProxyScreen(shadow: Boolean, onSave: () -> Unit, onClose: () -> Unit, menuItems: List<Pair<String, () -> Unit>>) {
    val store = DataStore.profileCacheStore
    var revision by remember { mutableStateOf(0) }
    var section by rememberSaveable { mutableStateOf("") }
    fun read(key: String) = store.getString(key, "").orEmpty()
    fun write(key: String, value: String) { store.putString(key,value); revision++ }
    val valid = remember(revision) {
        read("serverAddress").isNotBlank() && (read("serverPort").toIntOrNull() ?: 0) in 1..65535 &&
            (if (shadow) read("version") in listOf("1","2","3") && (read("version") == "1" || read("password").isNotBlank())
             else read("password").isNotBlank() && (read("minIdleSession").isBlank() || (read("minIdleSession").toIntOrNull() ?: -1) >= 0))
    }
    androidx.activity.compose.BackHandler(section.isNotEmpty()) { section = "" }
    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = section.ifEmpty { if (shadow) "ShadowTLS" else "AnyTLS" }, onNavigation = onClose, onBack = { if (section.isNotEmpty()) section = "" else onClose() }, extraActions = {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = onSave, enabled = valid) { Text("Save") }
            Box {
                IconButton(onClick = { expanded = true }) { Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_baseline_more_vert_24), "More options") }
                DropdownMenu(expanded, { expanded = false }) { menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) } }
            }
        })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section.isEmpty()) {
                val sections = listOf("General" to "Server and authentication", "Security" to "TLS and certificates") + if (shadow) emptyList() else listOf("Sessions" to "Idle session management")
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
            item {
                if (section.isNotEmpty()) SimpleSection(section) {
                    when(section) {
                        "General" -> {
                            SimpleField("Profile name",read("name")) { write("name",it) }
                            SimpleField("Server address",read("serverAddress")) { write("serverAddress",it) }
                            SimpleField("Server port",read("serverPort")) { write("serverPort",it) }
                            if (shadow) ProfileChoices(listOf("1" to "Version 1","2" to "Version 2","3" to "Version 3"),read("version")) { write("version",it) }
                            if (!shadow || read("version") != "1") SimpleField("Password",read("password"),true) { write("password",it) }
                        }
                        "Security" -> {
                            SimpleField("Server name (SNI)",read("sni")) { write("sni",it) }
                            SimpleField("ALPN",read("alpn")) { write("alpn",it) }
                            SimpleField("CA certificate (PEM)",read("certificates")) { write("certificates",it) }
                            SimpleToggle("Allow insecure",store.getBoolean("allowInsecure",false)) { store.putBoolean("allowInsecure",it); revision++ }
                            SimpleField("uTLS fingerprint",read("utlsFingerprint")) { write("utlsFingerprint",it) }
                            if (!shadow) SimpleField("ECH configuration",read("echConfig")) { write("echConfig",it) }
                        }
                        "Sessions" -> {
                            SimpleField("Idle session check interval",read("idleSessionCheckInterval")) { write("idleSessionCheckInterval",it) }
                            SimpleField("Idle session timeout",read("idleSessionTimeout")) { write("idleSessionTimeout",it) }
                            SimpleField("Minimum idle sessions",read("minIdleSession")) { write("minIdleSession",it) }
                            SimpleField("Client metadata",read("clientMetadata")) { write("clientMetadata",it) }
                        }
                    }
                }
            }
        }
    }
}
