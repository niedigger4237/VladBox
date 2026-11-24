package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.flow.StateFlow

data class GroupCardState(val id: Long, val name: String, val user: String, val traffic: String,
    val status: String, val subscription: Boolean, val editable: Boolean, val updating: Boolean, val progress: Float?)

fun installGroupCard(host: ComposeView, state: StateFlow<GroupCardState?>,
    onEdit: () -> Unit, onUpdate: () -> Unit, onAction: (Int) -> Unit) {
    host.setContent {
        HubTheme {
            val item by state.collectAsState()
            item?.let { group ->
                var menu by remember(group.id) { mutableStateOf(0) }
                val colors = MaterialTheme.colorScheme
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.lerp(colors.surface, colors.onSurface, 0.07f))) {
                    if (group.updating) {
                        if (group.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = group.progress, modifier = Modifier.fillMaxWidth())
                    }
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(group.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                if (group.user.isNotEmpty()) Text(group.user, fontSize = 13.sp, color = colors.onSurfaceVariant)
                            }
                            if (group.editable) IconButton(onClick = onEdit) {
                                Icon(painterResource(R.drawable.ic_image_edit), stringResource(R.string.edit))
                            }
                            Box {
                                IconButton(onClick = { menu = 1 }) {
                                    Icon(painterResource(R.drawable.ic_baseline_more_vert_24), "More")
                                }
                                DropdownMenu(menu != 0, { menu = 0 }) {
                                    when (menu) {
                                        1 -> {
                                            if (group.subscription) DropdownMenuItem(text = { Text(stringResource(R.string.share_subscription)) }, onClick = { menu = 2 })
                                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { menu = 3 })
                                            DropdownMenuItem(text = { Text(stringResource(R.string.clear_profiles)) }, onClick = { menu = 0; onAction(R.id.action_clear) })
                                        }
                                        2 -> {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export_clipboard)) }, onClick = { menu = 0; onAction(R.id.action_universal_clipboard) })
                                            DropdownMenuItem(text = { Text(stringResource(R.string.share_qr_nfc)) }, onClick = { menu = 0; onAction(R.id.action_universal_qr) })
                                        }
                                        3 -> {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export_clipboard)) }, onClick = { menu = 0; onAction(R.id.action_export_clipboard) })
                                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export_file)) }, onClick = { menu = 0; onAction(R.id.action_export_file) })
                                        }
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (group.traffic.isNotEmpty()) Text(group.traffic, fontSize = 13.sp, color = colors.onSurfaceVariant)
                                Text(group.status, fontSize = 13.sp, color = colors.onSurfaceVariant)
                            }
                            if (group.subscription && !group.updating) TextButton(onClick = onUpdate) { Text(stringResource(R.string.group_update)) }
                        }
                    }
                }
            }
        }
    }
}
