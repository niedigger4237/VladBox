package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import io.nekohasekai.sagernet.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VladTopAppBar(
    title: String,
    subtitle: String? = null,
    onNavigation: () -> Unit,
    onAdd: (() -> Unit)? = null,
    addLabel: String = "",
    onProfiles: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    resetLabel: String = "",
    extraActions: @Composable RowScope.() -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigation) {
                Icon(painterResource(R.drawable.ic_navigation_menu), "Open navigation menu")
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        actions = {
            extraActions()
            if (onAdd != null) IconButton(onClick = onAdd) {
                Icon(painterResource(R.drawable.ic_baseline_add_road_24), addLabel)
            }
            if (onProfiles != null) TextButton(onClick = onProfiles) { Text("Profiles") }
            if (onReset != null) {
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(painterResource(R.drawable.ic_baseline_more_vert_24), "More options")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text(resetLabel) }, onClick = { expanded = false; onReset() })
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
