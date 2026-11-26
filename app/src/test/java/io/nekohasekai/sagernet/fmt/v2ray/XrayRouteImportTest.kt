package io.nekohasekai.sagernet.fmt.v2ray

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XrayRouteImportTest {
    private val direct = XrayRouteImport.Target(special = -1L)
    private val block = XrayRouteImport.Target(special = -2L)
    private val targets = mapOf("direct" to direct, "block" to block)

    @Test fun selectorsAndOrder() {
        val root = JSONObject("""{"routing":{"domainStrategy":"AsIs","rules":[{"type":"field","domain":["example","domain:example.org","full:one.example.org","regexp:^A","geosite:RU"],"outboundTag":"direct"},{"type":"field","ip":["geoip:RU","192.0.2.1"],"port":"80,443,1000-2000","outboundTag":"block"}]}}""")
        val p = requireNotNull(XrayRouteImport.plan(root,"test",targets,direct))
        assertEquals("keyword:example\ndomain:example.org\nfull:one.example.org\nregexp:^A\ngeosite:ru",p.rules[0].rule.domains)
        assertEquals("geoip:ru\n192.0.2.1/32",p.rules[1].rule.ip)
        assertEquals("80\n443\n1000:2000",p.rules[1].rule.port)
        assertEquals(listOf(1L,2L),p.rules.map { it.rule.userOrder })
        assertEquals(block,p.rules[1].target)
    }

    @Test fun directDns() {
        val p = XrayRouteImport.plan(JSONObject("""{"dns":{"servers":["https+local://dns.example.org/dns-query"],"queryStrategy":"UseIPv4"}}"""),"test",targets,direct)!!
        assertEquals("https://dns.example.org/dns-query",p.dns!!.address)
        assertTrue(p.dns!!.direct)
        assertEquals("ipv4_only",p.dns!!.strategy)
    }

    @Test fun refusesLossyConversion() {
        for (raw in listOf(
            """{"routing":{"domainStrategy":"IPOnDemand"}}""",
            """{"routing":{"rules":[{"domain":["example"],"ip":["geoip:ru"],"outboundTag":"direct"}]}}""",
            """{"routing":{"rules":[{"domain":["geosite:ru@ads"],"outboundTag":"direct"}]}}""",
            """{"dns":{"servers":["1.1.1.1","8.8.8.8"]}}""",
            """{"routing":{"rules":[{"domain":["example"],"outboundTag":"missing"}]}}"""
        )) assertTrue(raw,runCatching { XrayRouteImport.plan(JSONObject(raw),"test",targets,direct) }.isFailure)
    }
}
