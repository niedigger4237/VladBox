package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class LatencyPolicyTest {
    @Test fun distinguishesProbeAndControllerHttpFailures() {
        assertEquals(LatencyPolicy.HttpFailure.TIMEOUT, LatencyPolicy.httpFailure(504))
        assertEquals(LatencyPolicy.HttpFailure.TIMEOUT, LatencyPolicy.httpFailure(408))
        assertEquals(LatencyPolicy.HttpFailure.PROBE, LatencyPolicy.httpFailure(503))
        for (status in listOf(400, 401, 403, 404, 500))
            assertEquals(LatencyPolicy.HttpFailure.CONTROL, LatencyPolicy.httpFailure(status))
    }

    @Test fun classifiesNativeAndWrappedErrors() {
        assertEquals(LatencyPolicy.Failure.TIMEOUT, LatencyPolicy.failure(SocketTimeoutException()))
        assertEquals(LatencyPolicy.Failure.TIMEOUT, LatencyPolicy.failure(Exception("native: context deadline exceeded")))
        assertEquals(LatencyPolicy.Failure.REFUSED, LatencyPolicy.failure(Exception("dial: ECONNREFUSED")))
        assertEquals(LatencyPolicy.Failure.TIMEOUT, LatencyPolicy.failure(Exception("wrapped", SocketTimeoutException())))
        assertEquals(LatencyPolicy.Failure.OTHER, LatencyPolicy.failure(Exception("TLS certificate invalid")))
    }

    @Test fun rejectsInvalidLatency() {
        assertEquals(42, LatencyPolicy.requirePositive(42))
        assertTrue(runCatching { LatencyPolicy.requirePositive(0) }.isFailure)
        assertTrue(runCatching { LatencyPolicy.requirePositive(-1) }.isFailure)
    }
}
