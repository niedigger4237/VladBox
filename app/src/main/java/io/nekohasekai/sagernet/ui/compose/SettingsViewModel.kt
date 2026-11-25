package io.nekohasekai.sagernet.ui.compose

import androidx.lifecycle.ViewModel
import io.nekohasekai.sagernet.bg.root.RootShell.Access
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val revision: Int = 0,
    val category: String? = null,
    val query: String = "",
    val rootAccess: Access = Access.UNKNOWN,
    val checkingRoot: Boolean = false,
)

class SettingsViewModel : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUiState())
    val state = mutable.asStateFlow()
    fun beginRootCheck(): Boolean {
        if (mutable.value.checkingRoot) return false
        mutable.update { it.copy(checkingRoot = true) }
        return true
    }
    fun finishRootCheck(access: Access) = mutable.update {
        it.copy(rootAccess = access, checkingRoot = false, revision = it.revision + 1)
    }
    fun refresh() = mutable.update { it.copy(revision = it.revision + 1) }
    fun select(category: String?) = mutable.update { it.copy(category = category, query = "") }
    fun search(query: String) = mutable.update { it.copy(query = query) }
}

private val rootOnlySettings = setOf(
    "rootTproxyPort", "bpfBridgePort", "rootTunInterfaces", "rootTunBypassRuleSets",
    "rootHevMultiQueue", "rootHevTcpFastOpen", "rootFlowMetadata", "rootEbpfMatcher",
    "rootIgnoredInterfaces", "tetherWifi", "tetherBluetooth", "tetherUsb", "tetherEthernet",
    "tetherCustom", "serviceControl", "startOnBoot", "rootCoreUpdate", "rootCoreImport",
    "rootCoreRollback", "rootDisableSystemIpv6"
)

internal fun settingVisible(key: String?, mode: String, access: Access = Access.UNKNOWN): Boolean {
    if (access == Access.UNAVAILABLE && key in rootOnlySettings) return false
    return when (key) {
    "rootTproxyPort" -> mode == "root_tproxy"
    "bpfBridgePort" -> mode == "root_bpf2socks"
    "rootTunInterfaces", "rootTunBypassRuleSets" -> mode == "root_tun"
    "rootHevMultiQueue", "rootHevTcpFastOpen" -> mode == "root_tun2socks"
    "rootFlowMetadata" -> mode == "root_tun2socks" || mode == "root_bpf2socks"
    "rootEbpfMatcher" -> mode == "root_tproxy" || mode == "root_tun2socks"
    "rootIgnoredInterfaces" -> mode.startsWith("root_")
    "tetherWifi", "tetherBluetooth", "tetherUsb", "tetherEthernet", "tetherCustom" ->
        mode == "vpn" || mode.startsWith("root_")
    "serviceControl", "startOnBoot", "rootCoreUpdate", "rootCoreImport", "rootCoreRollback", "rootDisableSystemIpv6" -> mode.startsWith("root_")
    "meteredNetwork", "appendHttpProxy" -> mode == "vpn"
    "tunImplementation", "mtu" -> mode == "vpn" || mode == "root_tun" || mode == "root_tun2socks"
    else -> true
}
}
