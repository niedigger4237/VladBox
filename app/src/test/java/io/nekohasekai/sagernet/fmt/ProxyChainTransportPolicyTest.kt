package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.ProxyChainTransportPolicy.Hop
import io.nekohasekai.sagernet.fmt.ProxyChainTransportPolicy.Network.*
import org.junit.Assert.*
import org.junit.Test

class ProxyChainTransportPolicyTest {
    @Test fun tcpOnlyCannotCarryQuic() {
        val naive = Hop("Naive", TCP, setOf(TCP))
        val hy2 = Hop("Hysteria2", UDP, null)
        val error = runCatching { ProxyChainTransportPolicy.validate(listOf(naive, hy2)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("requires UDP"))
    }

    @Test fun knownCompatibleAndUnknownAreNotRejected() {
        ProxyChainTransportPolicy.validate(listOf(Hop("WireGuard", UDP, setOf(TCP, UDP)), Hop("Naive", UDP, setOf(TCP))))
        ProxyChainTransportPolicy.validate(listOf(Hop("Custom", null, null), Hop("Hysteria2", UDP, null)))
        ProxyChainTransportPolicy.validate(listOf(Hop("Naive", TCP, setOf(TCP)), Hop("Trojan", TCP, null)))
    }

    @Test fun wireGuardAndAwgCanCarryEachOther() {
        val wg = Hop("WireGuard", UDP, setOf(TCP, UDP))
        val awg = Hop("AmneziaWG", UDP, setOf(TCP, UDP))
        ProxyChainTransportPolicy.validate(listOf(wg, awg))
        ProxyChainTransportPolicy.validate(listOf(awg, wg))
        ProxyChainTransportPolicy.validate(listOf(wg, awg, Hop("Hysteria2", UDP, null)))
        assertTrue(runCatching {
            ProxyChainTransportPolicy.validate(listOf(Hop("HTTP", TCP, setOf(TCP)), awg))
        }.exceptionOrNull()?.message?.contains("requires UDP") == true)
    }

    @Test fun fixedWireGuardPortIsOnlyAllowedAtPhysicalEntry() {
        val fixed = Hop("AmneziaWG", UDP, setOf(TCP, UDP), fixedWireGuardListenPort = 51820)
        val auto = Hop("WireGuard", UDP, setOf(TCP, UDP))
        ProxyChainTransportPolicy.validate(listOf(fixed))
        ProxyChainTransportPolicy.validate(listOf(fixed, auto))
        val error = runCatching {
            ProxyChainTransportPolicy.validate(listOf(auto, fixed))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("Listen port 51820"))
    }

    @Test fun udpOnlyCannotCarryTcp() {
        assertTrue(runCatching {
            ProxyChainTransportPolicy.validate(listOf(Hop("UDP only", UDP, setOf(UDP)), Hop("SSH", TCP, setOf(TCP))))
        }.isFailure)
    }
}
