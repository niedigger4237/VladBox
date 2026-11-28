package io.nekohasekai.sagernet.ui.compose

import org.junit.Assert.*
import org.junit.Test

class DashboardTrafficHistoryTest {
    @Test fun keepsOnlyActualRecentSamples() {
        var history = emptyList<DashboardTrafficSample>()
        for (second in 0..100) history = DashboardTrafficHistory.append(history, second * 1000L, 10, 20)
        assertEquals(61, history.size)
        assertEquals(40_000L, history.first().elapsedMs)
        assertTrue(DashboardTrafficHistory.visible(history, 161_000L).isEmpty())
    }

    @Test fun coalescesWithinSecondAndClampsRates() {
        val first = DashboardTrafficHistory.append(emptyList(), 1000, 10, 20)
        val next = DashboardTrafficHistory.append(first, 1200, -1, 42)
        assertEquals(listOf(DashboardTrafficSample(1200, 0, 42)), next)
    }

    @Test fun gapsAreNotInterpolated() {
        val samples = listOf(DashboardTrafficSample(1000, 1, 2), DashboardTrafficSample(2000, 1, 2), DashboardTrafficSample(10_000, 1, 2))
        assertEquals(listOf(2, 1), DashboardTrafficHistory.segments(samples).map { it.size })
        assertEquals(0f, DashboardTrafficHistory.position(0, 60_000), 0.001f)
        assertEquals(1f, DashboardTrafficHistory.position(60_000, 60_000), 0.001f)
    }
}
