package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.Build
import com.google.android.material.color.MaterialColors
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.database.RouteProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Card-based selector for independent routing profiles. */
class RouteProfilesActivity : ThemedActivity() {
    private lateinit var cards: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20) + resources.getDimensionPixelSize(resources.getIdentifier("status_bar_height", "dimen", "android")).coerceAtLeast(0), dp(16), dp(12))
        }
        val header = TextView(this).apply {
            text = "Routing profiles"
            textSize = 23f
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(header)
        val add = MaterialButton(this).apply {
            text = "+ New profile"
            isAllCaps = false
            cornerRadius = dp(18)
            setOnClickListener { nameDialog(null) }
        }
        root.addView(add)
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(cards) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun perform(action: () -> Unit) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                refresh()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@RouteProfilesActivity)
                    .setMessage(e.message ?: "Operation failed")
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private fun nameDialog(profileId: String?) {
        val input = EditText(this).apply {
            hint = "Profile name"
            setSingleLine(true)
            setPadding(32, 16, 32, 16)
            if (profileId != null) {
                setText(RouteProfiles.all().firstOrNull { it.id == profileId }?.name.orEmpty())
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (profileId == null) "New routing profile" else "Rename profile")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) perform {
                    if (profileId == null) RouteProfiles.create(name)
                    else RouteProfiles.rename(profileId, name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun refresh() {
        cards.removeAllViews()
        val selected = RouteProfiles.selectedId()
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        for (profile in RouteProfiles.all()) {
            val active = profile.id == selected
            val card = MaterialCardView(this).apply {
                radius = dp(24).toFloat()
                cardElevation = 0f
                strokeWidth = if (active) dp(2) else 0
                if (Build.VERSION.SDK_INT >= 31) {
                    setCardBackgroundColor(getColor(if (active) android.R.color.system_accent1_50 else android.R.color.system_neutral2_50))
                    if (active) strokeColor = getColor(android.R.color.system_accent1_300)
                } else {
                    setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
                    if (active) strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
                }
                setContentPadding(dp(20), dp(18), dp(20), dp(18))
            }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(this).apply {
                text = profile.name + if (active) "  ✓" else ""
                textSize = 20f
            }
            column.addView(title)
            val count = RouteProfiles.ruleCount(profile)
            column.addView(TextView(this).apply {
                text = "${profile.routeFinal} · $count rules"
                textSize = 13f
            })
            val actions = LinearLayout(this).apply { gravity = Gravity.END; orientation = LinearLayout.HORIZONTAL }
            fun action(label: String, click: () -> Unit) {
                actions.addView(MaterialButton(this).apply {
                    text = label
                    textSize = 12f
                    isAllCaps = false
                    cornerRadius = dp(14)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(6), 0, dp(6), 0)
                    setOnClickListener { click() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            }
            action(if (active) "Edit rules" else "Select") {
                if (active) finish() else perform { RouteProfiles.select(profile.id) }
            }
            action("Rename") { nameDialog(profile.id) }
            if (profile.id != "default") {
                action("Delete") {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Delete ${profile.name}?")
                        .setMessage("This routing profile and its rules will be deleted.")
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            perform { RouteProfiles.delete(profile.id) }
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
            column.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            card.addView(column)
            cards.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            })
        }
    }
}
