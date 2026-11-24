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
fun VlessProfileScreen(onSave: () -> Unit) {
    val store = DataStore.profileCacheStore
    var revision by remember { mutableStateOf(0) }
    fun read(key: String) = store.getString(key, "") ?: ""
    val transport = remember(revision) { read("type").ifBlank { "tcp" } }
    val security = remember(revision) { read("security") }
    val mux = remember(revision) { store.getBoolean("enableMux", false) }
    fun write(key: String, value: String) { store.putString(key, value); revision++ }
    fun toggle(key: String, value: Boolean) { store.putBoolean(key, value); revision++ }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ProfileSection("General") {
                ProfileText("Profile name", "name", read("name"), ::write)
                ProfileText("Server address", "serverAddress", read("serverAddress"), ::write)
                ProfileText("Server port", "serverPort", read("serverPort"), ::write)
                ProfileText("UUID", "uuid", read("uuid"), ::write)
                ProfileChoices(listOf("" to "None", "xtls-rprx-vision" to "Vision"), read("encryption")) { write("encryption", it) }
                ProfileText("Encryption", "vlessEncryption", read("vlessEncryption"), ::write)
                ProfileText("Packet encoding (index)", "packetEncoding", read("packetEncoding"), ::write)
            }
        }
        item {
            ProfileSection("Transport") {
                ProfileChoices(listOf("tcp" to "TCP", "ws" to "WebSocket", "grpc" to "gRPC", "xhttp" to "XHTTP", "http" to "HTTP", "httpupgrade" to "HTTP Upgrade", "kcp" to "mKCP", "quic" to "QUIC"), transport) { write("type", it) }
                if (transport in listOf("ws","http","httpupgrade","xhttp")) {
                    ProfileText("Host", "host", read("host"), ::write)
                    ProfileText("Path", "path", read("path"), ::write)
                }
                if (transport == "xhttp") {
                    ProfileChoices(listOf("auto","packet-up","stream-up","stream-one").map { it to it }, read("xhttpMode").ifBlank { "auto" }) { write("xhttpMode", it) }
                    ProfileText("Extra JSON", "xhttpExtra", read("xhttpExtra"), ::write)
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
        item {
            ProfileSection("Security") {
                ProfileChoices(listOf("" to "None", "tls" to "TLS"), security) { write("security", it) }
                if ("tls" in security) {
                    ProfileText("SNI", "sni", read("sni"), ::write)
                    ProfileText("Fingerprint", "utlsFingerprint", read("utlsFingerprint"), ::write)
                    ProfileText("Reality public key", "realityPubKey", read("realityPubKey"), ::write)
                    ProfileText("Reality short ID", "realityShortId", read("realityShortId"), ::write)
                    listOf("alpn","certificates","tlsPubKeySha256","tlsCipherSuites","tlsMinVersion","tlsMaxVersion","tlsCurves","tlsHandshakeTimeout","tlsClientCertificate","tlsClientKey","tlsEngine","tlsCertificatePath","tlsClientCertificatePath","tlsClientKeyPath","echConfig","echConfigPath","echQueryName","tlsFragmentFallbackDelay","tlsSpoof","tlsSpoofMethod").forEach { ProfileText(it,it,read(it),::write) }
                    listOf("allowInsecure","tlsDisableSni","enableECH","realityHybrid","tlsFragment","tlsRecordFragment","tlsKernelTx","tlsKernelRx").forEach { key -> ProfileSwitch(key,store.getBoolean(key,false)) { toggle(key,it) } }
                }
            }
        }
        item {
            ProfileSection("Multiplex") {
                ProfileSwitch("Enable Multiplex", mux) { toggle("enableMux",it) }
                if (mux) {
                    ProfileChoices(listOf("0" to "h2mux","1" to "smux","2" to "yamux","3" to "rmux"), read("muxType")) { write("muxType",it) }
                    listOf("muxConcurrency","muxMaxConnections","muxMinStreams","muxBrutalUp","muxBrutalDown").forEach { ProfileText(it,it,read(it),::write) }
                    listOf("muxPadding","muxBrutal").forEach { key -> ProfileSwitch(key,store.getBoolean(key,false)) { toggle(key,it) } }
                }
            }
        }
        item { ProfileSection("Connection") { ProfileChoices(listOf("" to "TCP + UDP","tcp" to "TCP","udp" to "UDP"), read("vlessNetwork")) { write("vlessNetwork",it) } } }
        item { Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = read("serverAddress").isNotBlank() && read("serverPort").toIntOrNull() in 1..65535) { Text("Save profile") } }
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
