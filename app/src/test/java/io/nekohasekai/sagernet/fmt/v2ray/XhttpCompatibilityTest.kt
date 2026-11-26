package io.nekohasekai.sagernet.fmt.v2ray

import org.junit.Assert.*
import org.junit.Test

class XhttpCompatibilityTest {
    @Test fun automaticModes() {
        assertEquals("packet-up", XhttpExtra.resolveMode("auto", false, false))
        assertEquals("stream-one", XhttpExtra.resolveMode("auto", true, false))
        assertEquals("stream-up", XhttpExtra.resolveMode("auto", true, true))
        assertEquals("packet-up", XhttpExtra.resolveMode("packet-up", true, false))
    }
    @Test fun aliasesAndRanges() {
        val out = XhttpExtra.toSingBox("""{"noGRPCHeader":true,"xPaddingBytes":0,"sessionIDPlacement":"header","headers":{"X-Test":"v"},"xmux":{"maxConcurrency":{"from":8,"to":16},"hKeepAlivePeriod":-1}}""")
        assertEquals(true, out["no_grpc_header"])
        assertEquals("100-1000", out["x_padding_bytes"])
        assertEquals("header", out["session_placement"])
        assertEquals("v", (out["headers"] as Map<*, *>)["X-Test"])
        assertEquals("8-16", (out["xmux"] as Map<*, *>)["max_concurrency"])
        assertEquals(-1L, (out["xmux"] as Map<*, *>)["h_keep_alive_period"])
    }
    @Test fun separateDownload() {
        val out = XhttpExtra.toSingBox("""{"downloadSettings":{"address":"example.test","port":443,"network":"xhttp","security":"reality","realitySettings":{"password":"abc","fingerprint":"chrome"},"xhttpSettings":{"host":"cdn.example.test","path":"/download","extra":{"xPaddingBytes":"100-200"}}}}""", "auto", true)
        val d = out["download"] as Map<*, *>
        assertEquals("cdn.example.test", d["host"])
        assertEquals("/download", d["path"])
        assertEquals("100-200", d["x_padding_bytes"])
        assertEquals("stream-up", XhttpExtra.resolveMode("auto", true, true))
    }
    @Test fun rejectsUnsupportedAndConflictingParameters() {
        for (raw in listOf("""{"h2Params":{}}""","""{"xmux":{"maxConcurrency":8,"maxConnections":2}}""","""{"xPaddingBytes":"200-100"}""","""{"download":{}}""")) {
            assertTrue(raw, runCatching { XhttpExtra.toSingBox(raw) }.isFailure)
        }
    }
}
