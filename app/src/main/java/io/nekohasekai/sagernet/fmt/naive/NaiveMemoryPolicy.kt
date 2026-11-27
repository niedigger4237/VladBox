package io.nekohasekai.sagernet.fmt.naive

/** Explicit opt-in receive-window limits, not a hard limit on process RSS.
 * Automatic leaves the pinned Cronet implementation's defaults untouched. */
internal object NaiveMemoryPolicy {
    fun options(transport: String, profile: String): Map<String, Any> = when (profile) {
        "automatic" -> emptyMap()
        "low_memory" -> when (transport) {
            "https" -> mapOf("stream_receive_window" to "16 MB")
            "quic" -> mapOf("stream_receive_window" to "2 MB", "quic_session_receive_window" to "6 MB")
            else -> error("Unsupported NaiveProxy transport")
        }
        else -> error("Unsupported NaiveProxy memory profile")
    }
}
