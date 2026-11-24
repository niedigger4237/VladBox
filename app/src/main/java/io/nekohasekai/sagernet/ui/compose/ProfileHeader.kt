package io.nekohasekai.sagernet.ui.compose

import android.view.View
import android.view.Menu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity

fun installProfileHeader(fragment: ConfigurationFragment, root: View) {
    val toolbar = fragment.toolbar
    val tabs = fragment.tabLayout
    val pager = fragment.groupPager
    val adapter = fragment.adapter
    toolbar.visibility = View.GONE
    val header = root.findViewById<ComposeView>(R.id.compose_header)
    header.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    header.setContent {
        HubTheme {
            var searching by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (searching) {
                        searching = false
                        query = ""
                        fragment.onQueryTextChange("")
                    } else (fragment.requireActivity() as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
                }) { Icon(painterResource(if (searching) R.drawable.ic_navigation_close else R.drawable.ic_navigation_menu), "Menu") }
                if (searching) {
                    TextField(query, { query = it; fragment.onQueryTextChange(it) }, Modifier.weight(1f), singleLine = true)
                } else {
                    Text(toolbar.title?.toString() ?: stringResource(R.string.app_name),
                        Modifier.weight(1f).clickable { toolbar.performClick() }, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    HeaderMenu(toolbar.menu.findItem(R.id.action_add).subMenu, R.drawable.ic_preview_add, stringResource(R.string.add_profile), fragment)
                    IconButton(onClick = { searching = true }) {
                        Text("⌕", fontSize = 30.sp)
                    }
                    HeaderMenu(toolbar.menu.findItem(R.id.action_misc).subMenu, R.drawable.ic_baseline_more_vert_24, "More", fragment)
                }
            }
        }
    }
    val groupHost = root.findViewById<ComposeView>(R.id.compose_groups)
    groupHost.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    groupHost.setContent {
        HubTheme {
            var revision by remember { mutableStateOf(0) }
            var selected by remember { mutableStateOf(pager.currentItem) }
            DisposableEffect(adapter, pager) {
                val observer = object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { revision++ }
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { revision++ }
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { revision++ }
                    override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { revision++ }
                }
                val callback = object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) { selected = position }
                }
                adapter.registerAdapterDataObserver(observer)
                pager.registerOnPageChangeCallback(callback)
                onDispose {
                    adapter.unregisterAdapterDataObserver(observer)
                    pager.unregisterOnPageChangeCallback(callback)
                }
            }
            val names = remember(revision) { adapter.groupList.map { it.displayName().toString() } }
            if (names.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEachIndexed { index, name ->
                    FilterChip(selected = index == selected, onClick = { pager.setCurrentItem(index, true) }, label = { Text(name) })
                }
            }
        }
    }
    tabs.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (tabs.visibility != View.GONE) tabs.visibility = View.GONE
    }
    tabs.visibility = View.GONE
}

@Composable
private fun HeaderMenu(menu: Menu?, icon: Int, description: String, fragment: ConfigurationFragment) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(menu) }
    Box {
        IconButton(onClick = { current = menu; expanded = true }) { Icon(painterResource(icon), description) }
        DropdownMenu(expanded, { expanded = false }) {
            val items = current
            if (items != null) for (i in 0 until items.size()) {
                val item = items.getItem(i)
                if (item.isVisible) DropdownMenuItem(text = { Text(item.title.toString()) }, enabled = item.isEnabled, onClick = {
                    if (item.hasSubMenu()) current = item.subMenu
                    else { expanded = false; fragment.onMenuItemClick(item) }
                })
            }
        }
    }
}
