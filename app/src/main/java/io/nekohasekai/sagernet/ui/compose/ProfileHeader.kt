package io.nekohasekai.sagernet.ui.compose

import android.view.View
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
                    Text(stringResource(R.string.app_name),
                        Modifier.weight(1f).clickable { fragment.scrollToSelectedProfile() }, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    HeaderMenu(profileAddMenu, R.drawable.ic_preview_add, stringResource(R.string.add_profile), fragment)
                    IconButton(onClick = { searching = true }) {
                        Icon(painterResource(R.drawable.ic_hub_search), stringResource(android.R.string.search_go))
                    }
                    HeaderMenu(profileMiscMenu, R.drawable.ic_baseline_more_vert_24, "More", fragment)
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

private data class ProfileMenuEntry(val id: Int, val title: Int, val children: List<ProfileMenuEntry> = emptyList(), val literal: String? = null)
private fun entry(id: Int, title: Int) = ProfileMenuEntry(id, title)
private val profileAddMenu = listOf(
    entry(R.id.action_scan_qr_code, R.string.add_profile_methods_scan_qr_code),
    entry(R.id.action_import_clipboard, R.string.action_import),
    entry(R.id.action_import_file, R.string.action_import_file),
    ProfileMenuEntry(0, R.string.add_profile_methods_manual_settings, listOf(
        entry(R.id.action_new_socks, R.string.action_socks),
        entry(R.id.action_new_http, R.string.action_http),
        entry(R.id.action_new_ss, R.string.action_shadowsocks),
        entry(R.id.action_new_vmess, R.string.action_vmess),
        ProfileMenuEntry(R.id.action_new_vless, 0, literal = "VLESS"),
        entry(R.id.action_new_trojan, R.string.action_trojan),
        entry(R.id.action_new_trojan_go, R.string.action_trojan_go),
        entry(R.id.action_new_mieru, R.string.action_mieru),
        entry(R.id.action_new_naive, R.string.action_naive),
        entry(R.id.action_new_hysteria, R.string.action_hysteria),
        entry(R.id.action_new_tuic, R.string.action_tuic),
        entry(R.id.action_new_shadowtls, R.string.action_shadowtls),
        entry(R.id.action_new_anytls, R.string.action_anytls),
        entry(R.id.action_new_ssh, R.string.action_ssh),
        entry(R.id.action_new_wg, R.string.action_wireguard),
        entry(R.id.action_new_config, R.string.custom_config),
        entry(R.id.action_new_chain, R.string.proxy_chain)
    ))
)
private val profileMiscMenu = listOf(
    entry(R.id.action_update_subscription, R.string.update_current_subscription),
    entry(R.id.action_clear_traffic_statistics, R.string.clear_traffic_statistics),
    entry(R.id.action_remove_duplicate, R.string.remove_duplicate),
    entry(R.id.action_connection_tcp_ping, R.string.connection_test_tcp_ping),
    entry(R.id.action_connection_url_test, R.string.connection_test_url_test),
    entry(R.id.action_connection_test_clear_results, R.string.connection_test_clear_results),
    entry(R.id.action_connection_test_delete_unavailable, R.string.connection_test_delete_unavailable),
    ProfileMenuEntry(R.id.action_order, R.string.group_order, listOf(
        entry(R.id.action_order_origin, R.string.group_order_origin),
        entry(R.id.action_order_by_name, R.string.group_order_by_name),
        entry(R.id.action_order_by_delay, R.string.group_order_by_delay)
    ))
)

@Composable
private fun HeaderMenu(menu: List<ProfileMenuEntry>, icon: Int, description: String, fragment: ConfigurationFragment) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(menu) }
    var order by remember { mutableStateOf(fragment.currentProfileOrder()) }
    Box {
        IconButton(onClick = { current = menu; order = fragment.currentProfileOrder(); expanded = true }) { Icon(painterResource(icon), description) }
        DropdownMenu(expanded, { expanded = false }) {
            current.forEach { item ->
                val checked = when (item.id) {
                    R.id.action_order_origin -> order == io.nekohasekai.sagernet.GroupOrder.ORIGIN
                    R.id.action_order_by_name -> order == io.nekohasekai.sagernet.GroupOrder.BY_NAME
                    R.id.action_order_by_delay -> order == io.nekohasekai.sagernet.GroupOrder.BY_DELAY
                    else -> false
                }
                DropdownMenuItem(text = { Text((if (checked) "✓  " else "") + (item.literal ?: stringResource(item.title))) }, onClick = {
                    if (item.children.isNotEmpty()) current = item.children
                    else { expanded = false; fragment.onProfileAction(item.id) }
                })
            }
        }
    }
}
