package io.nekohasekai.sagernet.ui.compose

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.MainActivity

fun installHubDrawer(activity: MainActivity, navigation: NavigationView) {
    val drawer = activity.binding.drawerLayout
    val params = navigation.layoutParams as DrawerLayout.LayoutParams
    val host = ComposeView(activity)
    val revision = mutableStateOf(0)
    navigation.visibility = View.GONE
    drawer.removeView(navigation)
    drawer.addView(host, DrawerLayout.LayoutParams(
        (320 * activity.resources.displayMetrics.density).toInt(),
        params.height, params.gravity))
    drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
            if (drawerView === host) revision.value++
        }
        override fun onDrawerOpened(drawerView: View) { revision.value++ }
    })
    host.setContent {
        HubTheme {
            val version = revision.value
            val items = remember(version) {
                (0 until navigation.menu.size()).map { navigation.menu.getItem(it) }.filter { it.isVisible }
            }
            Surface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp), color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, 0.07f)) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(activity.getString(R.string.app_name), Modifier.padding(horizontal = 16.dp, vertical = 20.dp), fontSize = 22.sp)
                    items.forEach { item ->
                        key(item.itemId) {
                            NavigationDrawerItem(
                                label = { Text(item.title.toString()) },
                                selected = item.isChecked,
                                colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = MaterialTheme.colorScheme.onSurface, selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                onClick = { activity.onNavigationItemSelected(item); revision.value++ },
                                icon = {
                                    item.icon?.let { drawable ->
                                        val bitmap = remember(drawable) { (drawable.constantState?.newDrawable()?.mutate()?.apply { setTint(android.graphics.Color.WHITE); alpha = 255 } ?: drawable).toBitmap(48, 48).asImageBitmap() }
                                        Image(bitmap, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(if (item.isChecked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
