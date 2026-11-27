package io.nekohasekai.sagernet.ui.compose

import android.text.format.Formatter
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Identity belongs to the occurrence, not the profile ID or its current index. */
data class ChainEditorEntry(val key: Long, val profile: ProxyEntity)

@Composable
fun ChainProfileScreen(
    profiles: List<ChainEditorEntry>, loaded: Boolean, missingIds: List<Long>,
    menuItems: List<Pair<String, () -> Unit>>, onDiscardMissing: () -> Unit,
    onRestore: (Int, ChainEditorEntry) -> Unit, onSave: () -> Unit, onClose: () -> Unit,
    onPick: (Int) -> Unit, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit
) {
    var name by remember { mutableStateOf(DataStore.profileName) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val move by rememberUpdatedState(onMove)
    val entries by rememberUpdatedState(profiles)
    var draggedKey by remember { mutableStateOf<Long?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var settlingKey by remember { mutableStateOf<Long?>(null) }
    var settleOffset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val edgeSize = with(LocalDensity.current) { 72.dp.toPx() }
    val maxScroll = with(LocalDensity.current) { 18.dp.toPx() }
    val valid = loaded && profiles.isNotEmpty() && missingIds.isEmpty() && draggedKey == null

    fun reorder() {
        val key = draggedKey ?: return
        val from = entries.indexOfFirst { it.key == key }
        if (from < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == key } ?: return
        // Wait until the list has measured the previous move before comparing again.
        if (dragged.index != from + 1) return
        val center = dragTop + dragged.size / 2f
        val target = ChainReorderTarget.find(
            dragged.index, center,
            visible.filter { it.key is Long && it.key != key }
                .map { ChainReorderTarget.Item(it.index, it.offset + it.size / 2f) }
        ) ?: return
        val to = target - 1
        if (to in entries.indices && to != from) move(from, to)
    }

    fun finishDrag() {
        val key = draggedKey ?: return
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        val offset = if (item == null) 0f else dragTop - item.offset
        draggedKey = null
        settleJob?.cancel()
        settlingKey = key
        settleOffset = offset
        settleJob = scope.launch {
            animate(offset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                settleOffset = value
            }
            settlingKey = null
        }
    }

    // Keep scrolling while the finger is held at the edge, even without drag events.
    LaunchedEffect(draggedKey) {
        val key = draggedKey ?: return@LaunchedEffect
        var previousFrame = 0L
        while (draggedKey == key) {
            val frame = withFrameNanos { it }
            val elapsed = if (previousFrame == 0L) 1f else
                ((frame - previousFrame) / 16_666_667f).coerceIn(0f, 2f)
            previousFrame = frame
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: continue
            val center = dragTop + item.size / 2f
            val scroll = when {
                center < layout.viewportStartOffset + edgeSize ->
                    -maxScroll * ((layout.viewportStartOffset + edgeSize - center) / edgeSize).coerceIn(0f, 1f)
                center > layout.viewportEndOffset - edgeSize ->
                    maxScroll * ((center - layout.viewportEndOffset + edgeSize) / edgeSize).coerceIn(0f, 1f)
                else -> 0f
            }
            if (scroll != 0f) listState.scrollBy(scroll * elapsed)
            reorder()
        }
    }

    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = "Chain", onNavigation = onClose, onBack = onClose, extraActions = {
            TextButton(onClick = onSave, enabled = valid) { Text("Save") }
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }, enabled = draggedKey == null) { Text("⋮") }
                DropdownMenu(expanded, { expanded = false }) {
                    menuItems.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) }
                }
            }
        })
        LazyColumn(
            Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "chain-header") {
                SimpleField("Profile name", name) { name = it; DataStore.profileName = it }
                if (missingIds.isNotEmpty()) {
                    Text("Missing profiles: ${missingIds.joinToString()}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDiscardMissing) { Text("Remove missing references") }
                }
                Text("Proxy chain", style = MaterialTheme.typography.titleLarge)
                Text("Long press the handle and drag to reorder, or use the arrow buttons.", style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(profiles, key = { _, entry -> entry.key }) { i, entry ->
                val profile = entry.profile
                val dragging = draggedKey == entry.key
                val settling = settlingKey == entry.key
                val idle = draggedKey == null
                Column(
                    Modifier.fillMaxWidth()
                        .animateItem(placementSpec = if (dragging) null else spring(stiffness = Spring.StiffnessMediumLow))
                        .zIndex(if (dragging || settling) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) {
                                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.key }
                                if (info == null) 0f else dragTop - info.offset
                            } else if (settling) settleOffset else 0f
                        }
                ) {
                    Card(
                        Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 0.dp),
                        colors = CardDefaults.cardColors(containerColor = if (dragging)
                            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row {
                                Text("${i + 1}", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(profile.displayName(), style = MaterialTheme.typography.titleMedium)
                                    Text(profile.displayType(), style = MaterialTheme.typography.bodySmall)
                                    val bean = profile.requireBean()
                                    if (bean.serverAddress.isNotBlank()) Text("${bean.serverAddress}:${bean.serverPort}", style = MaterialTheme.typography.bodySmall)
                                    if (profile.rx + profile.tx > 0L) Text("↓ ${Formatter.formatFileSize(context, profile.rx)}   ↑ ${Formatter.formatFileSize(context, profile.tx)}", style = MaterialTheme.typography.bodySmall)
                                }
                                Box(
                                    Modifier.size(48.dp)
                                        .semantics { contentDescription = "Reorder profile" }
                                        .pointerInput(entry.key) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.key }
                                                    if (info != null && draggedKey == null) {
                                                        settleJob?.cancel()
                                                        settlingKey = null
                                                        dragTop = info.offset.toFloat()
                                                        draggedKey = entry.key
                                                    }
                                                },
                                                onDragEnd = { if (draggedKey == entry.key) finishDrag() },
                                                onDragCancel = { if (draggedKey == entry.key) finishDrag() }
                                            ) { change, amount ->
                                                if (draggedKey == entry.key) {
                                                    change.consume()
                                                    dragTop += amount.y
                                                    reorder()
                                                }
                                            }
                                        },
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) { Text("☰", style = MaterialTheme.typography.titleLarge) }
                            }
                            Row {
                                TextButton(onClick = { onMove(i, i - 1) }, enabled = idle && i > 0) { Text("↑") }
                                TextButton(onClick = { onMove(i, i + 1) }, enabled = idle && i < profiles.lastIndex) { Text("↓") }
                                TextButton(onClick = { onPick(i) }, enabled = idle) { Text("Replace") }
                                TextButton(onClick = {
                                    onRemove(i)
                                    scope.launch {
                                        if (snackbar.showSnackbar("Profile removed", actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                                            onRestore(i, entry)
                                    }
                                }, enabled = idle) { Text("Remove") }
                            }
                        }
                    }
                    if (i < profiles.lastIndex) Text("↓", modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.primary)
                }
            }
            item(key = "chain-footer") {
                if (!loaded) CircularProgressIndicator()
                else {
                    FilledTonalButton(onClick = { onPick(-1) }, enabled = draggedKey == null, modifier = Modifier.fillMaxWidth()) { Text("+ Add profile") }
                    if (profiles.isEmpty()) Text("Add at least one profile to the chain.")
                    Button(onClick = onSave, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Save chain") }
                }
            }
        }
        SnackbarHost(snackbar)
    }
}
