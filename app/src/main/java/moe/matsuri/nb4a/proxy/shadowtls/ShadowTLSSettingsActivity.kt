package moe.matsuri.nb4a.proxy.shadowtls

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class ShadowTLSSettingsActivity : ProfileSettingsActivity<ShadowTLSBean>() {

    override fun createEntity() = ShadowTLSBean()

    private val pbm = PreferenceBindingManager()
    private val tlsMinVersion = pbm.add(PreferenceBinding(Type.Text, "tlsMinVersion"))
    private val tlsMaxVersion = pbm.add(PreferenceBinding(Type.Text, "tlsMaxVersion"))
    private val tlsHandshakeTimeout = pbm.add(PreferenceBinding(Type.Text, "tlsHandshakeTimeout"))
    private val tlsCertificatePath = pbm.add(PreferenceBinding(Type.Text, "tlsCertificatePath"))
    private val tlsPubKeySha256 = pbm.add(PreferenceBinding(Type.Text, "tlsPubKeySha256"))
    private val tlsCurves = pbm.add(PreferenceBinding(Type.Text, "tlsCurves"))
    private val tlsCipherSuites = pbm.add(PreferenceBinding(Type.Text, "tlsCipherSuites"))
    private val tlsClientCertificate = pbm.add(PreferenceBinding(Type.Text, "tlsClientCertificate"))
    private val tlsClientCertificatePath = pbm.add(PreferenceBinding(Type.Text, "tlsClientCertificatePath"))
    private val tlsClientKey = pbm.add(PreferenceBinding(Type.Text, "tlsClientKey"))
    private val tlsClientKeyPath = pbm.add(PreferenceBinding(Type.Text, "tlsClientKeyPath"))
    private val outboundDialer = pbm.add(PreferenceBinding(Type.Text, "outboundDialer"))
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val version = pbm.add(PreferenceBinding(Type.TextToInt, "version"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))

    override fun ShadowTLSBean.init() {
        pbm.writeToCacheAll(this)

    }

    override fun ShadowTLSBean.serialize() {
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
        this@ShadowTLSSettingsActivity.onCreateOptionsMenu(menu)
        val actions = (0 until menu.size()).map { menu.getItem(it) }
            .filter { it.isVisible && it.itemId != R.id.action_apply }
            .map { item -> item.title.toString() to { this@ShadowTLSSettingsActivity.onOptionsItemSelected(item); Unit } }
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
                    shadow = true,
                    onSave = { io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher { saveAndExit() } },
                    onClose = { this@ShadowTLSSettingsActivity.onBackPressed() },
                    menuItems = actions
                )
            }
        }
    }
}
