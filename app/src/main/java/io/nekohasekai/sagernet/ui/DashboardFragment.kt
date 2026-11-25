package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.compose.*
import kotlinx.coroutines.delay

class DashboardFragment : ToolbarFragment() {
    private var dashboardVisible by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        dashboardVisible = true
    }

    override fun onPause() {
        dashboardVisible = false
        super.onPause()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val activity = requireActivity() as MainActivity
        val model = ViewModelProvider(activity)[ConnectionViewModel::class.java]
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HubTheme {
                    val ui by model.state.collectAsState()
                    val connected = ui.serviceState.connected
                    var tab by rememberSaveable { mutableStateOf(0) }
                    val history = remember { mutableStateListOf<Pair<Long, Long>>() }
                    LaunchedEffect(connected, dashboardVisible) {
                        history.clear()
                        while (connected && dashboardVisible) {
                            val current = model.state.value
                            if (history.size >= 60) history.removeAt(0)
                            history.add(current.rxBytes to current.txBytes)
                            delay(1000)
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { activity.displayFragmentWithId(R.id.nav_configuration) }) {
                                Canvas(Modifier.size(24.dp)) {
                                    val c = androidx.compose.ui.graphics.Color.Gray
                                    drawLine(c, Offset(size.width*.2f,size.height*.5f), Offset(size.width*.85f,size.height*.5f), 2.dp.toPx())
                                    drawLine(c, Offset(size.width*.2f,size.height*.5f), Offset(size.width*.5f,size.height*.2f), 2.dp.toPx())
                                    drawLine(c, Offset(size.width*.2f,size.height*.5f), Offset(size.width*.5f,size.height*.8f), 2.dp.toPx())
                                }
                            }
                            Text("Dashboard", style = MaterialTheme.typography.titleLarge)
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Overview", "Connections", "Proxies", "Rules").forEachIndexed { index, label ->
                                Surface(modifier = Modifier.weight(1f).clickable { tab = index }, shape = RoundedCornerShape(18.dp),
                                    color = if (tab == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(listOf("⌂", "⇄", "▱", "☷")[index], style = MaterialTheme.typography.titleLarge)
                                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (tab == 0) {
                                val green = Color(0xFF81D99A)
                                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.outlinedCardColors(containerColor = if (connected) green.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceVariant),
                                    border = BorderStroke(1.dp, if (connected) green.copy(alpha = .6f) else MaterialTheme.colorScheme.outlineVariant)) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(painterResource(if (connected) R.drawable.ic_dashboard_connected else R.drawable.ic_dashboard_idle), null, tint = if (connected) green else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Column {
                                            Text(if (connected) "Service active" else "Service inactive", style = MaterialTheme.typography.titleMedium)
                                            Text(ui.mode + " · sing-box", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    DashboardMetric("↓  Download", ui.rx, Modifier.weight(1f))
                                    DashboardMetric("↑  Upload", ui.tx, Modifier.weight(1f))
                                }
                                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Traffic (last 60 s)", style = MaterialTheme.typography.titleMedium)
                                        val primary = MaterialTheme.colorScheme.primary
                                        val grid = MaterialTheme.colorScheme.outlineVariant
                                        val samples = history.toList()
                                        val peak = maxOf(1L, samples.maxOfOrNull { maxOf(it.first, it.second) } ?: 0L)
                                        Text(String.format(java.util.Locale.getDefault(), "%.1f Mbps", peak * 8.0 / 1000000), style = MaterialTheme.typography.labelSmall)
                                        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                                            for (i in 0..4) {
                                                val y = size.height * i / 4
                                                drawLine(grid, Offset(0f,y), Offset(size.width,y))
                                            }
                                            for (series in 0..1) {
                                                val tint = if (series == 0) primary else green
                                                val path = Path()
                                                samples.forEachIndexed { index, pair ->
                                                    val x = size.width * (60 - samples.size + index) / 59f
                                                    val value = if (series == 0) pair.first else pair.second
                                                    val y = size.height * (1f - value.toFloat() / peak)
                                                    if (index == 0) path.moveTo(x,y) else path.lineTo(x,y)
                                                }
                                                drawPath(path, tint, style = Stroke(width = 2.dp.toPx()))
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                            Text("● Download", color = primary)
                                            Spacer(Modifier.width(20.dp))
                                            Text("● Upload", color = green)
                                        }
                                    }
                                }
                                var used by remember { mutableStateOf(0L) }
                                val runtime = Runtime.getRuntime()
                                val limit = runtime.maxMemory()
                                LaunchedEffect(dashboardVisible) {
                                    while (dashboardVisible) {
                                        used = runtime.totalMemory() - runtime.freeMemory()
                                        delay(1000)
                                    }
                                }
                                val ratio = (used.toFloat() / limit.coerceAtLeast(1)).coerceIn(0f,1f)
                                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("App Java heap", style = MaterialTheme.typography.titleMedium)
                                            Text((ratio * 100).toInt().toString() + "%")
                                        }
                                        Text((used / 1048576).toString() + " MB / " + (limit / 1048576) + " MB")
                                        LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            } else if (!dashboardVisible) {
                                // Remove live tabs from composition while paused.
                            } else if (tab == 2) {
                                DashboardLiveProxies(connected)
                            } else if (tab == 3) {
                                DashboardLiveRules(connected)
                            } else {
                                DashboardLiveTab(tab, connected)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, modifier: Modifier) {
    OutlinedCard(modifier, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.trimStart('▲', '▼', ' ').ifBlank { "0 B/s" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
