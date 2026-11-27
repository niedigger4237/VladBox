package io.nekohasekai.sagernet.ui.compose

/** Pick the furthest crossed slot in physical list order, not the nearest
 * point to the finger. This avoids an early swap and preserves intermediate hops. */
internal object ChainReorderTarget {
    data class Item(val index: Int, val center: Float)
    fun find(source: Int, center: Float, visible: List<Item>): Int? {
        val down = visible.filter { it.index > source && center >= it.center }.maxOfOrNull { it.index }
        val up = visible.filter { it.index < source && center <= it.center }.minOfOrNull { it.index }
        return down ?: up
    }
}
