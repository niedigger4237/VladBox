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
    val valid = remember(revision, DataStore.profileCacheStore.observableRevision) {
        val tlsValid = runCatching {
            val extra = org.json.JSONObject(if (shadow) "{}" else read("tlsExtras").ifBlank { "{}" })
            fun value(bean: String, json: String) = if (shadow) read(bean) else extra.optString(json)
            val min = value("tlsMinVersion","min_version")
            val max = value("tlsMaxVersion","max_version")
            val versions = listOf("1.0","1.1","1.2","1.3")
            require(min.isBlank() || min in versions)
            require(max.isBlank() || max in versions)
            require(min.isBlank() || max.isBlank() || versions.indexOf(min) <= versions.indexOf(max))
            val cert = value("tlsClientCertificate","client_certificate")
            val certPath = value("tlsClientCertificatePath","client_certificate_path")
            val key = value("tlsClientKey","client_key")
            val keyPath = value("tlsClientKeyPath","client_key_path")
            require(cert.isBlank() || certPath.isBlank())
            require(key.isBlank() || keyPath.isBlank())
            require((cert.isNotBlank() || certPath.isNotBlank()) == (key.isNotBlank() || keyPath.isNotBlank()))
            require(read("certificates").isBlank() || value("tlsCertificatePath","certificate_path").isBlank())
            require(read("echConfig").isBlank() || value("echConfigPath","ech_config_path").isBlank())
            if (!shadow && extra.optString("reality_public_key").isNotBlank()) {
                require(read("sni").isNotBlank())
                require(read("echConfig").isBlank() && extra.optString("ech_config_path").isBlank() && extra.optString("ech_query_server_name").isBlank())
            }
            io.nekohasekai.sagernet.fmt.v2ray.applyClientDialer(read("outboundDialer"), "", mutableMapOf())
        }.isSuccess
        tlsValid && read("serverAddress").isNotBlank() && (read("serverPort").toIntOrNull() ?: 0) in 1..65535 &&
            (if (shadow) read("version") in listOf("1","2","3") && (read("version") == "1" || read("password").isNotBlank())
             else read("password").isNotBlank() && (read("minIdleSession").isBlank() || (read("minIdleSession").toIntOrNull() ?: -1) >= 0))
    }
    androidx.activity.compose.BackHandler(section.isNotEmpty()) { section = "" }
    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = section.ifEmpty { if (shadow) "ShadowTLS" else "AnyTLS" }, onNavigation = onClose, onBack = { if (section.isNotEmpty()) section = "" else onClose() }, extraActions = {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = onSave, enabled = valid) { Text("Save") }
            Box {
                IconButton(onClick = { expanded = true }) { Text("⋮") }
                DropdownMenu(expanded, { expanded = false }) { menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) } }
            }
        })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section.isEmpty()) {
                val sections = listOf("General" to "Server and authentication", "Security" to "TLS and certificates", "Advanced" to "Dialer options") + if (shadow) emptyList() else listOf("Sessions" to "Idle session management")
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
                if (!valid) Text("Check required fields and TLS / connection settings.", color = MaterialTheme.colorScheme.error)
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
                            if (shadow) {
                                SimpleToggle("Enable ECH",store.getBoolean("enableECH",false)) { store.putBoolean("enableECH",it); revision++ }
                                if (store.getBoolean("enableECH",false)) {
                                    SimpleField("ECH configuration",read("echConfig")) { write("echConfig",it) }
                                    SimpleField("ECH config path",read("echConfigPath")) { write("echConfigPath",it) }
                                    SimpleField("ECH query server name",read("echQueryName")) { write("echQueryName",it) }
                                }
                                SimpleToggle("TLS fragmentation",store.getBoolean("tlsFragment",false)) { store.putBoolean("tlsFragment",it); revision++ }
                                if (store.getBoolean("tlsFragment",false)) SimpleField("Fragment fallback delay",read("tlsFragmentFallbackDelay")) { write("tlsFragmentFallbackDelay",it) }
                                SimpleToggle("TLS record fragmentation",store.getBoolean("tlsRecordFragment",false)) { store.putBoolean("tlsRecordFragment",it); revision++ }
                            }
                            if (shadow && read("version") == "1") Text("ShadowTLS v1 always uses TLS 1.2.")
                            val extra = runCatching { org.json.JSONObject(read("tlsExtras").ifBlank { "{}" }) }.getOrElse { org.json.JSONObject() }
                            if (!shadow) {
                                listOf("reality_public_key" to "Reality public key", "reality_short_id" to "Reality short ID", "ech_config_path" to "ECH config path", "ech_query_server_name" to "ECH query server name", "fragment_fallback_delay" to "Fragment fallback delay").forEach { (key,label) ->
                                    SimpleField(label,extra.optString(key)) { value -> if (value.isBlank()) extra.remove(key) else extra.put(key,value); write("tlsExtras",extra.toString()) }
                                }
                                listOf("fragment" to "TLS fragmentation", "record_fragment" to "TLS record fragmentation").forEach { (key,label) ->
                                    SimpleToggle(label,extra.optBoolean(key)) { extra.put(key,it); write("tlsExtras",extra.toString()) }
                                }
                            }
                            SimpleField("Minimum TLS version",if (shadow) read("tlsMinVersion") else extra.optString("min_version"),false) { value ->
                                if (shadow) write("tlsMinVersion",value) else {
                                    if (value.isBlank()) extra.remove("min_version") else extra.put("min_version",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("Maximum TLS version",if (shadow) read("tlsMaxVersion") else extra.optString("max_version"),false) { value ->
                                if (shadow) write("tlsMaxVersion",value) else {
                                    if (value.isBlank()) extra.remove("max_version") else extra.put("max_version",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("Handshake timeout",if (shadow) read("tlsHandshakeTimeout") else extra.optString("handshake_timeout"),false) { value ->
                                if (shadow) write("tlsHandshakeTimeout",value) else {
                                    if (value.isBlank()) extra.remove("handshake_timeout") else extra.put("handshake_timeout",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("CA certificate path",if (shadow) read("tlsCertificatePath") else extra.optString("certificate_path"),false) { value ->
                                if (shadow) write("tlsCertificatePath",value) else {
                                    if (value.isBlank()) extra.remove("certificate_path") else extra.put("certificate_path",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("SHA-256 public key pins",if (shadow) read("tlsPubKeySha256") else extra.optString("certificate_public_key_sha256"),false) { value ->
                                if (shadow) write("tlsPubKeySha256",value) else {
                                    if (value.isBlank()) extra.remove("certificate_public_key_sha256") else extra.put("certificate_public_key_sha256",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("Curve preferences",if (shadow) read("tlsCurves") else extra.optString("curve_preferences"),false) { value ->
                                if (shadow) write("tlsCurves",value) else {
                                    if (value.isBlank()) extra.remove("curve_preferences") else extra.put("curve_preferences",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("Cipher suites",if (shadow) read("tlsCipherSuites") else extra.optString("cipher_suites"),false) { value ->
                                if (shadow) write("tlsCipherSuites",value) else {
                                    if (value.isBlank()) extra.remove("cipher_suites") else extra.put("cipher_suites",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("Client certificate PEM",if (shadow) read("tlsClientCertificate") else extra.optString("client_certificate"),false) { value ->
                                if (shadow) write("tlsClientCertificate",value) else {
                                    if (value.isBlank()) extra.remove("client_certificate") else extra.put("client_certificate",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("Client certificate path",if (shadow) read("tlsClientCertificatePath") else extra.optString("client_certificate_path"),false) { value ->
                                if (shadow) write("tlsClientCertificatePath",value) else {
                                    if (value.isBlank()) extra.remove("client_certificate_path") else extra.put("client_certificate_path",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("Client private key PEM",if (shadow) read("tlsClientKey") else extra.optString("client_key"),true) { value ->
                                if (shadow) write("tlsClientKey",value) else {
                                    if (value.isBlank()) extra.remove("client_key") else extra.put("client_key",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                            SimpleField("Client key path",if (shadow) read("tlsClientKeyPath") else extra.optString("client_key_path"),false) { value ->
                                if (shadow) write("tlsClientKeyPath",value) else {
                                    if (value.isBlank()) extra.remove("client_key_path") else extra.put("client_key_path",value)
                                    write("tlsExtras",extra.toString())
                                }
                            }
                        }
                        "Advanced" -> {
                            VlessDialer(read("outboundDialer")) { value ->
                                val json = org.json.JSONObject(value.ifBlank { "{}" })
                                if (!shadow) json.remove("tcp_fast_open")
                                write("outboundDialer",json.toString())
                            }
                            if (!shadow) Text("TCP Fast Open is not supported by AnyTLS.")
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
