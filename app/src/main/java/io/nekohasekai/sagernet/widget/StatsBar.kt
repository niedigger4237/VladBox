package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.ComposeView
import io.nekohasekai.sagernet.ui.compose.HubTheme
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private val statusState = mutableStateOf("")
    private val modeState = mutableStateOf("")
    private val txState = mutableStateOf("")
    private val rxState = mutableStateOf("")
    private val connectedState = mutableStateOf(false)
    private var composeInitialized = false
    var allowShow = true

    fun performShow() {
        if (allowShow) visibility = View.VISIBLE
        refreshMode()
    }

    fun performHide() {
        visibility = View.GONE
    }

    private fun refreshMode() {
        modeState.value = DataStore.serviceMode.uppercase() + " Mode"
    }

    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
        if (!composeInitialized) {
            composeInitialized = true
            val surface = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
            val foreground = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
            setCardBackgroundColor(androidx.core.graphics.ColorUtils.blendARGB(surface, foreground, 0.08f))
            findViewById<ComposeView>(R.id.stats_compose).setContent {
                HubTheme {
                    Column(Modifier.padding(start = 4.dp, end = 8.dp)) {
                        Text((if (connectedState.value) "●  " else "") + statusState.value,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(modeState.value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(rxState.value, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f, false))
                            Text(txState.value, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f, false))
                        }
                    }
                }
            }
        }
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        statusState.value = text.toString()
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        refreshMode()
        val connected = state == BaseService.State.Connected
        connectedState.value = connected
        statusText.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (connected) android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                val size = (8 * resources.displayMetrics.density).toInt()
                setSize(size, size)
                setColor(if (androidx.core.graphics.ColorUtils.calculateLuminance(com.google.android.material.color.MaterialColors.getColor(this@StatsBar, com.google.android.material.R.attr.colorSurface)) < 0.5) android.graphics.Color.parseColor("#A5D5B1") else android.graphics.Color.parseColor("#386A49"))
            } else null, null, null, null
        )
        statusText.compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
        if (state == BaseService.State.Connected) {
            postWhenStarted {
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
            }
        } else {
            postWhenStarted {
                if (allowShow) performShow() else performHide()
            }
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
        txState.value = txText.text.toString()
        rxState.value = rxText.text.toString()
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_testing))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
