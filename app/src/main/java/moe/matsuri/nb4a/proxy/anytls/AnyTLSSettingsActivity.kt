package moe.matsuri.nb4a.proxy.anytls

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class AnyTLSSettingsActivity : ProfileSettingsActivity<AnyTLSBean>() {
    override fun createEntity() = AnyTLSBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))

    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))
    private val idleSessionCheckInterval = pbm.add(PreferenceBinding(Type.Text, "idleSessionCheckInterval"))
    private val idleSessionTimeout = pbm.add(PreferenceBinding(Type.Text, "idleSessionTimeout"))
    private val minIdleSession = pbm.add(PreferenceBinding(Type.Text, "minIdleSession"))
    private val clientMetadata = pbm.add(PreferenceBinding(Type.Text, "clientMetadata"))

    override fun AnyTLSBean.init() {
        pbm.writeToCacheAll(this)

    }

    override fun AnyTLSBean.serialize() {
        pbm.fromCacheAll(this)
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
        this@AnyTLSSettingsActivity.onCreateOptionsMenu(menu)
        val actions = (0 until menu.size()).map { menu.getItem(it) }
            .filter { it.isVisible && it.itemId != R.id.action_apply }
            .map { item -> item.title.toString() to { this@AnyTLSSettingsActivity.onOptionsItemSelected(item); Unit } }
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
                io.nekohasekai.sagernet.ui.compose.TlsProxyScreen(
                    shadow = false,
                    onSave = { io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher { saveAndExit() } },
                    onClose = { this@AnyTLSSettingsActivity.onBackPressed() },
                    menuItems = actions
                )
            }
        }
    }
}
