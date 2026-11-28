package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import android.text.TextWatcher
import android.text.Editable
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.AppListPreference
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.EditConfigPreference

@Suppress("UNCHECKED_CAST")
class RouteSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_settings_activity,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    fun init(packageName: String?) {
        RuleEntity().apply {
            if (!packageName.isNullOrBlank()) {
                packages = setOf(packageName)
                name = app.getString(R.string.route_for, PackageCache.loadLabel(packageName))
            }
        }.init()
    }

    fun RuleEntity.init() {
        DataStore.routeName = name
        DataStore.serverConfig = config
        DataStore.routeDomain = domains
        DataStore.routeIP = ip
        DataStore.routePort = port
        DataStore.routeSourcePort = sourcePort
        DataStore.routeNetwork = network
        DataStore.routeSource = source
        DataStore.routeProtocol = protocol
        DataStore.routeOutboundRule = outbound
        DataStore.routeOutbound = when (outbound) {
            0L -> 0
            -1L -> 1
            -2L -> 2
            else -> 3
        }
        DataStore.routePackages = packages.joinToString("\n")
    }

    fun RuleEntity.serialize() {
        name = DataStore.routeName
        config = DataStore.serverConfig
        domains = DataStore.routeDomain
        ip = DataStore.routeIP
        port = DataStore.routePort
        sourcePort = DataStore.routeSourcePort
        network = DataStore.routeNetwork
        source = DataStore.routeSource
        protocol = DataStore.routeProtocol
        outbound = when (DataStore.routeOutbound) {
            0 -> 0L
            1 -> -1L
            2 -> -2L
            else -> DataStore.routeOutboundRule
        }
        packages = DataStore.routePackages.split("\n").filter { it.isNotBlank() }.toSet()

        if (DataStore.editingId == 0L) {
            enabled = true
        }
    }

    private lateinit var editConfigPreference: EditConfigPreference

    fun needSave(): Boolean {
        return DataStore.dirty
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.route_preferences)

        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!
    }

    override fun onResume() {
        super.onResume()

        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
        }
    }

    val selectProfileForAdd = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (resultCode, data) ->
        if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                data!!.getLongExtra(
                    ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                )
            ) ?: return@runOnDefaultDispatcher
            DataStore.routeOutboundRule = profile.id
            onMainDispatcher {
                if (nativeEditor) { DataStore.routeOutbound = 3; refreshEditor() } else outbound.value = "3"
            }
        }
    }

    val selectAppList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (_, _) ->
        if (nativeEditor) refreshEditor() else apps.postUpdate()
    }

    lateinit var outbound: OutboundPreference
    lateinit var apps: AppListPreference


    private data class OnlineSrs(val name: String, val downloadUrl: String, val provider: String)

    private fun githubTree(owner: String, repo: String, branch: String, folder: String, label: String): List<OnlineSrs> {
        val endpoint = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VladBox-SRS-Catalog")
        }
        try {
            check(connection.responseCode == 200) { "GitHub catalog HTTP ${connection.responseCode}: $label" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(!json.optBoolean("truncated")) { "$label catalog is truncated; cannot reliably search all sets" }
            val tree = json.getJSONArray("tree")
            val results = mutableListOf<OnlineSrs>()
            for (i in 0 until tree.length()) {
                val item = tree.getJSONObject(i)
                val path = item.optString("path")
                if (item.optString("type") != "blob" || !path.endsWith(".srs", true)) continue
                if (folder.isNotBlank() && !path.startsWith("$folder/")) continue
                val filename = path.substringAfterLast('/')
                if (!filename.startsWith("geoip-") && !filename.startsWith("geosite-")) continue
                val encodedPath = path.split('/').joinToString("/") { android.net.Uri.encode(it) }
                results.add(OnlineSrs(filename, "https://raw.githubusercontent.com/$owner/$repo/$branch/$encodedPath", label))
            }
            return results
        } finally { connection.disconnect() }
    }

    private fun onlineCatalog(provider: Int): List<OnlineSrs> = when (provider) {
        0 -> githubTree("SagerNet", "sing-geosite", "rule-set", "", "SagerNet") +
             githubTree("SagerNet", "sing-geoip", "rule-set", "", "SagerNet")
        1 -> githubTree("runetfreedom", "russia-v2ray-rules-dat", "release", "sing-box", "RunetFreedom")
        else -> {
            // A custom URL may point to one concrete SRS, but does not expose a searchable catalog.
            emptyList()
        }
    }.distinctBy { it.name }.sortedBy { it.name }

    private fun installOnlineSrs(item: OnlineSrs) {
        val target = File(SagerNet.application.externalAssets, item.name)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val connection = (URL(item.downloadUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 60000
                        setRequestProperty("User-Agent", "VladBox-SRS-Catalog")
                    }
                    try {
                        check(connection.responseCode == 200) { "Download HTTP ${connection.responseCode}" }
                        val temp = File.createTempFile("online-srs-", ".tmp", target.parentFile)
                        try {
                            connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                            val valid = temp.inputStream().buffered().use {
                                it.read() == 0x53 && it.read() == 0x52 && it.read() == 0x53 &&
                                    it.read() > 0 && it.read() >= 0
                            }
                            check(valid) { "Invalid SRS file: ${item.name}" }
                            // Keep the existing installed set intact until the replacement is fully downloaded.
                            val backup = File(target.parentFile, "${target.name}.backup")
                            if (target.exists()) check(target.renameTo(backup)) { "Cannot back up installed SRS" }
                            if (!temp.renameTo(target)) {
                                if (backup.exists()) backup.renameTo(target)
                                error("Cannot install SRS")
                            }
                            backup.delete()
                            File(target.parentFile, "${target.nameWithoutExtension}.source-url.txt").writeText(item.downloadUrl)
                            File(target.parentFile, "${target.nameWithoutExtension}.version.txt").writeText(item.provider)
                        } finally { temp.delete() }
                    } finally { connection.disconnect() }
                }
                val selector = item.name.removeSuffix(".srs").let {
                    if (it.startsWith("geoip-")) "geoip:" + it.removePrefix("geoip-")
                    else "geosite:" + it.removePrefix("geosite-")
                }
                if (selector.startsWith("geoip:")) {
                    val values = DataStore.routeIP.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    if (selector !in values) values.add(selector)
                    DataStore.routeIP = values.joinToString("\n")
                } else {
                    val values = DataStore.routeDomain.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    if (selector !in values) values.add(selector)
                    DataStore.routeDomain = values.joinToString("\n")
                }
                DataStore.dirty = true
                refreshEditor()
                Toast.makeText(this@RouteSettingsActivity, "Installed and selected: $selector", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@RouteSettingsActivity)
                    .setTitle("SRS download failed").setMessage(e.message ?: "Unknown error")
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private fun showOnlineSrsPicker() {
        val providers = arrayOf("SagerNet", "RunetFreedom")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val provider = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(this@RouteSettingsActivity, android.R.layout.simple_spinner_dropdown_item, providers)
            setSelection(when (DataStore.rulesProvider) { 5 -> 1; else -> 0 })
        }
        val search = EditText(this).apply { hint = "Search online SRS"; setSingleLine(true) }
        val list = ListView(this)
        container.addView(provider)
        container.addView(search)
        container.addView(list, LinearLayout.LayoutParams(-1, (300 * resources.displayMetrics.density).toInt()))
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Online SRS repository")
            .setView(container).setNegativeButton(android.R.string.cancel, null).create()
        var catalog = emptyList<OnlineSrs>()
        fun refresh() {
            val query = search.text.toString().trim()
            val visible = catalog.filter { it.name.contains(query, ignoreCase = true) }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                visible.map { it.name.removeSuffix(".srs") })
            list.setOnItemClickListener { _, _, position, _ ->
                dialog.dismiss()
                installOnlineSrs(visible[position])
            }
        }
        var requestId = 0
        fun load() {
            val id = ++requestId
            val selected = provider.selectedItemPosition
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("Loading repository catalog…"))
            list.onItemClickListener = null
            lifecycleScope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) { onlineCatalog(selected) }
                    if (id != requestId || !dialog.isShowing) return@launch
                    catalog = loaded
                    refresh()
                    if (catalog.isEmpty()) Toast.makeText(this@RouteSettingsActivity,
                        "No SRS files found in this repository", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    if (id != requestId || !dialog.isShowing) return@launch
                    list.adapter = ArrayAdapter(this@RouteSettingsActivity,
                        android.R.layout.simple_list_item_1, listOf("Catalog error: ${e.message}"))
                    list.onItemClickListener = null
                }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
        provider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { load() }
        }
        dialog.show()
    }

    fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        outbound = findPreference(Key.ROUTE_OUTBOUND)!!
        apps = findPreference(Key.ROUTE_PACKAGES)!!
        findPreference<Preference>("routeSrsPicker")?.setOnPreferenceClickListener { showInstalledSrs(); true }


        outbound.setOnPreferenceChangeListener { _, newValue ->
            if (newValue.toString() == "3") {
                selectProfileForAdd.launch(
                    Intent(
                        this@RouteSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
                false
            } else {
                true
            }
        }

        apps.setOnPreferenceClickListener {
            selectAppList.launch(
                Intent(
                    this@RouteSettingsActivity, AppListActivity::class.java
                )
            )
            true
        }
    }

    val editorRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val nativeEditor get() = !SagerNet.isTv
    fun refreshEditor() { editorRevision.value++ }
    fun editorSave() { runOnDefaultDispatcher { saveAndExit() } }
    fun editorDelete() {
        if (DataStore.editingId == 0L) finish()
        else DeleteConfirmationDialogFragment().apply {
            arg(ProfileIdArg(DataStore.editingId))
            key()
        }.show(supportFragmentManager, null)
    }
    private fun showNativeEditor() {
        val host = androidx.compose.ui.platform.ComposeView(this)
        setContentView(host)
        io.nekohasekai.sagernet.ui.compose.installRouteEditor(this, host)
    }
    fun showInstalledSrs() {
            val files = SagerNet.application.externalAssets.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".srs") &&
                    (it.name.startsWith("geosite-") || it.name.startsWith("geoip-")) }
                ?.sortedBy { it.name }.orEmpty()
            if (files.isEmpty()) {
                MaterialAlertDialogBuilder(this@RouteSettingsActivity)
                    .setTitle("No installed SRS files")
                    .setMessage("Download rule sets from Route → Assets first.")
                    .setPositiveButton("Open Assets") { _, _ ->
                        startActivity(Intent(this@RouteSettingsActivity, AssetsActivity::class.java))
                    }.setNeutralButton("Online") { _, _ -> showOnlineSrsPicker() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } else {
                val selectors = files.map { file ->
                    val base = file.name.removeSuffix(".srs")
                    if (base.startsWith("geoip-")) "geoip:" + base.removePrefix("geoip-")
                    else "geosite:" + base.removePrefix("geosite-")
                }
                val domains = DataStore.routeDomain.split('\n', ',').map { it.trim() }
                    .filter { it.isNotEmpty() }.toMutableList()
                val ips = DataStore.routeIP.split('\n', ',').map { it.trim() }
                    .filter { it.isNotEmpty() }.toMutableList()
                val selected = selectors.map { it in domains || it in ips }.toBooleanArray()
                val container = LinearLayout(this@RouteSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, 0)
                }
                val search = EditText(this@RouteSettingsActivity).apply {
                    hint = "Search SRS"
                    setSingleLine(true)
                }
                container.addView(search)
                val list = ListView(this@RouteSettingsActivity).apply {
                    choiceMode = ListView.CHOICE_MODE_MULTIPLE
                }
                container.addView(list, LinearLayout.LayoutParams(-1, (300 * resources.displayMetrics.density).toInt()))
                var visible = selectors.indices.toList()
                fun refresh(query: String) {
                    visible = selectors.indices.filter { selectors[it].contains(query, ignoreCase = true) }
                    list.adapter = ArrayAdapter(this@RouteSettingsActivity,
                        android.R.layout.simple_list_item_multiple_choice, visible.map { selectors[it] })
                    visible.forEachIndexed { position, index -> list.setItemChecked(position, selected[index]) }
                }
                refresh("")
                list.setOnItemClickListener { _, _, position, _ ->
                    selected[visible[position]] = list.isItemChecked(position)
                }
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        refresh(s?.toString().orEmpty())
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
                MaterialAlertDialogBuilder(this@RouteSettingsActivity)
                    .setTitle("Installed SRS rule sets")
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        selectors.forEachIndexed { index, selector ->
                            val target = if (selector.startsWith("geoip:")) ips else domains
                            target.removeAll { it == selector }
                            if (selected[index]) target.add(selector)
                        }
                        DataStore.routeDomain = domains.joinToString("\n")
                        DataStore.routeIP = ips.joinToString("\n")
                        DataStore.dirty = true
                    }
                    .setNeutralButton("Online") { _, _ -> showOnlineSrsPicker() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }

    }
    fun displayPreferenceDialog(preference: Preference): Boolean {
        return false
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as RouteSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val ruleId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_route_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteRule(arg.ruleId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_ROUTE_ID = "id"
        const val EXTRA_PACKAGE_NAME = "pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installBackGuard({ needSave() }) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        }
        if (!nativeEditor) setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cag_route)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    init(intent.getStringExtra(EXTRA_PACKAGE_NAME))
                } else {
                    val ruleEntity = SagerDatabase.rulesDao.getById(editingId)
                    if (ruleEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    ruleEntity.init()
                }

                onMainDispatcher {
                    if (nativeEditor) showNativeEditor() else supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()

                    DataStore.dirty = false
                    DataStore.profileCacheStore.registerChangeListener(this@RouteSettingsActivity)
                }
            }
        } else if (nativeEditor) {
            showNativeEditor()
            DataStore.profileCacheStore.registerChangeListener(this)
        }
    }

    private fun missingRouteSrs(): List<String> {
        val assets = SagerNet.application.externalAssets
        val missing = linkedSetOf<String>()
        listOf(DataStore.routeDomain, DataStore.routeIP).forEach { input ->
            input.split('\n', ',').map { it.trim() }.forEach { entry ->
                val filename = when {
                    entry.startsWith("geosite:", ignoreCase = true) ->
                        "geosite-${entry.substringAfter(':').trim()}.srs"
                    entry.startsWith("geoip:", ignoreCase = true) ->
                        "geoip-${entry.substringAfter(':').trim()}.srs"
                    else -> null
                }
                if (filename != null && filename.substringAfter('-').removeSuffix(".srs").isNotBlank()) {
                    val file = java.io.File(assets, filename)
                    if (!file.isFile || file.length() == 0L) missing.add(filename)
                }
            }
        }
        return missing.toList()
    }

    suspend fun saveAndExit(skipSrsWarning: Boolean = false) {

        if (!needSave()) {
            onMainDispatcher {
                MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.empty_route)
                    .setMessage(R.string.empty_route_notice)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }

        if (!skipSrsWarning) {
            val missing = missingRouteSrs()
            if (missing.isNotEmpty()) {
                onMainDispatcher {
                    MaterialAlertDialogBuilder(this@RouteSettingsActivity)
                        .setTitle("Missing SRS rule sets")
                        .setMessage("These rule sets are not installed:\n\n" +
                            missing.joinToString("\n") +
                            "\n\nInstall them in Assets or save the rule anyway.")
                        .setPositiveButton("Open Assets") { _, _ ->
                            startActivity(Intent(this@RouteSettingsActivity, AssetsActivity::class.java))
                        }
                        .setNeutralButton("Save anyway") { _, _ ->
                            runOnDefaultDispatcher { saveAndExit(skipSrsWarning = true) }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                return
            }
        }

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            if (intent.hasExtra(EXTRA_PACKAGE_NAME)) {
                setResult(RESULT_OK, Intent())
            }

            ProfileManager.createRule(RuleEntity().apply { serialize() })
        } else {
            val entity = SagerDatabase.rulesDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            ProfileManager.updateRule(entity.apply { serialize() })
        }
        finish()

    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (nativeEditor) return false
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)


    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: RouteSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as RouteSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)

            activity?.apply {
                viewCreated(view, savedInstanceState)
            }
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(ProfileIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity?.apply {
                if (displayPreferenceDialog(preference)) return
            }
            super.onDisplayPreferenceDialog(preference)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

}