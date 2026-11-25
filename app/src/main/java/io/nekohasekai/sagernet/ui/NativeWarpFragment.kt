package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.GravityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.generator.warp.WarpScreen
import io.nekohasekai.sagernet.generator.warp.WarpViewModel
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ui.compose.HubTheme
import io.nekohasekai.sagernet.ui.compose.VladTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NativeWarpFragment : ToolbarFragment() {
    private val model by lazy { ViewModelProvider(this)[WarpViewModel::class.java] }
    private var pendingText: String? = null
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingText
        pendingText = null
        if (uri != null && text != null) {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                            it.write(text.toByteArray(Charsets.UTF_8))
                        }
                    }.isSuccess
                }
                Toast.makeText(context, if (ok) "Configuration saved" else "Unable to save configuration", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HubTheme {
                    Column(Modifier.fillMaxSize()) {
                        VladTopAppBar(title = getString(R.string.menu_warp), onNavigation = {
                            (requireActivity() as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
                        })
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            WarpScreen(model, onCopy = { text ->
                                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("config", text))
                            }, onSave = { output ->
                                pendingText = output.text
                                save.launch(output.fileName)
                            }, onImport = ::importConfig)
                        }
                    }
                }
            }
        }

    private fun importConfig(text: String) {
        val context = requireContext().applicationContext
        val name = model.state.value.output?.fileName?.substringBeforeLast('.') ?: "WARP"
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val target = DataStore.selectedGroupForImport()
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
            Toast.makeText(context, if (count > 0) context.getString(R.string.warpgen_imported, count)
                else "Unable to import configuration", Toast.LENGTH_LONG).show()
        }
    }
}
