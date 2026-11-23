package io.nekohasekai.sagernet.ui

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RouteProfiles
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutEmptyRouteBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.UndoSnackbarManager

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    private lateinit var routeBackCallback: OnBackPressedCallback
    private var showingRules = false
    private lateinit var profileHome: LinearLayout
    lateinit var activity: MainActivity
    lateinit var ruleListView: RecyclerView
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = requireActivity() as MainActivity
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() { renderProfileHome() }
            }.also { routeBackCallback = it })

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_route)
        toolbar.subtitle = "Default"
        toolbar.inflateMenu(R.menu.add_route_menu)
        toolbar.menu.add(0, 0x741231, 0, "Profiles").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener(this)

        val content = view.findViewById<LinearLayout>(R.id.route_content)
        profileHome = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        content.addView(ScrollView(requireContext()).apply { addView(profileHome) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        ruleListView = view.findViewById(R.id.route_list)
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)
        ruleAdapter = RuleAdapter()
        ProfileManager.addListener(ruleAdapter)
        ruleListView.adapter = ruleAdapter
        undoManager = UndoSnackbarManager(activity, ruleAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index <= 0) return
                val rule = (viewHolder as RuleAdapter.RuleHolder).rule
                // Persist the swipe immediately; the snackbar only offers restoration.
                val deleted = SagerDatabase.rulesDao.deleteById(rule.id)
                if (deleted > 0) {
                    ruleAdapter.remove(index)
                    undoManager.remove(index to rule)
                    needReload()
                } else {
                    ruleAdapter.notifyItemChanged(index)
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target is RuleAdapter.DocumentHolder) {
                    false
                } else {
                    ruleAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    true
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                ruleAdapter.commitMove()
            }
        }).attachToRecyclerView(ruleListView)
    }

    override fun onResume() {
        super.onResume()
        if (showingRules) {
            toolbar.subtitle = RouteProfiles.all().firstOrNull { it.id == RouteProfiles.selectedId() }?.name ?: "Default"
            if (::ruleAdapter.isInitialized) runOnDefaultDispatcher { ruleAdapter.reload() }
        } else renderProfileHome()
    }


    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun openRules() {
        showingRules = true
        routeBackCallback.isEnabled = true
        (profileHome.parent as View).visibility = View.GONE
        ruleListView.visibility = View.VISIBLE
        toolbar.setTitle(R.string.menu_route)
        toolbar.subtitle = RouteProfiles.all().firstOrNull { it.id == RouteProfiles.selectedId() }?.name
        runOnDefaultDispatcher { ruleAdapter.reload() }
    }

    private fun profileAction(block: () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                renderProfileHome()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(e.message ?: "Operation failed")
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private fun profileNameDialog(id: String? = null) {
        val input = EditText(requireContext()).apply {
            hint = "Profile name"
            setSingleLine()
            setText(RouteProfiles.all().firstOrNull { it.id == id }?.name.orEmpty())
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (id == null) "New routing profile" else "Rename profile")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) profileAction {
                    if (id == null) RouteProfiles.create(name) else RouteProfiles.rename(id, name)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun renderProfileHome() {
        if (!::profileHome.isInitialized) return
        showingRules = false
        routeBackCallback.isEnabled = false
        toolbar.subtitle = null
        toolbar.title = "Routing profiles"
        ruleListView.visibility = View.GONE
        (profileHome.parent as View).visibility = View.VISIBLE
        profileHome.removeAllViews()
        profileHome.addView(MaterialButton(requireContext()).apply {
            text = "+ New profile"
            setOnClickListener { profileNameDialog() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(16) })
        val selected = RouteProfiles.selectedId()
        RouteProfiles.all().forEach { profile ->
            val active = profile.id == selected
            val card = MaterialCardView(requireContext()).apply {
                radius = dp(18).toFloat()
                setContentPadding(dp(18), dp(16), dp(18), dp(16))
                isCheckable = true
                isChecked = active
            }
            val column = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(requireContext()).apply {
                text = profile.name + if (active) "  ✓" else ""
                textSize = 20f
            })
            column.addView(TextView(requireContext()).apply {
                text = "${if (active) DataStore.routeFinal else profile.routeFinal} · ${if (active) ruleAdapter.ruleList.size else profile.rules.size} rules"
                textSize = 13f
            })
            val actions = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            fun addAction(label: String, action: () -> Unit) {
                actions.addView(MaterialButton(requireContext()).apply {
                    text = label
                    textSize = 12f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(8), 0, dp(8), 0)
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
            }
            addAction(if (active) "Edit rules" else "Select") {
                if (active) openRules() else profileAction { RouteProfiles.select(profile.id) }
            }
            addAction("Rename") { profileNameDialog(profile.id) }
            if (profile.id != "default") addAction("Delete") {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete ${profile.name}?")
                    .setMessage("This routing profile and its rules will be deleted.")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        profileAction { RouteProfiles.delete(profile.id) }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
            column.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            card.addView(column)
            profileHome.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
    }

    override fun onDestroy() {
        if (::ruleAdapter.isInitialized) {
            ProfileManager.removeListener(ruleAdapter)
        }
        super.onDestroy()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            0x741231 -> renderProfileHome()
            R.id.action_new_route -> {
                startActivity(Intent(context, RouteSettingsActivity::class.java))
            }
            R.id.action_reset_route -> {
                MaterialAlertDialogBuilder(activity).setTitle(R.string.confirm)
                    .setMessage(R.string.clear_profiles_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            SagerDatabase.rulesDao.reset()
                            DataStore.rulesFirstCreate = false
                            ruleAdapter.reload()
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
        return true
    }

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        val ruleList = ArrayList<RuleEntity>()
        suspend fun reload() {
            val rules = ProfileManager.getRules()
            ruleListView.post {
                ruleList.clear()
                ruleList.addAll(rules)
                ruleAdapter.notifyDataSetChanged()
            }
        }

        init {
            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                DocumentHolder(LayoutEmptyRouteBinding.inflate(layoutInflater, parent, false))
            } else {
                RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return 0
            return 1
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is DocumentHolder) {
                holder.bind()
            } else if (holder is RuleHolder) {
                holder.bind(ruleList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return ruleList.size + 1
        }

        override fun getItemId(position: Int): Long {
            if (position == 0) return 0L
            return ruleList[position - 1].id
        }

        private val updated = HashSet<RuleEntity>()
        fun move(from: Int, to: Int) {
            val first = ruleList[from - 1]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from - 1 until to - 1) else Pair(-1, to downTo from - 1)
            for (i in range) {
                val next = ruleList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                ruleList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            ruleList[to - 1] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            if (updated.isNotEmpty()) {
                SagerDatabase.rulesDao.updateRules(updated.toList())
                updated.clear()
                needReload()
            }
        }

        fun remove(index: Int) {
            ruleList.removeAt(index - 1)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            for ((index, item) in actions) {
                // Keep the original ID and order so references and rule priority survive Undo.
                SagerDatabase.rulesDao.createRule(item)
                val insertAt = (index - 1).coerceIn(0, ruleList.size)
                ruleList.add(insertAt, item)
                notifyItemInserted(insertAt + 1)
            }
            needReload()
        }

        // Deletion was already persisted in onSwiped.
        override fun commit(actions: List<Pair<Int, RuleEntity>>) = Unit

        override suspend fun onAdd(rule: RuleEntity) {
            ruleListView.post {
                ruleList.add(rule)
                ruleAdapter.notifyItemInserted(ruleList.size)
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            val index = ruleList.indexOfFirst { it.id == rule.id }
            if (index == -1) return
            ruleListView.post {
                ruleList[index] = rule
                ruleAdapter.notifyItemChanged(index + 1)
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            val index = ruleList.indexOfFirst { it.id == ruleId }
            if (index == -1) {
                onMainDispatcher {
                    needReload()
                }
            } else ruleListView.post {
                ruleList.removeAt(index)
                ruleAdapter.notifyItemRemoved(index + 1)
                needReload()
            }
        }

        override suspend fun onCleared() {
            ruleListView.post {
                ruleList.clear()
                ruleAdapter.notifyDataSetChanged()
                needReload()
            }
        }

        inner class DocumentHolder(val binding: LayoutEmptyRouteBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind() {
                val ctx = binding.root.context
                binding.tabOutboundValue.text =
                    ctx.getString(if (DataStore.routeFinal == "direct") R.string.route_direct else R.string.route_proxy)
                binding.tabDnsValue.text =
                    ctx.getString(if (DataStore.dnsMain == "direct") R.string.route_direct else R.string.route_remote)
                binding.tabOutbound.setOnClickListener {
                    val values = arrayOf("proxy", "direct")
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.route_outbound)
                        .setSingleChoiceItems(
                            arrayOf(getString(R.string.route_proxy), getString(R.string.route_direct)),
                            values.indexOf(DataStore.routeFinal).coerceAtLeast(0)
                        ) { d, which ->
                            DataStore.routeFinal = values[which]
                            d.dismiss()
                            notifyItemChanged(0)
                            needReload()
                        }.setNegativeButton(R.string.no, null).show()
                }
                binding.tabDns.setOnClickListener {
                    val values = arrayOf("remote", "direct")
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.route_dns_tunnel)
                        .setSingleChoiceItems(
                            arrayOf(getString(R.string.route_remote), getString(R.string.route_direct)),
                            values.indexOf(DataStore.dnsMain).coerceAtLeast(0)
                        ) { d, which ->
                            DataStore.dnsMain = values[which]
                            d.dismiss()
                            notifyItemChanged(0)
                            needReload()
                        }.setNegativeButton(R.string.no, null).show()
                }
                binding.tabAssets.setOnClickListener {
                    startActivity(Intent(requireContext(), AssetsActivity::class.java))
                }
            }
        }

        inner class RuleHolder(binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {

            lateinit var rule: RuleEntity
            val profileName = binding.profileName
            val profileType = binding.profileType
            val routeOutbound = binding.routeOutbound
            val editButton = binding.edit
            val shareLayout = binding.share
            val enableSwitch = binding.enable

            fun bind(ruleEntity: RuleEntity) {
                rule = ruleEntity
                profileName.text = rule.displayName()
                profileType.text = rule.mkSummary()
                routeOutbound.text = rule.displayOutbound()
                itemView.setOnClickListener {
                    enableSwitch.performClick()
                }
                enableSwitch.isChecked = rule.enabled
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    runOnDefaultDispatcher {
                        rule.enabled = isChecked
                        SagerDatabase.rulesDao.updateRule(rule)
                        onMainDispatcher {
                            needReload()
                        }
                    }
                }
                editButton.setOnClickListener {
                    startActivity(Intent(it.context, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id)
                    })
                }
            }
        }

    }

}