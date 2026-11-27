package io.nekohasekai.sagernet.fmt

/** Conservative check: unknown transport or capabilities are not rejected. */
internal object ProxyChainTransportPolicy {
    enum class Network { TCP, UDP }
    data class Hop(val name: String, val requires: Network?, val provides: Set<Network>?)

    fun validate(physicalOrder: List<Hop>) {
        physicalOrder.zipWithNext().forEach { (previous, next) ->
            val required = next.requires
            val provided = previous.provides
            require(required == null || provided == null || required in provided) {
                "Incompatible proxy chain: ${next.name} requires $required, but ${previous.name} does not support it"
            }
        }
    }
}
