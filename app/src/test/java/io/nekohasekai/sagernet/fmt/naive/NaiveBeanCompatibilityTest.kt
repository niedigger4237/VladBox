package io.nekohasekai.sagernet.fmt.naive

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.*
import org.junit.Test

class NaiveBeanCompatibilityTest {
    // Reproduce the historical on-disk layouts, including AbstractBean's trailer.
    private fun legacy(version: Int): ByteArray {
        val out = ByteBufferOutput(256, -1)
        out.writeInt(version)
        out.writeString("example.org")
        out.writeInt(5443)
        out.writeString("quic")
        out.writeString("user")
        out.writeString("password")
        out.writeString("X-Test: value")
        if (version >= 2) {
            out.writeString("test-ca")
            out.writeString("example.net")
        }
        if (version >= 1) out.writeInt(2)
        if (version >= 3) out.writeBoolean(true)
        out.writeInt(1)
        out.writeString("Old profile")
        out.writeString("{\"test\":1}")
        out.writeString("{\"test\":2}")
        return out.toBytes()
    }

    @Test fun historicalLayoutsKeepFieldsAndTrailer() {
        for (version in 0..3) {
            val bean = KryoConverters.naiveDeserialize(legacy(version))
            assertEquals("example.org", bean.serverAddress)
            assertEquals(5443, bean.serverPort.toInt())
            assertEquals("quic", bean.proto)
            assertEquals("user", bean.username)
            assertEquals("password", bean.password)
            assertEquals("X-Test: value", bean.extraHeaders)
            assertEquals(if (version >= 2) "test-ca" else "", bean.certificates)
            assertEquals(if (version >= 2) "example.net" else "", bean.sni)
            assertEquals(if (version >= 1) 2 else 0, bean.insecureConcurrency.toInt())
            assertEquals(version >= 3, bean.sUoT)
            assertEquals("automatic", bean.memoryProfile)
            assertEquals("Old profile", bean.name)
            assertEquals("{\"test\":1}", bean.customOutboundJson)
            assertEquals("{\"test\":2}", bean.customConfigJson)
        }
    }

    @Test fun currentLayoutKeepsMemoryProfileAndTrailer() {
        val bean = KryoConverters.naiveDeserialize(legacy(3))
        bean.memoryProfile = "low_memory"
        val restored = KryoConverters.naiveDeserialize(KryoConverters.serialize(bean))
        assertEquals("low_memory", restored.memoryProfile)
        assertEquals(bean.name, restored.name)
        assertEquals(bean.customOutboundJson, restored.customOutboundJson)
        assertEquals(bean.customConfigJson, restored.customConfigJson)
        assertEquals(bean.sUoT, restored.sUoT)
    }
}
