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
    val serviceState: io.nekohasekai.sagernet.bg.BaseService.State = io.nekohasekai.sagernet.bg.BaseService.State.Stopped
)

class ConnectionViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ConnectionUiState())
    val state = mutableState.asStateFlow()

    fun updateServiceState(value: io.nekohasekai.sagernet.bg.BaseService.State) { mutableState.update { it.copy(serviceState = value) } }
    fun updateStatus(value: String) { mutableState.update { it.copy(status = value) } }
    fun updateMode(value: String) { mutableState.update { it.copy(mode = value) } }
    fun updateConnected(value: Boolean) { mutableState.update { it.copy(connected = value) } }
    fun updateSpeed(tx: String, rx: String, txBytes: Long = 0, rxBytes: Long = 0) { mutableState.update { it.copy(tx = tx, rx = rx, txBytes = txBytes, rxBytes = rxBytes) } }
}
