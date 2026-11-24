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
    val connected: Boolean = false
)

class ConnectionViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ConnectionUiState())
    val state = mutableState.asStateFlow()

    fun updateStatus(value: String) { mutableState.update { it.copy(status = value) } }
    fun updateMode(value: String) { mutableState.update { it.copy(mode = value) } }
    fun updateConnected(value: Boolean) { mutableState.update { it.copy(connected = value) } }
    fun updateSpeed(tx: String, rx: String) { mutableState.update { it.copy(tx = tx, rx = rx) } }
}
