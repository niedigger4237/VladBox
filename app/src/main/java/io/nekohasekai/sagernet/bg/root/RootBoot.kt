package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import java.io.File

/**
 * «Запуск при загрузке»: скрипт в /data/adb/service.d (Magisk / KernelSU) запускает `asteriskd boot`
 * с отдельным подтверждённым загрузочным конфигом. Путь к нативным библиотекам приложения меняется при обновлении, поэтому
 * при включённой опции asteriskd, помощники и встроенное ядро копируются в [BIN].
 */
object RootBoot {
    private const val SCRIPT = "/data/adb/service.d/niefigger-hub.sh"
    private val BIN get() = "${RootCore.BASE}/bin"
    private val BOOT get() = "${RootCore.BASE}/boot"
    internal val bootConfigPath get() = "$BOOT/asteriskd.json"
    internal val bootCoreConfigPath get() = "$BOOT/config.json"
    private val ready get() = "$BOOT/ready"
    private val libDir get() = SagerNet.application.applicationInfo.nativeLibraryDir

    /** a file from the app's lib dir, or its persistent copy when start-on-boot is on */
    fun exec(f: File): File =
        if (DataStore.configurationStore.getBoolean("startOnBoot", false) && f.parent == libDir) File(BIN, f.name) else f

    /** copies the executables (called on every start while the option is on) */
    @Synchronized
    fun stage(): String? {
        if (!DataStore.configurationStore.getBoolean("startOnBoot", false)) return null
        val names = listOf("libasteriskd.so", "libhevtun.so", "libbpf2socks.so", "libbpf-matcher.so", "libsingbox.so")
        val cmds = mutableListOf("set -e", "umask 077", "mkdir -p ${RootShell.q(BIN)} ${RootShell.q(BOOT)}", "chmod 700 ${RootShell.q(BOOT)}", "rm -f ${RootShell.q(ready)}")
        for (n in names) {
            val src = File(libDir, n)
            if (!src.isFile) continue
            val target = RootShell.q("$BIN/$n")
            val temp = RootShell.q("$BIN/.$n.new")
            // Never overwrite a running executable in place; preserve the old file if copying fails.
            cmds += "if ! cmp -s ${RootShell.q(src)} $target; then rm -f $temp; cp ${RootShell.q(src)} $temp; chmod 755 $temp; mv -f $temp $target; else chmod 755 $target; fi"
        }
        val result = RootShell.run(*cmds.toTypedArray(), timeoutMs = 60_000)
        if (!result.ok) return result.err.ifBlank { "Cannot stage ROOT executables" }
        return installScript()
    }

    private fun script(): String = """#!/system/bin/sh
# NieFigger Hub: root service at boot (written by the app, do not edit)
umask 077
BASE=${RootCore.BASE}
until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 5
[ -f "${'$'}BASE/boot/ready" ] && [ -f "${'$'}BASE/boot/asteriskd.json" ] && [ -f "${'$'}BASE/boot/config.json" ] || exit 0
mkdir -p "${'$'}BASE/run/logs" || exit 1
# A manual connection may already have started during boot.
STATUS="${'$'}("${'$'}BASE/bin/libasteriskd.so" status 2>&1)"
printf '%s\n' "${'$'}STATUS" | grep -Eq '"code"[[:space:]]*:[[:space:]]*"not_running"' || exit 0
[ -f "${'$'}BASE/boot/ready" ] || exit 0
nohup "${'$'}BASE/bin/libasteriskd.so" boot --config "${'$'}BASE/boot/asteriskd.json" </dev/null >"${'$'}BASE/run/logs/boot.out" 2>&1 &
"""

    private fun installScript(): String? {
        val tmp = File.createTempFile("niefigger-boot-", ".sh", SagerNet.application.noBackupFilesDir)
        return try {
            tmp.writeText(script())
            val r = RootShell.run(
                "set -e", "umask 077", "mkdir -p /data/adb/service.d",
                "cp -f ${RootShell.q(tmp)} ${RootShell.q(SCRIPT + ".new")}",
                "chmod 755 ${RootShell.q(SCRIPT + ".new")}",
                "mv -f ${RootShell.q(SCRIPT + ".new")} ${RootShell.q(SCRIPT)}",
            )
            if (r.ok) null else r.err.ifBlank { "Cannot install ROOT boot script" }
        } finally {
            tmp.delete()
        }
    }

    /** Publish only after the interactive daemon has passed readiness. A missing
     * marker prevents the boot script from consuming an incomplete pair. */
    @Synchronized
    internal fun publish(coreJson: String, daemonJson: String): String? {
        if (!DataStore.configurationStore.getBoolean("startOnBoot", false)) return null
        val core = File.createTempFile("boot-core-", ".json", SagerNet.application.noBackupFilesDir)
        val daemon = File.createTempFile("boot-daemon-", ".json", SagerNet.application.noBackupFilesDir)
        return try {
            core.writeText(coreJson)
            daemon.writeText(daemonJson)
            val result = RootShell.run(
                "set -e", "umask 077",
                "mkdir -p ${RootShell.q(BOOT)} && chmod 700 ${RootShell.q(BOOT)}",
                "rm -f ${RootShell.q(ready)}",
                "cp ${RootShell.q(core)} ${RootShell.q(bootCoreConfigPath + ".new")}",
                "cp ${RootShell.q(daemon)} ${RootShell.q(bootConfigPath + ".new")}",
                "chmod 600 ${RootShell.q(bootCoreConfigPath + ".new")} ${RootShell.q(bootConfigPath + ".new")}",
                "mv -f ${RootShell.q(bootCoreConfigPath + ".new")} ${RootShell.q(bootCoreConfigPath)}",
                "mv -f ${RootShell.q(bootConfigPath + ".new")} ${RootShell.q(bootConfigPath)}",
                "touch ${RootShell.q(ready)}",
            )
            if (result.ok) null else result.err.ifBlank { "Cannot publish ROOT boot config" }
        } finally {
            core.delete()
            daemon.delete()
        }
    }

    /** Enabling arms only a subsequently verified session, not stale JSON. */
    @Synchronized
    fun apply(enable: Boolean): String? {
        if (!RootShell.isAvailable()) return "root is not available"
        val previous = DataStore.configurationStore.getBoolean("startOnBoot", false)
        DataStore.configurationStore.putBoolean("startOnBoot", enable)
        if (!enable) {
            val result = RootShell.run("set -e", "rm -f ${RootShell.q(ready)} ${RootShell.q(SCRIPT)} ${RootShell.q(SCRIPT + ".new")}")
            if (!result.ok) {
                DataStore.configurationStore.putBoolean("startOnBoot", previous)
                return result.err.ifBlank { "Cannot remove boot script" }
            }
            return null
        }
        // Reconnecting after enabling publishes a fresh config pair.
        val disarm = RootShell.run("rm -f ${RootShell.q(ready)}")
        if (!disarm.ok) {
            DataStore.configurationStore.putBoolean("startOnBoot", previous)
            return disarm.err.ifBlank { "Cannot disarm previous boot snapshot" }
        }
        val error = stage()
        if (error != null) DataStore.configurationStore.putBoolean("startOnBoot", previous)
        return error
    }
}
