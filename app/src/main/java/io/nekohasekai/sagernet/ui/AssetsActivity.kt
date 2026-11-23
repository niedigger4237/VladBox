package io.nekohasekai.sagernet.ui

import android.os.Bundle
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
import java.util.concurrent.atomic.AtomicInteger

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
            adapter.reloadAssets()
            layout.refreshLayout.isRefreshing = false
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
        menu.findItem(R.id.action_geo_provider)?.isVisible = false
        menu.findItem(R.id.action_geo_custom)?.isVisible = false
        return true
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }?.let(cursor::getString) else null
            }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
            if (!name.endsWith(".srs", ignoreCase = true)) {
                alert(getString(R.string.route_not_asset, name)).show()
                return@registerForActivityResult
            }
            runOnDefaultDispatcher {
                val target = File(SagerNet.application.externalAssets, name)
                contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    ?: error("Cannot open selected file")
                check(target.length() > 0L) { "Imported SRS file is empty" }
                adapter.reloadAssets()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_import_file) {
            startFilesForResult(importFile, "application/octet-stream")
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(), UndoSnackbarManager.Interface<File> {
        val assets = ArrayList<File>()
        init { reloadAssets() }

        fun reloadAssets() {
            val dir = SagerNet.application.externalAssets
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".srs", true) }
                ?.sortedBy { it.name } ?: emptyList()
            assets.clear()
            assets.addAll(files)
            layout.recyclerView.post { notifyDataSetChanged() }
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

    private val updating = AtomicInteger()

    inner class AssetHolder(private val binding: LayoutAssetItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")
            val version = if (versionFile.isFile) runCatching { versionFile.readText().trim() }.getOrDefault("<unknown>")
                else "Local-${DateFormat.getDateFormat(app).format(Date(file.lastModified()))}"
            binding.assetStatus.text = getString(R.string.route_asset_status, version)
            binding.rulesUpdate.isInvisible = !(file.name.startsWith("geoip-") || file.name.startsWith("geosite-"))
            binding.rulesUpdate.setOnClickListener {
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                runOnDefaultDispatcher {
                    runCatching { downloadRuleSet(file, versionFile) }.onFailure {
                        onMainDispatcher { alert(it.readableMessage).tryToShow() }
                    }
                    onMainDispatcher {
                        binding.rulesUpdate.isInvisible = false
                        binding.subscriptionUpdateProgress.isInvisible = true
                        if (updating.decrementAndGet() == 0) layout.refreshLayout.isEnabled = true
                    }
                }
            }
        }
    }

    private suspend fun downloadRuleSet(file: File, versionFile: File) {
        val repo = when {
            file.name.startsWith("geoip-") -> "sing-geoip"
            file.name.startsWith("geosite-") -> "sing-geosite"
            else -> error("Only official geoip-*.srs and geosite-*.srs updates are supported")
        }
        val client = Libcore.newHttpClient().apply { modernTLS(); keepAlive() }
        try {
            val temp = File(file.parentFile, file.name + ".download")
            val response = client.newRequest().apply {
                setURL("https://raw.githubusercontent.com/SagerNet/$repo/rule-set/${file.name}")
            }.execute()
            response.writeTo(temp.canonicalPath)
            check(temp.isFile && temp.length() > 0L) { "Downloaded ${file.name} is empty" }
            val header = temp.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            check(header.size == 4 && header[0] == 0x53.toByte() &&
                header[1] == 0x52.toByte() && header[2] == 0x53.toByte() &&
                header[3].toInt() > 0) {
                "Downloaded file is not a valid SRS Rule Set"
            }
            val staged = File(file.parentFile, file.name + ".install")
            temp.copyTo(staged, overwrite = true)
            check(staged.renameTo(file)) { "Cannot install ${file.name}" }
            temp.delete()
            staged.delete()
            versionFile.writeText("SagerNet/rule-set")
            adapter.reloadAssets()
            onMainDispatcher { snackbar(R.string.route_asset_updated).show() }
        } finally { client.close() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onBackPressed() { finish() }
    override fun onResume() { super.onResume(); if (::adapter.isInitialized) adapter.reloadAssets() }
}
