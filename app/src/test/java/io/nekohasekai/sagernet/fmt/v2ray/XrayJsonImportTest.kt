package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XrayJsonImportTest {
    private val id = "00000000-0000-4000-8000-000000000001"

    @Test fun flatRealityXhttp() {
        val json = JSONObject("""{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"example.org","port":443,"id":"$id","encryption":"none"},"streamSettings":{"network":"xhttp","security":"reality","realitySettings":{"serverName":"example.net","fingerprint":"firefox","password":"test-key","shortId":"abcd"},"xhttpSettings":{"path":"/test","mode":"auto","extra":{"xmux":{"maxConcurrency":"8-16"}}}}},{"protocol":"freedom","tag":"direct"}]}""")
        assertTrue(XrayJsonImport.recognizes(json))
        val b = XrayJsonImport.parse(json).single() as VMessBean
        assertEquals(-1, b.alterId.toInt())
        assertEquals(id, b.uuid)
        assertEquals("xhttp", b.type)
        assertEquals("test-key", b.realityPubKey)
        assertEquals("auto", b.effectiveRealityHybridMode())
        assertEquals("stream-one", XhttpExtra.resolveMode(b.xhttpMode, true, false))
        assertEquals("8-16", (XhttpExtra.toSingBox(b.xhttpExtra, b.xhttpMode, true)["xmux"] as Map<*, *>)["max_concurrency"])
    }

    @Test fun legacyVmessAndTrojan() {
        val vmess = JSONObject("""{"protocol":"vmess","settings":{"vnext":[{"address":"example.org","port":443,"users":[{"id":"$id","security":"auto","alterId":0}]}]},"streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"example.net","alpn":["h2","http/1.1"]},"wsSettings":{"path":"/ws","headers":{"Host":"example.net","X-Test":"yes"}}}}""")
        val b = XrayJsonImport.parse(vmess).single() as VMessBean
        assertEquals("example.net", b.host)
        assertEquals("X-Test: yes", b.transportHeaders)
        assertEquals("h2,http/1.1", b.alpn)
        val t = XrayJsonImport.parse(JSONObject("""{"protocol":"trojan","settings":{"servers":[{"address":"example.org","port":443,"password":"test"}]},"streamSettings":{"security":"tls"}}""")).single() as TrojanBean
        assertEquals("test", t.password)
    }

    @Test fun refusesChainAndNonEquivalentMux() {
        val b = JSONObject("""{"protocol":"vless","settings":{"address":"example.org","port":443,"id":"$id","encryption":"none"},"proxySettings":{"tag":"hop"}}""")
        assertTrue(runCatching { XrayJsonImport.parse(b) }.isFailure)
        b.remove("proxySettings")
        b.put("mux", JSONObject().put("enabled", true))
        assertTrue(runCatching { XrayJsonImport.parse(b) }.isFailure)
    }

    private fun withSocket(socket: String): VMessBean {
        return XrayJsonImport.parse(JSONObject("""{"protocol":"vless","settings":{"address":"example.org","port":443,"id":"$id"},"streamSettings":{"sockopt":$socket}}""")).single() as VMessBean
    }

    @Test fun ignoresXraySocketDnsAndHappyEyeballs() {
        for (strategy in listOf("AsIs", "ForceIPv4", "UseIPv6v4", "future-strategy")) {
            val b = withSocket("""{"domainStrategy":"$strategy","happyEyeballs":{"prioritizeIPv6":true,"tryDelayMs":250,"interleave":2,"maxConcurrentTry":4}}""")
            assertEquals(0, JSONObject(b.outboundDialer.ifBlank { "{}" }).length())
        }
    }

    @Test fun retainsSupportedSocketOptions() {
        val b = withSocket("""{"domainStrategy":"ForceIPv4","happyEyeballs":{"tryDelayMs":250},"tcpFastOpen":true,"tcpKeepAliveIdle":30,"tcpKeepAliveInterval":10}""")
        val dialer = JSONObject(b.outboundDialer)
        assertEquals(3, dialer.length())
        assertTrue(dialer.getBoolean("tcp_fast_open"))
        assertEquals("30s", dialer.getString("tcp_keep_alive"))
        assertEquals("10s", dialer.getString("tcp_keep_alive_interval"))
    }

    @Test fun stillRejectsOtherUnsupportedSocketOptions() {
        assertTrue(runCatching { withSocket("""{"domainStrategy":"AsIs","customSockopt":[{"opt":"1"}]}""") }.isFailure)
    }

    @Test fun prefersExplicitProfileName() {
        val outbound = JSONObject("""{"protocol":"vless","tag":"proxy","remarks":"Swiss VPN","name":"Other","settings":{"address":"example.org","port":443,"id":"$id"}}""")
        assertEquals("Swiss VPN", XrayJsonImport.parse(outbound, "file.json").single().name)
        outbound.remove("remarks")
        assertEquals("Other", XrayJsonImport.parse(outbound, "file.json").single().name)
        outbound.remove("name")
        val full = JSONObject().put("remarks", "Config title").put("outbounds", org.json.JSONArray().put(outbound))
        assertEquals("Config title", XrayJsonImport.parse(full, "file.json").single().name)
    }

    @Test fun genericTagFallsBackToFilenameOrEndpoint() {
        val outbound = JSONObject("""{"protocol":"vless","tag":"proxy","settings":{"address":"example.org","port":443,"id":"$id"}}""")
        assertEquals("My VPN", XrayJsonImport.parse(outbound, "My VPN.JSON").single().name)
        assertEquals("VLESS — example.org", XrayJsonImport.parse(outbound).single().name)
        outbound.put("tag", "Zurich")
        assertEquals("Zurich", XrayJsonImport.parse(outbound).single().name)
    }

    @Test fun nativeSingBoxNotMisidentified() {
        assertFalse(XrayJsonImport.recognizes(JSONObject("""{"outbounds":[{"type":"vless","server":"example.org","server_port":443}]}""")))
    }
}
