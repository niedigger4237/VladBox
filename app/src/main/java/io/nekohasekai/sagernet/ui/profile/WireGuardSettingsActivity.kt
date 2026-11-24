package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    override fun createEntity() = WireGuardBean()

    private var legacyRequested = false

    override fun PreferenceFragmentCompat.viewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        val legacyList = listView
        val parent = legacyList.parent as? android.view.ViewGroup ?: return
        val index = parent.indexOfChild(legacyList)
        val params = legacyList.layoutParams
        val menu = androidx.appcompat.widget.PopupMenu(requireContext(), legacyList).menu
        this@WireGuardSettingsActivity.onCreateOptionsMenu(menu)
        val actions = (0 until menu.size()).map { menu.getItem(it) }
            .filter { it.isVisible && it.itemId != R.id.action_apply }
            .map { item -> item.title.toString() to { this@WireGuardSettingsActivity.onOptionsItemSelected(item); Unit } }
        supportActionBar?.hide()
        parent.removeView(legacyList)
        val host = androidx.compose.ui.platform.ComposeView(requireContext())
        host.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        parent.addView(host, index, params)
        host.setContent {
            io.nekohasekai.sagernet.ui.compose.HubTheme {
                io.nekohasekai.sagernet.ui.compose.WireGuardProfileScreen(
                    onImport = {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@WireGuardSettingsActivity)
                            .setMessage("Replace current connection settings?")
                            .setPositiveButton("Import") { _, _ -> importConf.launch(arrayOf("*/*")) }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    },
                    onExport = {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@WireGuardSettingsActivity)
                            .setMessage("The exported file contains private keys. Keep it secure.")
                            .setPositiveButton("Export") { _, _ -> exportConf.launch(if (DataStore.profileCacheStore.getBoolean("awgEnabled",false)) "amneziawg.conf" else "wireguard.conf") }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    },
                    onClose = { this@WireGuardSettingsActivity.onBackPressed() },
                    menuItems = actions,
                    onSave = {
                        io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher { saveAndExit() }
                    }
                )
            }
        }
    }

    private val importConf = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot read file")
                val result = io.nekohasekai.sagernet.fmt.wireguard.WireGuardConf.parseWithWarnings(text)
                val bean = result.bean
                val current = createEntity().apply { initializeDefaultValues(); pbm.fromCacheAll(this) }
                bean.name = current.name
                val protocol = if (bean.isAmnezia) "AmneziaWG" else "WireGuard"
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Detected protocol: " + protocol)
                    .setMessage((listOf("Replace current connection settings?") + result.warnings).joinToString("\n\n"))
                    .setPositiveButton("Import") { _, _ ->
                        pbm.writeToCacheAll(bean)
                        DataStore.profileCacheStore.putBoolean("awgEnabled", bean.isAmnezia)
                        legacyRequested = false
                        supportFragmentManager.beginTransaction().replace(R.id.settings, ProfileSettingsActivity.MyPreferenceFragmentCompat()).commitNow()
                        DataStore.dirty = true
                    }
                    .setNegativeButton(android.R.string.cancel,null).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "WireGuard import failed: " + e.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    private val exportConf = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                val bean = createEntity().apply { initializeDefaultValues(); pbm.fromCacheAll(this) }
                if (!DataStore.profileCacheStore.getBoolean("awgEnabled", false)) bean.clearAmnezia()
                val text = io.nekohasekai.sagernet.fmt.wireguard.WireGuardConf.export(bean)
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("Cannot write file")
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "WireGuard export failed: " + e.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val persistentKeepalive = pbm.add(PreferenceBinding(Type.TextToInt, "persistentKeepalive"))
    private val system = pbm.add(PreferenceBinding(Type.Bool, "system"))
    private val workers = pbm.add(PreferenceBinding(Type.TextToInt, "workers"))
    private val udpTimeout = pbm.add(PreferenceBinding(Type.Text, "udpTimeout"))
    private val wgInterfaceName = pbm.add(PreferenceBinding(Type.Text, "wgInterfaceName"))
    private val wgListenPort = pbm.add(PreferenceBinding(Type.TextToInt, "wgListenPort"))
    private val wgUdpMapping = pbm.add(PreferenceBinding(Type.Text, "wgUdpMapping"))
    private val wgUdpFiltering = pbm.add(PreferenceBinding(Type.Text, "wgUdpFiltering"))
    private val wgUdpNatMax = pbm.add(PreferenceBinding(Type.TextToInt, "wgUdpNatMax"))
    private val wgBuffers = pbm.add(PreferenceBinding(Type.TextToInt, "wgBuffers"))
    private val wgDisablePauses = pbm.add(PreferenceBinding(Type.Bool, "wgDisablePauses"))
    private val wgAllowedIps = pbm.add(PreferenceBinding(Type.Text, "wgAllowedIps"))
    private val wgDialer = pbm.add(PreferenceBinding(Type.Text, "wgDialer"))
    private val wgExtraPeers = pbm.add(PreferenceBinding(Type.Text, "wgExtraPeers"))
    private val awgJc = pbm.add(PreferenceBinding(Type.TextToInt, "awgJc"))
    private val awgJmin = pbm.add(PreferenceBinding(Type.TextToInt, "awgJmin"))
    private val awgJmax = pbm.add(PreferenceBinding(Type.TextToInt, "awgJmax"))
    private val awgS1 = pbm.add(PreferenceBinding(Type.TextToInt, "awgS1"))
    private val awgS2 = pbm.add(PreferenceBinding(Type.TextToInt, "awgS2"))
    private val awgS3 = pbm.add(PreferenceBinding(Type.TextToInt, "awgS3"))
    private val awgS4 = pbm.add(PreferenceBinding(Type.TextToInt, "awgS4"))
    private val awgH1 = pbm.add(PreferenceBinding(Type.Text, "awgH1"))
    private val awgH2 = pbm.add(PreferenceBinding(Type.Text, "awgH2"))
    private val awgH3 = pbm.add(PreferenceBinding(Type.Text, "awgH3"))
    private val awgH4 = pbm.add(PreferenceBinding(Type.Text, "awgH4"))
    private val awgI1 = pbm.add(PreferenceBinding(Type.Text, "awgI1"))
    private val awgI2 = pbm.add(PreferenceBinding(Type.Text, "awgI2"))
    private val awgI3 = pbm.add(PreferenceBinding(Type.Text, "awgI3"))
    private val awgI4 = pbm.add(PreferenceBinding(Type.Text, "awgI4"))
    private val awgI5 = pbm.add(PreferenceBinding(Type.Text, "awgI5"))
    private val awgHpk = pbm.add(PreferenceBinding(Type.Text, "awgHpk"))
    private val awgCpa = pbm.add(PreferenceBinding(Type.Text, "awgCpa"))
    private val awgRekeyAfter = pbm.add(PreferenceBinding(Type.Text, "awgRekeyAfter"))
    private val awgRekeyTimeout = pbm.add(PreferenceBinding(Type.Text, "awgRekeyTimeout"))
    private val awgRejectAfter = pbm.add(PreferenceBinding(Type.Text, "awgRejectAfter"))
    private val awgKeepaliveTimeout = pbm.add(PreferenceBinding(Type.Text, "awgKeepaliveTimeout"))
    private val awgMaxHandshake = pbm.add(PreferenceBinding(Type.Text, "awgMaxHandshake"))
    private val awgRandomTrailers = pbm.add(PreferenceBinding(Type.Bool, "awgRandomTrailers"))
    private val awgDisableCookies = pbm.add(PreferenceBinding(Type.Bool, "awgDisableCookies"))

    private fun showPeers(pref: EditTextPreference) {
        val peers = try { org.json.JSONArray(pref.text.orEmpty().ifBlank { "[]" }) }
        catch (e: Exception) {
            android.widget.Toast.makeText(this, "Invalid peers JSON. Correct it before editing.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val labels = (0 until peers.length()).map { i ->
            val p = peers.optJSONObject(i)
            "Peer ${i + 2}: " + (p?.optString("address").orEmpty().ifBlank { "No endpoint" })
        } + "Add peer"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Additional WireGuard peers")
            .setItems(labels.toTypedArray()) { _, index -> editPeer(pref, peers, index) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editPeer(pref: EditTextPreference, peers: org.json.JSONArray, index: Int) {
        val existing = peers.optJSONObject(index)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        fun field(label: String, value: String, secret: Boolean = false): android.widget.EditText {
            container.addView(android.widget.TextView(this).apply { text = label })
            return android.widget.EditText(this).apply {
                setText(value)
                if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                container.addView(this)
            }
        }
        val address = field("Endpoint address", existing?.optString("address").orEmpty())
        val port = field("Endpoint port", existing?.optString("port").orEmpty())
        val publicKey = field("Public key", existing?.optString("public_key").orEmpty())
        val psk = field("Pre-shared key", existing?.optString("pre_shared_key").orEmpty(), true)
        val ips = existing?.optJSONArray("allowed_ips")
        val allowed = field("Allowed IPs", if (ips == null) "" else (0 until ips.length()).joinToString(", ") { ips.getString(it) })
        val keepalive = field("Persistent keepalive (seconds)", existing?.optString("persistent_keepalive_interval") ?: "0")
        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add peer" else "Edit peer")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) builder.setNeutralButton("Remove") { _, _ ->
            peers.remove(index)
            pref.text = peers.toString()
            showPeers(pref)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val p = port.text.toString().trim().ifBlank { "0" }.toIntOrNull()
                val k = keepalive.text.toString().trim().ifBlank { "0" }.toIntOrNull()
                val routes = allowed.text.toString().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (p == null || p !in 0..65535 || k == null || k !in 0..65535 || routes.isEmpty() || publicKey.text.isBlank()) {
                    android.widget.Toast.makeText(this, "Check port, keepalive, public key and Allowed IPs", android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val obj = existing ?: org.json.JSONObject()
                obj.put("address", address.text.toString().trim())
                obj.put("port", p)
                obj.put("public_key", publicKey.text.toString().trim())
                obj.put("pre_shared_key", psk.text.toString().trim())
                obj.put("allowed_ips", org.json.JSONArray(routes))
                obj.put("persistent_keepalive_interval", k)
                peers.put(index, obj)
                pref.text = peers.toString()
                dialog.dismiss()
                showPeers(pref)
            }
        }
        dialog.show()
    }

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putBoolean("awgEnabled", isAmnezia)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
        // переключатель AmneziaWG выключен — профиль становится обычным WireGuard
        if (!DataStore.profileCacheStore.getBoolean("awgEnabled", false)) clearAmnezia()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        if (!legacyRequested) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            return
        }
        addPreferencesFromResource(R.xml.wireguard_preferences)
        val wgAdvanced = PreferenceCategory(requireContext()).apply { title = "WireGuard advanced settings"; key = "wgAdvanced" }
        preferenceScreen.addPreference(wgAdvanced)
        wgAdvanced.addPreference(EditTextPreference(requireContext()).apply {
            key = "wgInterfaceName"
            title = "Interface name"
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        })
        wgAdvanced.addPreference(EditTextPreference(requireContext()).apply {
            key = "wgListenPort"
            title = "Listen port"
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        })
        wgAdvanced.addPreference(androidx.preference.ListPreference(requireContext()).apply {
            key = "wgUdpMapping"
            title = "UDP mapping"
            entries = arrayOf("Default", "Endpoint independent", "Address dependent", "Address and port dependent")
            entryValues = arrayOf("", "endpoint_independent", "address_dependent", "address_and_port_dependent")
            summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
        })
        wgAdvanced.addPreference(androidx.preference.ListPreference(requireContext()).apply {
            key = "wgUdpFiltering"
            title = "UDP filtering"
            entries = arrayOf("Default", "Endpoint independent", "Address dependent", "Address and port dependent")
            entryValues = arrayOf("", "endpoint_independent", "address_dependent", "address_and_port_dependent")
            summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
        })
        wgAdvanced.addPreference(EditTextPreference(requireContext()).apply {
            key = "wgUdpNatMax"
            title = "UDP NAT max"
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        })
        wgAdvanced.addPreference(EditTextPreference(requireContext()).apply {
            key = "wgBuffers"
            title = "Preallocated buffers per pool"
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        })
        wgAdvanced.addPreference(SwitchPreference(requireContext()).apply {
            key = "wgDisablePauses"
            title = "Disable pauses"

        })
        wgAdvanced.addPreference(EditTextPreference(requireContext()).apply {
            key = "wgAllowedIps"
            title = "Allowed IPs"
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        })
        wgAdvanced.addPreference(EditTextPreference(requireContext()).apply {
            key = "wgExtraPeers"
            title = "Additional peers (JSON array)"
            summary = "Additional peers in native sing-box JSON format"
        })
        wgAdvanced.addPreference(EditTextPreference(requireContext()).apply {
            key = "wgDialer"
            title = "Dialer extra JSON"
            summary = "Advanced native sing-box dial fields"
        })
        pbm.setPreferenceFragment(this)
        val dialerJson = wgDialer.preference as EditTextPreference
        val dialerCategory = PreferenceCategory(requireContext()).apply { title = "WireGuard dialer settings" }
        preferenceScreen.addPreference(dialerCategory)
        fun readDialer() = org.json.JSONObject(dialerJson.text.orEmpty().ifBlank { "{}" })
        fun updateDialer(key: String, value: Any?): Boolean = try {
            val json = readDialer()
            if (value == null) json.remove(key) else json.put(key, value)
            dialerJson.text = json.toString()
            true
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Invalid dialer JSON or value", android.widget.Toast.LENGTH_SHORT).show()
            false
        }
        val textFields = linkedMapOf(
            "bind_interface" to "Bind interface", "inet4_bind_address" to "IPv4 bind address",
            "inet6_bind_address" to "IPv6 bind address", "protect_path" to "Protect path",
            "routing_mark" to "Routing mark", "netns" to "Network namespace",
            "connect_timeout" to "Connect timeout", "tcp_keep_alive" to "TCP keepalive",
            "tcp_keep_alive_interval" to "TCP keepalive interval", "domain_resolver" to "Domain resolver (tag or JSON)",
            "network_strategy" to "Network strategy", "network_type" to "Network types",
            "fallback_network_type" to "Fallback network types", "fallback_delay" to "Fallback delay"
        )
        for ((field, label) in textFields) {
            val pref = EditTextPreference(requireContext()).apply {
                key = "wg_dialer_" + field
                title = label
                isPersistent = false
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            fun refresh() { pref.text = runCatching { readDialer().opt(field)?.toString().orEmpty() }.getOrDefault("") }
            refresh()
            pref.setOnPreferenceClickListener { refresh(); false }
            pref.setOnPreferenceChangeListener { _, value ->
                try {
                    val raw = value.toString().trim()
                    updateDialer(field, if (raw.isBlank()) null else when (field) {
                        "routing_mark" -> raw
                        "domain_resolver" -> if (raw.startsWith("{")) org.json.JSONObject(raw) else raw
                        "network_type", "fallback_network_type" -> if (raw.startsWith("[")) org.json.JSONArray(raw) else org.json.JSONArray(raw.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                        else -> raw
                    })
                } catch (e: Exception) { false }
            }
            dialerCategory.addPreference(pref)
        }
        for ((field, label) in linkedMapOf("bind_address_no_port" to "Bind address without port", "reuse_addr" to "Reuse address", "tcp_fast_open" to "TCP Fast Open", "tcp_multi_path" to "Multipath TCP", "disable_tcp_keep_alive" to "Disable TCP keepalive", "udp_fragment" to "UDP fragmentation")) {
            dialerCategory.addPreference(androidx.preference.ListPreference(requireContext()).apply {
                key = "wg_dialer_" + field
                title = label
                isPersistent = false
                entries = arrayOf("Default", "Enabled", "Disabled")
                entryValues = arrayOf("", "true", "false")
                value = runCatching { readDialer().opt(field)?.toString().orEmpty() }.getOrDefault("")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newValue -> updateDialer(field, newValue.toString().takeIf { it.isNotBlank() }?.toBooleanStrict()) }
            })
        }
        val peersJson = wgExtraPeers.preference as EditTextPreference
        val peerManager = androidx.preference.Preference(requireContext()).apply {
            title = "Manage additional peers"
            summary = "Add, edit or remove WireGuard peers"
            setOnPreferenceClickListener {
                showPeers(peersJson)
                true
            }
        }
        wgAdvanced.addPreference(peerManager)
        preferenceScreen.addPreference(androidx.preference.Preference(requireContext()).apply {
            title = "Import WireGuard / AmneziaWG configuration"
            summary = "Replace current WireGuard connection settings from a .conf file"
            setOnPreferenceClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setMessage("Replace current connection settings?")
                    .setPositiveButton("Import") { _, _ -> importConf.launch(arrayOf("*/*")) }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
        })
        wgAdvanced.addPreference(androidx.preference.Preference(requireContext()).apply {
            title = "Export WireGuard configuration"
            summary = "Save .conf containing private keys"
            setOnPreferenceClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setMessage("The exported file contains private keys. Keep it secure.")
                    .setPositiveButton("Export") { _, _ -> exportConf.launch("wireguard.conf") }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
        })

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        listOf(persistentKeepalive, workers).forEach {
            (it.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        val awgCat = findPreference<PreferenceCategory>("awgCategory")!!
        val awgSwitch = findPreference<SwitchPreference>("awgEnabled")!!
        fun applyAwg(on: Boolean) {
            wgAdvanced.isVisible = !on
            dialerCategory.isVisible = !on
            for (i in 0 until awgCat.preferenceCount) {
                val p = awgCat.getPreference(i)
                if (p.key != "awgEnabled") p.isVisible = on
            }
        }
        applyAwg(awgSwitch.isChecked)
        awgSwitch.setOnPreferenceChangeListener { _, value ->
            val on = value as Boolean
            applyAwg(on)
            val jc = awgJc.preference as EditTextPreference
            if (on && (jc.text.isNullOrBlank() || jc.text == "0")) {
                jc.text = "4"
                (awgJmin.preference as EditTextPreference).text = "40"
                (awgJmax.preference as EditTextPreference).text = "70"
            }
            true
        }
        listOf(awgJc, awgJmin, awgJmax, awgS1, awgS2, awgS3, awgS4).forEach {
            (it.preference as EditTextPreference)
                .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
    }

}