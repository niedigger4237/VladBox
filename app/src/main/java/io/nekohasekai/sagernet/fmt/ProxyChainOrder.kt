package io.nekohasekai.sagernet.fmt

/** Stored chain order is physical ingress -> exit; reverse only the fully
 * expanded chain, because sing-box builds outbounds from exit to ingress. */
internal object ProxyChainOrder {
    fun <T> flatten(root: T, id: (T) -> Long, children: (T) -> List<Long>?,
                    lookup: (List<Long>) -> Map<Long, T>): List<T> {
        fun visit(node: T, ancestors: Set<Long>): List<T> {
            val childIds = children(node) ?: return listOf(node)
            val nodeId = id(node)
            require(nodeId !in ancestors) { "Circular proxy chain: $nodeId" }
            require(ancestors.size < 32) { "Proxy chain nesting exceeds 32 levels" }
            require(childIds.isNotEmpty()) { "Empty proxy chain: $nodeId" }
            val found = lookup(childIds)
            return childIds.flatMap { childId ->
                val child = found[childId] ?: error("Missing proxy in chain: $childId")
                visit(child, ancestors + nodeId)
            }
        }
        return visit(root, emptySet())
    }

    fun <T> dialOrder(front: List<T>, main: List<T>, landing: List<T>): List<T> =
        (front + main + landing).asReversed()
}
