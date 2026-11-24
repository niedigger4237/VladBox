package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    override fun createEntity() = WireGuardBean()


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

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putBoolean("awgEnabled", isAmnezia)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
        // переключатель AmneziaWG выключен — профиль становится обычным WireGuard
        if (!DataStore.profileCacheStore.getBoolean("awgEnabled", false)) clearAmnezia()
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }
}
