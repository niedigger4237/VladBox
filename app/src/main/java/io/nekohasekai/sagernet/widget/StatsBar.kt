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
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.painterResource
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
    private val latencyMs = mutableStateOf<Int?>(null)
    private val latencyLoading = mutableStateOf(false)
    private val latencyFailed = mutableStateOf(false)

    fun refreshConnectionCountry(force: Boolean = false) {
        val activity = context as MainActivity
        if (!DataStore.serviceState.connected) {
            countryJob?.cancel()
            countryProfile = null
            countryMode = null
            countryCode.value = null
            countryLoading.value = false
            connectionTestJob?.cancel()
            connectionTestJob = null
            latencyMs.value = null
            latencyLoading.value = false
            latencyFailed.value = false
            return
        }
        val profile = DataStore.currentProfile
        val mode = DataStore.serviceMode
        val changed = countryProfile != profile || countryMode != mode
        if (!force && !changed) return
        if (changed) {
            connectionTestJob?.cancel()
            connectionTestJob = null
            latencyMs.value = null
            latencyLoading.value = false
            latencyFailed.value = false
        }
        countryJob?.cancel()
        countryProfile = profile
        countryMode = mode
        countryCode.value = null
        countryLoading.value = true
        if (changed) startLatencyTest(manual = false)
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
        refreshMode()
    }

    fun performHide() {
        visibility = View.GONE
    }

    private fun refreshMode() {
        model.updateMode(DataStore.serviceMode.uppercase() + " Mode")
    }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener(l)
        if (!composeInitialized) {
            composeInitialized = true
            setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            findViewById<ComposeView>(R.id.stats_compose).apply {
                setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val ui = model.state.collectAsState().value
                    HubTheme {
                        val colors = MaterialTheme.colorScheme
                        val accent = if (ui.connected) colors.tertiary else colors.primary
                        Surface(
                            shape = RoundedCornerShape(26.dp),
                            color = colors.surfaceContainerHigh,
                            border = BorderStroke(1.dp, accent.copy(alpha = .38f))
                        ) {
                            Box {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (ui.connected) CountryStrip()
                                    Column(Modifier.fillMaxWidth().clickable { performClick() },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                            if (ui.connected) Icon(painterResource(R.drawable.ic_dash_symbol_check_circle), contentDescription = null, modifier = Modifier.size(18.dp), tint = accent)
                                            Text(if (ui.connected) stringResource(R.string.connection_panel_connected) else ui.status,
                                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                        Text(ui.mode, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SpeedIndicator("Download", ui.rx, false, Modifier.weight(1f).clickable { performClick() })
                                        Box(Modifier.size(80.dp)) { if (powerVisible.value) ConnectionPower(ui.serviceState, onPowerClick, tonalRing = true) }
                                        SpeedIndicator("Upload", ui.tx, true, Modifier.weight(1f).clickable { performClick() })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun CountryStrip() {
        val colors = MaterialTheme.colorScheme
        val code = countryCode.value
        val accent = colors.tertiary
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = colors.surfaceContainerHighest,
            border = BorderStroke(1.dp, colors.outlineVariant)
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).heightIn(min = 42.dp).clickable { refreshConnectionCountry(force = true) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.secondaryContainer, modifier = Modifier.size(38.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (code != null) Text(ConnectionCountry.flag(code), fontSize = 24.sp)
                            else if (countryLoading.value) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(painterResource(R.drawable.ic_dash_symbol_public), contentDescription = null, tint = colors.onSecondaryContainer)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (code != null) ConnectionCountry.name(code) else stringResource(if (countryLoading.value) R.string.connection_country_loading else R.string.connection_country_unknown),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.connection_panel_location), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    Icon(painterResource(R.drawable.ic_dash_symbol_refresh), contentDescription = stringResource(R.string.connection_panel_refresh_country), modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
                Box(Modifier.width(1.dp).height(30.dp).background(colors.outlineVariant))
                val tint = when {
                    latencyFailed.value -> colors.error
                    latencyMs.value != null -> accent
                    else -> colors.onSurfaceVariant
                }
                Surface(onClick = { testConnection() }, enabled = !latencyLoading.value,
                    shape = RoundedCornerShape(16.dp), color = tint.copy(alpha = .12f), contentColor = tint) {
                    Row(Modifier.widthIn(min = 68.dp, max = 112.dp).heightIn(min = 42.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                        if (latencyLoading.value) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = tint)
                        else Icon(painterResource(R.drawable.ic_dash_symbol_bolt), contentDescription = stringResource(R.string.connection_test), modifier = Modifier.size(16.dp), tint = tint)
                        Text(if (latencyFailed.value) stringResource(R.string.unavailable) else latencyMs.value?.let { "$it ms" } ?: "—",
                            style = if (latencyFailed.value) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SpeedIndicator(label: String, speed: String, upload: Boolean, modifier: Modifier) {
        val colors = MaterialTheme.colorScheme
        val tint = if (upload) colors.primary else colors.tertiary
        Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHighest.copy(alpha = .72f)) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(painterResource(if (upload) R.drawable.ic_dash_symbol_upload else R.drawable.ic_dash_symbol_download),
                    contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
                Text(speed.trimStart('▲', '▼', ' ').ifBlank { "0 B/s" }, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
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

    fun testConnection() = startLatencyTest(manual = true)

    private fun startLatencyTest(manual: Boolean) {
        val activity = context as MainActivity
        if (connectionTestJob?.isActive == true || !DataStore.serviceState.connected) return
        val profile = DataStore.currentProfile
        val mode = DataStore.serviceMode
        latencyLoading.value = true
        latencyFailed.value = false
        connectionTestJob = activity.lifecycleScope.launch {
            fun stillCurrent() = DataStore.serviceState.connected && DataStore.currentProfile == profile && DataStore.serviceMode == mode
            try {
                val url = DataStore.connectionTestURL
                val elapsed = kotlinx.coroutines.withTimeout(io.nekohasekai.sagernet.bg.proto.LATENCY_REQUEST_TIMEOUT_MS) {
                    val raw = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        io.nekohasekai.sagernet.database.SagerDatabase.proxyDao.getById(profile)?.isRawCoreConfig() == true
                    }
                    if (raw) kotlinx.coroutines.withContext(Dispatchers.IO) { activity.urlTest() }
                    else io.nekohasekai.sagernet.bg.proto.activeProfileDelay(profile, url)
                }
                if (stillCurrent()) {
                    latencyMs.value = elapsed.takeIf { it > 0 }
                    latencyFailed.value = elapsed <= 0
                    if (manual) TooltipCompat.setTooltipText(this@StatsBar, app.getString(
                        if (url.startsWith("https://", true)) R.string.connection_test_available
                        else R.string.connection_test_available_http, elapsed))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Logs.w(e.toString())
                if (stillCurrent()) {
                    latencyMs.value = null
                    latencyFailed.value = true
                    if (manual) activity.snackbar(app.getString(R.string.connection_test_error, e.readableMessage)).show()
                }
            } finally {
                if (stillCurrent() && connectionTestJob === coroutineContext[kotlinx.coroutines.Job]) latencyLoading.value = false
            }
        }
    }
}
