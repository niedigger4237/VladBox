package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import io.nekohasekai.sagernet.ui.compose.ConnectionViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import io.nekohasekai.sagernet.ui.compose.ConnectionPower
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
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.isRawCoreConfig
import io.nekohasekai.sagernet.bg.proto.ConnectionCountry
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
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
    private val model by lazy { ViewModelProvider(context as MainActivity)[ConnectionViewModel::class.java] }
    private var composeInitialized = false
    private val countryCode = mutableStateOf<String?>(null)
    private val countryLoading = mutableStateOf(false)
    private var countryJob: kotlinx.coroutines.Job? = null
    private var countryProfile: Long? = null
    private var countryMode: String? = null
    private fun countryView(): ComposeView? = (parent as? View)?.findViewById(R.id.connection_country)

    private fun syncCountryVisibility() {
        countryView()?.visibility = if (allowShow && visibility == View.VISIBLE && DataStore.serviceState.connected) View.VISIBLE else View.GONE
    }

    fun refreshConnectionCountry(force: Boolean = false) {
        val activity = context as MainActivity
        if (!DataStore.serviceState.connected) {
            countryJob?.cancel()
            countryProfile = null
            countryMode = null
            countryCode.value = null
            countryLoading.value = false
            syncCountryVisibility()
            return
        }
        val profile = DataStore.currentProfile
        val mode = DataStore.serviceMode
        if (!force && countryProfile == profile && countryMode == mode) return
        countryJob?.cancel()
        countryProfile = profile
        countryMode = mode
        countryCode.value = null
        countryLoading.value = true
        syncCountryVisibility()
        countryJob = activity.lifecycleScope.launch {
            try {
                delay(350L)
                var code = ConnectionCountry.lookup()
                if (code == null) {
                    delay(700L)
                    code = ConnectionCountry.lookup()
                }
                if (DataStore.serviceState.connected && DataStore.currentProfile == profile && DataStore.serviceMode == mode) {
                    countryCode.value = code
                    countryLoading.value = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (countryProfile == profile && countryMode == mode) countryLoading.value = false
            }
        }
    }
    var allowShow = true
    var onPowerClick: () -> Unit = {}
    private val powerVisible = androidx.compose.runtime.mutableStateOf(true)
    fun setPowerVisible(visible: Boolean) { powerVisible.value = visible }

    fun performShow() {
        powerVisible.value = true
        if (allowShow) visibility = View.VISIBLE
        syncCountryVisibility()
        refreshMode()
    }

    fun performHide() {
        visibility = View.GONE
        syncCountryVisibility()
    }

    private fun refreshMode() {
        model.updateMode(DataStore.serviceMode.uppercase() + " Mode")
    }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener(l)
        if (!composeInitialized) {
            composeInitialized = true
            countryView()?.apply {
                setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    HubTheme {
                        val code = countryCode.value
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f)) {
                                Row(Modifier.clickable { refreshConnectionCountry(force = true) }.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    if (code != null) {
                                        Text(ConnectionCountry.flag(code), fontSize = 18.sp)
                                        Text(ConnectionCountry.name(code), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    } else {
                                        if (countryLoading.value) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                                        else Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_dash_symbol_public), contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text(stringResource(if (countryLoading.value) R.string.connection_country_loading else R.string.connection_country_unknown), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val surface = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
            val foreground = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
            setCardBackgroundColor(androidx.core.graphics.ColorUtils.blendARGB(surface, foreground, 0.08f))
            findViewById<ComposeView>(R.id.stats_compose).setContent {
                val ui = model.state.collectAsState().value
                HubTheme {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.fillMaxWidth().clickable { performClick() },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text((if (ui.connected) "●  " else "") + ui.status,
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Text(ui.mode, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SpeedIndicator("Download", ui.rx, false, Modifier.weight(1f).clickable { performClick() })
                            Box(Modifier.size(76.dp)) { if (powerVisible.value) ConnectionPower(ui.serviceState, onPowerClick) }
                            SpeedIndicator("Upload", ui.tx, true, Modifier.weight(1f).clickable { performClick() })
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SpeedIndicator(label: String, speed: String, upload: Boolean, modifier: Modifier) {
        val tint = MaterialTheme.colorScheme.primary
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Canvas(Modifier.size(20.dp)) {
                val x = size.width / 2
                val top = size.height * .2f
                val bottom = size.height * .8f
                val tip = if (upload) top else bottom
                val shoulder = if (upload) size.height * .45f else size.height * .55f
                val stroke = 2.dp.toPx()
                drawLine(tint, Offset(x, top), Offset(x, bottom), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, Offset(x, tip), Offset(size.width * .25f, shoulder), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, Offset(x, tip), Offset(size.width * .75f, shoulder), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Text(speed.trimStart('▲', '▼', ' ').ifBlank { "0 B/s" }, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    private fun setStatus(text: CharSequence) {
        model.updateStatus(text.toString())
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
        model.updateConnected(connected)
        refreshConnectionCountry()
        if (!connected) connectionTestJob?.cancel()
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
        val tx = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        val rx = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
        model.updateSpeed(tx, rx, txRate, rxRate)
    }

    private var connectionTestJob: kotlinx.coroutines.Job? = null

    fun testConnection() {
        val activity = context as MainActivity
        if (connectionTestJob?.isActive == true) return
        if (!DataStore.serviceState.connected) return
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        connectionTestJob = activity.lifecycleScope.launch {
            try {
                val url = DataStore.connectionTestURL
                val elapsed = kotlinx.coroutines.withTimeout(io.nekohasekai.sagernet.bg.proto.LATENCY_REQUEST_TIMEOUT_MS) {
                    val id = DataStore.currentProfile
                    val raw = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        io.nekohasekai.sagernet.database.SagerDatabase.proxyDao.getById(id)?.isRawCoreConfig() == true
                    }
                    if (raw) kotlinx.coroutines.withContext(Dispatchers.IO) { activity.urlTest() }
                    else io.nekohasekai.sagernet.bg.proto.activeProfileDelay(id, url)
                }
                if (DataStore.serviceState.connected) {
                    setStatus(app.getString(
                        if (url.startsWith("https://", true)) R.string.connection_test_available
                        else R.string.connection_test_available_http, elapsed))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Logs.w(e.toString())
                if (DataStore.serviceState.connected) {
                    val message = app.getString(R.string.connection_test_error, e.readableMessage)
                    setStatus(message)
                    activity.snackbar(message).show()
                }
            } finally {
                isEnabled = true
            }
        }
    }
}
