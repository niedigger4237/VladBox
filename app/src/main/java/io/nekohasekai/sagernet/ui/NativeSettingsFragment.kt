package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.root.RootShell
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*

class NativeSettingsFragment : androidx.fragment.app.Fragment() {

    private lateinit var isProxyApps: Preference

    private lateinit var globalCustomConfig: EditConfigPreference


    private val importCore = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runOnDefaultDispatcher {
            val ctx = requireContext()
            val tmp = java.io.File(ctx.cacheDir, "import-core").apply { delete() }
            ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val res = io.nekohasekai.sagernet.bg.root.RootCore.install(tmp, "imported-" + System.currentTimeMillis() / 1000)
            tmp.delete()
            rootCoreResult(res)
        }
    }

    private fun rootCoreResult(res: Result<String>) = runOnMainDispatcher {
        val ctx = context ?: return@runOnMainDispatcher
        android.widget.Toast.makeText(
            ctx, res.fold({ "sing-box: $it" }, { it.message ?: it.toString() }),
            android.widget.Toast.LENGTH_LONG
        ).show()
        refreshRootCoreSummary()
    }

    private fun refreshRootCoreSummary() = runOnDefaultDispatcher {
        val label = if (model.state.value.rootAccess == RootShell.Access.GRANTED)
            io.nekohasekai.sagernet.bg.root.RootCore.selectedLabel() else "-"
        runOnMainDispatcher {
            if (!isAdded) return@runOnMainDispatcher
            findPreference<Preference>("rootCoreUpdate")?.summary =
                getString(R.string.root_core_summary, label)
            model.refresh()
        }
    }

    private fun setupRootCore() {
        findPreference<Preference>("rootCoreUpdate")!!.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("ROOT core update channel")
                .setItems(arrayOf("Patched (recommended)", "Upstream (without app patches)")) { _, choice ->
                    val channel = if (choice == 0) io.nekohasekai.sagernet.bg.root.RootCore.Channel.PATCHED
                        else io.nekohasekai.sagernet.bg.root.RootCore.Channel.UPSTREAM
                    fun update() = runOnDefaultDispatcher {
                        val rel = io.nekohasekai.sagernet.bg.root.RootCore.fetchLatest(channel)
                            .getOrElse { e -> rootCoreResult(Result.failure(e)); return@runOnDefaultDispatcher }
                        rootCoreResult(io.nekohasekai.sagernet.bg.root.RootCore.downloadAndInstall(rel))
                    }
                    if (choice == 0) update()
                    else MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Use upstream core?")
                        .setMessage("Upstream does not guarantee Firefox 148 uTLS, Flow Metadata or other NieFigger patches. Reconnect to apply.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ -> update() }
                        .show()
                }.show()
            true
        }
        findPreference<Preference>("rootCoreImport")!!.setOnPreferenceClickListener {
            importCore.launch("*/*")
            true
        }
        findPreference<Preference>("rootCoreBundled")?.setOnPreferenceClickListener {
            runOnDefaultDispatcher {
                val ok = io.nekohasekai.sagernet.bg.root.RootCore.useBundled()
                rootCoreResult(if (ok) Result.success("Bundled (patched) — reconnect to apply") else Result.failure(IllegalStateException("Cannot reset core selection")))
            }
            true
        }
        findPreference<Preference>("rootCoreRollback")!!.setOnPreferenceClickListener {
            runOnDefaultDispatcher {
                val ok = io.nekohasekai.sagernet.bg.root.RootCore.rollback()
                rootCoreResult(if (ok) Result.success("rolled back") else Result.failure(IllegalStateException("No previous core")))
            }
            true
        }
    }

    private val model by lazy { androidx.lifecycle.ViewModelProvider(this)[io.nekohasekai.sagernet.ui.compose.SettingsViewModel::class.java] }
    private lateinit var screen: PreferenceScreen
    private fun <T : Preference> findPreference(key: CharSequence): T? = screen.findPreference<T>(key)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = PreferenceManager(requireContext())
        manager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        screen = io.nekohasekai.sagernet.ui.compose.buildSettingsTree(manager)
        configureSettings()
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): View {
        return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            io.nekohasekai.sagernet.ui.compose.installSettingsScreen(this, screen, model) { model.refresh() }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (model.state.value.category != null || model.state.value.query.isNotEmpty()) {
                        model.select(null)
                    } else {
                        isEnabled = false
                        try { requireActivity().onBackPressedDispatcher.onBackPressed() }
                        finally { isEnabled = true }
                    }
                }
            })
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    private fun configureSettings() {
        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        val allowAccess = findPreference<Preference>(Key.ALLOW_ACCESS)!!
        val appendHttpProxy = findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)!!

        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<MTUPreference>(Key.MTU)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true

            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = 50
                setText(size.toString())
            }

            MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = view.text.toString().toInt()
                    if (DataStore.logBufSize <= 0) DataStore.logBufSize = 50
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)

        val metedNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            metedNetwork.isVisible = false
        }
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        // opening the list must not flip the mode: it is switched inside the list (toolbar → Settings)
        isProxyApps.setOnPreferenceClickListener {
            startActivity(Intent(activity, AppManagerActivity::class.java))
            true
        }
        updateProxyAppsSummary()

        val profileTrafficStatistics =
            findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }

        findPreference<EditTextPreference>(Key.ROOT_TPROXY_PORT)!!
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        setupRootCore()

        serviceMode.setOnPreferenceChangeListener { _, value ->
            if (value.toString().startsWith("root_") && model.state.value.rootAccess != RootShell.Access.GRANTED) {
                checkRootAccess()
                false
            } else {
                if (DataStore.serviceState.started) SagerNet.stopService()
                true
            }
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val resolveDestination = findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)!!
        val acquireWakeLock = findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!
        val enableClashAPI = findPreference<SwitchPreference>(Key.ENABLE_CLASH_API)!!
        enableClashAPI.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }

        mixedPort.onPreferenceChangeListener = reloadListener
        appendHttpProxy.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.onPreferenceChangeListener = reloadListener

        for (key in listOf(Key.DNS_ENGINE_MODE, Key.DNS_GLOBAL_STRATEGY, Key.DNS_OPTIMISTIC_WINDOW, Key.DNS_QUERY_TIMEOUT, Key.DNS_CACHE_CAPACITY, Key.DNS_CACHE_STORAGE, Key.DNS_MAIN,
            "domain_strategy_for_direct", "domain_strategy_for_remote", "domain_strategy_for_server")) {
            findPreference<Preference>(key)?.onPreferenceChangeListener = reloadListener
        }
        findPreference<Preference>(Key.DNS_CACHE_CLEAR)?.setOnPreferenceClickListener {
            DataStore.dnsCacheGeneration = java.util.UUID.randomUUID().toString()
            val directory = io.nekohasekai.sagernet.fmt.PersistentDnsPolicy.directory()
            val cleared = runCatching { libcore.Libcore.clearManagedDnsCache(directory.absolutePath) }.isSuccess
            android.widget.Toast.makeText(requireContext(),
                if (cleared) R.string.dns_cache_cleared else R.string.dns_cache_clear_pending,
                android.widget.Toast.LENGTH_LONG).show()
            if (!cleared) needReload()
            true
        }
        findPreference<Preference>(Key.ROOT_DNS_CACHE_STORAGE)?.setOnPreferenceChangeListener { _, _ ->
            // Record the transition even when selecting Memory, so the next
            // interactive startup clears any previously managed ROOT database.
            DataStore.dnsRootCacheGeneration = java.util.UUID.randomUUID().toString()
            needReload()
            true
        }
        findPreference<Preference>(Key.ROOT_DNS_CACHE_CLEAR)?.setOnPreferenceClickListener {
            DataStore.dnsRootCacheGeneration = java.util.UUID.randomUUID().toString()
            android.widget.Toast.makeText(requireContext(), R.string.dns_root_cache_clear_pending,
                android.widget.Toast.LENGTH_LONG).show()
            needReload()
            true
        }
        val bootstrapAddress = findPreference<EditTextPreference>(Key.DNS_BOOTSTRAP_ADDRESS)!!
        val bootstrapTlsName = findPreference<EditTextPreference>(Key.DNS_BOOTSTRAP_TLS_NAME)!!
        fun updateBootstrapVisibility(preset: String) {
            bootstrapAddress.isVisible = preset == "custom"
            bootstrapTlsName.isVisible = preset == "custom"
        }
        updateBootstrapVisibility(DataStore.dnsBootstrapPreset)
        findPreference<Preference>(Key.DNS_BOOTSTRAP_PRESET)!!.setOnPreferenceChangeListener { _, v ->
            updateBootstrapVisibility(v.toString())
            needReload()
            true
        }
        bootstrapAddress.setOnPreferenceChangeListener { _, v ->
            val valid = runCatching {
                io.nekohasekai.sagernet.fmt.BootstrapDnsPolicy.parse(v.toString(), DataStore.dnsBootstrapTlsName)
            }.isSuccess
            if (valid) needReload()
            else android.widget.Toast.makeText(requireContext(), R.string.dns_bootstrap_invalid, android.widget.Toast.LENGTH_LONG).show()
            valid
        }
        bootstrapTlsName.setOnPreferenceChangeListener { _, v ->
            val valid = io.nekohasekai.sagernet.fmt.BootstrapDnsPolicy.validTlsName(v.toString())
            if (valid) needReload()
            else android.widget.Toast.makeText(requireContext(), R.string.dns_bootstrap_invalid_tls, android.widget.Toast.LENGTH_LONG).show()
            valid
        }
        enableFakeDns.onPreferenceChangeListener = reloadListener
        remoteDns.onPreferenceChangeListener = reloadListener
        directDns.onPreferenceChangeListener = reloadListener

        // готовые DNS: поле своего адреса видно только при выборе «Свой…»
        val remotePreset = findPreference<Preference>("remoteDnsPreset")!!
        val directPreset = findPreference<Preference>("directDnsPreset")!!
        remoteDns.isVisible = DataStore.remoteDnsPreset == "custom"
        directDns.isVisible = DataStore.directDnsPreset == "custom"
        remotePreset.setOnPreferenceChangeListener { _, v -> remoteDns.isVisible = v == "custom"; needReload(); true }
        directPreset.setOnPreferenceChangeListener { _, v -> directDns.isVisible = v == "custom"; needReload(); true }
        findPreference<Preference>("hijackDns")!!.onPreferenceChangeListener = reloadListener
        for (k in listOf("socksPort", "bpfBridgePort")) findPreference<EditTextPreference>(k)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
            onPreferenceChangeListener = reloadListener
        }
        for (k in listOf("tetherDnsHijack", "tetherHevDnsHijack", "tetherWifi", "tetherBluetooth", "tetherUsb", "tetherEthernet", "tetherCustom",
            "rootTunInterfaces", "rootTunBypassRuleSets", "rootIgnoredInterfaces", "rootDisableSystemIpv6",
            "rootHevMultiQueue", "rootHevTcpFastOpen", "rootFlowMetadata", "rootEbpfMatcher")) {
            findPreference<Preference>(k)!!.onPreferenceChangeListener = reloadListener
        }
        findPreference<Preference>("serviceControl")!!.setOnPreferenceClickListener {
            startActivity(android.content.Intent(requireContext(), ServiceControlActivity::class.java)); true
        }
        findPreference<Preference>("startOnBoot")!!.setOnPreferenceChangeListener { _, v ->
            io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher {
                val err = io.nekohasekai.sagernet.bg.root.RootBoot.apply(v as Boolean)
                if (err != null) io.nekohasekai.sagernet.ktx.onMainDispatcher {
                    android.widget.Toast.makeText(requireContext(), err, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            true
        }
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        for (key in listOf("tunAddressMode", "tunIpv4Subnet", "tunIpv6Subnet")) {
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                needRestart()
                true
            }
        }
        ipv6Mode.onPreferenceChangeListener = reloadListener
        allowAccess.onPreferenceChangeListener = reloadListener

        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.onPreferenceChangeListener = reloadListener
        acquireWakeLock.onPreferenceChangeListener = reloadListener
        globalCustomConfig.onPreferenceChangeListener = reloadListener
    }

    private fun updateProxyAppsSummary() {
        isProxyApps.summary = getString(if (DataStore.proxyApps) R.string.on else R.string.off) +
                " · " + getString(if (DataStore.bypass) R.string.bypass_apps else R.string.route_proxy)
    }

    private fun checkRootAccess() {
        if (!model.beginRootCheck()) return
        runOnDefaultDispatcher {
            val access = try {
                RootShell.probeAccess()
            } catch (e: Exception) {
                Logs.w(e)
                RootShell.Access.UNKNOWN
            }
            runOnMainDispatcher {
                model.finishRootCheck(access)
                if (!isAdded) return@runOnMainDispatcher
                val inactive = DataStore.serviceState == io.nekohasekai.sagernet.bg.BaseService.State.Idle ||
                    DataStore.serviceState == io.nekohasekai.sagernet.bg.BaseService.State.Stopped
                if (access == RootShell.Access.UNAVAILABLE && inactive && DataStore.serviceMode.startsWith("root_")) {
                    // Recover an inactive configuration, but never stop a live session from a UI probe.
                    findPreference<ListPreference>(Key.SERVICE_MODE)?.value = Key.MODE_VPN
                    model.refresh()
                }
                if (access == RootShell.Access.GRANTED) refreshRootCoreSummary()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkRootAccess()
        model.refresh()

        if (::isProxyApps.isInitialized) {
            updateProxyAppsSummary()
        }
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
        model.refresh()
    }

}