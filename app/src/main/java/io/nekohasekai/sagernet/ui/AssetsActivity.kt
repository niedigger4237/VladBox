package io.nekohasekai.sagernet.ui

import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import libcore.Libcore
import java.io.File
import java.util.Date


/** Manages local sing-box binary Rule Sets. Only .srs files are accepted. */
class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layout = LayoutAssetsBinding.inflate(layoutInflater)
        setContentView(layout.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        layout.recyclerView.layoutManager = FixedLinearLayoutManager(layout.recyclerView)
        adapter = AssetAdapter()
        layout.recyclerView.adapter = adapter
        layout.refreshLayout.setOnRefreshListener {
            loadInstalledSets()
        }
        layout.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))
        undoManager = UndoSnackbarManager(this, adapter)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
                super.getSwipeDirs(recyclerView, viewHolder)
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                val file = adapter.assets[index]
                adapter.remove(index)
                undoManager.remove(index to file)
            }
        }).attachToRecyclerView(layout.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }?.let(cursor::getString) else null
            }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
            if (File(name).name != name || !name.endsWith(".srs", ignoreCase = true)) {
                alert(getString(R.string.route_not_asset, name)).show()
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val target = File(SagerNet.application.externalAssets, name)
                        val temp = File.createTempFile("import-", ".tmp", target.parentFile)
                        try {
                            val input = contentResolver.openInputStream(uri) ?: error("Cannot open selected file")
                            input.use { temp.outputStream().use { output -> it.copyTo(output) } }
                            installRuleSet(temp, target, "Custom")
                        } finally { temp.delete() }
                    }
                    adapter.reloadAssets()
                }.onFailure { alert(it.readableMessage).tryToShow() }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> startFilesForResult(importFile, "*/*")
            R.id.action_geo_custom -> editSources()
            R.id.action_geo_provider -> {
                val ids = resources.getStringArray(R.array.srs_provider_values).map(String::toInt)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.route_rules_provider)
                    .setSingleChoiceItems(R.array.rules_dat_provider, ids.indexOf(DataStore.rulesProvider).coerceAtLeast(0)) { d, i ->
                        d.dismiss()
                        if (ids[i] == 4) editSources() else DataStore.rulesProvider = ids[i]
                    }.show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun editSources() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(label: String, value: String) = EditText(this).apply {
            hint = label
            setSingleLine()
            setText(value)
            box.addView(this)
        }
        val ip = field("GeoIP URL", DataStore.geoipSrsSource)
        val site = field("GeoSite URL", DataStore.geositeSrsSource)
        MaterialAlertDialogBuilder(this).setTitle(R.string.route_rules_custom)
            .setMessage(R.string.srs_source_hint).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.geoipSrsSource = ip.text.toString().trim()
                DataStore.geositeSrsSource = site.text.toString().trim()
                DataStore.rulesProvider = 4
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private var loading = false

    private fun loadInstalledSets() {
        if (loading) return
        loading = true
        layout.refreshLayout.isRefreshing = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Libcore.ensureRuleSets() }
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) alert(e.readableMessage).tryToShow()
            } finally {
                adapter.reloadAssets()
                loading = false
                layout.refreshLayout.isRefreshing = false
            }
        }
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(), UndoSnackbarManager.Interface<File> {
        val assets = ArrayList<File>()

        suspend fun reloadAssets() {
            val dir = SagerNet.application.externalAssets
            val files = withContext(Dispatchers.IO) {
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".srs", true) }
                    ?.sortedBy { it.name } ?: emptyList()
            }
            assets.clear()
            assets.addAll(files)
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        override fun onBindViewHolder(holder: AssetHolder, position: Int) = holder.bind(assets[position])
        override fun getItemCount() = assets.size
        fun remove(index: Int) { assets.removeAt(index); notifyItemRemoved(index) }
        override fun undo(actions: List<Pair<Int, File>>) {
            actions.forEach { (index, file) -> assets.add(index, file); notifyItemInserted(index) }
        }
        override fun commit(actions: List<Pair<Int, File>>) {
            runOnDefaultDispatcher { actions.forEach { it.second.delete() } }
        }
    }

    private val updating = mutableSetOf<File>()

    inner class AssetHolder(private val binding: LayoutAssetItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")
            val version = if (versionFile.isFile) runCatching { versionFile.readText().trim() }.getOrDefault("<unknown>")
                else "Local-${DateFormat.getDateFormat(app).format(Date(file.lastModified()))}"
            binding.assetStatus.text = getString(R.string.route_asset_status, version)
            val canUpdate = file.name.startsWith("geoip-") || file.name.startsWith("geosite-")
            binding.rulesUpdate.isInvisible = !canUpdate || file in updating
            binding.subscriptionUpdateProgress.isInvisible = file !in updating
            binding.rulesUpdate.setOnClickListener {
                val position = bindingAdapterPosition
                if (position < 0 || !updating.add(file)) return@setOnClickListener
                adapter.notifyItemChanged(position)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { downloadRuleSet(file) }
                        snackbar(R.string.route_asset_updated).show()
                    } catch (e: Exception) {
                        if (!isFinishing && !isDestroyed) alert(e.readableMessage).tryToShow()
                    } finally {
                        updating.remove(file)
                        val index = adapter.assets.indexOf(file)
                        if (index >= 0) adapter.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    private fun downloadRuleSet(file: File) {
        val repo = when {
            file.name.startsWith("geoip-") -> "sing-geoip"
            file.name.startsWith("geosite-") -> "sing-geosite"
            else -> error("Only official geoip-*.srs and geosite-*.srs updates are supported")
        }
        val provider = DataStore.rulesProvider
        val base = when (provider) {
            2 -> "https://raw.githubusercontent.com/Chocolate4U/Iran-sing-box-rules/rule-set"
            4 -> (if (repo == "sing-geoip") DataStore.geoipSrsSource else DataStore.geositeSrsSource)
                .also { require(it.isNotBlank()) { getString(R.string.route_custom_url_empty, repo) } }
            else -> "https://raw.githubusercontent.com/SagerNet/$repo/rule-set"
        }
        val name = Uri.encode(file.name)
        val url = if ("{name}" in base) base.replace("{name}", name) else base.trimEnd('/') + "/" + name
        require(Uri.parse(url).scheme == "https" && !Uri.parse(url).host.isNullOrBlank()) { "Invalid HTTPS source" }
        val client = Libcore.newHttpClient().apply { modernTLS(); keepAlive(); trySocks5(DataStore.mixedPort) }
        try {
            val temp = File.createTempFile("srs-", ".download", file.parentFile)
            try {
                client.newRequest().apply { setURL(url) }.execute().writeTo(temp.canonicalPath)
                val label = when (provider) { 4 -> "Custom"; 2 -> "Chocolate4U"; else -> "SagerNet" }
                installRuleSet(temp, file, label)
            } finally { temp.delete() }
        } finally { client.close() }
    }

    private fun installRuleSet(temp: File, file: File, label: String) {
        val valid = temp.inputStream().buffered().use {
            it.read() == 0x53 && it.read() == 0x52 && it.read() == 0x53 && it.read() > 0 && it.read() >= 0
        }
        require(valid) { "Invalid SRS file: ${file.name}" }
        check(temp.renameTo(file)) { "Cannot install ${file.name}" }
        File(file.parentFile, "${file.nameWithoutExtension}.version.txt").writeText(label)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onBackPressed() { finish() }
    override fun onResume() { super.onResume(); loadInstalledSets() }
}
