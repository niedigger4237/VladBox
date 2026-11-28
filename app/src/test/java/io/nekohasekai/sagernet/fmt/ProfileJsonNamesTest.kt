package io.nekohasekai.sagernet.fmt

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileJsonNamesTest {
    @Test fun exportRenamesReferencesNotCredentials() {
        val json = JSONObject("""{"outbounds":[{"type":"vless","tag":"proxy","server":"proxy","uuid":"proxy","detour":"g-1"},{"type":"socks","tag":"g-1"},{"type":"direct","tag":"direct"}],"dns":{"servers":[{"tag":"proxy","detour":"proxy"}],"final":"proxy"},"route":{"final":"proxy","rules":[{"type":"logical","rules":[{"outbound":"g-1"}]}],"rule_set":[{"download_detour":"proxy"}]}}""")
        val result = JSONObject(ProfileJsonNames.export(json.toString(), linkedMapOf("proxy" to "🇨🇭 Home", "g-1" to "Hop")))
        val node = result.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("🇨🇭 Home", node.getString("tag"))
        assertEquals("proxy", node.getString("server"))
        assertEquals("proxy", node.getString("uuid"))
        assertEquals("Hop", node.getString("detour"))
        assertEquals("🇨🇭 Home", result.getJSONObject("route").getString("final"))
        assertEquals("Hop", result.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getJSONArray("rules").getJSONObject(0).getString("outbound"))
        assertEquals("proxy", result.getJSONObject("dns").getString("final"))
        assertEquals("🇨🇭 Home", result.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"))
    }

    @Test fun namesAndEndpointMetadata() {
        val node = JSONObject("""{"type":"wireguard","tag":"proxy","name":"wg0","remarks":"VPN 🇨🇭","peers":[]}""")
        assertEquals("VPN 🇨🇭", ProfileJsonNames.importedName(node))
        assertEquals("wg0", ProfileJsonNames.cleanOutbound(node).getString("name"))
        assertFalse(ProfileJsonNames.cleanOutbound(node).has("remarks"))
        node.remove("remarks")
        assertEquals("Saved", ProfileJsonNames.importedName(node, file = "folder/Saved.JSON"))
        assertTrue(ProfileJsonNames.isEndpoint(node))
        assertEquals(1, ProfileJsonNames.proxies(JSONObject().put("endpoints", org.json.JSONArray().put(node))).size)
    }

    @Test fun duplicateNamesDoNotReplaceExistingTags() {
        val json = """{"outbounds":[{"type":"vless","tag":"proxy"},{"type":"socks","tag":"existing"},{"type":"direct","tag":"direct"}]}"""
        val result = JSONObject(ProfileJsonNames.export(json, mapOf("proxy" to "existing")))
        assertEquals("existing (2)", result.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        assertEquals("existing", result.getJSONArray("outbounds").getJSONObject(1).getString("tag"))
    }
}
