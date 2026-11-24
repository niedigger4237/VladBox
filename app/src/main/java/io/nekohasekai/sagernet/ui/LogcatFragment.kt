package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.compose.HubTheme
import io.nekohasekai.sagernet.ui.compose.VladTopAppBar
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat) {
    private val logs = mutableStateOf("")
    private val busy = mutableStateOf(false)

    private val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val resolver = requireContext().contentResolver
            lifecycleScope.launch {
                busy.value = true
                try {
                    withContext(Dispatchers.IO) {
                        val file = SendLog.createLogFile("NieFigger-Hub")
                        try {
                            val output = resolver.openOutputStream(uri) ?: error("Cannot open destination")
                            output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                        } finally { file.delete() }
                    }
                    if (isAdded) snackbar("Logs saved").show()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (isAdded) snackbar(e.readableMessage).show()
                } finally { busy.value = false }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root = view as ViewGroup
        root.removeAllViews()
        val host = ComposeView(requireContext())
        host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        root.addView(host, ViewGroup.LayoutParams(-1, -1))
        host.setContent {
            HubTheme {
                var query by remember { mutableStateOf("") }
                var errorsOnly by remember { mutableStateOf(false) }
                var confirmClear by remember { mutableStateOf(false) }
                var exportMenu by remember { mutableStateOf(false) }
                val scroll = rememberScrollState()
                val lines = remember(logs.value, query, errorsOnly) {
                    logs.value.lineSequence().filter { it.contains(query, ignoreCase = true) &&
                        (!errorsOnly || it.contains("ERROR[") || it.contains(" [Error]") || it.contains("WARN[") || it.contains(" [Warning]")) }.toList()
                }
                val colors = MaterialTheme.colorScheme
                val annotated = remember(lines, colors) {
                    buildAnnotatedString {
                        lines.forEachIndexed { index, line ->
                            val color = when {
                                line.contains("ERROR[") || line.contains(" [Error]") -> colors.error
                                line.contains("WARN[") || line.contains(" [Warning]") -> colors.tertiary
                                line.contains("INFO[") || line.contains(" [Info]") -> colors.primary
                                else -> colors.onSurface
                            }
                            withStyle(SpanStyle(color = color)) { append(line) }
                            if (index != lines.lastIndex) append('\n')
                        }
                    }
                }
                LaunchedEffect(logs.value) { scroll.scrollTo(scroll.maxValue) }
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    VladTopAppBar(title = getString(R.string.menu_log),
                        onNavigation = { (requireActivity() as MainActivity).binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START) },
                        extraActions = {
                            IconButton(onClick = { reloadSession() }, enabled = !busy.value) { Icon(painterResource(R.drawable.ic_baseline_refresh_24), getString(R.string.group_update)) }
                            Box {
                                IconButton(onClick = { exportMenu = true }, enabled = !busy.value) {
                                    Icon(painterResource(R.drawable.baseline_send_24), "Export logs")
                                }
                                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                                    DropdownMenuItem(text = { Text("Share logs") }, onClick = { exportMenu = false; shareLogs() })
                                    DropdownMenuItem(text = { Text("Save to device") }, onClick = {
                                        exportMenu = false
                                        saveLog.launch("niefigger_hub_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.log")
                                    })
                                }
                            }
                            IconButton(onClick = { confirmClear = true }, enabled = !busy.value) { Icon(painterResource(R.drawable.baseline_delete_sweep_24), getString(R.string.clear_logcat)) }
                        })
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search logs") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !errorsOnly, onClick = { errorsOnly = false }, label = { Text("All") })
                        FilterChip(selected = errorsOnly, onClick = { errorsOnly = true }, label = { Text("Warnings & errors") })
                    }
                    if (busy.value) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Surface(Modifier.weight(1f).fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.large, color = colors.surfaceVariant.copy(alpha = .35f)) {
                        SelectionContainer {
                            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                                if (annotated.isEmpty()) Text("No logs", color = colors.onSurfaceVariant)
                                else Text(annotated, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
                if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
                    title = { Text(getString(R.string.clear_logcat)) },
                    confirmButton = { TextButton(onClick = { confirmClear = false; clearLogs() }) { Text(getString(android.R.string.ok)) } },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(getString(android.R.string.cancel)) } })
            }
        }
        reloadSession()
    }

    private fun reloadSession() = viewLifecycleOwner.lifecycleScope.launch {
        busy.value = true
        try { logs.value = withContext(Dispatchers.IO) { String(SendLog.getNekoLog(50 * 1024)) } }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; snackbar(e.readableMessage).show() }
        finally { busy.value = false }
    }

    private fun clearLogs() = viewLifecycleOwner.lifecycleScope.launch {
        busy.value = true
        try {
            withContext(Dispatchers.IO) {
                Libcore.nekoLogClear()
                Runtime.getRuntime().exec("/system/bin/logcat -c")
            }
            logs.value = ""
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; snackbar(e.readableMessage).show() }
        finally { busy.value = false }
    }

    private fun shareLogs() {
        val context = requireContext()
        runOnDefaultDispatcher { SendLog.sendLog(context, "NB4A") }
    }
}
