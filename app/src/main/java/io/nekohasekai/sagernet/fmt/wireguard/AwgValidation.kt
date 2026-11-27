package io.nekohasekai.sagernet.fmt.wireguard

/** Validation shared by the editor, INI import/export and the native endpoint builder.
 * Matches the pinned WireGuard-go UAPI's uint32 ranges and header cipher requirements.
 * The core remains authoritative for signature packet grammar and other limits. */
object AwgValidation {
    private val rangePattern = Regex("[0-9]+(?:-[0-9]+)?")
    private val keyPattern = Regex("[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=")
    private fun range(raw: String): LongRange? {
        val value = raw.trim()
        if (!rangePattern.matches(value)) return null
        val first = value.substringBefore('-').toLongOrNull() ?: return null
        val last = value.substringAfter('-', value).toLongOrNull() ?: return null
        if (first !in 0L..4294967295L || last !in first..4294967295L) return null
        return first..last
    }

    fun error(junk: List<Int>, padding: List<Int>, headers: List<String>,
              ranges: Map<String, String>, hpk: String, signatures: List<String>): String? {
        if (junk.any { it < 0 }) return "Invalid AWG junk packet parameter"
        if (junk[1] > junk[2]) return "Jmin must not exceed Jmax"
        if (padding.any { it !in 0..65535 }) return "S1–S4 must be within 0..65535"
        val parsed = headers.mapIndexed { index, raw ->
            if (raw.isBlank()) (index + 1L)..(index + 1L)
            else range(raw) ?: return "Invalid H${index + 1} range"
        }
        for (i in parsed.indices) for (j in i + 1 until parsed.size) {
            if (parsed[i].first <= parsed[j].last && parsed[j].first <= parsed[i].last)
                return "H${i + 1} and H${j + 1} must not overlap"
        }
        for ((name, value) in ranges) {
            if (value.isNotBlank() && range(value) == null) return "Invalid $name range"
        }
        val key = hpk.trim()
        if (key.isNotEmpty()) {
            if (!keyPattern.matches(key)) return "Header protection key must contain 32 bytes in Base64"
            if (key != "A".repeat(43) + "=" && padding.any { it < 12 })
                return "Header protection requires S1–S4 of at least 12"
        }
        if (signatures.any { '\n' in it.trim() || '\r' in it.trim() })
            return "AWG signature packets must be single-line"
        return null
    }

    fun error(bean: WireGuardBean): String? = error(
        listOf(bean.awgJc, bean.awgJmin, bean.awgJmax),
        listOf(bean.awgS1, bean.awgS2, bean.awgS3, bean.awgS4),
        listOf(bean.awgH1, bean.awgH2, bean.awgH3, bean.awgH4),
        linkedMapOf("Content padding addition" to bean.awgCpa, "Rekey after time" to bean.awgRekeyAfter,
            "Rekey timeout" to bean.awgRekeyTimeout, "Reject after time" to bean.awgRejectAfter,
            "Keepalive timeout" to bean.awgKeepaliveTimeout, "Max handshake attempts" to bean.awgMaxHandshake),
        bean.awgHpk, listOf(bean.awgI1, bean.awgI2, bean.awgI3, bean.awgI4, bean.awgI5)
    )

    fun requireValid(bean: WireGuardBean) {
        error(bean)?.let { throw IllegalArgumentException(it) }
    }
}
