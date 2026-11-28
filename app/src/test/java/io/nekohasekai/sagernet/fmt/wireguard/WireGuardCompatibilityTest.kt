package io.nekohasekai.sagernet.fmt.wireguard

import org.junit.Assert.*
import org.junit.Test

class WireGuardCompatibilityTest {
    private val conf = """
        [Interface]
        PrivateKey = test-private
        Address = 10.20.30.2/32
        ListenPort = 51821
        Jc = 4
        Jmin = 40
        Jmax = 70
        [Peer]
        PublicKey = test-public
        Endpoint = example.org:51820
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 0
    """.trimIndent()

    @Test fun awgSingleStackAndListenPortSurviveConfRoundTrip() {
        val bean = WireGuardConf.parse(conf)
        assertTrue(bean.isAmnezia)
        assertEquals("0.0.0.0/0", bean.wgAllowedIps)
        assertEquals(51821, bean.wgListenPort.toInt())
        assertEquals(0, bean.persistentKeepalive.toInt())
        bean.name = "🇨🇭 Test #1"
        val restored = WireGuardConf.parse(WireGuardConf.export(bean))
        assertEquals(bean.name, restored.name)
        assertEquals(bean.wgAllowedIps, restored.wgAllowedIps)
        assertEquals(bean.wgListenPort, restored.wgListenPort)
        assertEquals(bean.persistentKeepalive, restored.persistentKeepalive)
    }

    @Test fun runtimeHonorsConfiguredPeerSettings() {
        val bean = WireGuardConf.parse(conf)
        val endpoint = buildSingBoxEndpointWireguardBean(bean, fastHandshakeRetry = true)
        assertEquals(listOf("0.0.0.0/0"), endpoint.peers.first().allowed_ips)
        assertEquals(51821, endpoint._hack_config_map["listen_port"])
        assertEquals(0, endpoint.peers.first().persistent_keepalive_interval ?: 0)
        assertEquals("2", endpoint.amnezia.rekey_timeout)
        bean.persistentKeepalive = 15
        bean.awgRekeyTimeout = "5"
        val explicit = buildSingBoxEndpointWireguardBean(bean, fastHandshakeRetry = true)
        assertEquals(15, explicit.peers.first().persistent_keepalive_interval)
        assertEquals("5", explicit.amnezia.rekey_timeout)
    }
}
