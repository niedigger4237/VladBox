package io.nekohasekai.sagernet.ui.compose
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.flow.StateFlow
import android.text.format.Formatter

fun installAssetsPanel(host: ComposeView, count: StateFlow<Int>, bytes: StateFlow<Long>, loading: StateFlow<Boolean>, query: StateFlow<String>,
    update: () -> Unit, download: () -> Unit, sources: () -> Unit, provider: () -> Unit, importFile: () -> Unit, search: (String) -> Unit) {
    host.setContent {
        HubTheme {
            val n by count.collectAsState()
            val size by bytes.collectAsState()
            val busy by loading.collectAsState()
            val q by query.collectAsState()
            var menu by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Geodata · SRS", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_baseline_more_vert_24), "More") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Provider") }, onClick = { menu = false; provider() })
                                    DropdownMenuItem(text = { Text("Custom sources") }, onClick = { menu = false; sources() })
                                    DropdownMenuItem(text = { Text("Import SRS") }, onClick = { menu = false; importFile() })
                                }
                            }
                        }
                        Text("$n rule sets · ${Formatter.formatFileSize(LocalContext.current, size)}")
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = update, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Update all") }
                            OutlinedButton(onClick = download, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Download") }
                        }
                    }
                }
                OutlinedTextField(q, search, Modifier.fillMaxWidth(), label = { Text("Search SRS") }, singleLine = true)
            }
        }
    }
}

fun installAssetTile(host: ComposeView, name: String, version: String, size: String, busy: Boolean, canUpdate: Boolean, update: () -> Unit) {
    host.setContent {
        HubTheme {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_baseline_layers_24), null)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.SemiBold)
                            Text(version, style = MaterialTheme.typography.bodySmall)
                            Text(size, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ Installed", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        if (canUpdate) TextButton(onClick = update) { Text("Update") }
                    }
                }
            }
        }
    }
}
