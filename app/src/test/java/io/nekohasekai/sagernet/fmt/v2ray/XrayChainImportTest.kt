package io.nekohasekai.sagernet.fmt.v2ray

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XrayChainImportTest {
    private fun node(tag: String, next: String = "") = JSONObject().apply {
        put("tag", tag)
        put("protocol", "vless")
        put("settings", JSONObject().put("address", "$tag.example.org").put("port", 443).put("id", "00000000-0000-4000-8000-000000000001").put("encryption", "none"))
        if (next.isNotEmpty()) put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", next)))
    }
    private fun config(vararg nodes: JSONObject) = JSONObject().put("outbounds", org.json.JSONArray(nodes.toList()))

    @Test fun chainOrderAndIdentity() {
        val chains = mutableListOf<XrayJsonImport.ImportedChain>()
        val beans = XrayJsonImport.parse(config(node("exit", "middle"), node("entry"), node("middle", "entry")), onChains = { chains.addAll(it) })
        val chain = chains.single { it.name == "exit (Chain)" }
        assertEquals(listOf("entry.example.org", "middle.example.org", "exit.example.org"), chain.hops.map { it.serverAddress })
        assertSame(beans[0], chain.hops.last())
        assertEquals(2, chains.size)
    }

    @Test fun rejectsMissingAndCircularReferences() {
        for (value in listOf(config(node("a", "missing")), config(node("a", "b"), node("b", "a")), config(node("a"), node("a")))) {
            assertTrue(runCatching { XrayJsonImport.parse(value, onChains = {}) }.isFailure)
        }
    }

    @Test fun legacyTransportLayerOnly() {
        val exit = node("exit").put("proxySettings", JSONObject().put("tag", "entry").put("transportLayer", true))
        val chains = mutableListOf<XrayJsonImport.ImportedChain>()
        XrayJsonImport.parse(config(exit, node("entry")), onChains = { chains.addAll(it) })
        assertEquals("entry.example.org", chains.single().hops.first().serverAddress)
        exit.getJSONObject("proxySettings").put("transportLayer", false)
        assertTrue(runCatching { XrayJsonImport.parse(config(exit, node("entry")), onChains = {}) }.isFailure)
    }

    @Test fun subscriptionCannotFlattenChain() {
        assertTrue(runCatching { XrayJsonImport.parse(config(node("exit", "entry"), node("entry"))) }.isFailure)
    }
}
