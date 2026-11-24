package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class SSHSettingsActivity : ProfileSettingsActivity<SSHBean>() {

    private val extra = moe.matsuri.nb4a.proxy.PreferenceBindingManager()
    private val privateKeyPath = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "privateKeyPath"))
    private val outboundDialer = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "outboundDialer"))
    private val hostKeyAlgorithms = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "hostKeyAlgorithms"))
    private val clientVersion = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "clientVersion"))
    private val cipherAlgorithms = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "cipherAlgorithms"))
    private val macAlgorithms = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "macAlgorithms"))
    private val kexAlgorithms = extra.add(moe.matsuri.nb4a.proxy.PreferenceBinding(moe.matsuri.nb4a.proxy.Type.Text, "kexAlgorithms"))
    override fun createEntity() = SSHBean()

    override fun SSHBean.init() {
        extra.writeToCacheAll(this)
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverAuthType = authType
        DataStore.serverPassword = password
        DataStore.serverPrivateKey = privateKey
        DataStore.serverPassword1 = privateKeyPassphrase
        DataStore.serverCertificates = publicKey
    }

    override fun SSHBean.serialize() {
        extra.fromCacheAll(this)
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        authType = DataStore.serverAuthType
        when (authType) {
            SSHBean.AUTH_TYPE_NONE -> {
            }
            SSHBean.AUTH_TYPE_PASSWORD -> {
                password = DataStore.serverPassword
            }
            SSHBean.AUTH_TYPE_PRIVATE_KEY -> {
                privateKey = DataStore.serverPrivateKey
                privateKeyPassphrase = DataStore.serverPassword1
            }
        }
        publicKey = DataStore.serverCertificates
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
        this@SSHSettingsActivity.onCreateOptionsMenu(menu)
        val actions = (0 until menu.size()).map { menu.getItem(it) }
            .filter { it.isVisible && it.itemId != R.id.action_apply }
            .map { item -> item.title.toString() to { this@SSHSettingsActivity.onOptionsItemSelected(item); Unit } }
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
                io.nekohasekai.sagernet.ui.compose.SshProfileScreen(
                    onSave = { io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher { saveAndExit() } },
                    onClose = { this@SSHSettingsActivity.onBackPressed() },
                    menuItems = actions
                )
            }
        }
    }
}
