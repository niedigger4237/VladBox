package io.nekohasekai.sagernet.ui.compose

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConnectionUiState(
    val status: String = "",
    val mode: String = "",
    val tx: String = "",
    val rx: String = "",
    val connected: Boolean = false,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val trafficHistory: List<DashboardTrafficSample> = emptyList(),
    val serviceState: io.nekohasekai.sagernet.bg.BaseService.State = io.nekohasekai.sagernet.bg.BaseService.State.Stopped
)

class ConnectionViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ConnectionUiState())
    val state = mutableState.asStateFlow()

    fun updateServiceState(value: io.nekohasekai.sagernet.bg.BaseService.State) {
        mutableState.update {
            if (!value.connected || !it.serviceState.connected)
                it.copy(serviceState = value, trafficHistory = emptyList(), txBytes = 0, rxBytes = 0)
            else it.copy(serviceState = value)
        }
    }
    fun updateStatus(value: String) { mutableState.update { it.copy(status = value) } }
    fun updateMode(value: String) { mutableState.update { it.copy(mode = value) } }
    fun updateConnected(value: Boolean) { mutableState.update { it.copy(connected = value) } }
    fun updateSpeed(tx: String, rx: String, txBytes: Long = 0, rxBytes: Long = 0) {
        val now = android.os.SystemClock.elapsedRealtime()
        mutableState.update {
            val history = if (it.serviceState.connected)
                DashboardTrafficHistory.append(it.trafficHistory, now, rxBytes, txBytes)
            else emptyList()
            it.copy(tx = tx, rx = rx, txBytes = txBytes, rxBytes = rxBytes, trafficHistory = history)
        }
    }
}
