package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import io.nekohasekai.sagernet.bg.core.LibcoreBox
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    suspend fun doTest(): Int = withContext(Dispatchers.IO) {
        // The test job owns the temporary core until all native calls return.
        // Never close a Go instance concurrently with its blocking urlTest.
        use {
            coroutineScope {
                val fatal = CompletableDeferred<java.io.IOException>()
                processes = GuardedProcessPool {
                    Logs.w(it)
                    fatal.complete(it)
                }
                val worker = async {
                    init()
                    ensureActive()
                    launch()
                    if (processes.processCount > 0) delay(500)
                    ensureActive()
                    box.urlTest(link, timeout).also { ensureActive() }
                }
                try {
                    select<Int> {
                        worker.onAwait { it }
                        fatal.onAwait { throw it }
                    }
                } finally {
                    worker.cancel()
                    fatal.cancel()
                    // coroutineScope joins worker before use closes the core.
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        // Never dump credential-bearing test configurations, even in debug builds.
        box = LibcoreBox(Libcore.newSingBoxInstance(config.config, LocalResolverImpl))
    }

}
