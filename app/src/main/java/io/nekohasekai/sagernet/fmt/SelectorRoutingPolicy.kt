package io.nekohasekai.sagernet.fmt

internal object SelectorRoutingPolicy {
    // Share only internal ingress hops; the logical entry identifies a member.
    fun shareIngress(selector: Boolean, index: Int, lastIndex: Int): Boolean =
        index == lastIndex && (!selector || index != 0)

    // Positive IDs pin a specific member; only the default proxy follows Selector.
    fun explicitRouteTag(selector: Boolean, mainId: Long, targetId: Long, tags: Map<Long, String>): String =
        if (!selector && mainId == targetId) "proxy" else tags[targetId].orEmpty()

    fun sameConfigOverride(startup: String?, requested: String?): Boolean =
        startup.orEmpty().trim() == requested.orEmpty().trim()
}
