package io.nekohasekai.sagernet.ui.compose

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.drawerlayout.widget.DrawerLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.MainActivity

fun installHubDrawer(activity: MainActivity, params: DrawerLayout.LayoutParams) {
    val drawer = activity.binding.drawerLayout
    val host = ComposeView(activity)
    drawer.addView(host, DrawerLayout.LayoutParams(
        (320 * activity.resources.displayMetrics.density).toInt(),
        params.height, params.gravity))
    host.setContent {
        HubTheme {
            val selected by activity.drawerSelection.collectAsState()
            val trafficVisible by activity.drawerTrafficVisible.collectAsState()
            val items = listOf(
                R.id.nav_configuration to R.string.menu_configuration,
                R.id.nav_group to R.string.menu_group,
                R.id.nav_route to R.string.menu_route,
                R.id.nav_settings to R.string.settings,
                R.id.nav_logcat to R.string.menu_log,
                R.id.nav_traffic to R.string.menu_dashboard,
                R.id.nav_warp to R.string.menu_warp,
                R.id.nav_proton to R.string.menu_proton,
                R.id.nav_tools to R.string.menu_backup,
                R.id.nav_about to R.string.menu_about
            ).filter { it.first != R.id.nav_traffic || trafficVisible }
            Surface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp), color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, 0.07f)) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(activity.getString(R.string.app_name), Modifier.padding(horizontal = 16.dp, vertical = 20.dp), fontSize = 22.sp)
                    items.forEach { (id, title) ->
                        key(id) {
                            NavigationDrawerItem(
                                label = { Text(activity.getString(title)) },
                                selected = id == selected,
                                colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = MaterialTheme.colorScheme.onSurface, selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                onClick = { if (id == selected) drawer.closeDrawers() else activity.displayFragmentWithId(id) },
                                icon = {
                                    HubDrawerIcon(id, if (id == selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubDrawerIcon(id: Int, tint: androidx.compose.ui.graphics.Color) {
    val data = when (id) {
        R.id.nav_configuration -> "M6,3H14L19,8V21H6Z M14,3V8H19 M9,12H16 M9,16H16"
        R.id.nav_group -> "M4,5H8V9H4Z M11,5H20 M4,11H8V15H4Z M11,11H20 M4,17H8V21H4Z M11,17H20"
        R.id.nav_route -> "M4,19V12Q4,8 8,8H20 M15,3L20,8L15,13 M4,12Q4,16 8,16H13"
        R.id.nav_settings -> "M12,3V6 M12,18V21 M3,12H6 M18,12H21 M5.6,5.6L7.7,7.7 M16.3,16.3L18.4,18.4 M5.6,18.4L7.7,16.3 M16.3,7.7L18.4,5.6 M12,6A6,6 0,1 0,12 18A6,6 0,1 0,12 6 M12,10A2,2 0,1 0,12 14A2,2 0,1 0,12 10"
        R.id.nav_logcat -> "M8,7L6,4 M16,7L18,4 M7,10H3 M17,10H21 M7,15H3 M17,15H21 M7,10A5,5 0,0 1,17 10V16A5,5 0,0 1,7 16Z M12,11V19"
        R.id.nav_traffic -> "M3,19H21 M5,16V11 M10,16V5 M15,16V9 M20,16V3"
        R.id.nav_warp -> "M3,7H17L13,3 M17,7L13,11 M21,17H7L11,13 M7,17L11,21"
        R.id.nav_proton -> "M12,3L20,6V12Q20,18 12,21Q4,18 4,12V6Z M8,12L11,15L16,9"
        R.id.nav_tools -> "M5,18H19A4,4 0,0 0,19 10A7,7 0,0 0,5 10A4,4 0,0 0,5 18 M12,16V8 M9,11L12,8L15,11"
        R.id.nav_about -> "M12,3A9,9 0,1 0,12 21A9,9 0,1 0,12 3 M12,11V17 M12,7V8"
        else -> "M12,3A9,9 0,1 0,12 21A9,9 0,1 0,12 3 M12,11V17 M12,7V8"
    }
    val path = remember(data) { PathParser().parsePathString(data).toPath() }
    Canvas(Modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, tint, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
