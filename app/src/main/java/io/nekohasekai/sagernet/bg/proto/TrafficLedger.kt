package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.fmt.TAG_BYPASS

/** Logical routed payload counters, not separate physical byte counts for chain hops. */
internal class TrafficLedger(
    private val profilesByTag: Map<String, List<Long>>,
    private val bases: Map<Long, Bytes>,
) {
    data class Bytes(val rx: Long = 0, val tx: Long = 0) {
        operator fun plus(other: Bytes) = Bytes(rx + other.rx, tx + other.tx)
    }

    fun profileDeltas(counters: Map<String, Bytes>): Map<Long, Bytes> {
        val result = linkedMapOf<Long, Bytes>()
        for ((tag, ids) in profilesByTag) {
            if (tag == TAG_BYPASS) continue
            val value = counters[tag] ?: Bytes()
            for (id in ids.distinct()) result[id] = (result[id] ?: Bytes()) + value
        }
        return result
    }

    fun profileTotals(counters: Map<String, Bytes>): Map<Long, Bytes> =
        profileDeltas(counters).mapValues { (id, delta) -> (bases[id] ?: Bytes()) + delta }

    // Sum each logical tag once, even if it belongs to several profiles.
    fun sessionTotal(counters: Map<String, Bytes>): Bytes =
        counters.filterKeys { it != TAG_BYPASS }.values.fold(Bytes()) { total, next -> total + next }
}
