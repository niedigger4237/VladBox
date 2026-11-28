package io.nekohasekai.sagernet.bg.proto

import java.net.SocketTimeoutException
import java.util.Locale

/** Probe deadline and local controller overhead are separate budgets. */
internal object LatencyPolicy {
    const val PROBE_TIMEOUT_MS = 5000
    const val API_REQUEST_TIMEOUT_MS = 20000L
    const val GROUP_TIMEOUT_MS = 10000
    const val GROUP_REQUEST_TIMEOUT_MS = 32000L

    enum class HttpFailure { TIMEOUT, PROBE, CONTROL }
    enum class Failure { TIMEOUT, REFUSED, OTHER }

    fun httpFailure(status: Int): HttpFailure = when (status) {
        408, 504 -> HttpFailure.TIMEOUT
        503 -> HttpFailure.PROBE
        else -> HttpFailure.CONTROL
    }

    fun requirePositive(delay: Int): Int {
        check(delay > 0) { "No valid latency returned" }
        return delay
    }

    fun failure(error: Throwable): Failure {
        var cause: Throwable? = error
        repeat(8) {
            val current = cause ?: return Failure.OTHER
            val message = current.message.orEmpty().lowercase(Locale.ROOT)
            if (current is SocketTimeoutException || message.contains("context deadline exceeded") ||
                message.contains("i/o timeout") || message.contains("timed out") ||
                message.contains("etimedout")) return Failure.TIMEOUT
            if (message.contains("connection refused") || message.contains("econnrefused"))
                return Failure.REFUSED
            cause = current.cause
        }
        return Failure.OTHER
    }
}
