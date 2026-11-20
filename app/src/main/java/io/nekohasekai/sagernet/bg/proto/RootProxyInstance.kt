package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.root.RootCore
import io.nekohasekai.sagernet.bg.root.RootDaemon
import io.nekohasekai.sagernet.bg.root.RootShell
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.ktx.Logs

/**
 * Runs the profile in an external sing-box executed as root instead of the in-process core.
 * asteriskd (RootDaemon) starts the core and owns all routing/firewall state:
 *  - Key.MODE_ROOT_TUN:    tun inbound (auto_route + auto_redirect) created by sing-box, device asterisk0
 *  - Key.MODE_ROOT_TPROXY: tproxy inbound + asteriskd's iptables/policy-routing rules
 *  - Key.MODE_ROOT_TUN2SOCKS / MODE_ROOT_BPF2SOCKS: the mixed (SOCKS) inbound fed by hev-socks5-tunnel / bpf2socks
 */
class RootProxyInstance(profile: ProxyEntity, service: BaseService.Interface?) :
    ProxyInstance(profile, service) {

    private var started = false

    override suspend fun loadConfig() {
        // no in-process core: `box` stays uninitialised
    }

    override fun startCore() {
        if (!RootShell.isAvailable()) throw IllegalStateException("Root access was denied")

        val core = RootCore.resolve() ?: throw IllegalStateException("No sing-box core for root mode")
        Logs.i("root core: ${core.label} (${core.path})")

        val mode = when (DataStore.serviceMode) {
            Key.MODE_ROOT_TPROXY -> RootDaemon.Mode.TPROXY
            Key.MODE_ROOT_TUN2SOCKS -> RootDaemon.Mode.TUN2SOCKS
            Key.MODE_ROOT_BPF2SOCKS -> RootDaemon.Mode.BPF2SOCKS
            else -> RootDaemon.Mode.TUN
        }
        val ipv6 = DataStore.ipv6Mode != io.nekohasekai.sagernet.IPv6Mode.DISABLE
        var err = RootDaemon.start(mode, core, config.config, DataStore.rootTproxyPort, ipv6)
        if (err != null && !core.bundled && RootCore.bundled.exists()) {
            // an installed (unpatched / incompatible) core must not leave the user without root mode
            Logs.w("core ${core.label} failed: $err\nretrying with the bundled core")
            err = RootDaemon.start(
                mode, RootCore.Active(RootCore.bundled.absolutePath, "bundled", true),
                config.config, DataStore.rootTproxyPort, ipv6
            )
        }
        if (err != null) throw IllegalStateException(err)
        started = true
    }

    override fun close() {
        // asteriskd removes its rules before it stops the core
        if (started) {
            RootDaemon.shutdown()
            started = false
        }
        super.close()
    }
}
