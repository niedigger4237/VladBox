package io.nekohasekai.sagernet.fmt.hysteria

/** Preserve Hysteria v1 window semantics with sing-box 1.14 QUIC fields.
 * The deprecated recv_window_conn/recv_window fallback in the pinned core
 * maps the old fields in the opposite order. Values are exact bytes. */
internal fun hysteria1QuicOptions(streamWindow: Int, connectionWindow: Int, disableMtu: Boolean): Map<String, Any> =
    buildMap {
        if (streamWindow > 0) put("stream_receive_window", "$streamWindow B")
        if (connectionWindow > 0) put("connection_receive_window", "$connectionWindow B")
        if (disableMtu) put("disable_path_mtu_discovery", true)
    }
