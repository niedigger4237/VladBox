package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.GravityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.generator.proton.ProtonParser
import io.nekohasekai.sagernet.generator.proton.ProtonPresets
import io.nekohasekai.sagernet.generator.proton.ProtonScreen
import io.nekohasekai.sagernet.generator.proton.ProtonConfExporter as Conf
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ui.compose.HubTheme
import io.nekohasekai.sagernet.ui.compose.VladTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

open class NativeProtonFragment : ToolbarFragment() {
    class FileState : ViewModel() {
        internal val sources = mutableStateOf<List<ProtonParser.Source>>(emptyList())
        internal var pending: List<Conf.Output> = emptyList()
    }
    private val model by lazy { ViewModelProvider(this)[FileState::class.java] }
    private val presets by lazy { ProtonPresets(requireContext().applicationContext) }
    private fun notify(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val (sources, failures) = withContext(Dispatchers.IO) {
                    val loaded = mutableListOf<ProtonParser.Source>()
                    var failed = 0
                    for (uri in uris.take(100)) {
                        try {
                            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            } ?: "proton_${loaded.size + 1}.conf"
                            val text = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { reader ->
                                val out = StringBuilder()
                                val buffer = CharArray(4096)
                                while (true) {
                                    val n = reader.read(buffer)
                                    if (n < 0) break
                                    require(out.length + n <= 1024 * 1024) { "File too large" }
                                    out.append(buffer, 0, n)
                                }
                                out.toString()
                            }
                            loaded += ProtonParser.Source(name, text)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed++ }
                    }
                    loaded to (failed + maxOf(0, uris.size - 100))
                }
                if (sources.isNotEmpty()) model.sources.value = sources
                notify(context, "Loaded ${sources.size} configuration(s), failed: $failures")
            }
        }
    }
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val output = model.pending.singleOrNull()
        model.pending = emptyList()
        if (uri != null && output != null) {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { write(context, uri, output.text) }.isSuccess }
                notify(context, if (ok) "Configuration saved" else "Unable to save configuration")
            }
        }
    }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val outputs = model.pending
        model.pending = emptyList()
        if (uri != null && outputs.isNotEmpty()) {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val count = withContext(Dispatchers.IO) {
                    val dir = DocumentFile.fromTreeUri(context, uri)
                    outputs.count { output ->
                        runCatching {
                            val file = requireNotNull(dir?.createFile("application/octet-stream", output.fileName))
                            try { write(context, file.uri, output.text) }
                            catch (e: Exception) { file.delete(); throw e }
                        }.isSuccess
                    }
                }
                notify(context, "Saved $count of ${outputs.size} configuration(s)")
            }
        }
    }
    private fun write(context: Context, uri: Uri, text: String) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HubTheme {
                    Column(Modifier.fillMaxSize()) {
                        VladTopAppBar(title = getString(R.string.menu_proton), onNavigation = {
                            (requireActivity() as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
                        })
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            ProtonScreen(presets, model.sources.value, { model.sources.value = it },
                                onPick = { pick.launch(arrayOf("*/*")) },
                                onCopy = { text ->
                                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("config", text))
                                },
                                onSave = { outputs ->
                                    model.pending = outputs
                                    if (outputs.size == 1) save.launch(outputs.first().fileName)
                                    else if (outputs.isNotEmpty()) folder.launch(null)
                                },
                                onImport = ::importConfig)
                        }
                    }
                }
            }
        }
    private fun importConfig(text: String) {
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val target = DataStore.selectedGroupForImport()
                    val name = text.lineSequence().firstOrNull()?.removePrefix("#")?.trim()?.takeIf { it.isNotEmpty() } ?: "Proton"
                    var imported = 0
                    for (bean in RawUpdater.parseWireGuard(text)) {
                        bean.name = name
                        ProfileManager.createProfile(target, bean)
                        imported++
                    }
                    if (imported > 0) DataStore.editingGroup = target
                    imported
                }.getOrDefault(0)
            }
            notify(context, if (count > 0) context.getString(R.string.warpgen_imported, count) else "Unable to import configuration")
        }
    }
}
