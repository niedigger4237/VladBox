package io.nekohasekai.sagernet.bg.throne

import android.app.Application
import io.nekohasekai.sagernet.aidl.IThroneCallback
import io.throneproj.mobile.LogSink
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.SetupOptions
import java.io.File

/** Runs in the :throne process only (libthrone.so, the only Go runtime of that process). */
object ThroneRuntime {

    const val WORKING_DIR = "throne"

    private var ready = false

    @Volatile
    var callback: IThroneCallback? = null

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
        Mobile.setLogSink(Sink)
        callback?.log(Mobile.LogLevelInfo.toInt(), "[Throne] core: sing-box ${Mobile.version()}, xray ${Mobile.xrayVersion()}, ${Mobile.goVersion()}")
        ready = true
    }

    private object Sink : LogSink {
        override fun write(level: Int, message: String) {
            try {
                callback?.log(level, message)
            } catch (_: Throwable) {
            }
        }
    }
}
