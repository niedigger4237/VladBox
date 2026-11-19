package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
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
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wireguard_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        listOf(persistentKeepalive, workers).forEach {
            (it.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        listOf(awgJc, awgJmin, awgJmax, awgS1, awgS2, awgS3, awgS4).forEach {
            (it.preference as EditTextPreference)
                .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
    }

}