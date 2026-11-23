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
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START
        ) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val index = viewHolder.bindingAdapterPosition
                if (index < 2) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                adapter.remove(index)
                undoManager.remove(index to (viewHolder as AssetHolder).file)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    val assetNames = arrayOf("geoip.db", "geosite.db")

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            val fileName = contentResolver.query(file, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
                .substringAfterLast('/')
                .substringAfter(':')

            if (!fileName.endsWith(".db")) {
                alert(getString(R.string.route_not_asset, fileName)).show()
                return@registerForActivityResult
            }
            val filesDir = getExternalFilesDir(null) ?: filesDir

            runOnDefaultDispatcher {
                val outFile = File(filesDir, fileName).apply {
                    parentFile?.mkdirs()
                }

                contentResolver.openInputStream(file)?.use(outFile.outputStream())

                File(outFile.parentFile, outFile.nameWithoutExtension + ".version.txt").apply {
                    if (isFile) delete()
                    createNewFile()
                    val fw = FileWriter(this)
                    fw.write("Custom")
                    fw.close()
                }

                adapter.reloadAssets()
            }

        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }
            R.id.action_geo_provider -> {
                chooseProvider()
                return true
            }
            R.id.action_geo_custom -> {
                editCustomUrls()
                return true
            }
        }
        return false
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        val assets = ArrayList<File>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val files = filesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".db") && it.name !in assetNames }
            assets.clear()
            assets.add(File(filesDir, "geoip.db"))
            assets.add(File(filesDir, "geosite.db"))
            if (files != null) assets.addAll(files)

            layout.refreshLayout.post {
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, item) in actions) {
                assets.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val groups = actions.map { it.second }.toTypedArray()
            runOnDefaultDispatcher {
                groups.forEach { it.deleteRecursively() }
            }
        }

    }

    val updating = AtomicInteger()

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file

            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")

            val localVersion = if (file.isFile) {
                if (versionFile.isFile) {
                    try {
                        versionFile.readText().trim()
                    } catch (e: Throwable) {
                        snackbar(e.readableMessage)
                        "<unknown>"
                    }
                } else {
                    "Unknown-" + DateFormat.getDateFormat(app).format(Date(file.lastModified()))
                }
            } else {
                "<unknown>"
            }

            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)

            binding.rulesUpdate.isInvisible = file.name !in assetNames
            binding.rulesUpdate.setOnClickListener {
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                runOnDefaultDispatcher {
                    runCatching {
                        updateAsset(file, versionFile, localVersion)
                    }.onFailure {
                        onMainDispatcher {
                            alert(it.readableMessage).tryToShow()
                        }
                    }

                    onMainDispatcher {
                        binding.rulesUpdate.isInvisible = false
                        binding.subscriptionUpdateProgress.isInvisible = true
                        if (updating.decrementAndGet() == 0) {
                            layout.refreshLayout.isEnabled = true
                        }
                    }
                }
            }

        }

    }

    private val rulesProviders = listOf(
        RuleAssetsProvider(
            "SagerNet/sing-geoip",
            "SagerNet/sing-geosite",
        ),
        RuleAssetsProvider(
            "soffchen/sing-geoip",
            "soffchen/sing-geosite",
        ),
        RuleAssetsProvider(
            "Chocolate4U/Iran-sing-box-rules"
        ),
        RuleAssetsProvider(
            // репозиторий переехал: L11R/antizapret-sing-box-geo -> savely-krasovsky/antizapret-sing-box
            "savely-krasovsky/antizapret-sing-box"
        ),
    )

    private val CUSTOM_PROVIDER = 4

    /** Прямая ссылка на .db (или .db.xz) из настроек «Свой источник». */
    suspend fun updateAssetFromUrl(file: File, versionFile: File) {
        val url = (if (file.name == "geoip.db") DataStore.customGeoipUrl else DataStore.customGeositeUrl).trim()
        if (url.isEmpty()) error(getString(R.string.route_custom_url_empty, file.name))
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            trySocks5(DataStore.mixedPort)
        }
        try {
            val response = client.newRequest().apply { setURL(url) }.execute()
            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()
            response.writeTo(cacheFile.canonicalPath)
            if (url.endsWith(".xz")) {
                Libcore.unxz(cacheFile.absolutePath, file.absolutePath)
                cacheFile.delete()
            } else {
                cacheFile.renameTo(file)
            }
            versionFile.writeText("custom-" + DateFormat.getDateFormat(app).format(Date()))
            adapter.reloadAssets()
            onMainDispatcher { snackbar(R.string.route_asset_updated).show() }
        } finally {
            client.close()
        }
    }

    private fun chooseProvider() {
        val names = resources.getStringArray(R.array.rules_dat_provider)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_rules_provider)
            .setSingleChoiceItems(names, DataStore.rulesProvider.coerceIn(0, names.size - 1)) { d, which ->
                DataStore.rulesProvider = which
                d.dismiss()
                if (which == CUSTOM_PROVIDER) editCustomUrls()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun editCustomUrls() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val ip = android.widget.EditText(this).apply { hint = "geoip.db URL"; setText(DataStore.customGeoipUrl); setSingleLine() }
        val site = android.widget.EditText(this).apply { hint = "geosite.db URL"; setText(DataStore.customGeositeUrl); setSingleLine() }
        box.addView(ip); box.addView(site)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_rules_custom)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.customGeoipUrl = ip.text.toString().trim()
                DataStore.customGeositeUrl = site.text.toString().trim()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    suspend fun updateAsset(file: File, versionFile: File, localVersion: String) {
        val fileName = file.name

        if (DataStore.rulesProvider == CUSTOM_PROVIDER) {
            updateAssetFromUrl(file, versionFile)
            return
        }
        val ruleProvider = rulesProviders[DataStore.rulesProvider.coerceIn(0, rulesProviders.size - 1)]
        val repo = ruleProvider.repoByFileName[fileName] ?: error("No repository for $fileName")

        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            trySocks5(DataStore.mixedPort)
        }

        try {
            // 1) Пробуем узнать версию и ссылку через GitHub API.
            //    Без авторизации у API жёсткий лимит запросов (особенно за общим IP
            //    мобильного оператора), поэтому при любой ошибке идём дальше по запасному пути.
            var tagName: String? = null
            var downloadUrl: String? = null
            var apiError: Throwable? = null
            try {
                val response = client.newRequest().apply {
                    setURL("https://api.github.com/repos/$repo/releases/latest")
                }.execute()
                val release = JSONObject(Util.getStringBox(response.contentString))
                tagName = release.optString("tag_name").takeIf { it.isNotBlank() }
                val assetsArray = release.optJSONArray("assets")
                if (assetsArray != null) {
                    for (i in 0 until assetsArray.length()) {
                        val asset = assetsArray.optJSONObject(i) ?: continue
                        if (asset.optString("name") == fileName) {
                            downloadUrl = asset.optString("browser_download_url")
                                .takeIf { it.isNotBlank() }
                            break
                        }
                    }
                }
                if (tagName == null || downloadUrl == null) {
                    error(
                        release.optString("message").takeIf { it.isNotBlank() }
                            ?: "File $fileName not found in latest release of $repo"
                    )
                }
            } catch (e: Throwable) {
                apiError = e
            }

            if (tagName != null && tagName == localVersion) {
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            // 2) Скачиваем файл. Если API не ответил, берём прямую ссылку на последний релиз
            //    (она не использует API и не упирается в его лимиты).
            val url = downloadUrl ?: "https://github.com/$repo/releases/latest/download/$fileName"

            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()

            try {
                val response = client.newRequest().apply {
                    setURL(url)
                }.execute()
                response.writeTo(cacheFile.canonicalPath)
            } catch (e: Throwable) {
                cacheFile.delete()
                if (apiError != null) {
                    error("API: ${apiError.readableMessage}\nDownload: ${e.readableMessage}")
                }
                throw e
            }

            if (fileName.endsWith(".xz")) {
                Libcore.unxz(cacheFile.absolutePath, file.absolutePath)
                cacheFile.delete()
            } else {
                cacheFile.renameTo(file)
            }

            versionFile.writeText(
                tagName ?: ("latest-" + DateFormat.getDateFormat(app).format(Date()))
            )

            adapter.reloadAssets()

            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }

    private data class RuleAssetsProvider(
        val repoByFileName: Map<String, String>
    ) {
        constructor(
            geoipRepo: String,
            geositeRepo: String = geoipRepo,
        ) : this(
            mapOf(
                "geoip.db" to geoipRepo,
                "geosite.db" to geositeRepo,
            )
        )
    }
}
