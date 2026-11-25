package io.nekohasekai.sagernet.ui.compose

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(val revision: Int = 0, val category: String? = null, val query: String = "")

class SettingsViewModel : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUiState())
    val state = mutable.asStateFlow()
    fun refresh() = mutable.update { it.copy(revision = it.revision + 1) }
    fun select(category: String?) = mutable.update { it.copy(category = category, query = "") }
    fun search(query: String) = mutable.update { it.copy(query = query) }
}

internal fun settingVisible(key: String?, mode: String): Boolean = when (key) {
    "rootTproxyPort" -> mode == "root_tproxy"
    "bpfBridgePort" -> mode == "root_bpf2socks"
    "rootTunInterfaces", "rootTunBypassRuleSets" -> mode == "root_tun"
    "rootHevMultiQueue", "rootHevTcpFastOpen" -> mode == "root_tun2socks"
    "rootFlowMetadata" -> mode == "root_tun2socks" || mode == "root_bpf2socks"
    "rootEbpfMatcher" -> mode == "root_tproxy" || mode == "root_tun2socks"
    "rootIgnoredInterfaces", "tetherWifi", "tetherBluetooth", "tetherUsb", "tetherEthernet", "tetherCustom" ->
        mode.startsWith("root_") && mode != "root_tun"
    "serviceControl", "startOnBoot", "rootCoreUpdate", "rootCoreImport", "rootCoreRollback", "rootDisableSystemIpv6" -> mode.startsWith("root_")
    "meteredNetwork", "appendHttpProxy" -> mode == "vpn"
    "tunImplementation", "mtu" -> mode == "vpn" || mode == "root_tun" || mode == "root_tun2socks"
    else -> true
}
