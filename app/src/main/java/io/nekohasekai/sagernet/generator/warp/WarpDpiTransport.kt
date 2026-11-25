package io.nekohasekai.sagernet.generator.warp

import android.content.Context
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CancellationException

/** Internal transaction adapter. Production caller must supply an isolated engine. */
internal class WarpDpiTransport(private val context: Context) {
    fun <T> execute(
        open: (DpiStrategySelector.Strategy) -> WarpDpiSession,
        cancelled: () -> Boolean,
        progress: (Int, Int, DpiStrategySelector.Strategy) -> Unit,
        transaction: (ApiSession) -> T
    ): T = DpiStrategySelector.withWorkingStrategy(
        DpiStrategyCatalog.strategies,
        DpiStrategyCatalog.preferred(context),
        open, cancelled, progress
    ) { match, session ->
        val address = session.proxy.address() as? InetSocketAddress
            ?: error("Invalid ByeDPI proxy address")
        require(session.proxy.type() == Proxy.Type.SOCKS)
        require(address.address?.isLoopbackAddress == true)
        val api = ApiSession(
            WarpApi.Connection.CustomProxy(address.hostString, address.port, Proxy.Type.SOCKS),
            cancelled
        )
        try {
            val result = transaction(api)
            if (cancelled()) throw CancellationException()
            DpiStrategyCatalog.remember(context, match)
            result
        } finally {
            api.invalidate()
        }
    }

    /** Registration entry point for a verified, isolated engine adapter only. */
    fun register(
        open: (DpiStrategySelector.Strategy) -> WarpDpiSession,
        cancelled: () -> Boolean,
        progress: (Int, Int, DpiStrategySelector.Strategy) -> Unit
    ): WarpRegistration.Account = execute(open, cancelled, progress) { api ->
        WarpRegistration.register { method, endpoint, body, token ->
            api.request(method, endpoint, body, token)
        }
    }

    internal class ApiSession(
        private val connection: WarpApi.Connection,
        private val cancelled: () -> Boolean
    ) {
        @Volatile private var active = true

        @Synchronized fun request(method: String, endpoint: String,
                    body: JSONObject? = null, token: String? = null): JSONObject {
            check(active) { "WARP API session closed" }
            if (cancelled()) throw CancellationException()
            return WarpApi.request(method, endpoint, body, token, connection)
        }

        @Synchronized internal fun invalidate() { active = false }
    }
}
