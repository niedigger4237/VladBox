package moe.matsuri.nb4a.proxy.config

import android.os.Bundle
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.ui.EditConfigPreference

class ConfigSettingActivity :
    ProfileSettingsActivity<ConfigBean>(),
    OnPreferenceDataStoreChangeListener {

    private val isOutboundOnlyKey = "isOutboundOnly"

    override fun createEntity() = ConfigBean()

    override fun ConfigBean.init() {
        // CustomBean to input
        DataStore.profileCacheStore.putBoolean(isOutboundOnlyKey, type == 1)
        DataStore.profileName = name
        DataStore.serverConfig = config
    }

    override fun ConfigBean.serialize() {
        // CustomBean from input
        type = if (DataStore.profileCacheStore.getBoolean(isOutboundOnlyKey, false)) 1 else 0
        name = DataStore.profileName
        config = DataStore.serverConfig
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
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
        this@ConfigSettingActivity.onCreateOptionsMenu(menu)
        val actions = (0 until menu.size()).map { menu.getItem(it) }
            .filter { it.isVisible && it.itemId != R.id.action_apply }
            .map { item -> item.title.toString() to { this@ConfigSettingActivity.onOptionsItemSelected(item); Unit } }
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
                io.nekohasekai.sagernet.ui.compose.CustomConfigProfileScreen(
                    onSave = { io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher { saveAndExit() } },
                    onClose = { this@ConfigSettingActivity.onBackPressed() },
                    menuItems = actions
                )
            }
        }
    }
}
