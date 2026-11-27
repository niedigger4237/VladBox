package io.nekohasekai.sagernet.ktx

/** Best-effort diagnostic redaction, not a general-purpose anonymizer. */
object LogRedactor {
    private const val REDACTED = "[redacted]"
    private val sensitive = Regex(
        """(?i)(?:private[\s_-]*key|pre[\s_-]*shared[\s_-]*key|password|passwd|passphrase|secret|(?:access|refresh|auth|flow[\s_-]*metadata|api|registration)[\s_-]*token|authorization|cookie|api[\s_-]*key|license[\s_-]*key|credential|subscription[\s_-]*(?:url|link)|token|psk|uuid|auth|user[\s_-]*id|auth[\s_-]*str|auth[\s_-]*string|username|user[\s_-]*info|short[\s_-]*id|authentication|auth[\s_-]*key|access[\s_-]*key|registration[\s_-]*key|license|obfs[\s_-]*password)\s*(?:\\?["'])?\s*(?:[:=]|->|\s+["'])"""
    )
    private val bearer = Regex("""(?i)\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+""")
    private val splitBearer = Regex("""(?i)\b(?:Bearer|Basic)\s*["']?\s*$""")
    private val uri = Regex("""(?i)\b(?:https?|vless|vmess|ss|ssr|trojan(?:-go)?|hysteria2?|hy2|tuic|socks(?:4a?|5h?)?|wireguard|wg|awg|amneziawg|ssh|naive(?:\+(?:https|quic))?|shadowtls|mieru|anytls):(?:\\?/){2}[^\s<>"']+""")
    private val uuid = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
    private val opaque = Regex("""(?<![A-Za-z0-9_+/=-])[A-Za-z0-9_+/-]{32,}={0,2}(?![A-Za-z0-9_+/=-])""")
    private val pemBegin = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----""")
    private val pemEnd = Regex("""-----END [A-Z0-9 ]*PRIVATE KEY-----""")

    fun line(value: String): String {
        if (value.length > 65536) return "[oversized diagnostic omitted]"
        if (sensitive.containsMatchIn(value) || pemBegin.containsMatchIn(value) || pemEnd.containsMatchIn(value))
            return "[credential-bearing diagnostic omitted]"
        return opaque.replace(uuid.replace(uri.replace(bearer.replace(value, REDACTED), REDACTED), REDACTED), REDACTED)
    }

    class Stream {
        private var privateKey = false
        private var pendingCredential = false
        fun redact(value: String): String {
            if (pemBegin.containsMatchIn(value)) privateKey = true
            if (privateKey) {
                if (pemEnd.containsMatchIn(value)) privateKey = false
                return "[private key material omitted]"
            }
            // Credential key and value can be split across diagnostic lines.
            // Keep state through blank lines and suppress the next nonblank line.
            val pending = pendingCredential
            val match = sensitive.find(value)
            if (match != null) {
                val suffix = value.substring(match.range.last + 1).trim()
                pendingCredential = suffix.isEmpty() || suffix in setOf("\"", "'", "\\\"", "\\'") ||
                    splitBearer.containsMatchIn(suffix)
            } else if (value.isNotBlank()) {
                pendingCredential = splitBearer.containsMatchIn(value)
            }
            if (pending) return "[credential continuation omitted]"
            return line(value)
        }
    }

    fun text(value: String): String {
        val stream = Stream()
        return value.lineSequence().joinToString("\n") { stream.redact(it) }
    }
}
