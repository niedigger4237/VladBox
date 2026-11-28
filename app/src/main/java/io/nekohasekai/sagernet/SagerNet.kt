package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import go.Seq
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isOss
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import libcore.Libcore
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.cleanWebview
import java.io.File
import android.util.Log
import java.io.RandomAccessFile
import androidx.work.Configuration as WorkConfiguration

class SagerNet : Application(),
    WorkConfiguration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        application = this
    }

    private val nativeInterface = NativeInterface()

    // The core, Assets UI and routing must all use the same app-private directory.
    val externalAssets: File by lazy { filesDir }

    private fun migrateLegacyRuleSets() {
        val legacy = getExternalFilesDir(null) ?: return
        val destination = externalAssets
        if (legacy.absolutePath == destination.absolutePath || !legacy.isDirectory) return
        val lockFile = File(destination, "srs-migration.lock")
        try {
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.lock().use {
                    val files = legacy.listFiles() ?: return
                    for (source in files) {
                        val name = source.name
                        if (!source.isFile || !(name.endsWith(".srs", true) ||
                                    name.endsWith(".version.txt") || name == "rule-set.version.txt")) continue
                        val target = File(destination, name)
                        if (name.endsWith(".srs", true) &&
                            getSharedPreferences("deleted_srs", MODE_PRIVATE).getBoolean(name, false)) continue
                        if (target.exists()) continue
                        val temporary = File(destination, ".${name}.migrating")
                        try {
                            source.inputStream().use { input ->
                                temporary.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (temporary.length() != source.length() || temporary.length() == 0L) {
                                throw java.io.IOException("Incomplete migration of $name")
                            }
                            if (!temporary.renameTo(target)) {
                                throw java.io.IOException("Cannot install migrated $name")
                            }
                            Log.i("VladBoxSRS", "Migrated legacy SRS asset: $name")
                        } catch (e: Exception) {
                            Log.w("VladBoxSRS", "Cannot migrate legacy SRS asset: $name", e)
                        } finally {
                            temporary.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VladBoxSRS", "Legacy SRS migration unavailable; bundled rules will be restored", e)
        }
        // Never delete legacy files. They remain available for manual recovery.
    }
    /** Install bundled SRS once; never overwrite user-updated rule sets. */
    private fun installBundledDefaultRuleSets() {
        val prefix = "sing-box/rule-set"
        val files = assets.list(prefix)
            ?: throw java.io.IOException("Default rule-set assets are missing from APK")
        val required = listOf(
            "geosite-category-ads-all.srs", "geoip-ru.srs", "geoip-private.srs",
            "geosite-category-gov-ru.srs", "geosite-category-ru.srs",
            "geosite-ru-suffixes.srs", "geosite-yandex.srs", "geosite-mailru.srs",
            "geosite-vk.srs", "geosite-private.srs"
        )
        for (name in required) {
            if (getSharedPreferences("deleted_srs", MODE_PRIVATE).getBoolean(name, false)) continue
            check(name in files) { "Missing bundled Default rule set: $name" }
            val destination = File(externalAssets, name)
            if (!destination.isFile || destination.length() == 0L) {
                val temporary = File(externalAssets, ".$name.installing")
                try {
                    assets.open("$prefix/$name").use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    temporary.inputStream().use { input ->
                        check(input.read() == 0x53 && input.read() == 0x52 && input.read() == 0x53) {
                            "Invalid bundled SRS: $name"
                        }
                    }
                    check(temporary.renameTo(destination)) { "Cannot install $name" }
                } finally { temporary.delete() }
            }
            val sourceName = name.removeSuffix(".srs") + ".source-url.txt"
            val sourceFile = File(externalAssets, sourceName)
            if (!sourceFile.exists() && sourceName in files) {
                assets.open("$prefix/$sourceName").use { input ->
                    sourceFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    val process: String = JavaUtil.getProcessName()
    private val isMainProcess = process == BuildConfig.APPLICATION_ID
    val isBgProcess = process.endsWith(":bg")

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)

        if (isMainProcess || isBgProcess) {
            externalAssets.mkdirs()
            migrateLegacyRuleSets()
            if (isMainProcess) installBundledDefaultRuleSets()
            // earlier builds defaulted the log level to "none" and the settings screen persisted it
            if (!DataStore.configurationStore.getBoolean("logLevelDefaultInfo", false)) {
                if (DataStore.logLevel == 0) DataStore.logLevel = 2
                DataStore.configurationStore.putBoolean("logLevelDefaultInfo", true)
            }
            Seq.setContext(this)
            Libcore.initCore(
                process,
                cacheDir.absolutePath + "/",
                filesDir.absolutePath + "/",
                externalAssets.absolutePath + "/",
                DataStore.logBufSize,
                DataStore.logLevel > 0,
                nativeInterface, nativeInterface, LocalResolverImpl
            )

            // fix multi process issue in Android 9+
            JavaUtil.handleWebviewDir(this)

            runOnDefaultDispatcher {
                PackageCache.register()
                cleanWebview()
            }
        }

        if (isMainProcess) {
            ProfileManager.installDefaultRouteTemplate()
            ProfileManager.migrateDefaultAnalysisDomains()
            Theme.apply(this)
            Theme.applyNightTheme()
            runOnDefaultDispatcher {
                DefaultNetworkListener.start(this) {
                    underlyingNetwork = it
                }

                updateNotificationChannels()
            }
        }

        if (BuildConfig.DEBUG) {
            System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannels()
    }

    override fun getWorkManagerConfiguration(): WorkConfiguration {
        return WorkConfiguration.Builder()
            .setDefaultProcessName("${BuildConfig.APPLICATION_ID}:bg")
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        Libcore.forceGc()
    }

    @SuppressLint("InlinedApi")
    companion object {

        lateinit var application: SagerNet

        val isTv by lazy {
            uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

        val configureIntent: (Context) -> PendingIntent by lazy {
            {
                PendingIntent.getActivity(
                    it,
                    0,
                    Intent(
                        application, MainActivity::class.java
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            }
        }
        val activity by lazy { application.getSystemService<ActivityManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val user by lazy { application.getSystemService<UserManager>()!! }
        val uiMode by lazy { application.getSystemService<UiModeManager>()!! }
        val power by lazy { application.getSystemService<PowerManager>()!! }

        fun getClipboardText(): String {
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString() ?: ""
        }

        fun trySetPrimaryClip(clip: String) = try {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
            true
        } catch (e: RuntimeException) {
            Logs.w(e)
            false
        }

        fun updateNotificationChannels() {
            if (Build.VERSION.SDK_INT >= 26) @RequiresApi(26) {
                notification.createNotificationChannels(
                    listOf(
                        NotificationChannel(
                            "service-vpn",
                            application.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW
                        ),   // #1355
                        NotificationChannel(
                            "service-proxy",
                            application.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW
                        ), NotificationChannel(
                            "service-subscription",
                            application.getText(R.string.service_subscription),
                            NotificationManager.IMPORTANCE_DEFAULT
                        ), NotificationChannel(
                            "connection-test",
                            application.getText(R.string.connection_test),
                            NotificationManager.IMPORTANCE_DEFAULT
                        )
                    )
                )
            }
        }

        fun startService() = ContextCompat.startForegroundService(
            application, Intent(application, SagerConnection.serviceClass)
        )

        fun reloadService() =
            application.sendBroadcast(Intent(Action.RELOAD).setPackage(application.packageName))

        // Deliver committed configuration changes to the service process, even
        // when the subscription worker and UI run in different processes.
        fun notifySelectorChanged(groupIds: Collection<Long>, profileIds: Collection<Long> = emptyList()) {
            if (groupIds.isEmpty() && profileIds.isEmpty()) return
            application.sendBroadcast(Intent(Action.SELECTOR_CHANGED).setPackage(application.packageName)
                .putExtra("groups", groupIds.distinct().toLongArray())
                .putExtra("profiles", profileIds.distinct().toLongArray()))
        }

        fun stopService() =
            application.sendBroadcast(Intent(Action.CLOSE).setPackage(application.packageName))

        var underlyingNetwork: Network? = null

        var appVersionNameForDisplay = {
            var n = BuildConfig.VERSION_NAME
            if (isPreview) {
                n += " " + BuildConfig.PRE_VERSION_NAME
            } else if (!isOss) {
                n += " ${BuildConfig.FLAVOR}"
            }
            if (BuildConfig.DEBUG) {
                n += " DEBUG"
            }
            n
        }()
    }

}
