package io.nekohasekai.sagernet.ktx

import org.junit.Assert.*
import org.junit.Test

class LogRedactorTest {
    @Test fun hidesCredentialAssignmentsAndOpaqueValues() {
        val secret = "example-secret-123"
        for (key in listOf("private_key", "PrivateKey", "PresharedKey", "flowMetadataToken", "password", "secret", "Authorization", "client_secret", "refreshToken", "short_id")) {
            assertFalse(LogRedactor.text("""{"$key":"$secret"}""").contains(secret))
        }
        assertFalse(LogRedactor.text("Bearer $secret").contains(secret))
        assertFalse(LogRedactor.text("hysteria2://$secret@example.org:443").contains(secret))
        assertFalse(LogRedactor.text("https://example.org/sub/$secret").contains(secret))
        assertFalse(LogRedactor.text("peer 01234567-89ab-cdef-0123-456789abcdef").contains("01234567"))
        assertFalse(LogRedactor.text("key AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").contains("AAAAAAAA"))
    }

    @Test fun preservesRoutingAndHandshakeDiagnostics() {
        val text = "AWG handshake: authenticated response received\nip route 192.168.43.0/24 dev wlan2\nROOT TUN: MTU=9000"
        assertEquals(text, LogRedactor.text(text))
    }

    @Test fun hidesShortCredentialContinuationsAndEscapedAssignments() {
        val stream = LogRedactor.Stream()
        assertFalse(stream.redact("password:").contains("password:"))
        stream.redact("")
        assertFalse(stream.redact("tiny-value").contains("tiny-value"))
        assertEquals("connected", stream.redact("connected"))
        for (key in listOf("authentication", "authKey", "access_key", "registrationKey", "license")) {
            assertFalse(LogRedactor.text("$key: tiny-value").contains("tiny-value"))
        }
        assertFalse(LogRedactor.text("""{\"password\":\"tiny-value\"}""").contains("tiny-value"))
    }

    @Test fun hidesMieruSimpleShareLink() {
        val link = "mierus://user:short-secret@example.org?profile=test&port=443&protocol=TCP"
        val result = LogRedactor.text("Imported " + link)
        assertFalse(result.contains("short-secret"))
        assertFalse(result.contains("example.org"))
    }

    @Test fun hidesMultilinePrivateKey() {
        val s = LogRedactor.Stream()
        assertFalse(s.redact("-----BEGIN PRIVATE KEY-----").contains("BEGIN PRIVATE"))
        assertFalse(s.redact("short-secret").contains("short-secret"))
        s.redact("-----END PRIVATE KEY-----")
        assertEquals("connected", s.redact("connected"))
    }
}
