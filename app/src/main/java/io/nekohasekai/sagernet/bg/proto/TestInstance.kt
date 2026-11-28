package io.nekohasekai.sagernet.bg.proto

import com.google.gson.JsonParser
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

    private var prepared = false

    suspend fun doTest(): Int = withContext(Dispatchers.IO) {
        // Build once, before admission, so nested chains and JSON overrides
        // are classified from the actual outbound configuration.
        buildConfig()
        val json = JsonParser.parseString(config.config).asJsonObject
        val types = listOf("outbounds", "endpoints").flatMap { key ->
            json.getAsJsonArray(key)?.mapNotNull { item ->
                if (item.isJsonObject) item.asJsonObject.get("type")?.asString else null
            }.orEmpty()
        }
        val heavy = TestConcurrencyPolicy.isHeavy(
            types, config.externalIndex.any { it.chain.isNotEmpty() }, profile.isRawCoreConfig()
        )
        TestCoreAdmission.withSlot(heavy) {
            ensureActive()
            // Keep both admission permits until the native call and external
            // plugin guards are finished. Cancellation may not free them early.
            val fatal = CompletableDeferred<java.io.IOException>()
            val pool = GuardedProcessPool {
                Logs.w(it)
                fatal.complete(it)
            }
            processes = pool
            try {
                use {
                    coroutineScope {
                        val worker = async {
                            init()
                            ensureActive()
                            // GuardedProcessPool.start is Main-thread-owned.
                            // Keep the blocking native startup on IO.
                            withContext(Dispatchers.Main.immediate) { launchPlugins() }
                            ensureActive()
                            startCore()
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
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    // close() cancels the guards asynchronously; wait for their
                    // cleanup before letting the next temporary core start.
                    pool.cancel()
                    pool.coroutineContext[Job]?.join()
                }
            }
        }
    }

    override fun buildConfig() {
        if (!prepared) {
            config = buildConfig(profile, true)
            prepared = true
        }
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        // Never dump credential-bearing test configurations, even in debug builds.
        box = LibcoreBox(Libcore.newSingBoxInstance(config.config, LocalResolverImpl))
    }

}
