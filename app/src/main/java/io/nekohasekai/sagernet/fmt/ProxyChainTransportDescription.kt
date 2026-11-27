package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.ProxyChainTransportPolicy.Network
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean

internal fun describeChainTransport(profile: ProxyEntity): ProxyChainTransportPolicy.Hop {
    val bean = profile.requireBean()
    val name = "${profile.displayType()} (${profile.id})"
    // Custom overrides can change transport and network; don't assume their semantics.
    if (bean is ConfigBean || bean.customOutboundJson.isNotBlank() || bean.customConfigJson.isNotBlank())
        return ProxyChainTransportPolicy.Hop(name, null, null)
    val tcp = Network.TCP
    val udp = Network.UDP
    val tcpOnly = setOf(tcp)
    fun restricted(value: String?): Set<Network>? = when (value) {
        "tcp" -> tcpOnly
        "udp" -> setOf(udp)
        else -> null
    }
    val required = when (bean) {
        is NaiveBean -> if (bean.proto == "quic") udp else if (bean.proto == "https") tcp else null
        is HysteriaBean -> if (!profile.needExternal()) udp else null
        is WireGuardBean, is TuicBean -> udp
        is MieruBean -> when (bean.protocol) { "TCP" -> tcp; "UDP" -> udp; else -> null }
        is HttpBean, is ShadowTLSBean, is SSHBean, is SOCKSBean, is TrojanGoBean -> tcp
        is StandardV2RayBean -> when (bean.type) {
            "quic", "kcp" -> udp
            "tcp", "ws", "http", "grpc", "httpupgrade" -> tcp
            else -> null
        }
        else -> null
    }
    val provides = when (bean) {
        is NaiveBean -> if (bean.sUoT) null else tcpOnly
        is HttpBean, is SSHBean, is ShadowTLSBean -> tcpOnly
        is SOCKSBean -> if (bean.protocol != SOCKSBean.PROTOCOL_SOCKS5) tcpOnly else null
        is HysteriaBean -> if (bean.protocolVersion == 2) restricted(bean.hy2Network) else null
        is TuicBean -> restricted(bean.tuicNetwork)
        is StandardV2RayBean -> restricted(bean.vlessNetwork)
        else -> null
    }
    return ProxyChainTransportPolicy.Hop(name, required, provides)
}
