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
fun QuicProfileScreen(tuic: Boolean, onSave: () -> Unit, onClose: () -> Unit, menuItems: List<Pair<String, () -> Unit>>) {
    val store = DataStore.profileCacheStore
    var revision by remember { mutableStateOf(0) }
    var section by rememberSaveable { mutableStateOf("") }
    val version = remember(revision) { DataStore.protocolVersion }
    val hy2 = !tuic && version == 2
    val prefix = if (tuic) "tuic" else "hy2"
    val title = if (tuic) "TUIC" else "Hysteria $version"
    val valid = remember(revision) {
        DataStore.serverAddress.isNotBlank() && if (tuic) {
            DataStore.serverPort in 1..65535 && runCatching { java.util.UUID.fromString(DataStore.serverUsername) }.isSuccess
        } else {
            DataStore.serverPorts.split(",").all { part ->
                val ends = part.trim().split("-", ":")
                ends.size in 1..2 && ends.all { (it.toIntOrNull() ?: 0) in 1..65535 } &&
                    (ends.size == 1 || ends.first().toInt() <= ends.last().toInt())
            } && (!hy2 || DataStore.serverPassword.isNotBlank())
        }
    }
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
                val sections = listOf("General" to "Server and authentication", "Security" to "TLS and certificates", "Transport" to "Bandwidth and congestion", "Advanced" to "QUIC and connection options")
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
                    @Composable fun field(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
                        SimpleField(label, value, secret) { change(it); revision++ }
                    }
                    @Composable fun toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
                        SimpleToggle(label, value) { change(it); revision++ }
                    }
                    @Composable fun extra(key: String, label: String) {
                        field(label, store.getString(key, "").orEmpty()) { store.putString(key,it) }
                    }
                    @Composable fun flag(key: String, label: String) {
                        toggle(label, store.getBoolean(key,false)) { store.putBoolean(key,it) }
                    }
                    @Composable fun number(label: String, value: Int, change: (Int) -> Unit) {
                        var draft by rememberSaveable { mutableStateOf(value.toString()) }
                        field(label,draft) { draft = it; change(it.toIntOrNull() ?: 0) }
                    }
                    @Composable fun choices(values: List<Pair<String,String>>, current: String, change: (String)->Unit) {
                        ProfileChoices(values,current) { change(it); revision++ }
                    }
                    when(section) {
                        "General" -> {
                            if (!tuic) choices(listOf("1" to "Hysteria 1","2" to "Hysteria 2"),version.toString()) { DataStore.protocolVersion = it.toInt() }
                            field("Profile name",DataStore.profileName) { DataStore.profileName = it }
                            field("Server address",DataStore.serverAddress) { DataStore.serverAddress = it }
                            if (tuic) {
                                number("Server port",DataStore.serverPort) { DataStore.serverPort = it }
                                field("UUID",DataStore.serverUsername) { DataStore.serverUsername = it }
                            } else {
                                field("Server ports",DataStore.serverPorts) { DataStore.serverPorts = it }
                                if (!hy2) choices(listOf("0" to "None","1" to "String","2" to "Base64"),DataStore.serverAuthType.toString()) { DataStore.serverAuthType = it.toInt() }
                            }
                            if (tuic || hy2 || DataStore.serverAuthType != 0) field(if (tuic || hy2) "Password" else "Authentication payload",DataStore.serverPassword,true) { DataStore.serverPassword = it }
                        }
                        "Security" -> {
                            if (tuic) toggle("Disable SNI",DataStore.serverDisableSNI) { DataStore.serverDisableSNI = it }
                            if (!tuic || !DataStore.serverDisableSNI) field("Server name (SNI)",DataStore.serverSNI) { DataStore.serverSNI = it }
                            if (!hy2) field("ALPN",DataStore.serverALPN) { DataStore.serverALPN = it }
                            field("CA certificate (PEM)",DataStore.serverCertificates) { DataStore.serverCertificates = it }
                            toggle("Allow insecure",DataStore.serverAllowInsecure) { DataStore.serverAllowInsecure = it }
                        }
                        "Transport" -> {
                            if (tuic) {
                                choices(listOf("cubic","new_reno","bbr").map { it to it },DataStore.serverCongestionController) { DataStore.serverCongestionController = it }
                                choices(listOf("native" to "Native","quic" to "QUIC"),DataStore.serverUDPRelayMode) { DataStore.serverUDPRelayMode = it }
                                toggle("Zero RTT handshake",DataStore.serverReduceRTT) { DataStore.serverReduceRTT = it }
                                flag("tuicUdpOverStream","UDP over stream")
                                extra("tuicHeartbeat","Heartbeat")
                            } else {
                                if (!hy2) choices(listOf("0" to "UDP","1" to "FakeTCP","2" to "WeChat video"),DataStore.serverProtocolInt.toString()) { DataStore.serverProtocolInt = it.toInt() }
                                number("Upload bandwidth (Mbps)",DataStore.serverUploadSpeed) { DataStore.serverUploadSpeed = it }
                                number("Download bandwidth (Mbps)",DataStore.serverDownloadSpeed) { DataStore.serverDownloadSpeed = it }
                                if (hy2) choices(listOf("salamander" to "Salamander","gecko" to "Gecko"),store.getString("hy2ObfsType","salamander").orEmpty()) { store.putString("hy2ObfsType",it) }
                                field("Obfuscation password",DataStore.serverObfs,true) { DataStore.serverObfs = it }
                                if (hy2) {
                                    extra("hy2GeckoMin","Gecko minimum packet size")
                                    extra("hy2GeckoMax","Gecko maximum packet size")
                                    choices(listOf("" to "Default","standard" to "Standard","conservative" to "Conservative","aggressive" to "Aggressive"),store.getString("hy2BbrProfile","").orEmpty()) { store.putString("hy2BbrProfile",it) }
                                }
                                number("Hop interval (seconds)",DataStore.serverHopInterval) { DataStore.serverHopInterval = it }
                                if (hy2) extra("hy2HopIntervalMax","Maximum hop interval")
                            }
                        }
                        "Advanced" -> {
                            if (tuic || hy2) {
                                choices(listOf("" to "TCP + UDP","tcp" to "TCP","udp" to "UDP"),store.getString(prefix+"Network","").orEmpty()) { store.putString(prefix+"Network",it) }
                                listOf("IdleTimeout" to "Idle timeout","KeepAlive" to "Keep alive period","StreamWindow" to "Stream receive window","ConnectionWindow" to "Connection receive window","MaxStreams" to "Maximum concurrent streams","InitialPacket" to "Initial packet size").forEach { (key,label) -> extra(prefix+key,label) }
                                flag(prefix+"DisablePathMtu","Disable path MTU discovery")
                                if (hy2) {
                                    flag("hy2BrutalDebug","Brutal debug")
                                    flag("hy2DisableChrome","Disable Chrome parrot")
                                    extra("hy2Realm","Realm (JSON)")
                                }
                            } else {
                                number("Stream receive window",DataStore.serverStreamReceiveWindow) { DataStore.serverStreamReceiveWindow = it }
                                number("Connection receive window",DataStore.serverConnectionReceiveWindow) { DataStore.serverConnectionReceiveWindow = it }
                                toggle("Disable MTU discovery",DataStore.serverDisableMtuDiscovery) { DataStore.serverDisableMtuDiscovery = it }
                            }
                        }
                    }
                }
            }
        }
    }
}
