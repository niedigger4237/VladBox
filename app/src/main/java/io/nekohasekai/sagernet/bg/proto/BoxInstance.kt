package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import io.nekohasekai.sagernet.bg.core.CoreBox
import io.nekohasekai.sagernet.bg.core.LibcoreBox
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.File

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: ConfigBuildResult
    lateinit var box: CoreBox
    fun isBoxInitialized() = ::box.isInitialized

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private val cacheFiles = ArrayList<File>()

    private fun newPluginFile(prefix: String, suffix: String): File {
        val directory = File(SagerNet.application.cacheDir, "tmpcfg")
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) {
            "Cannot create plugin configuration directory"
        }
        // Atomically allocate a unique name and track it before writing secrets.
        return File.createTempFile(prefix, suffix, directory).also { cacheFiles.add(it) }
    }
    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildConfig(profile)
    }

    protected open suspend fun loadConfig() {
        box = LibcoreBox(Libcore.newSingBoxInstance(config.config, LocalResolverImpl))
    }

    open suspend fun init() {
        buildConfig()
        for ((chain) in config.externalIndex) {
            chain.entries.forEach { (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }

                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteria1Config(port) {
                            newPluginFile("hysteria_", ".ca")
                        }
                    }
                }
            }
        }
        loadConfig()
    }

    override fun launch() {
        launchPlugins()
        startCore()
    }

    /** External plugin setup remains on the service thread. ROOT can start its
     * blocking native daemon separately, without moving the plugin pool off Main. */
    protected fun launchPlugins() {
        for ((chain) in config.externalIndex) {
            chain.entries.forEach { (port, profile) ->
                val bean = profile.requireBean()
                val config = pluginConfigs[port]?.second.orEmpty()

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }

                    bean is TrojanGoBean -> {
                        val configFile = newPluginFile("trojan_go_", ".json")
                        configFile.writeText(config)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }

                    bean is HysteriaBean -> {
                        val configFile = newPluginFile("hysteria_", ".json")
                        configFile.writeText(config)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.logLevel > 0) "trace" else "warn",
                            "client"
                        )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands)
                    }
                }
            }
        }

    }

    /** Starts the sing-box core. Root modes override this to run an external binary. */
    protected open fun startCore() {
        box.start()
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        try {
            if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)
            if (::box.isInitialized) box.close()
        } finally {
            cacheFiles.forEach { file ->
                if (!file.delete() && file.exists()) Logs.w("Temporary plugin file could not be removed")
            }
            cacheFiles.clear()
            pluginConfigs.clear()
            pluginPath.clear()
            externalInstances.clear()
        }
    }

}