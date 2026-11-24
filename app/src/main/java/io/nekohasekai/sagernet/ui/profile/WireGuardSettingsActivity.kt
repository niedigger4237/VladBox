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
        pbm.setPreferenceFragment(this)

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