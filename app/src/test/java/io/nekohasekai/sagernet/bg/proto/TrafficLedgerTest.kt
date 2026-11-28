package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.proto.TrafficLedger.Bytes
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficLedgerTest {
    @Test fun separateServerCountersSurviveSwitches() {
        val ledger = TrafficLedger(mapOf("A" to listOf(1L), "B" to listOf(2L)),
            mapOf(1L to Bytes(100, 50), 2L to Bytes(200, 60)))
        // A keeps transferring even after B was selected; neither counter moves.
        val samples = mapOf("A" to Bytes(30, 10), "B" to Bytes(70, 20), "bypass" to Bytes(1000, 1000))
        assertEquals(mapOf(1L to Bytes(130, 60), 2L to Bytes(270, 80)), ledger.profileTotals(samples))
        assertEquals(Bytes(100, 30), ledger.sessionTotal(samples))
    }

    @Test fun sharedChainProfilesDoNotMultiplySessionTotals() {
        val ledger = TrafficLedger(mapOf("A" to listOf(1L, 3L, 3L), "B" to listOf(2L, 3L)), emptyMap())
        val samples = mapOf("A" to Bytes(10, 1), "B" to Bytes(20, 2), "proxy" to Bytes(5, 1))
        assertEquals(Bytes(30, 3), ledger.profileDeltas(samples)[3L])
        assertEquals(Bytes(35, 4), ledger.sessionTotal(samples))
    }
}
