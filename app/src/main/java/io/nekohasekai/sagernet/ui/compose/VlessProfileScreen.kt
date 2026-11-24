package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.nekohasekai.sagernet.database.DataStore

@Composable
fun VlessProfileScreen(onSave: () -> Unit, onClose: () -> Unit, menuItems: List<Pair<String, () -> Unit>> = emptyList()) {
    val store = DataStore.profileCacheStore
    var revision by remember { mutableStateOf(0) }
    fun read(key: String) = store.getString(key, "") ?: ""
    val transport = remember(revision) { read("type").ifBlank { "tcp" } }
    val security = remember(revision) { read("security") }
    val mux = remember(revision) { store.getBoolean("enableMux", false) }
    fun write(key: String, value: String) { store.putString(key, value); revision++ }
    fun toggle(key: String, value: Boolean) { store.putBoolean(key, value); revision++ }
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var reality by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(read("realityPubKey").isNotBlank()) }
    val valid = read("serverAddress").isNotBlank() && read("serverPort").toIntOrNull()?.let { it in 1..65535 } == true && read("uuid").isNotBlank() && (!reality || read("realityPubKey").isNotBlank())
    androidx.activity.compose.BackHandler(enabled = section.isNotEmpty()) { section = "" }
    Column(Modifier.fillMaxSize()) {
    VladTopAppBar(title = section.ifEmpty { "VLESS" }, onBack = { if (section.isNotEmpty()) section = "" else onClose() }, extraActions = {
        var expanded by remember { mutableStateOf(false) }
        TextButton(onClick = onSave, enabled = valid) { Text("Save") }
        Box {
            IconButton(onClick = { expanded = true }) { Icon(androidx.compose.ui.res.painterResource(io.nekohasekai.sagernet.R.drawable.ic_baseline_more_vert_24), "More options") }
            DropdownMenu(expanded, { expanded = false }) { menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) } }
        }
    })
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (section.isEmpty()) {
            val sections = listOf("General" to "Server, UUID and flow", "Transport" to transport.uppercase(), "Security" to (if (reality) "Reality" else security.ifBlank { "None" }), "Multiplex" to (if (mux) "Enabled" else "Disabled"), "Connection" to "Network options")
            items(sections.size) { index ->
                val (name, description) = sections[index]
                Card(onClick = { section = name }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleLarge); Text(description, style = MaterialTheme.typography.bodyMedium) }
                        Text("›", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
        if (section == "General") item {
            ProfileSection("General") {
                ProfileText("Profile name", "name", read("name"), ::write)
                ProfileText("Server address", "serverAddress", read("serverAddress"), ::write)
                ProfileText("Server port", "serverPort", read("serverPort"), ::write)
                ProfileText("UUID", "uuid", read("uuid"), ::write)
                ProfileChoices(listOf("" to "None", "xtls-rprx-vision" to "Vision"), read("encryption")) { write("encryption", it) }
                ProfileText("Encryption", "vlessEncryption", read("vlessEncryption"), ::write)
                Text("Packet encoding")
                ProfileChoices(listOf("0" to "None","1" to "Packet address","2" to "XUDP"), read("packetEncoding")) { write("packetEncoding",it) }
            }
        }
        if (section == "Transport") item {
            ProfileSection("Transport") {
                ProfileChoices(listOf("tcp" to "TCP", "ws" to "WebSocket", "grpc" to "gRPC", "xhttp" to "XHTTP", "http" to "HTTP", "httpupgrade" to "HTTP Upgrade", "kcp" to "mKCP", "quic" to "QUIC"), transport) { write("type", it) }
                if (transport in listOf("ws","http","httpupgrade","xhttp")) {
                    ProfileText("Host", "host", read("host"), ::write)
                    ProfileText("Path", "path", read("path"), ::write)
                }
                if (transport == "xhttp") {
                    ProfileChoices(listOf("auto","packet-up","stream-up","stream-one").map { it to it }, read("xhttpMode").ifBlank { "auto" }) { write("xhttpMode", it) }
                    XhttpComposeFields(read("xhttpExtra"), read("xhttpMode")) { write("xhttpExtra", it) }
                }
                if (transport == "grpc") {
                    ProfileText("Service name", "path", read("path"), ::write)
                    ProfileText("Idle timeout", "grpcIdleTimeout", read("grpcIdleTimeout"), ::write)
                    ProfileText("Ping timeout", "grpcPingTimeout", read("grpcPingTimeout"), ::write)
                }
                if (transport == "ws") {
                    ProfileText("Max early data", "wsMaxEarlyData", read("wsMaxEarlyData"), ::write)
                    ProfileText("Early data header", "earlyDataHeaderName", read("earlyDataHeaderName"), ::write)
                }
                if (transport == "http") ProfileText("HTTP method", "httpMethod", read("httpMethod"), ::write)
                if (transport in listOf("http","ws","httpupgrade")) ProfileText("Headers", "transportHeaders", read("transportHeaders"), ::write)
                if (transport == "kcp") {
                    listOf("kcpMtu","kcpTti","kcpUp","kcpDown","kcpRead","kcpWrite","kcpHeader","kcpSeed").forEach { ProfileText(it, it, read(it), ::write) }
                    ProfileSwitch("Congestion control", store.getBoolean("kcpCongestion", false)) { toggle("kcpCongestion", it) }
                }
            }
        }
        if (section == "Security") {
            item {
                ProfileSection("Security") {
                    ProfileChoices(listOf("" to "None", "tls" to "TLS", "reality" to "Reality"), if (reality) "reality" else security) {
                        reality = it == "reality"
                        write("security", if (reality) "tls" else it)
                        if (!reality) { write("realityPubKey",""); write("realityShortId",""); toggle("realityHybrid",false) }
                        else { toggle("tlsDisableSni",false); write("tlsSpoof",""); write("tlsSpoofMethod","") }
                    }
                    if ("tls" in security) {
                        ProfileText("Server name (SNI)", "sni", read("sni"), ::write)
                        ProfileText("ALPN", "alpn", read("alpn"), ::write)
                        ProfileText("uTLS fingerprint", "utlsFingerprint", read("utlsFingerprint"), ::write)
                        if (!reality) {
                            ProfileSwitch("Allow insecure",store.getBoolean("allowInsecure",false)) { toggle("allowInsecure",it) }
                            ProfileSwitch("Disable SNI",store.getBoolean("tlsDisableSni",false)) { toggle("tlsDisableSni",it) }
                        }
                    }
                }
            }
            if ("tls" in security) {
                if (reality) item {
                    ProfileSection("Reality") {
                        ProfileText("Public key","realityPubKey",read("realityPubKey"),::write)
                        ProfileText("Short ID","realityShortId",read("realityShortId"),::write)
                        ProfileSwitch("X25519MLKEM768",store.getBoolean("realityHybrid",false)) { toggle("realityHybrid",it) }
                    }
                }
                item {
                    ProfileSection("TLS parameters") {
                        for ((key,label) in listOf("tlsMinVersion" to "Minimum TLS version","tlsMaxVersion" to "Maximum TLS version")) {
                            Text(label)
                            ProfileChoices(listOf("" to "Default","1.0" to "1.0","1.1" to "1.1","1.2" to "1.2","1.3" to "1.3"),read(key)) { write(key,it) }
                        }
                        ProfileText("Cipher suites","tlsCipherSuites",read("tlsCipherSuites"),::write)
                        ProfileText("Curve preferences","tlsCurves",read("tlsCurves"),::write)
                        ProfileText("Handshake timeout","tlsHandshakeTimeout",read("tlsHandshakeTimeout"),::write)
                        Text("TLS engine")
                        ProfileChoices(listOf("" to "Default","go" to "Go"),read("tlsEngine")) { write("tlsEngine",it) }
                    }
                }
                item {
                    ProfileSection("Certificates and mTLS") {
                        for ((key,label) in listOf("certificates" to "CA certificate (PEM)","tlsCertificatePath" to "CA certificate path","tlsPubKeySha256" to "Public key SHA-256 pin","tlsClientCertificate" to "Client certificate (PEM)","tlsClientCertificatePath" to "Client certificate path","tlsClientKey" to "Client private key (PEM)","tlsClientKeyPath" to "Client private key path")) ProfileText(label,key,read(key),::write)
                    }
                }
                item {
                    ProfileSection("Encrypted Client Hello (ECH)") {
                        ProfileSwitch("Enable ECH",store.getBoolean("enableECH",false)) { toggle("enableECH",it) }
                        if (store.getBoolean("enableECH",false)) {
                            for ((key,label) in listOf("echConfig" to "ECH configuration","echConfigPath" to "ECH configuration path","echQueryName" to "ECH query server name")) ProfileText(label,key,read(key),::write)
                        }
                    }
                }
                item {
                    ProfileSection("Fragmentation and advanced TLS") {
                        ProfileSwitch("TLS fragmentation",store.getBoolean("tlsFragment",false)) { toggle("tlsFragment",it) }
                        if (store.getBoolean("tlsFragment",false)) ProfileText("Fragment fallback delay","tlsFragmentFallbackDelay",read("tlsFragmentFallbackDelay"),::write)
                        ProfileSwitch("TLS record fragmentation",store.getBoolean("tlsRecordFragment",false)) { toggle("tlsRecordFragment",it) }
                        if (!reality) {
                            ProfileText("Spoof server name","tlsSpoof",read("tlsSpoof"),::write)
                            if (read("tlsSpoof").isNotBlank()) {
                                Text("Spoof method")
                                ProfileChoices(listOf("" to "Default","wrong-sequence" to "Wrong sequence","wrong-checksum" to "Wrong checksum","wrong-ack" to "Wrong ACK","wrong-md5" to "Wrong MD5","wrong-timestamp" to "Wrong timestamp"),read("tlsSpoofMethod")) { write("tlsSpoofMethod",it) }
                            }
                        }
                        ProfileSwitch("Kernel TLS TX",store.getBoolean("tlsKernelTx",false)) { toggle("tlsKernelTx",it) }
                        ProfileSwitch("Kernel TLS RX",store.getBoolean("tlsKernelRx",false)) { toggle("tlsKernelRx",it) }
                    }
                }
            }
        }
        if (section == "Multiplex") item {
            ProfileSection("Multiplex") {
                ProfileSwitch("Enable Multiplex", mux) { toggle("enableMux",it) }
                if (mux) {
                    ProfileChoices(listOf("0" to "h2mux","1" to "smux","2" to "yamux","3" to "rmux"), read("muxType")) { write("muxType",it) }
                    listOf("muxConcurrency","muxMaxConnections","muxMinStreams","muxBrutalUp","muxBrutalDown").forEach { ProfileText(it,it,read(it),::write) }
                    listOf("muxPadding","muxBrutal").forEach { key -> ProfileSwitch(key,store.getBoolean(key,false)) { toggle(key,it) } }
                }
            }
        }
        if (section == "Connection") item { ProfileSection("Connection") { ProfileChoices(listOf("" to "TCP + UDP","tcp" to "TCP","udp" to "UDP"), read("vlessNetwork")) { write("vlessNetwork",it) } } }
        if (section.isEmpty()) item { Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = valid) { Text("Save profile") } }
    }
    }
}

@Composable private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
@Composable private fun ProfileText(label: String, key: String, value: String, write: (String,String)->Unit) {
    OutlinedTextField(value, { write(key,it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), visualTransformation = if (key == "tlsClientKey") PasswordVisualTransformation() else VisualTransformation.None)
}
@Composable private fun ProfileSwitch(label: String, value: Boolean, change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, change)
    }
}
@Composable private fun ProfileChoices(options: List<Pair<String,String>>, selected: String, change: (String)->Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (value,label) ->
                    if (selected == value) Button(onClick = { change(value) }, modifier = Modifier.weight(1f)) { Text(label) }
                    else OutlinedButton(onClick = { change(value) }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
        }
    }
}

@Composable private fun XhttpComposeFields(raw: String, mode: String, change: (String) -> Unit) {
        val fields = linkedMapOf(
            "headers" to "Headers (JSON)",
            "x_padding_bytes" to "Padding bytes",
            "no_grpc_header" to "Disable gRPC header",
            "no_sse_header" to "Disable SSE header",
            "sc_max_each_post_bytes" to "Max POST bytes",
            "sc_min_posts_interval_ms" to "Min POST interval (ms)",
            "sc_max_buffered_posts" to "Max buffered POSTs",
            "sc_stream_up_server_secs" to "Stream up server seconds",
            "x_padding_obfs_mode" to "Padding obfuscation",
            "x_padding_key" to "Padding key",
            "x_padding_header" to "Padding header",
            "x_padding_placement" to "Padding placement",
            "x_padding_method" to "Padding method",
            "uplink_http_method" to "Uplink HTTP method",
            "session_placement" to "Session placement",
            "session_key" to "Session key",
            "session_id_table" to "Session ID table",
            "session_id_length" to "Session ID length",
            "seq_placement" to "Sequence placement",
            "seq_key" to "Sequence key",
            "uplink_data_placement" to "Uplink data placement",
            "uplink_data_key" to "Uplink data key",
            "uplink_chunk_size" to "Uplink chunk size",
            "server_max_header_bytes" to "Max header bytes",
            "domain_strategy" to "Domain strategy",
            "congestion_controller" to "Congestion controller",
            "cwnd" to "CWND",
            "trusted_x_forwarded_for" to "Trusted X-Forwarded-For",
            "xmux" to "XMUX (JSON)",
            "download" to "Download settings (JSON)"
        )

        val xmuxFields = linkedMapOf(
            "xmux.max_concurrency" to "XMUX Max concurrency",
            "xmux.max_connections" to "XMUX Max connections",
            "xmux.c_max_reuse_times" to "XMUX Connection reuse times",
            "xmux.h_max_request_times" to "XMUX HTTP request times",
            "xmux.h_max_reusable_secs" to "XMUX Reusable seconds",
            "xmux.h_keep_alive_period" to "XMUX Keep alive period"
        )
        val downloadFields = linkedMapOf(
            "download.server" to "Download server address",
            "download.server_port" to "Download server port",
            "download.host" to "Download host",
            "download.path" to "Download path",
            "download.x_padding_bytes" to "Download padding bytes",
            "download.headers" to "Download headers (JSON)",
            "download.detour" to "Download detour",
            "download.tls.enabled" to "Download TLS",
            "download.tls.server_name" to "Download SNI",
            "download.tls.insecure" to "Download allow insecure",
            "download.tls.alpn" to "Download ALPN (JSON array)",
            "download.tls.utls.enabled" to "Download uTLS",
            "download.tls.utls.fingerprint" to "Download fingerprint",
            "download.tls.reality.enabled" to "Download Reality",
            "download.tls.reality.public_key" to "Download Reality public key",
            "download.tls.reality.short_id" to "Download Reality short ID"
        )

    val json = remember(raw) { runCatching { org.json.JSONObject(raw.ifBlank { "{}" }) }.getOrNull() }
    var showRaw by remember { mutableStateOf(false) }
    fun read(key: String): Any? {
        val parts = key.split('.')
        val parent = parts.dropLast(1).fold(json) { obj, part -> obj?.optJSONObject(part) }
        return parent?.opt(parts.last())?.takeUnless { it == org.json.JSONObject.NULL }
    }
    fun update(key: String, value: Any?) {
        if (json == null) return
        val copy = org.json.JSONObject(json.toString())
        val parts = key.split('.')
        val target = parts.dropLast(1).fold(copy) { obj, part -> obj.optJSONObject(part) ?: org.json.JSONObject().also { obj.put(part,it) } }
        if (value == null) target.remove(parts.last()) else target.put(parts.last(),value)
        change(copy.toString())
    }
    if (json == null) Text("Invalid XHTTP JSON", color = MaterialTheme.colorScheme.error)
    else {
        val bools = setOf("no_grpc_header","no_sse_header","x_padding_obfs_mode","download.tls.enabled","download.tls.insecure","download.tls.utls.enabled","download.tls.reality.enabled")
        val numbers = setOf("sc_max_buffered_posts","server_max_header_bytes","cwnd","xmux.h_keep_alive_period","download.server_port")
        val objects = setOf("headers","download.headers")
        val arrays = setOf("download.tls.alpn","trusted_x_forwarded_for")
        val download = read("download") != null
        for ((title, entries) in listOf("XHTTP settings" to fields.filterKeys { it !in setOf("xmux","download") }, "XMUX" to xmuxFields, "Download settings" to downloadFields)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (title == "Download settings") {
                ProfileSwitch("Separate download connection",download) { update("download",if (it) org.json.JSONObject().put("x_padding_bytes","100-1000") else null) }
                if (mode == "stream-one" && download) Text("Download connection is not supported in stream-one mode.",color = MaterialTheme.colorScheme.error)
                if (!download) continue
            }
            for ((key,label) in entries) {
                if (key in bools) ProfileSwitch(label,read(key) == true) { update(key,it) }
                else {
                    var draft by remember(key,read(key)?.toString()) { mutableStateOf(read(key)?.toString().orEmpty()) }
                    var error by remember(key) { mutableStateOf(false) }
                    OutlinedTextField(draft,{ value ->
                        draft = value
                        runCatching {
                            val v = value.trim()
                            val parsed = if (v.isEmpty()) null else when (key) {
                                in numbers -> v.toLong().also { require(it >= 0); if (key == "download.server_port") require(it in 1L..65535L) }
                                in objects -> org.json.JSONObject(v)
                                in arrays -> org.json.JSONArray(v)
                                else -> v
                            }
                            update(key,parsed)
                        }.onSuccess { error = false }.onFailure { error = true }
                    },label = { Text(label) },modifier = Modifier.fillMaxWidth(),isError = error)
                }
            }
        }
    }
    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide Extra JSON" else "Show Extra JSON") }
    if (showRaw || json == null) OutlinedTextField(raw,change,label = { Text("Extra JSON") },modifier = Modifier.fillMaxWidth())
}
