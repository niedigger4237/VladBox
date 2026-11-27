package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class ProxyChainOrderTest {
    private data class Node(val id: Long, val children: List<Long>? = null)
    private fun flatten(root: Node, vararg nodes: Node) =
        ProxyChainOrder.flatten(root, Node::id, Node::children) { ids ->
            nodes.filter { it.id in ids }.associateBy { it.id }
        }.map { it.id }

    @Test fun nestedChainKeepsPhysicalOrder() {
        val nested = Node(10, listOf(2, 3))
        val root = Node(20, listOf(1, 10, 4))
        assertEquals(listOf(1L, 2L, 3L, 4L), flatten(root, Node(1), Node(2), Node(3), Node(4), nested))
        assertEquals(listOf(4, 3, 2, 1), ProxyChainOrder.dialOrder(listOf(1), listOf(2, 3), listOf(4)))
    }

    @Test fun repeatedHopsKeepTheirPositions() {
        assertEquals(listOf(1L, 2L, 1L), flatten(Node(10, listOf(1, 2, 1)), Node(1), Node(2)))
    }

    @Test fun rejectsMissingEmptyAndCircularChains() {
        assertTrue(runCatching { flatten(Node(10, listOf(1))) }.isFailure)
        assertTrue(runCatching { flatten(Node(10, emptyList())) }.isFailure)
        val a = Node(10, listOf(20))
        val b = Node(20, listOf(10))
        assertTrue(runCatching { flatten(a, a, b) }.isFailure)
    }
}
