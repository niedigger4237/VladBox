package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class TuicSettingsActivity : ProfileSettingsActivity<TuicBean>() {

    private val extra = moe.matsuri.nb4a.proxy.PreferenceBindingManager()
    private val tuicHeartbeat = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "tuicHeartbeat"))
    private val tuicNetwork = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "tuicNetwork"))
    private val tuicIdleTimeout = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "tuicIdleTimeout"))
    private val tuicKeepAlive = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "tuicKeepAlive"))
    private val tuicStreamWindow = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "tuicStreamWindow"))
    private val tuicConnectionWindow = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "tuicConnectionWindow"))
    private val tuicMaxStreams = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "tuicMaxStreams"))
    private val tuicInitialPacket = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "tuicInitialPacket"))
    private val tuicUdpOverStream = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Bool, "tuicUdpOverStream"))
    private val tuicDisablePathMtu = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Bool, "tuicDisablePathMtu"))

    override fun createEntity() = TuicBean().applyDefaultValues()

    override fun TuicBean.init() {
        extra.writeToCacheAll(this)
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = uuid
        DataStore.serverPassword = token
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = caText
        DataStore.serverUDPRelayMode = udpRelayMode
        DataStore.serverCongestionController = congestionController
        DataStore.serverDisableSNI = disableSNI
        DataStore.serverSNI = sni
        DataStore.serverReduceRTT = reduceRTT
        DataStore.serverAllowInsecure = allowInsecure
    }

    override fun TuicBean.serialize() {
        extra.fromCacheAll(this)
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        uuid = DataStore.serverUsername
        token = DataStore.serverPassword
        alpn = DataStore.serverALPN
        caText = DataStore.serverCertificates
        udpRelayMode = DataStore.serverUDPRelayMode
        congestionController = DataStore.serverCongestionController
        disableSNI = DataStore.serverDisableSNI
        sni = DataStore.serverSNI
        reduceRTT = DataStore.serverReduceRTT
        allowInsecure = DataStore.serverAllowInsecure
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.tuic_preferences)

        val disableSNI = findPreference<SwitchPreference>(Key.SERVER_DISABLE_SNI)!!
        val sni = findPreference<EditTextPreference>(Key.SERVER_SNI)!!
        sni.isEnabled = !disableSNI.isChecked
        disableSNI.setOnPreferenceChangeListener { _, newValue ->
            sni.isEnabled = !(newValue as Boolean)
            true
        }

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }

}