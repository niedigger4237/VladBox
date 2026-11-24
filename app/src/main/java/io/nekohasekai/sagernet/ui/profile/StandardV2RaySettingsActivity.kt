package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import moe.matsuri.nb4a.ui.SimpleMenuPreference

abstract class StandardV2RaySettingsActivity : ProfileSettingsActivity<StandardV2RayBean>() {

    var tmpBean: StandardV2RayBean? = null

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val uuid = pbm.add(PreferenceBinding(Type.Text, "uuid"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val alterId = pbm.add(PreferenceBinding(Type.TextToInt, "alterId"))
    private val encryption = pbm.add(PreferenceBinding(Type.Text, "encryption"))
    private val type = pbm.add(PreferenceBinding(Type.Text, "type"))
    private val host = pbm.add(PreferenceBinding(Type.Text, "host"))
    private val path = pbm.add(PreferenceBinding(Type.Text, "path"))
    private val xhttpMode = pbm.add(PreferenceBinding(Type.Text, "xhttpMode"))
    private val xhttpExtra = pbm.add(PreferenceBinding(Type.Text, "xhttpExtra"))
    private val packetEncoding = pbm.add(PreferenceBinding(Type.TextToInt, "packetEncoding"))
    private val wsMaxEarlyData = pbm.add(PreferenceBinding(Type.TextToInt, "wsMaxEarlyData"))
    private val earlyDataHeaderName = pbm.add(PreferenceBinding(Type.Text, "earlyDataHeaderName"))
    private val security = pbm.add(PreferenceBinding(Type.Text, "security"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val realityPubKey = pbm.add(PreferenceBinding(Type.Text, "realityPubKey"))
    private val realityShortId = pbm.add(PreferenceBinding(Type.Text, "realityShortId"))

    private val enableECH = pbm.add(PreferenceBinding(Type.Bool, "enableECH"))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))

    private val enableMux = pbm.add(PreferenceBinding(Type.Bool, "enableMux"))
    private val muxPadding = pbm.add(PreferenceBinding(Type.Bool, "muxPadding"))
    private val muxType = pbm.add(PreferenceBinding(Type.TextToInt, "muxType"))
    private val muxConcurrency = pbm.add(PreferenceBinding(Type.TextToInt, "muxConcurrency"))
    private val muxMaxConnections = pbm.add(PreferenceBinding(Type.TextToInt, "muxMaxConnections"))
    private val muxMinStreams = pbm.add(PreferenceBinding(Type.TextToInt, "muxMinStreams"))
    private val muxBrutal = pbm.add(PreferenceBinding(Type.Bool, "muxBrutal"))
    private val muxBrutalUp = pbm.add(PreferenceBinding(Type.TextToInt, "muxBrutalUp"))
    private val muxBrutalDown = pbm.add(PreferenceBinding(Type.TextToInt, "muxBrutalDown"))

    private val tlsDisableSni = pbm.add(PreferenceBinding(Type.Bool, "tlsDisableSni"))
    private val tlsPubKeySha256 = pbm.add(PreferenceBinding(Type.Text, "tlsPubKeySha256"))
    private val tlsCipherSuites = pbm.add(PreferenceBinding(Type.Text, "tlsCipherSuites"))
    private val tlsFragment = pbm.add(PreferenceBinding(Type.Bool, "tlsFragment"))
    private val tlsFragmentFallbackDelay = pbm.add(PreferenceBinding(Type.Text, "tlsFragmentFallbackDelay"))
    private val tlsRecordFragment = pbm.add(PreferenceBinding(Type.Bool, "tlsRecordFragment"))
    private val httpMethod = pbm.add(PreferenceBinding(Type.Text, "httpMethod"))
    private val transportHeaders = pbm.add(PreferenceBinding(Type.Text, "transportHeaders"))
    private val grpcIdleTimeout = pbm.add(PreferenceBinding(Type.TextToInt, "grpcIdleTimeout"))
    private val grpcPingTimeout = pbm.add(PreferenceBinding(Type.TextToInt, "grpcPingTimeout"))
    private val globalPadding = pbm.add(PreferenceBinding(Type.Bool, "globalPadding"))
    private val authenticatedLength = pbm.add(PreferenceBinding(Type.Bool, "authenticatedLength"))

    private val tlsMinVersion = pbm.add(PreferenceBinding(Type.Text, "tlsMinVersion"))
    private val tlsMaxVersion = pbm.add(PreferenceBinding(Type.Text, "tlsMaxVersion"))
    private val tlsCurves = pbm.add(PreferenceBinding(Type.Text, "tlsCurves"))
    private val tlsHandshakeTimeout = pbm.add(PreferenceBinding(Type.Text, "tlsHandshakeTimeout"))
    private val tlsClientCertificate = pbm.add(PreferenceBinding(Type.Text, "tlsClientCertificate"))
    private val tlsClientKey = pbm.add(PreferenceBinding(Type.Text, "tlsClientKey"))
    private val echQueryName = pbm.add(PreferenceBinding(Type.Text, "echQueryName"))
    private val realityHybrid = pbm.add(PreferenceBinding(Type.Bool, "realityHybrid"))
    private val vlessEncryption = pbm.add(PreferenceBinding(Type.Text, "vlessEncryption"))
    private val vlessNetwork = pbm.add(PreferenceBinding(Type.Text, "vlessNetwork"))
    private val kcpMtu = pbm.add(PreferenceBinding(Type.Text, "kcpMtu"))
    private val kcpTti = pbm.add(PreferenceBinding(Type.Text, "kcpTti"))
    private val kcpUp = pbm.add(PreferenceBinding(Type.Text, "kcpUp"))
    private val kcpDown = pbm.add(PreferenceBinding(Type.Text, "kcpDown"))
    private val kcpRead = pbm.add(PreferenceBinding(Type.Text, "kcpRead"))
    private val kcpWrite = pbm.add(PreferenceBinding(Type.Text, "kcpWrite"))
    private val kcpHeader = pbm.add(PreferenceBinding(Type.Text, "kcpHeader"))
    private val kcpSeed = pbm.add(PreferenceBinding(Type.Text, "kcpSeed"))
    private val kcpCongestion = pbm.add(PreferenceBinding(Type.Bool, "kcpCongestion"))
    private val tlsEngine = pbm.add(PreferenceBinding(Type.Text, "tlsEngine"))
    private val tlsCertificatePath = pbm.add(PreferenceBinding(Type.Text, "tlsCertificatePath"))
    private val tlsClientCertificatePath = pbm.add(PreferenceBinding(Type.Text, "tlsClientCertificatePath"))
    private val tlsClientKeyPath = pbm.add(PreferenceBinding(Type.Text, "tlsClientKeyPath"))
    private val echConfigPath = pbm.add(PreferenceBinding(Type.Text, "echConfigPath"))
    private val tlsSpoof = pbm.add(PreferenceBinding(Type.Text, "tlsSpoof"))
    private val tlsSpoofMethod = pbm.add(PreferenceBinding(Type.Text, "tlsSpoofMethod"))
    private val tlsKernelTx = pbm.add(PreferenceBinding(Type.Bool, "tlsKernelTx"))
    private val tlsKernelRx = pbm.add(PreferenceBinding(Type.Bool, "tlsKernelRx"))
    private val httpExtra = PreferenceBindingManager()
    private val httpPath = httpExtra.add(PreferenceBinding(Type.Text, "httpPath"))
    private val httpHeaders = httpExtra.add(PreferenceBinding(Type.Text, "httpHeaders"))

    override fun StandardV2RayBean.init() {
        if (this is HttpBean) httpExtra.writeToCacheAll(this)
        if (this is TrojanBean) {
            this@StandardV2RaySettingsActivity.uuid.fieldName = "password"
            this@StandardV2RaySettingsActivity.password.disable = true
        }

        tmpBean = this // copy bean
        pbm.writeToCacheAll(this)
    }

    override fun StandardV2RayBean.serialize() {
        pbm.fromCacheAll(this)
        if (this is HttpBean) httpExtra.fromCacheAll(this)
    }

    private lateinit var securityCategory: PreferenceCategory
    private lateinit var tlsCamouflageCategory: PreferenceCategory
    private lateinit var wsCategory: PreferenceCategory
    private lateinit var echCategory: PreferenceCategory

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.standard_v2ray_preferences)
        pbm.setPreferenceFragment(this)
        securityCategory = findPreference(Key.SERVER_SECURITY_CATEGORY)!!
        tlsCamouflageCategory = findPreference(Key.SERVER_TLS_CAMOUFLAGE_CATEGORY)!!
        echCategory = findPreference(Key.SERVER_ECH_CATEORY)!!
        wsCategory = findPreference(Key.SERVER_WS_CATEGORY)!!


        // vmess/vless/http/trojan
        val isHttp = tmpBean is HttpBean
        findPreference<PreferenceCategory>("httpAdvanced")?.isVisible = isHttp
        val isVmess = tmpBean is VMessBean && tmpBean?.isVLESS == false
        val isVless = tmpBean?.isVLESS == true
        vlessEncryption.preference.isVisible = isVless
        vlessNetwork.preference.isVisible = isVless

        serverPort.preference.apply {
            this as EditTextPreference
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        alterId.preference.apply {
            this as EditTextPreference
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        uuid.preference.summaryProvider = PasswordSummaryProvider

        type.preference.isVisible = !isHttp
        uuid.preference.isVisible = !isHttp
        packetEncoding.preference.isVisible = isVmess || isVless
        alterId.preference.isVisible = isVmess
        globalPadding.preference.isVisible = isVmess
        authenticatedLength.preference.isVisible = isVmess
        encryption.preference.isVisible = isVmess || isVless
        username.preference.isVisible = isHttp
        password.preference.isVisible = isHttp

        if (tmpBean is TrojanBean) {
            uuid.preference.title = resources.getString(R.string.password)
        }

        encryption.preference.apply {
            this as SimpleMenuPreference
            if (isVless) {
                title = resources.getString(R.string.xtls_flow)
                setIcon(R.drawable.ic_baseline_stream_24)
                setEntries(R.array.xtls_flow_value)
                setEntryValues(R.array.xtls_flow_value)
            } else {
                setEntries(R.array.vmess_encryption_value)
                setEntryValues(R.array.vmess_encryption_value)
            }
        }

        fun updateMux(enabled: Boolean, brutal: Boolean) {
            muxType.preference.isVisible = enabled
            muxPadding.preference.isVisible = enabled
            muxConcurrency.preference.isVisible = enabled
            muxMaxConnections.preference.isVisible = enabled
            muxMinStreams.preference.isVisible = enabled
            muxBrutal.preference.isVisible = enabled
            muxBrutalUp.preference.isVisible = enabled && brutal
            muxBrutalDown.preference.isVisible = enabled && brutal
        }
        val muxSwitch = enableMux.preference as androidx.preference.TwoStatePreference
        val brutalSwitch = muxBrutal.preference as androidx.preference.TwoStatePreference
        enableMux.preference.isVisible = !isHttp
        updateMux(!isHttp && muxSwitch.isChecked, brutalSwitch.isChecked)
        muxSwitch.setOnPreferenceChangeListener { _, value ->
            updateMux(!isHttp && value as Boolean, brutalSwitch.isChecked)
            true
        }
        brutalSwitch.setOnPreferenceChangeListener { _, value ->
            updateMux(!isHttp && muxSwitch.isChecked, value as Boolean)
            true
        }
        addXhttpFields()
        // menu with listener

        type.preference.apply {
            updateView(type.readStringFromCache())
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateView(newValue as String)
                true
            }
        }

        security.preference.apply {
            updateTls(security.readStringFromCache())
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateTls(newValue as String)
                true
            }
        }
    }


    private fun PreferenceFragmentCompat.addXhttpFields() {
        val category = PreferenceCategory(requireContext()).apply {
            key = "xhttpStructured"
            title = "XHTTP advanced settings"
        }
        preferenceScreen.addPreference(category)
        val fields = linkedMapOf(
            "headers" to "Headers (JSON)",
            "x_padding_bytes" to "Padding bytes",
            "no_grpc_header" to "Disable gRPC header",
            "no_sse_header" to "Disable SSE header",
            "sc_max_each_post_bytes" to "Max POST bytes",
            "sc_min_posts_interval_ms" to "Min POST interval (ms)",
            "sc_max_buffered_posts" to "Max buffered POSTs",
            "sc_stream_up_server_secs" to "Stream up server seconds",
            "x_padding_obfs_mode" to "Padding obfuscation",
            "x_padding_key" to "Padding key",
            "x_padding_header" to "Padding header",
            "x_padding_placement" to "Padding placement",
            "x_padding_method" to "Padding method",
            "uplink_http_method" to "Uplink HTTP method",
            "session_placement" to "Session placement",
            "session_key" to "Session key",
            "session_id_table" to "Session ID table",
            "session_id_length" to "Session ID length",
            "seq_placement" to "Sequence placement",
            "seq_key" to "Sequence key",
            "uplink_data_placement" to "Uplink data placement",
            "uplink_data_key" to "Uplink data key",
            "uplink_chunk_size" to "Uplink chunk size",
            "server_max_header_bytes" to "Max header bytes",
            "domain_strategy" to "Domain strategy",
            "congestion_controller" to "Congestion controller",
            "cwnd" to "CWND",
            "trusted_x_forwarded_for" to "Trusted X-Forwarded-For",
            "xmux" to "XMUX (JSON)",
            "download" to "Download settings (JSON)"
        )
        val booleans = setOf("no_grpc_header", "no_sse_header", "x_padding_obfs_mode")
        val numbers = setOf("sc_max_buffered_posts", "server_max_header_bytes", "cwnd")
        val objects = setOf("headers", "xmux", "download")
        fun readExtra() = org.json.JSONObject(xhttpExtra.readStringFromCache().ifBlank { "{}" })
        fun writeExtra(json: org.json.JSONObject) {
            (xhttpExtra.preference as EditTextPreference).text = json.toString()
        }
        fun update(field: String, value: Any?): Boolean {
            return try {
                val json = readExtra()
                val parts = field.split('.')
                val target = if (parts.size == 2) {
                    json.optJSONObject(parts[0]) ?: org.json.JSONObject().also { json.put(parts[0], it) }
                } else json
                if (value == null) target.remove(parts.last()) else target.put(parts.last(), value)
                writeExtra(json)
                true
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), "Invalid XHTTP value", android.widget.Toast.LENGTH_SHORT).show()
                false
            }
        }
        fun read(field: String): Any? = runCatching {
            val json = readExtra()
            val parts = field.split('.')
            val target = if (parts.size == 2) json.optJSONObject(parts[0]) else json
            target?.opt(parts.last())?.takeUnless { it == org.json.JSONObject.NULL }
        }.getOrNull()
        val xmuxFields = linkedMapOf(
            "xmux.max_concurrency" to "XMUX Max concurrency",
            "xmux.max_connections" to "XMUX Max connections",
            "xmux.c_max_reuse_times" to "XMUX Connection reuse times",
            "xmux.h_max_request_times" to "XMUX HTTP request times",
            "xmux.h_max_reusable_secs" to "XMUX Reusable seconds",
            "xmux.h_keep_alive_period" to "XMUX Keep alive period"
        )
        for ((field, label) in fields + xmuxFields) {
            if (field in booleans) {
                category.addPreference(androidx.preference.SwitchPreferenceCompat(requireContext()).apply {
                    key = "xhttp_structured_" + field
                    title = label
                    isPersistent = false
                    isChecked = read(field) == true
                    setOnPreferenceChangeListener { _, value -> update(field, value as Boolean) }
                })
                continue
            }
            val pref = EditTextPreference(requireContext()).apply {
                key = "xhttp_structured_" + field
                title = label
                isPersistent = false
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            fun refresh() { pref.text = read(field)?.toString().orEmpty() }
            refresh()
            pref.setOnPreferenceClickListener { refresh(); false }
            pref.setOnPreferenceChangeListener { _, value ->
                try {
                    val raw = value.toString().trim()
                    update(field, if (raw.isEmpty()) null else when (field) {
                        in numbers, "xmux.h_keep_alive_period" -> raw.toLong()
                        in objects -> org.json.JSONObject(raw)
                        else -> raw
                    })
                } catch (e: Exception) {
                    android.widget.Toast.makeText(requireContext(), "Invalid XHTTP value", android.widget.Toast.LENGTH_SHORT).show()
                    false
                }
            }
            category.addPreference(pref)
        }
        xhttpStructured = category
    }
    private var xhttpStructured: PreferenceCategory? = null

    private fun updateView(network: String) {
        xhttpStructured?.isVisible = network == "xhttp" && tmpBean !is HttpBean
        findKcpVisibility(network == "kcp" && tmpBean !is HttpBean)
        host.preference.isVisible = false
        path.preference.isVisible = false
        xhttpMode.preference.isVisible = false
        xhttpExtra.preference.isVisible = false
        wsCategory.isVisible = false
        httpMethod.preference.isVisible = network == "http"
        transportHeaders.preference.isVisible = network in setOf("http", "ws", "httpupgrade")
        grpcIdleTimeout.preference.isVisible = network == "grpc"
        grpcPingTimeout.preference.isVisible = network == "grpc"

        when (network) {
            "tcp" -> {
                host.preference.setTitle(R.string.http_host)
                path.preference.setTitle(R.string.http_path)
            }

            "http" -> {
                host.preference.setTitle(R.string.http_host)
                path.preference.setTitle(R.string.http_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
            }

            "ws" -> {
                host.preference.setTitle(R.string.ws_host)
                path.preference.setTitle(R.string.ws_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
                wsCategory.isVisible = true
            }

            "grpc" -> {
                path.preference.setTitle(R.string.grpc_service_name)
                path.preference.isVisible = true
            }

            "xhttp" -> {
                host.preference.setTitle(R.string.xhttp_host)
                path.preference.setTitle(R.string.xhttp_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
                xhttpMode.preference.isVisible = true
                xhttpExtra.preference.isVisible = true
            }

            "httpupgrade" -> {
                host.preference.setTitle(R.string.http_upgrade_host)
                path.preference.setTitle(R.string.http_upgrade_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
            }
        }
    }

    private fun findKcpVisibility(visible: Boolean) {
        kcpMtu.preference.isVisible = visible
        kcpTti.preference.isVisible = visible
        kcpUp.preference.isVisible = visible
        kcpDown.preference.isVisible = visible
        kcpRead.preference.isVisible = visible
        kcpWrite.preference.isVisible = visible
        kcpHeader.preference.isVisible = visible
        kcpSeed.preference.isVisible = visible
        kcpCongestion.preference.isVisible = visible
    }

    private fun updateTls(tls: String) {
        val isTLS = "tls" in tls
        securityCategory.isVisible = isTLS
        tlsCamouflageCategory.isVisible = isTLS
        echCategory.isVisible = isTLS
        tlsMinVersion.preference.isVisible = isTLS
        tlsMaxVersion.preference.isVisible = isTLS
        tlsCurves.preference.isVisible = isTLS
        tlsHandshakeTimeout.preference.isVisible = isTLS
        tlsClientCertificate.preference.isVisible = isTLS
        tlsClientKey.preference.isVisible = isTLS
        echQueryName.preference.isVisible = isTLS
        realityHybrid.preference.isVisible = isTLS
        tlsSpoof.preference.isVisible = isTLS
        tlsSpoofMethod.preference.isVisible = isTLS
        tlsKernelTx.preference.isVisible = isTLS
        tlsKernelRx.preference.isVisible = isTLS
        tlsEngine.preference.isVisible = isTLS
        tlsCertificatePath.preference.isVisible = isTLS
        tlsClientCertificatePath.preference.isVisible = isTLS
        tlsClientKeyPath.preference.isVisible = isTLS
        echConfigPath.preference.isVisible = isTLS
    }

}