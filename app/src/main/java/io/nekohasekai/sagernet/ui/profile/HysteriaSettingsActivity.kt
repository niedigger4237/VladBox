package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class HysteriaSettingsActivity : ProfileSettingsActivity<HysteriaBean>() {

    private val extra = moe.matsuri.nb4a.proxy.PreferenceBindingManager()
    private val hy2ObfsType = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2ObfsType"))
    private val hy2BbrProfile = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2BbrProfile"))
    private val hy2HopIntervalMax = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2HopIntervalMax"))
    private val hy2GeckoMin = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2GeckoMin"))
    private val hy2GeckoMax = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2GeckoMax"))

    private val hy2Network = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2Network"))
    private val hy2IdleTimeout = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2IdleTimeout"))
    private val hy2KeepAlive = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2KeepAlive"))
    private val hy2StreamWindow = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2StreamWindow"))
    private val hy2ConnectionWindow = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2ConnectionWindow"))
    private val hy2MaxStreams = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2MaxStreams"))
    private val hy2InitialPacket = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2InitialPacket"))
    private val hy2Realm = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hy2Realm"))
    private val hy2BrutalDebug = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Bool, "hy2BrutalDebug"))
    private val hy2DisableChrome = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Bool, "hy2DisableChrome"))
    private val hy2DisablePathMtu = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Bool, "hy2DisablePathMtu"))

    override fun createEntity() = HysteriaBean().applyDefaultValues()

    override fun HysteriaBean.init() {
        extra.writeToCacheAll(this)
        DataStore.profileName = name
        DataStore.protocolVersion = protocolVersion
        DataStore.serverAddress = serverAddress
        DataStore.serverPorts = serverPorts
        DataStore.serverObfs = obfuscation
        DataStore.serverAuthType = authPayloadType
        DataStore.serverProtocolInt = protocol
        DataStore.serverPassword = authPayload
        DataStore.serverSNI = sni
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = caText
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverUploadSpeed = uploadMbps
        DataStore.serverDownloadSpeed = downloadMbps
        DataStore.serverStreamReceiveWindow = streamReceiveWindow
        DataStore.serverConnectionReceiveWindow = connectionReceiveWindow
        DataStore.serverDisableMtuDiscovery = disableMtuDiscovery
        DataStore.serverHopInterval = hopInterval
    }

    override fun HysteriaBean.serialize() {
        extra.fromCacheAll(this)
        name = DataStore.profileName
        protocolVersion = DataStore.protocolVersion
        serverAddress = DataStore.serverAddress
        serverPorts = DataStore.serverPorts
        obfuscation = DataStore.serverObfs
        authPayloadType = DataStore.serverAuthType
        authPayload = DataStore.serverPassword
        protocol = DataStore.serverProtocolInt
        sni = DataStore.serverSNI
        alpn = DataStore.serverALPN
        caText = DataStore.serverCertificates
        allowInsecure = DataStore.serverAllowInsecure
        uploadMbps = DataStore.serverUploadSpeed
        downloadMbps = DataStore.serverDownloadSpeed
        streamReceiveWindow = DataStore.serverStreamReceiveWindow
        connectionReceiveWindow = DataStore.serverConnectionReceiveWindow
        disableMtuDiscovery = DataStore.serverDisableMtuDiscovery
        hopInterval = DataStore.serverHopInterval
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.hysteria_preferences)

        val authType = findPreference<SimpleMenuPreference>(Key.SERVER_AUTH_TYPE)!!
        val authPayload = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!
        authPayload.isVisible = authType.value != "${HysteriaBean.TYPE_NONE}"
        authType.setOnPreferenceChangeListener { _, newValue ->
            authPayload.isVisible = newValue != "${HysteriaBean.TYPE_NONE}"
            true
        }

        val protocol = findPreference<SimpleMenuPreference>(Key.SERVER_PROTOCOL)!!
        val alpn = findPreference<EditTextPreference>(Key.SERVER_ALPN)!!

        fun updateVersion(v: Int) {
            findPreference<androidx.preference.PreferenceCategory>("hy2Advanced")!!.isVisible = v == 2
            if (v == 2) {
                authPayload.isVisible = true
                //
                authType.isVisible = false
                protocol.isVisible = false
                alpn.isVisible = false
                //
                findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.isVisible =
                    false
                findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.isVisible =
                    false
                findPreference<SwitchPreference>(Key.SERVER_DISABLE_MTU_DISCOVERY)!!.isVisible =
                    false
                //
                authPayload.title = resources.getString(R.string.password)
            } else {
                authType.isVisible = true
                authPayload.isVisible = true
                protocol.isVisible = true
                alpn.isVisible = true
                //
                findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.isVisible =
                    true
                findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.isVisible =
                    true
                findPreference<SwitchPreference>(Key.SERVER_DISABLE_MTU_DISCOVERY)!!.isVisible =
                    true
                //
                authPayload.title = resources.getString(R.string.hysteria_auth_payload)
            }
        }
        findPreference<SimpleMenuPreference>(Key.PROTOCOL_VERSION)!!.setOnPreferenceChangeListener { _, newValue ->
            updateVersion(newValue.toString().toIntOrNull() ?: 1)
            true
        }
        updateVersion(DataStore.protocolVersion)

        findPreference<EditTextPreference>(Key.SERVER_UPLOAD_SPEED)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_DOWNLOAD_SPEED)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_OBFS)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }

        findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
    }

}
