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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ui.compose.*
import kotlinx.coroutines.delay

class DashboardFragment : ToolbarFragment() {
    private var dashboardVisible by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        dashboardVisible = !isHidden
    }

    override fun onPause() {
        dashboardVisible = false
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        dashboardVisible = !hidden &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        dashboardVisible = false
        super.onDestroyView()
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
                    var searchOpen by rememberSaveable { mutableStateOf(false) }
                    var menuOpen by remember { mutableStateOf(false) }
                    // History is fed by the existing service speed callbacks.
                    // The clock only ages the visible chart; it never polls the core.
                    var chartNow by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
                    LaunchedEffect(connected, dashboardVisible, tab) {
                        if (tab == 0 && connected && dashboardVisible) {
                            while (true) {
                                chartNow = android.os.SystemClock.elapsedRealtime()
                                delay(1000)
                            }
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
                            Icon(painterResource(R.drawable.ic_dash_symbol_shield), contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Dashboard", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = { if (tab != 1 && tab != 3) { tab = 1; searchOpen = true } else searchOpen = !searchOpen }) {
                                Icon(painterResource(R.drawable.ic_dash_symbol_search), contentDescription = "Search dashboard", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_dash_symbol_more_vert), contentDescription = "Dashboard menu") }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    listOf("Overview", "Connections", "Proxies", "Rules").forEachIndexed { index, label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { tab = index; searchOpen = false; menuOpen = false })
                                    }
                                    DropdownMenuItem(text = { Text("Return to configuration") }, onClick = { menuOpen = false; activity.displayFragmentWithId(R.id.nav_configuration) })
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Overview", "Connections", "Proxies", "Rules").forEachIndexed { index, label ->
                                Surface(modifier = Modifier.weight(1f).clickable { tab = index; searchOpen = false }, shape = RoundedCornerShape(18.dp),
                                    color = androidx.compose.animation.animateColorAsState(if (tab == index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).value) {
                                    Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        DashboardTabIcon(index)
                                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    }
                                }
                            }
                        }
                        val tabScroll = key(tab) { rememberScrollState() }
                        // Connections owns a LazyColumn and must receive a finite height.
                        val contentModifier = Modifier.weight(1f).fillMaxWidth().let {
                            if (tab != 0) it else it.verticalScroll(tabScroll)
                        }.padding(10.dp)
                        Column(contentModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!dashboardVisible) {
                                // Dispose all tab-specific effects and scopes while hidden or paused.
                            } else if (tab == 0) {
                                val green = Color(0xFF81D99A)
                                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.outlinedCardColors(containerColor = if (connected) green.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceVariant),
                                    border = BorderStroke(1.dp, if (connected) green.copy(alpha = .6f) else MaterialTheme.colorScheme.outlineVariant)) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(painterResource(if (connected) R.drawable.ic_dash_symbol_check_circle else R.drawable.ic_dashboard_idle), null, tint = if (connected) green else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Column {
                                            Text(
                                                when (ui.serviceState) {
                                                    BaseService.State.Connected -> "Service active"
                                                    BaseService.State.Waiting -> stringResource(R.string.sc_waiting)
                                                    BaseService.State.Connecting -> stringResource(R.string.connecting)
                                                    BaseService.State.Stopping -> stringResource(R.string.stopping)
                                                    else -> "Service inactive"
                                                },
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(ui.mode + " · sing-box", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    DashboardMetric("Download", ui.rx, Modifier.weight(1f))
                                    DashboardMetric("Upload", ui.tx, Modifier.weight(1f))
                                }
                                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            DashboardOverviewIcon(2, MaterialTheme.colorScheme.primary)
                                            Text("Traffic (last 60 s)", style = MaterialTheme.typography.titleMedium)
                                        }
                                        val primary = MaterialTheme.colorScheme.primary
                                        val grid = MaterialTheme.colorScheme.outlineVariant
                                        val now = maxOf(chartNow, ui.trafficHistory.lastOrNull()?.elapsedMs ?: 0L)
                                        val samples = DashboardTrafficHistory.visible(ui.trafficHistory, now)
                                        val segments = DashboardTrafficHistory.segments(samples)
                                        val rawPeak = maxOf(125L, samples.maxOfOrNull { maxOf(it.rx, it.tx) } ?: 0L)
                                        val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(rawPeak.toDouble())))
                                        val peak = (kotlin.math.ceil(rawPeak / magnitude) * magnitude).toLong().coerceAtLeast(rawPeak)
                                        val divisor = if (peak >= 125000) 1000000.0 else 1000.0
                                        Text(if (divisor == 1000.0) "Kbps" else "Mbps", style = MaterialTheme.typography.labelSmall)
                                        Row(Modifier.fillMaxWidth()) {
                                            Column(Modifier.width(54.dp).height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                                for (i in 4 downTo 0) Text(String.format(java.util.Locale.getDefault(), "%.1f", peak * 8.0 / divisor * i / 4), style = MaterialTheme.typography.labelSmall)
                                            }
                                            Canvas(Modifier.weight(1f).height(120.dp)) {
                                            for (i in 0..4) {
                                                val y = size.height * i / 4
                                                drawLine(grid.copy(alpha = .5f), Offset(0f,y), Offset(size.width,y))
                                            }
                                            for (series in 0..1) {
                                                val tint = if (series == 0) green else primary
                                                // A missing callback is a gap, not zero traffic.
                                                fun x(sample: DashboardTrafficSample): Float =
                                                    size.width * DashboardTrafficHistory.position(sample.elapsedMs, now)
                                                fun y(sample: DashboardTrafficSample): Float =
                                                    size.height * (1f - (if (series == 0) sample.rx else sample.tx).toFloat() / peak)
                                                for (segment in segments) {
                                                    val path = Path()
                                                    segment.forEachIndexed { index, sample ->
                                                        if (index == 0) path.moveTo(x(sample), y(sample)) else {
                                                            val prev = segment[index - 1]
                                                            val mid = (x(prev) + x(sample)) / 2
                                                            path.cubicTo(mid, y(prev), mid, y(sample), x(sample), y(sample))
                                                        }
                                                    }
                                                    if (segment.size == 1) {
                                                        drawCircle(tint, radius = 2.dp.toPx(), center = Offset(x(segment.first()), y(segment.first())))
                                                    } else {
                                                        val area = Path().apply {
                                                            addPath(path)
                                                            lineTo(x(segment.last()), size.height)
                                                            lineTo(x(segment.first()), size.height)
                                                            close()
                                                        }
                                                        drawPath(area, brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint.copy(alpha = .32f), tint.copy(alpha = .03f))))
                                                        drawPath(path, tint, style = Stroke(width = 2.dp.toPx()))
                                                    }
                                                }
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
                                DashboardMemory(dashboardVisible, connected, ui.mode) { activity.connection.service }
                            } else if (tab == 2) {
                                DashboardLiveProxies(connected) { activity.connection.service }
                            } else if (tab == 3) {
                                DashboardLiveRules(connected, searchOpen, { searchOpen = it }) { activity.connection.service }
                            } else {
                                DashboardLiveTab(tab, connected, searchOpen, { searchOpen = it }) { activity.connection.service }
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(if (label == "Download") R.drawable.ic_dash_symbol_download else R.drawable.ic_dash_symbol_upload), contentDescription = null, modifier = Modifier.size(22.dp), tint = if (label == "Download") Color(0xFF81D99A) else MaterialTheme.colorScheme.primary)
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
            Text(value.trimStart('▲', '▼', ' ').ifBlank { "0 B/s" }, style = MaterialTheme.typography.titleLarge, color = if (label.contains("Download")) Color(0xFF81D99A) else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DashboardTabIcon(index: Int) {
    val icon = when (index) {
        0 -> R.drawable.ic_dash_symbol_home
        1 -> R.drawable.ic_dash_symbol_swap_horiz
        2 -> R.drawable.ic_dash_symbol_layers
        else -> R.drawable.ic_dash_symbol_format_list_bulleted
    }
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
}

@Composable
private fun DashboardMemory(visible: Boolean, connected: Boolean, mode: String,
    service: () -> io.nekohasekai.sagernet.aidl.ISagerNetService?) {
    val rootMode = mode.contains("root", ignoreCase = true) || mode.contains("tproxy", ignoreCase = true)
    var sample by remember(connected, mode) { mutableStateOf<DashboardMemorySample?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(visible, connected, mode, expanded) {
        while (visible) {
            val binder = service()
            val measured = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                val session = runCatching { binder?.selectorSessionId }.getOrNull()
                val info = android.os.Debug.MemoryInfo()
                android.os.Debug.getMemoryInfo(info)
                val runtime = Runtime.getRuntime()
                val servicePss = runCatching { binder?.servicePssBytes?.takeIf { it > 0L } }.getOrNull()
                val corePss = if (connected && rootMode && session != null && session != 0L) dashboardRootPss() else null
                val confirmed = runCatching { binder?.selectorSessionId }.getOrNull()
                DashboardMemorySample(info.totalPss.toLong() * 1024,
                    runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
                    android.os.Debug.getNativeHeapAllocatedSize(), servicePss,
                    corePss.takeIf { session == confirmed })
            }
            sample = if (service() === binder) measured else null
            delay(if (expanded) 10000L else 30000L)
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
                Text("Memory usage", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (expanded) "Less" else "Details") }
            }
            val current = sample
            fun mb(value: Long?): String = value?.let { String.format(java.util.Locale.getDefault(), "%.1f MiB", it / 1048576.0) } ?: "—"
            val rows = listOf("App process (PSS)" to mb(current?.pss),
                "Java heap" to (mb(current?.javaUsed) + " / " + mb(current?.javaLimit)),
                "Native heap (allocated)" to mb(current?.nativeAllocated),
                "Background service (PSS)" to mb(current?.backgroundPss),
                "ROOT core (PSS)" to if (rootMode && connected) mb(current?.rootPss) else "—")
            val pssValues = listOfNotNull(current?.pss, current?.backgroundPss, current?.rootPss)
            val complete = current != null && current.backgroundPss != null && (!connected || !rootMode || current.rootPss != null)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (complete) "UI + service + core PSS" else "Measured process PSS", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(mb(pssValues.takeIf { it.isNotEmpty() }?.sum()), style = MaterialTheme.typography.titleMedium)
            }
            if (expanded) rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(value, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (current != null) {
                val ratio = (current.javaUsed.toFloat() / current.javaLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Java heap · " + mb(current.javaUsed) + " / " + mb(current.javaLimit), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(String.format(java.util.Locale.getDefault(), "%.1f%%", ratio * 100), style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
            }
            if (expanded) Text("Measured UI, service and ROOT core only; helper processes are not included. Heap values overlap PSS. — means unavailable or inactive.",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

private data class DashboardMemorySample(val pss: Long, val javaUsed: Long, val javaLimit: Long, val nativeAllocated: Long, val backgroundPss: Long?, val rootPss: Long?)

private fun dashboardRootPss(): Long? {
    val configs = listOf(io.nekohasekai.sagernet.bg.root.RootDaemon.configPath,
        io.nekohasekai.sagernet.bg.root.RootBoot.bootCoreConfigPath,
        io.nekohasekai.sagernet.bg.root.RootBoot.runtimeBootCorePath)
    val quoted = configs.joinToString(" ") { "-e " + io.nekohasekai.sagernet.bg.root.RootShell.q(it) }
    val script = """
        for p in /proc/[0-9]*; do
            [ -r "${'$'}p/cmdline" ] || continue
            exe=${'$'}(readlink "${'$'}p/exe" 2>/dev/null) || continue
            case "${'$'}exe" in */sing-box|*/libsingbox.so) ;; *) continue ;; esac
            tr '\000' '\n' < "${'$'}p/cmdline" | grep -Fx $quoted >/dev/null || continue
            awk '/^Pss:/ {print ${'$'}2; exit}' "${'$'}p/smaps_rollup" 2>/dev/null
        done
    """.trimIndent()
    val result = io.nekohasekai.sagernet.bg.root.RootShell.run(script, timeoutMs = 3000)
    if (!result.ok) return null
    val values = result.out.lines().mapNotNull { it.trim().toLongOrNull()?.takeIf { value -> value > 0 } }
    return values.singleOrNull()?.times(1024)
}

@Composable
private fun DashboardOverviewIcon(kind: Int, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.width / 24f
        fun line(x: Float, y: Float, a: Float, b: Float) =
            drawLine(tint, Offset(x*s,y*s), Offset(a*s,b*s), 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        if (kind == 2) {
            line(3f,20f,3f,4f); line(3f,20f,21f,20f)
            line(6f,15f,10f,10f); line(10f,10f,14f,13f); line(14f,13f,21f,5f)
        } else {
            val tip = if (kind == 0) 17f else 4f
            val tail = if (kind == 0) 4f else 17f
            val wing = if (kind == 0) 11f else 10f
            line(12f,tail,12f,tip); line(6f,wing,12f,tip); line(18f,wing,12f,tip)
            line(5f,21f,19f,21f)
        }
    }
}
