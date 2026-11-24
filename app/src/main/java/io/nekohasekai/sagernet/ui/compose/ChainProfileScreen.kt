package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.text.format.Formatter
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

@Composable
fun ChainProfileScreen(profiles: List<ProxyEntity>, loaded: Boolean, missingIds: List<Long>, menuItems: List<Pair<String, () -> Unit>>, onDiscardMissing: () -> Unit, onRestore: (Int, ProxyEntity) -> Unit, onSave: () -> Unit, onClose: () -> Unit, onPick: (Int) -> Unit, onMove: (Int,Int) -> Unit, onRemove: (Int) -> Unit) {
    var name by remember { mutableStateOf(DataStore.profileName) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val valid = loaded && profiles.isNotEmpty() && missingIds.isEmpty()
    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = "Chain", onNavigation = onClose, onBack = onClose, extraActions = {
            TextButton(onClick = onSave, enabled = valid) { Text("Save") }
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }) { Text("⋮") }
                DropdownMenu(expanded,{ expanded = false }) {
                    menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) },onClick = { expanded = false; action() }) }
                }
            }
        })
        LazyColumn(Modifier.weight(1f),state = listState,contentPadding = PaddingValues(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SimpleField("Profile name",name) { name = it; DataStore.profileName = it }
                if (missingIds.isNotEmpty()) {
                    Text("Missing profiles: ${missingIds.joinToString()}",color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDiscardMissing) { Text("Remove missing references") }
                }
                Text("Proxy chain",style = MaterialTheme.typography.titleLarge)
                Text("Long press the handle to reorder, or use the arrow buttons.",style = MaterialTheme.typography.bodySmall)
            }
            items(profiles.size) { i ->
                val profile = profiles[i]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row {
                            Text("${i+1}",style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(profile.displayName(),style = MaterialTheme.typography.titleMedium)
                                Text(profile.displayType(),style = MaterialTheme.typography.bodySmall)
                                val bean = profile.requireBean()
                                if (bean.serverAddress.isNotBlank()) Text("${bean.serverAddress}:${bean.serverPort}",style = MaterialTheme.typography.bodySmall)
                                if (profile.rx + profile.tx > 0L) Text("↓ ${Formatter.formatFileSize(context,profile.rx)}   ↑ ${Formatter.formatFileSize(context,profile.tx)}",style = MaterialTheme.typography.bodySmall)
                            }
                            Text("☰",Modifier.pointerInput(i,profiles.size) {
                                var distance = 0f
                                detectDragGesturesAfterLongPress(onDragEnd = {
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    val source = visible.firstOrNull { it.index == i+1 }
                                    if (source != null) {
                                        val target = visible.filter { it.index in 1..profiles.size }.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - (source.offset + source.size / 2f + distance)) }
                                        if (target != null && target.index != i+1) onMove(i,target.index-1)
                                    }
                                    distance = 0f
                                },onDragCancel = { distance = 0f }) { change,amount -> change.consume(); distance += amount.y }
                            }.padding(12.dp))
                        }
                        Row {
                            TextButton(onClick = { onMove(i,i-1) },enabled = i>0) { Text("↑") }
                            TextButton(onClick = { onMove(i,i+1) },enabled = i<profiles.lastIndex) { Text("↓") }
                            TextButton(onClick = { onPick(i) }) { Text("Replace") }
                            TextButton(onClick = {
                                onRemove(i)
                                scope.launch {
                                    if (snackbar.showSnackbar("Profile removed",actionLabel = "Undo",duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onRestore(i,profile)
                                }
                            }) { Text("Remove") }
                        }
                    }
                }
                if (i < profiles.lastIndex) Text("↓",modifier = Modifier.fillMaxWidth(),textAlign = androidx.compose.ui.text.style.TextAlign.Center,color = MaterialTheme.colorScheme.primary)
            }
            item {
                if (!loaded) CircularProgressIndicator()
                else {
                    FilledTonalButton(onClick = { onPick(-1) },modifier = Modifier.fillMaxWidth()) { Text("+ Add profile") }
                    if (profiles.isEmpty()) Text("Add at least one profile to the chain.")
                    Button(onClick = onSave,enabled = valid,modifier = Modifier.fillMaxWidth()) { Text("Save chain") }
                }
            }
        }
        SnackbarHost(snackbar)
    }
}
