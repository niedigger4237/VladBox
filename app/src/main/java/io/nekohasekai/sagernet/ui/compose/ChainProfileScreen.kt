package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

@Composable
fun ChainProfileScreen(profiles: List<ProxyEntity>, loaded: Boolean, onSave: () -> Unit, onClose: () -> Unit, onPick: (Int) -> Unit, onMove: (Int,Int) -> Unit, onRemove: (Int) -> Unit) {
    var name by remember { mutableStateOf(DataStore.profileName) }
    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = "Chain", onNavigation = onClose, onBack = onClose, extraActions = {
            TextButton(onClick = onSave, enabled = loaded && profiles.isNotEmpty()) { Text("Save") }
        })
        LazyColumn(Modifier.weight(1f),contentPadding = PaddingValues(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SimpleField("Profile name",name) { name = it; DataStore.profileName = it }
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
                            }
                            Text("☰",Modifier.pointerInput(i,profiles.size) {
                                var distance = 0f
                                detectDragGesturesAfterLongPress(onDragEnd = {
                                    if (distance > 40.dp.toPx()) onMove(i,i+1)
                                    else if (distance < -40.dp.toPx()) onMove(i,i-1)
                                    distance = 0f
                                },onDragCancel = { distance = 0f }) { change,amount -> change.consume(); distance += amount.y }
                            }.padding(12.dp))
                        }
                        Row {
                            TextButton(onClick = { onMove(i,i-1) },enabled = i>0) { Text("↑") }
                            TextButton(onClick = { onMove(i,i+1) },enabled = i<profiles.lastIndex) { Text("↓") }
                            TextButton(onClick = { onPick(i) }) { Text("Replace") }
                            TextButton(onClick = { onRemove(i) }) { Text("Remove") }
                        }
                    }
                }
            }
            item {
                if (!loaded) CircularProgressIndicator()
                else {
                    FilledTonalButton(onClick = { onPick(-1) },modifier = Modifier.fillMaxWidth()) { Text("+ Add profile") }
                    if (profiles.isEmpty()) Text("Add at least one profile to the chain.")
                    Button(onClick = onSave,enabled = profiles.isNotEmpty(),modifier = Modifier.fillMaxWidth()) { Text("Save chain") }
                }
            }
        }
    }
}
