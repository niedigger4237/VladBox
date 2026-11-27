package io.nekohasekai.sagernet.ui.compose

import org.junit.Assert.*
import org.junit.Test

class ChainReorderTargetTest {
    private val items = listOf(ChainReorderTarget.Item(1, 100f), ChainReorderTarget.Item(2, 200f), ChainReorderTarget.Item(3, 300f), ChainReorderTarget.Item(4, 400f))
    @Test fun waitsForNeighborCenter() {
        assertNull(ChainReorderTarget.find(2, 250f, items))
        assertEquals(3, ChainReorderTarget.find(2, 300f, items))
    }
    @Test fun fastDragCrossesSeveralSlots() {
        assertEquals(4, ChainReorderTarget.find(1, 425f, items))
        assertEquals(1, ChainReorderTarget.find(4, 90f, items))
    }
}
