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

    @Test fun defaultSocketOptionsAreAccepted() {
        val b = withSocket("""{"domainStrategy":"AsIs","happyEyeballs":{"prioritizeIPv6":false,"tryDelayMs":250,"interleave":1,"maxConcurrentTry":4}}""")
        assertEquals(0, JSONObject(b.outboundDialer.ifBlank { "{}" }).length())
    }

    @Test fun socketResolverAndFallbackAreConverted() {
        val b = withSocket("""{"domainStrategy":"UseIPv6v4","happyEyeballs":{"prioritizeIPv6":true,"tryDelayMs":250,"interleave":1,"maxConcurrentTry":4}}""")
        val options = mutableMapOf<String, Any>()
        applyClientDialer(b.outboundDialer, "", options)
        assertEquals(mapOf("server" to "dns-direct", "strategy" to "prefer_ipv6"), options["domain_resolver"])
        assertEquals("250ms", options["fallback_delay"])
    }

    @Test fun forceIPv4AndDisabledRace() {
        val b = withSocket("""{"domainStrategy":"ForceIPv4","happyEyeballs":{"tryDelayMs":0}}""")
        val dialer = JSONObject(b.outboundDialer)
        assertEquals("ipv4_only", dialer.getJSONObject("domain_resolver").getString("strategy"))
        assertFalse(dialer.has("fallback_delay"))
        assertTrue(runCatching { withSocket("""{"domainStrategy":"invalid"}""") }.isFailure)
    }

    @Test fun nativeSingBoxNotMisidentified() {
        assertFalse(XrayJsonImport.recognizes(JSONObject("""{"outbounds":[{"type":"vless","server":"example.org","server_port":443}]}""")))
    }
}
