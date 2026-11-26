package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking

open class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

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
        super.loadConfig()
    }

    override fun launch() {
        if (isBoxInitialized()) box.setAsMain()
        super.launch() // start box
        // traffic stats need the in-process core (root modes run it externally)
        if (isBoxInitialized()) runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
        }
    }

    override fun close() {
        super.close()
        runBlocking {
            looper?.stop()
            looper = null
        }
    }
}
