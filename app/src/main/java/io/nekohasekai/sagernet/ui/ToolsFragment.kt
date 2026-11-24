package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.FrameLayout

/** Backup owns its entire screen, including the Compose top app bar. */
class ToolsFragment : ToolbarFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext()).apply {
            id = io.nekohasekai.sagernet.R.id.tools_pager
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(view.id) == null) {
            childFragmentManager.beginTransaction()
                .replace(view.id, BackupFragment())
                .commit()
        }
    }
}
