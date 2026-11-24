package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.GravityCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.GroupFragment

fun installGroupHeader(fragment: GroupFragment, host: ComposeView) {
    host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    host.setContent {
        HubTheme {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { fragment.activity.binding.drawerLayout.openDrawer(GravityCompat.START) }) {
                    Icon(painterResource(R.drawable.ic_navigation_menu), "Menu")
                }
                Text(stringResource(R.string.menu_group), Modifier.weight(1f), fontSize = 20.sp)
                IconButton(onClick = { fragment.onGroupAction(R.id.action_new_group) }) {
                    Icon(painterResource(R.drawable.ic_preview_add), stringResource(R.string.menu_group))
                }
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(painterResource(R.drawable.ic_baseline_more_vert_24), "More")
                    }
                    DropdownMenu(expanded, { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.update_all_subscription)) }, onClick = {
                            expanded = false
                            fragment.onGroupAction(R.id.action_update_all)
                        })
                    }
                }
            }
        }
    }
}
