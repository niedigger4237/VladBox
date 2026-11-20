package io.nekohasekai.sagernet.bg.throne

import android.app.Application
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.throneproj.mobile.LogSink
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.SetupOptions
import libcore.Libcore
import java.io.File

/** Loads Throne's core (ThroneCore.aar, libthrone.so) lazily, only when that engine is selected. */
object ThroneRuntime {

    const val WORKING_DIR = "throne"

    private var ready = false

    val platform: ThronePlatform by lazy { ThronePlatform() }

    @Synchronized
    fun ensureSetup(app: Application) {
        if (ready) return
        // the gomobile runtime of this AAR is `gt.*` (see buildScript/lib/throne/build_throne_core.sh)
        gt.Seq.setContext(app)
        val working = File(app.filesDir, WORKING_DIR).apply { mkdirs() }
        Mobile.setup(SetupOptions().apply {
            basePath = app.filesDir.absolutePath
            workingPath = working.absolutePath
            tempPath = app.cacheDir.absolutePath
            logMaxLines = 1024
            debug = false
        })
        Libcore.nekoLogPrintln("[Info] [Throne] core: sing-box ${Mobile.version()}, xray ${Mobile.xrayVersion()}, ${Mobile.goVersion()}")
        Mobile.setLogSink(Sink)
        ready = true
    }

    private object Sink : LogSink {
        override fun write(level: Int, message: String) {
            if (DataStore.logLevel == 0) return
            val name = when (level) {
                Mobile.LogLevelPanic -> "Panic"
                Mobile.LogLevelFatal -> "Fatal"
                Mobile.LogLevelError -> "Error"
                Mobile.LogLevelWarn -> "Warning"
                Mobile.LogLevelDebug -> "Debug"
                Mobile.LogLevelTrace -> "Trace"
                else -> "Info"
            }
            try {
                Libcore.nekoLogPrintln("[$name] $message")
            } catch (e: Throwable) {
                Logs.w("throne log: $e")
            }
        }
    }
}
