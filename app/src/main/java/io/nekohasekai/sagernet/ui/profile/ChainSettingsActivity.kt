package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.compose.*

class ChainSettingsActivity : ProfileSettingsActivity<ChainBean>() {
    override fun createEntity() = ChainBean()
    private val profiles = mutableStateListOf<ProxyEntity>()
    private var replacing = -1
    private var loaded by androidx.compose.runtime.mutableStateOf(false)

    override fun ChainBean.init() {
        DataStore.profileName = name
        DataStore.serverProtocol = proxies.joinToString(",")
    }
    override fun ChainBean.serialize() {
        name = DataStore.profileName
        proxies = profiles.map { it.id }
        initializeDefaultValues()
    }
    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }
    private fun allowed(profile: ProxyEntity, ancestors: Set<Long>): Boolean {
        if (profile.id in ancestors) return false
        val ids = profile.chainBean?.proxies ?: return true
        val children = ProfileManager.getProfiles(ids)
        return ids.isNotEmpty() && children.size == ids.distinct().size && children.all { allowed(it, ancestors + profile.id) }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0) ?: 0
            val index = replacing
            runOnDefaultDispatcher {
                val profile = ProfileManager.getProfile(id) ?: return@runOnDefaultDispatcher
                val valid = allowed(profile, setOf(DataStore.editingId))
                onMainDispatcher {
                    if (!valid) MaterialAlertDialogBuilder(this@ChainSettingsActivity).setTitle(R.string.circular_reference).setMessage(R.string.circular_reference_sum).setPositiveButton(android.R.string.ok,null).show()
                    else {
                        if (index in profiles.indices) profiles[index] = profile else profiles.add(profile)
                        DataStore.dirty = true
                    }
                }
            }
        }
    }
    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        val legacy = listView
        val parent = legacy.parent as? android.view.ViewGroup ?: return
        val index = parent.indexOfChild(legacy)
        val params = legacy.layoutParams
        supportActionBar?.hide()
        findViewById<View>(R.id.appbar)?.visibility = View.GONE
        findViewById<View>(R.id.settings)?.let { it.layoutParams = it.layoutParams.apply { height = -1 } }
        parent.removeView(legacy)
        val host = ComposeView(requireContext())
        host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        parent.addView(host,index,params)
        host.setContent {
            HubTheme {
                ChainProfileScreen(profiles,loaded,onSave = { runOnDefaultDispatcher { saveAndExit() } },onClose = { this@ChainSettingsActivity.onBackPressed() },onPick = { i ->
                    replacing = i
                    picker.launch(Intent(this@ChainSettingsActivity,ProfileSelectActivity::class.java).apply { if (i in profiles.indices) putExtra(ProfileSelectActivity.EXTRA_SELECTED,profiles[i]) })
                },onMove = { from,to ->
                    if (from in profiles.indices && to in profiles.indices) { profiles.add(to,profiles.removeAt(from)); DataStore.dirty = true }
                },onRemove = { i -> if (i in profiles.indices) { profiles.removeAt(i); DataStore.dirty = true } })
            }
        }
        runOnDefaultDispatcher {
            val ids = DataStore.serverProtocol.split(",").mapNotNull { it.toLongOrNull() }
            val found = ProfileManager.getProfiles(ids).associateBy { it.id }
            onMainDispatcher {
                profiles.clear()
                profiles.addAll(ids.mapNotNull { found[it] })
                loaded = true
            }
        }
    }
}
