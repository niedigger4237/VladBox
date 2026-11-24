package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.view.GravityCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.GroupFragment

fun installGroupHeader(fragment: GroupFragment, host: ComposeView) {
    host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    host.setContent {
        HubTheme {
            VladTopAppBar(
                title = stringResource(R.string.menu_group),
                onNavigation = { fragment.activity.binding.drawerLayout.openDrawer(GravityCompat.START) },
                extraActions = {
                    IconButton(onClick = { fragment.onGroupAction(R.id.action_new_group) }) {
                        Icon(painterResource(R.drawable.ic_preview_add), "New group")
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(painterResource(R.drawable.ic_baseline_more_vert_24), "More options")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.update_all_subscription)) },
                                onClick = {
                                    expanded = false
                                    fragment.onGroupAction(R.id.action_update_all)
                                }
                            )
                        }
                    }
                }
            )
        }
    }
}
