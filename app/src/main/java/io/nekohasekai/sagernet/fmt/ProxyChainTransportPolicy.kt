package io.nekohasekai.sagernet.fmt

/** Conservative check: unknown transport or capabilities are not rejected. */
internal object ProxyChainTransportPolicy {
    enum class Network { TCP, UDP }
    data class Hop(
        val name: String,
        val requires: Network?,
        val provides: Set<Network>?,
        val fixedWireGuardListenPort: Int = 0,
    )

    fun validate(physicalOrder: List<Hop>) {
        physicalOrder.zipWithNext().forEach { (previous, next) ->
            // The pinned sing-box WireGuard endpoint rejects listen_port with detour.
            // Never silently drop an explicitly configured local port.
            require(next.fixedWireGuardListenPort == 0) {
                "Incompatible proxy chain: ${next.name} uses Listen port ${next.fixedWireGuardListenPort}. Set Listen port to 0 (automatic) or move it to the first hop."
            }
            val required = next.requires
            val provided = previous.provides
            require(required == null || provided == null || required in provided) {
                "Incompatible proxy chain: ${next.name} requires $required, but ${previous.name} does not support it"
            }
        }
    }
}
