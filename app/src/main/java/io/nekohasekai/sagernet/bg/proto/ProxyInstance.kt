package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

open class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null
    private var trafficScope: CoroutineScope? = null

    override fun buildConfig() {
        if (notTmp) super.buildConfig() else config = buildConfig(profile, forSelectorCheck = true)
        lastSelectorGroupId = super.config.selectorGroupId
        // Config JSON and trafficMap contain credential-bearing beans.
        // Never dump either, including in debug builds.
        if (notTmp) Logs.d("Core configuration generated")
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        if (pluginConfigs.isNotEmpty()) Logs.d("External plugin configurations prepared: ${pluginConfigs.size}")
    }

    override suspend fun loadConfig() {
        // Only real, managed embedded VPN sessions own this optional database.
        // RootProxyInstance and TestInstance have independent loadConfig methods.
        if (!notTmp || service == null ||
            io.nekohasekai.sagernet.database.DataStore.serviceMode != io.nekohasekai.sagernet.Key.MODE_VPN ||
            profile.type == ProxyEntity.TYPE_CONFIG) {
            super.loadConfig()
            return
        }
        val data = io.nekohasekai.sagernet.database.DataStore
        val cache = io.nekohasekai.sagernet.fmt.PersistentDnsPolicy
        val scope = if (data.dnsEngineMode == "modern" && data.dnsCacheStorage == "persistent" &&
            config.selectorGroupId < 0 && config.externalIndex.all { it.chain.isEmpty() }) {
            cache.scope(config.config, profile.id,
                io.nekohasekai.sagernet.database.RouteProfiles.selectedId(), data.dnsCacheGeneration)
        } else null
        val directory = cache.directory()
        if (scope == null && !directory.exists()) {
            super.loadConfig()
            return
        }
        box = io.nekohasekai.sagernet.bg.core.LibcoreBox(
            libcore.Libcore.newManagedDnsCacheInstance(config.config,
                moe.matsuri.nb4a.net.LocalResolverImpl, directory.absolutePath, scope.orEmpty())
        )
    }

    override fun launch() {
        if (isBoxInitialized()) box.setAsMain()
        super.launch() // start box
        // traffic stats need the in-process core (root modes run it externally)
        if (isBoxInitialized() && looper == null) {
            service?.let { owner ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                trafficScope = scope
                looper = TrafficLooper(owner.data, scope).also { it.start() }
            }
        }
    }

    override fun close() {
        try {
            // Do not let a stats query race the native box shutdown.
            runBlocking { looper?.stop() }
        } finally {
            looper = null
            trafficScope?.cancel()
            trafficScope = null
            super.close()
        }
    }
}
