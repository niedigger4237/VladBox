package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.util.SparseBooleanArray
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.annotation.UiThread
import androidx.core.util.contains
import androidx.core.util.set
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAppsBinding
import io.nekohasekai.sagernet.databinding.LayoutAppsItemBinding
import io.nekohasekai.sagernet.bg.root.RootShell
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.NGUtil
import kotlin.coroutines.coroutineContext

class AppManagerActivity : ThemedActivity() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: AppManagerActivity? = null
        private const val SWITCH = "switch"

        private val cachedApps
            get() = PackageCache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
            }
    }

    private class ProxiedApp(
        val name: CharSequence,
        private val iconProvider: () -> Drawable,
        val uid: Int,
        val sys: Boolean,
        val packageName: String,
        /** user of the space the app lives in (0 = main space) */
        val user: Int = 0,
    ) {
        val icon: Drawable get() = iconProvider()

        /** how the app is stored in DataStore.individual: "pkg" or "u<user>:pkg" for the private space */
        val key: String get() = if (user == 0) packageName else "u$user:$packageName"

        constructor(pm: PackageManager, appInfo: ApplicationInfo, packageName: String) : this(
            appInfo.loadLabel(pm), { appInfo.loadIcon(pm) }, appInfo.uid,
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0, packageName
        )
    }

    private inner class AppViewHolder(val binding: LayoutAppsItemBinding) : RecyclerView.ViewHolder(
        binding.root
    ),
        View.OnClickListener {
        private lateinit var item: ProxiedApp

        init {
            binding.root.setOnClickListener(this)
        }

        fun bind(app: ProxiedApp) {
            item = app
            binding.itemicon.setImageDrawable(app.icon)
            binding.title.text = app.name
            binding.desc.text = "${app.packageName} (${app.uid})" + if (app.user != 0) " · user ${app.user}" else ""
            binding.itemcheck.isChecked = isProxiedApp(app)
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains(SWITCH)) binding.itemcheck.isChecked = isProxiedApp(item)
        }

        override fun onClick(v: View?) {
            if (isProxiedApp(item)) proxiedUids.delete(item.uid) else proxiedUids[item.uid] = true
            persist()

            appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {
        var filteredApps = apps

        suspend fun reload() {
            PackageCache.reload()
            if (space == 0) {
                normalApps = cachedApps.mapNotNull { (packageName, packageInfo) ->
                    coroutineContext[Job]!!.ensureActive()
                    packageInfo.applicationInfo?.let { ProxiedApp(packageManager, it, packageName) }
                }.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
            } else {
                val list = loadPrivateApps()
                val saved = DataStore.individual.lines().toHashSet()
                for (a in list) if (a.key in saved) proxiedUids[a.uid] = true
                privateApps = list.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                privateLoaded = true
            }
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
            holder.bind(filteredApps[position])

        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST") holder.handlePayload(payloads as List<String>)
                return
            }

            onBindViewHolder(holder, position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
            AppViewHolder(LayoutAppsItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = filteredApps.size

        private val filterImpl = object : Filter() {
            override fun performFiltering(constraint: CharSequence) = FilterResults().apply {
                var filteredApps = if (constraint.isEmpty()) apps else apps.filter {
                    it.name.contains(constraint, true) || it.packageName.contains(
                        constraint, true
                    ) || it.uid.toString().contains(constraint)
                }
                if (!sysApps) filteredApps = filteredApps.filter { !it.sys }
                count = filteredApps.size
                values = filteredApps
            }

            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                filteredApps = results.values as List<ProxiedApp>
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filterImpl

        override fun getSectionName(position: Int): String {
            return filteredApps[position].name.firstOrNull()?.toString() ?: ""
        }

    }

    private val loading by lazy { findViewById<View>(R.id.loading) }

    private lateinit var binding: LayoutAppsBinding
    private val proxiedUids = SparseBooleanArray()
    private var loader: Job? = null
    private var normalApps = emptyList<ProxiedApp>()
    private var privateApps = emptyList<ProxiedApp>()
    private var privateLoaded = false
    private var space = 0
    private var apps: List<ProxiedApp>
        get() = if (space == 0) normalApps else privateApps
        set(v) { if (space == 0) normalApps = v else privateApps = v }

    private val privKey = Regex("u\\d+:.+")

    /** writes the selection of both spaces; the space that is not loaded keeps its saved entries */
    private fun persist() {
        val keys = mutableListOf<String>()
        keys += normalApps.filter { isProxiedApp(it) }.map { it.key }
        keys += if (privateLoaded) privateApps.filter { isProxiedApp(it) }.map { it.key }
        else DataStore.individual.lines().filter { privKey.matches(it) }
        DataStore.individual = keys.joinToString("\n")
    }

    /** apps of the private space / other users (root): `pm list packages -U --user N` */
    private fun loadPrivateApps(): List<ProxiedApp> {
        val users = RootShell.run("pm list users", timeoutMs = 10_000).out
        val ids = Regex("UserInfo\\{(\\d+):").findAll(users).map { it.groupValues[1].toInt() }.filter { it != 0 }.toList()
        val result = ArrayList<ProxiedApp>()
        for (id in ids) {
            val third = RootShell.run("pm list packages -3 --user $id", timeoutMs = 15_000).out.lineSequence()
                .map { it.removePrefix("package:").trim() }.toHashSet()
            val all = RootShell.run("pm list packages -U --user $id", timeoutMs = 15_000).out
            for (m in Regex("package:(\\S+)\\s+uid:(\\d+)").findAll(all)) {
                val pkg = m.groupValues[1]
                if (pkg == BuildConfig.APPLICATION_ID) continue
                val uid = m.groupValues[2].toInt()
                val info = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                result += ProxiedApp(
                    info?.loadLabel(packageManager) ?: pkg,
                    { info?.loadIcon(packageManager) ?: packageManager.defaultActivityIcon },
                    uid, pkg !in third, pkg, id
                )
            }
        }
        return result
    }
    private val appsAdapter = AppsAdapter()

    private fun initProxiedUids(str: String = DataStore.individual) {
        proxiedUids.clear()
        val apps = cachedApps
        for (line in str.lineSequence()) {
            val app = (apps[line] ?: continue)
            val uid = app.applicationInfo?.uid ?: continue
            proxiedUids[uid] = true
        }
        val lines = str.lines().toHashSet()
        for (a in privateApps) if (a.key in lines) proxiedUids[a.uid] = true
    }

    private fun isProxiedApp(app: ProxiedApp) = proxiedUids[app.uid]

    @UiThread
    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launchWhenCreated {
            binding.appPlaceholder.root.visibility = View.GONE
            loading.crossFadeFrom(binding.list)
            val adapter = binding.list.adapter as AppsAdapter
            withContext(Dispatchers.IO) { adapter.reload() }
            adapter.filter.filter(binding.search.text?.toString() ?: "")
            if (apps.isEmpty()) {
                binding.list.visibility = View.GONE
                binding.appPlaceholder.root.crossFadeFrom(loading)
            } else {
                binding.list.crossFadeFrom(loading)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appPlaceholder.openSettings.setOnClickListener {
            val intent =
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
            startActivity(intent)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.proxied_apps)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        // the old chips are replaced by the "Settings" menu in the toolbar; opening the list does not touch the VPN-per-app switch
        binding.bypassGroup.visibility = View.GONE
        binding.showSystemApps.visibility = View.GONE
        setupSpaceTabs()
        binding.autoSelectProxyApps.setOnClickListener { selectProxyApp() }

        initProxiedUids()
        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = appsAdapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        binding.search.addTextChangedListener {
            appsAdapter.filter.filter(it?.toString() ?: "")
        }

        instance = this
        loadApps()
    }

    private var sysApps = true

    private fun rootModeSelected() = DataStore.serviceMode in listOf(
        io.nekohasekai.sagernet.Key.MODE_ROOT_TUN, io.nekohasekai.sagernet.Key.MODE_ROOT_TPROXY,
        io.nekohasekai.sagernet.Key.MODE_ROOT_TUN2SOCKS, io.nekohasekai.sagernet.Key.MODE_ROOT_BPF2SOCKS
    )

    /** "Normal" / "Private" spaces — only when root is in use (the private space is read through root) */
    private fun setupSpaceTabs() {
        val tabs = binding.spaceTabs
        tabs.visibility = View.GONE
        // the private space is shown whenever root is available (not only when a root mode is selected)
        runOnDefaultDispatcher {
            val root = rootModeSelected() || RootShell.isAvailable()
            if (root) onMainDispatcher { showSpaceTabs() }
        }
    }

    private fun showSpaceTabs() {
        val tabs = binding.spaceTabs
        if (tabs.tabCount > 0) return
        tabs.visibility = View.VISIBLE
        tabs.addTab(tabs.newTab().setText(R.string.apps_space_normal))
        tabs.addTab(tabs.newTab().setText(R.string.apps_space_private))
        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                space = tab.position
                if (space == 1 && !privateLoaded) loadApps() else showCurrentSpace()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    /** shows the already loaded list of the selected space (list or "empty" placeholder) */
    private fun showCurrentSpace() {
        loader?.cancel()
        for (v in listOf(loading, binding.list, binding.appPlaceholder.root)) { v.clearAnimation(); v.animate().cancel(); v.alpha = 1F }
        loading.visibility = View.GONE
        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
        if (apps.isEmpty()) {
            binding.list.visibility = View.GONE
            binding.appPlaceholder.root.visibility = View.VISIBLE
        } else {
            binding.appPlaceholder.root.visibility = View.GONE
            binding.list.visibility = View.VISIBLE
        }
    }

    private fun showAppsSettings() {
        val anchor = binding.toolbar.findViewById<View>(R.id.action_apps_settings) ?: binding.toolbar
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.apps_settings_menu, popup.menu)
        popup.menu.findItem(if (DataStore.bypass) R.id.apps_mode_bypass else R.id.apps_mode_proxy).isChecked = true
        popup.menu.findItem(R.id.apps_show_system).isChecked = sysApps
        popup.menu.findItem(R.id.apps_enable).isChecked = DataStore.proxyApps
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.apps_enable -> {
                    DataStore.proxyApps = !DataStore.proxyApps
                    DataStore.dirty = true
                }
                R.id.apps_mode_proxy -> DataStore.bypass = false
                R.id.apps_mode_bypass -> DataStore.bypass = true
                R.id.apps_show_system -> {
                    sysApps = !sysApps
                    appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                }
            }
            true
        }
        popup.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.per_app_proxy_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apps_settings -> {
                showAppsSettings()
                return true
            }

            R.id.action_invert_selections -> {
                runOnDefaultDispatcher {
                    val proxiedUidsOld = proxiedUids.clone()
                    for (app in apps) {
                        if (proxiedUidsOld.contains(app.uid)) {
                            proxiedUids.delete(app.uid)
                        } else {
                            proxiedUids[app.uid] = true
                        }
                    }
                    persist()
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                    onMainDispatcher {
                        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                    }
                }

                return true
            }

            R.id.action_clear_selections -> {
                runOnDefaultDispatcher {
                    proxiedUids.clear()
                    DataStore.individual = ""
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                    onMainDispatcher {
                        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                    }
                }
            }

            R.id.action_export_clipboard -> {
                val success =
                    SagerNet.trySetPrimaryClip("${DataStore.bypass}\n${DataStore.individual}")
                Snackbar.make(
                    binding.list,
                    if (success) R.string.action_export_msg else R.string.action_export_err,
                    Snackbar.LENGTH_LONG
                ).show()
                return true
            }

            R.id.action_import_clipboard -> {
                val proxiedAppString =
                    SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!proxiedAppString.isNullOrEmpty()) {
                    val i = proxiedAppString.indexOf('\n')
                    try {
                        val (enabled, apps) = if (i < 0) {
                            proxiedAppString to ""
                        } else proxiedAppString.substring(
                            0, i
                        ) to proxiedAppString.substring(i + 1)
                        DataStore.bypass = enabled.toBoolean()
                        DataStore.individual = apps
                        Snackbar.make(
                            binding.list, R.string.action_import_msg, Snackbar.LENGTH_LONG
                        ).show()
                        initProxiedUids(apps)
                        appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
                        return true
                    } catch (_: IllegalArgumentException) {
                    }
                }
                Snackbar.make(binding.list, R.string.action_import_err, Snackbar.LENGTH_LONG).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun selectProxyApp() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.confirm)
            .setMessage(R.string.auto_select_proxy_apps_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    val needProxyAppsList = getAutoProxyApps("")
                    val bypass = DataStore.bypass
                    proxiedUids.clear()
                    for (app in cachedApps) {
                        val needProxy =
                            needProxyAppsList.contains(app.key) || (app.value.applicationInfo?.uid
                                ?: 0) == 1000
                        if (needProxy) {
                            if (!bypass) {
                                app.value.applicationInfo?.apply {
                                    proxiedUids[uid] = true
                                }
                            }
                        } else {
                            if (bypass) {
                                app.value.applicationInfo?.apply {
                                    proxiedUids[uid] = true
                                }
                            }
                        }
                    }
                    persist()
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                    appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                } catch (e: Exception) {
                    Logs.e(e)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun getAutoProxyApps(content: String): List<String> {
        var list = listOf<String>()
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                NGUtil.readTextFromAssets(app, "proxy_packagename.txt")
            } else {
                content
            }
            if (!TextUtils.isEmpty(proxyApps)) {
                list = proxyApps.split("\n")
            }
        } catch (_: Exception) {
        }
        return list
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU) {
        if (binding.toolbar.isOverflowMenuShowing) binding.toolbar.hideOverflowMenu() else binding.toolbar.showOverflowMenu()
    } else super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        instance = null
        loader?.cancel()
        super.onDestroy()
    }
}
