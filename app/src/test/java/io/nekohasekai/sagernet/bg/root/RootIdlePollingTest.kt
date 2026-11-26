package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootIdlePollingTest {
    @Test fun skipOnlyWhenReliableAndAlreadyEmptyWithNoCandidates() {
        assertTrue(RootIdlePolling.skipEmptyRouteDump(true, true, true, true, true))
        for (i in 0..4) {
            val flags = MutableList(5) { true }.apply { this[i] = false }
            assertFalse(RootIdlePolling.skipEmptyRouteDump(flags[0], flags[1], flags[2], flags[3], flags[4]))
        }
    }

    @Test fun logBackoffIsBoundedAndResetsOnDataOrError() {
        var idle = 0
        assertEquals(1500L, RootIdlePolling.logDelayMs(idle))
        for (expected in listOf(3000L, 6000L, 12000L, 12000L)) {
            idle = RootIdlePolling.nextLogIdlePolls(idle, true, false)
            assertEquals(expected, RootIdlePolling.logDelayMs(idle))
        }
        assertEquals(0, RootIdlePolling.nextLogIdlePolls(idle, true, true))
        assertEquals(0, RootIdlePolling.nextLogIdlePolls(idle, false, false))
    }
}
