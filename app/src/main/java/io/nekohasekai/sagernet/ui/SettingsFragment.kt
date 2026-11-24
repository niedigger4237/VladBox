package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ListListener

class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.settings)
        if (!io.nekohasekai.sagernet.SagerNet.isTv) {
            val oldBar = view.findViewById<View>(R.id.appbar)
            val parent = oldBar.parent as android.view.ViewGroup
            val host = androidx.compose.ui.platform.ComposeView(requireContext())
            host.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            oldBar.visibility = View.GONE
            parent.addView(host, parent.indexOfChild(oldBar), android.view.ViewGroup.LayoutParams(-1, -2))
            host.setContent {
                io.nekohasekai.sagernet.ui.compose.HubTheme {
                    io.nekohasekai.sagernet.ui.compose.VladTopAppBar(
                        title = getString(R.string.settings),
                        onNavigation = { (requireActivity() as MainActivity).binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START) }
                    )
                }
            }
        }

        parentFragmentManager.beginTransaction()
            .replace(R.id.settings, if (io.nekohasekai.sagernet.SagerNet.isTv) SettingsPreferenceFragment() else NativeSettingsFragment())
            .commitAllowingStateLoss()
    }

}