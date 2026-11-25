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
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
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
                                    color = if (tab == index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
                                    Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        DashboardTabIcon(index)
                                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (tab == 0) {
                                val green = Color(0xFF81D99A)
                                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
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
                                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Traffic (last 60 s)", style = MaterialTheme.typography.titleMedium)
                                        val primary = MaterialTheme.colorScheme.primary
                                        val grid = MaterialTheme.colorScheme.outlineVariant
                                        val samples = history.toList()
                                        val peak = maxOf(125L, samples.maxOfOrNull { maxOf(it.first, it.second) } ?: 0L)
                                        val divisor = if (peak >= 125000) 1000000.0 else 1000.0
                                        Text(if (divisor == 1000.0) "Kbps" else "Mbps", style = MaterialTheme.typography.labelSmall)
                                        Row(Modifier.fillMaxWidth()) {
                                            Column(Modifier.width(54.dp).height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                                for (i in 4 downTo 0) Text(String.format(java.util.Locale.getDefault(), "%.1f", peak * 8.0 / divisor * i / 4), style = MaterialTheme.typography.labelSmall)
                                            }
                                            Canvas(Modifier.weight(1f).height(120.dp)) {
                                            for (i in 0..4) {
                                                val y = size.height * i / 4
                                                drawLine(grid, Offset(0f,y), Offset(size.width,y))
                                            }
                                            for (series in 0..1) {
                                                val tint = if (series == 0) green else primary
                                                val path = Path()
                                                samples.forEachIndexed { index, pair ->
                                                    val x = size.width * (60 - samples.size + index) / 59f
                                                    val value = if (series == 0) pair.first else pair.second
                                                    val y = size.height * (1f - value.toFloat() / peak)
                                                    if (index == 0) path.moveTo(x,y) else {
                                                        val prev = samples[index - 1]
                                                        val px = size.width * (59 - samples.size + index) / 59f
                                                        val py = size.height * (1f - (if (series == 0) prev.first else prev.second).toFloat() / peak)
                                                        val mid = (px + x) / 2
                                                        path.cubicTo(mid, py, mid, y, x, y)
                                                    }
                                                }
                                                if (samples.size > 1) {
                                                    val area = Path().apply {
                                                        addPath(path)
                                                        lineTo(size.width, size.height)
                                                        lineTo(size.width * (60 - samples.size) / 59f, size.height)
                                                        close()
                                                    }
                                                    drawPath(area, brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint.copy(alpha = .32f), tint.copy(alpha = .03f))))
                                                }
                                                drawPath(path, tint, style = Stroke(width = 2.dp.toPx()))
                                            }
                                        }
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                            Text("● Download", color = green)
                                            Spacer(Modifier.width(20.dp))
                                            Text("● Upload", color = primary)
                                        }
                                    }
                                }
                                DashboardMemory(dashboardVisible)
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
    OutlinedCard(modifier, shape = RoundedCornerShape(12.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.trimStart('▲', '▼', ' ').ifBlank { "0 B/s" }, style = MaterialTheme.typography.titleLarge, color = if (label.contains("Download")) Color(0xFF81D99A) else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DashboardTabIcon(index: Int) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val s = size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x*s,y*s), Offset(x2*s,y2*s), 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (index) {
            0 -> {
                line(3f,11f,12f,3f); line(12f,3f,21f,11f)
                line(6f,9f,6f,21f); line(18f,9f,18f,21f)
                line(6f,21f,10f,21f); line(14f,21f,18f,21f)
                line(10f,21f,10f,15f); line(10f,15f,14f,15f); line(14f,15f,14f,21f)
            }
            1 -> {
                line(4f,7f,20f,7f); line(16f,3f,20f,7f); line(20f,7f,16f,11f)
                line(20f,17f,4f,17f); line(8f,13f,4f,17f); line(4f,17f,8f,21f)
            }
            2 -> {
                line(3f,8f,12f,3f); line(12f,3f,21f,8f); line(21f,8f,12f,13f); line(12f,13f,3f,8f)
                line(3f,12f,12f,17f); line(12f,17f,21f,12f)
                line(3f,16f,12f,21f); line(12f,21f,21f,16f)
            }
            else -> for (y in listOf(5f,12f,19f)) {
                drawCircle(tint, 1.2f*s, Offset(4f*s,y*s)); line(9f,y,21f,y)
            }
        }
    }
}

@Composable
private fun DashboardMemory(visible: Boolean) {
    var sample by remember { mutableStateOf<DashboardMemorySample?>(null) }
    LaunchedEffect(visible) {
        while (visible) {
            sample = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val info = android.os.Debug.MemoryInfo()
                android.os.Debug.getMemoryInfo(info)
                val runtime = Runtime.getRuntime()
                DashboardMemorySample(info.totalPss.toLong() * 1024,
                    runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
                    android.os.Debug.getNativeHeapAllocatedSize())
            }
            delay(2000)
        }
    }
    val colors = MaterialTheme.colorScheme
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant.copy(alpha = .45f)),
        border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = .55f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.size(24.dp)) {
                    val tint = colors.primary
                    val stroke = 1.5.dp.toPx()
                    drawRect(tint, topLeft = Offset(size.width * .22f, size.height * .22f),
                        size = androidx.compose.ui.geometry.Size(size.width * .56f, size.height * .56f), style = Stroke(stroke))
                    for (i in 1..3) {
                        val p = size.width * (.22f + i * .14f)
                        drawLine(tint, Offset(p, 0f), Offset(p, size.height * .22f), stroke)
                        drawLine(tint, Offset(p, size.height * .78f), Offset(p, size.height), stroke)
                        drawLine(tint, Offset(0f, p), Offset(size.width * .22f, p), stroke)
                        drawLine(tint, Offset(size.width * .78f, p), Offset(size.width, p), stroke)
                    }
                }
                Text("Memory usage", style = MaterialTheme.typography.titleMedium)
            }
            val current = sample
            fun mb(value: Long?): String = value?.let { String.format(java.util.Locale.getDefault(), "%.1f MiB", it / 1048576.0) } ?: "—"
            val rows = listOf("App process (PSS)" to mb(current?.pss),
                "Java heap" to (mb(current?.javaUsed) + " / " + mb(current?.javaLimit)),
                "Native heap (allocated)" to mb(current?.nativeAllocated),
                "Separate sing-box process" to "Not measured")
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(value, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (current != null) {
                val ratio = (current.javaUsed.toFloat() / current.javaLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth())
                Text("Java heap limit · " + String.format(java.util.Locale.getDefault(), "%.1f%%", ratio * 100),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Text("Current app process only. Heap values overlap PSS and must not be added.",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

private data class DashboardMemorySample(val pss: Long, val javaUsed: Long, val javaLimit: Long, val nativeAllocated: Long)
