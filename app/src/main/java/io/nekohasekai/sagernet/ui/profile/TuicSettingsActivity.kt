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
        DataStore.profileCacheStore.putString("outboundDialer", outboundDialer)
        DataStore.profileCacheStore.putString("quicTlsOptions", quicTlsOptions)
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
        outboundDialer = DataStore.profileCacheStore.getString("outboundDialer", "").orEmpty()
        quicTlsOptions = DataStore.profileCacheStore.getString("quicTlsOptions", "").orEmpty()
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

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun PreferenceFragmentCompat.viewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        val legacyList = listView
        val parent = legacyList.parent as? android.view.ViewGroup ?: return
        val index = parent.indexOfChild(legacyList)
        val params = legacyList.layoutParams
        val menu = androidx.appcompat.widget.PopupMenu(requireContext(), legacyList).menu
        this@TuicSettingsActivity.onCreateOptionsMenu(menu)
        val actions = (0 until menu.size()).map { menu.getItem(it) }
            .filter { it.isVisible && it.itemId != R.id.action_apply }
            .map { item -> item.title.toString() to { this@TuicSettingsActivity.onOptionsItemSelected(item); Unit } }
        supportActionBar?.hide()
        findViewById<android.view.View>(R.id.appbar)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.settings)?.let { container ->
            container.layoutParams = container.layoutParams.apply { height = android.view.ViewGroup.LayoutParams.MATCH_PARENT }
        }
        parent.removeView(legacyList)
        val host = androidx.compose.ui.platform.ComposeView(requireContext())
        host.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        parent.addView(host, index, params)
        host.setContent {
            io.nekohasekai.sagernet.ui.compose.HubTheme {
                io.nekohasekai.sagernet.ui.compose.QuicProfileScreen(
                    tuic = true,
                    onSave = { io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher { saveAndExit() } },
                    onClose = { this@TuicSettingsActivity.onBackPressed() },
                    menuItems = actions
                )
            }
        }
    }
}
