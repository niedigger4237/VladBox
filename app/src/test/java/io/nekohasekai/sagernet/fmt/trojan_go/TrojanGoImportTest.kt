package io.nekohasekai.sagernet.fmt.trojan_go

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrojanGoImportTest {
    @Test fun importsNestedSettingsAndKeepsEncryptedProfilesExternal() {
        val bean = JSONObject("""{"remote_addr":"example.org","remote_port":443,"password":["test"],"ssl":{"sni":"example.net","verify":false},"websocket":{"enabled":true,"host":"example.net","path":"/ws"},"shadowsocks":{"enabled":true,"method":"aes-128-gcm","password":"ss:test"}}""").parseTrojanGo()
        assertEquals("test", bean.password)
        assertEquals("example.net", bean.sni)
        assertTrue(bean.allowInsecure)
        assertEquals("ws", bean.type)
        assertEquals("/ws", bean.path)
        assertEquals("ss;aes-128-gcm:ss:test", bean.encryption)
        assertFalse(bean.canUseNativeTrojan())
        val restored = parseTrojanGo(bean.toUri())
        assertEquals(bean.encryption, restored.encryption)
        assertEquals(bean.sni, restored.sni)
        assertTrue(restored.allowInsecure)
    }

    @Test fun importsSimpleJsonAndRejectsUnsupportedMux() {
        val json = JSONObject("""{"remote_addr":"example.org","password":"test"}""")
        assertTrue(json.parseTrojanGo().canUseNativeTrojan())
        json.put("mux", JSONObject().put("enabled", true))
        assertTrue(runCatching { json.parseTrojanGo() }.isFailure)
    }
}
