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
    internal val runtimeBootConfigPath get() = "$BOOT/runtime-asteriskd.json"
    internal val runtimeBootCorePath get() = "$BOOT/runtime-config.json"
    private val ready get() = "$BOOT/ready"
    private val monitorLock get() = "$BOOT/monitor.lock"
    private val libDir get() = SagerNet.application.applicationInfo.nativeLibraryDir

    /** a file from the app's lib dir, or its persistent copy when start-on-boot is on */
    fun exec(f: File): File =
        if (DataStore.configurationStore.getBoolean("startOnBoot", false) && f.parent == libDir) File(BIN, f.name) else f

    /** Stop new autonomous transactions and wait for any in-flight one before
     * touching the daemon. The boot snapshot stays armed for the next reboot. */
    internal fun quiesceMonitor(): Boolean {
        val result = RootShell.run(
            "set -e", "umask 077", "mkdir -p ${RootShell.q(BOOT)}",
            "chmod 700 ${RootShell.q(BOOT)}",
            "cat /proc/sys/kernel/random/boot_id > ${RootShell.q("$BOOT/manual-owner")}",
            "if command -v flock >/dev/null 2>&1; then (flock -x -w 90 9) 9>${RootShell.q(monitorLock)}; fi",
            timeoutMs = 95000,
        )
        return result.ok
    }

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
        return installMonitor()?.let { it } ?: installScript()
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
BOOT_ID="${'$'}(cat /proc/sys/kernel/random/boot_id)" || exit 1
[ "${'$'}(cat "${'$'}BASE/boot/manual-owner" 2>/dev/null)" != "${'$'}BOOT_ID" ] || exit 0
STATUS="${'$'}("${'$'}BASE/bin/libasteriskd.so" status 2>&1)"
printf '%s\n' "${'$'}STATUS" | grep -Eq '"code"[[:space:]]*:[[:space:]]*"not_running"' || exit 0
[ -f "${'$'}BASE/boot/ready" ] || exit 0
GEN="${'$'}(cat "${'$'}BASE/boot/ready")"
[ -n "${'$'}GEN" ] || exit 0
nohup "${'$'}BASE/bin/libasteriskd.so" boot --config "${'$'}BASE/boot/asteriskd.json" </dev/null >"${'$'}BASE/run/logs/boot.out" 2>&1 &
if [ -f "${'$'}BASE/boot/tether-policy" ]; then
    nohup sh "${'$'}BASE/bin/boot-tether-monitor.sh" "${'$'}BASE" "${'$'}GEN" </dev/null >/dev/null 2>&1 &
fi
"""

    private fun installMonitor(): String? {
        val tmp = File.createTempFile("boot-monitor-", ".sh", SagerNet.application.noBackupFilesDir)
        return try {
            SagerNet.application.assets.open("root/boot-tether-monitor.sh").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val target = "$BIN/boot-tether-monitor.sh"
            val r = RootShell.run("set -e", "umask 077",
                "cp ${RootShell.q(tmp)} ${RootShell.q(target + ".new")}",
                "chmod 700 ${RootShell.q(target + ".new")}",
                "mv -f ${RootShell.q(target + ".new")} ${RootShell.q(target)}")
            if (r.ok) null else r.err.ifBlank { "Cannot install ROOT boot monitor" }
        } finally {
            tmp.delete()
        }
    }

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
    internal fun publish(coreJson: String, daemonJson: String, tetherPolicy: String? = null, tunTemplate: String? = null, daemonTemplate: String? = null): String? {
        if (!DataStore.configurationStore.getBoolean("startOnBoot", false)) return null
        val core = File.createTempFile("boot-core-", ".json", SagerNet.application.noBackupFilesDir)
        val daemon = File.createTempFile("boot-daemon-", ".json", SagerNet.application.noBackupFilesDir)
        val policy = File.createTempFile("boot-tether-", ".txt", SagerNet.application.noBackupFilesDir)
        val tun = File.createTempFile("boot-tun-", ".json", SagerNet.application.noBackupFilesDir)
        val daemonTun = File.createTempFile("boot-tun-daemon-", ".json", SagerNet.application.noBackupFilesDir)
        return try {
            core.writeText(coreJson)
            daemon.writeText(daemonJson)
            policy.writeText(tetherPolicy.orEmpty())
            tun.writeText(tunTemplate.orEmpty())
            daemonTun.writeText(daemonTemplate.orEmpty())
            val policyPath = "$BOOT/tether-policy"
            val generation = java.util.UUID.randomUUID().toString()
            val result = RootShell.run(
                "set -e", "umask 077",
                "mkdir -p ${RootShell.q(BOOT)} && chmod 700 ${RootShell.q(BOOT)}",
                "rm -f ${RootShell.q(ready)}",
                "cp ${RootShell.q(core)} ${RootShell.q(bootCoreConfigPath + ".new")}",
                "cp ${RootShell.q(daemon)} ${RootShell.q(bootConfigPath + ".new")}",
                "chmod 600 ${RootShell.q(bootCoreConfigPath + ".new")} ${RootShell.q(bootConfigPath + ".new")}",
                "mv -f ${RootShell.q(bootCoreConfigPath + ".new")} ${RootShell.q(bootCoreConfigPath)}",
                "mv -f ${RootShell.q(bootConfigPath + ".new")} ${RootShell.q(bootConfigPath)}",
                "rm -f ${RootShell.q("$BOOT/downstream-status")} ${RootShell.q(policyPath)} ${RootShell.q("$BOOT/tun-template.json")} ${RootShell.q("$BOOT/daemon-template.json")}",
                if (tunTemplate != null && daemonTemplate != null) "cp ${RootShell.q(tun)} ${RootShell.q("$BOOT/tun-template.json")} && cp ${RootShell.q(daemonTun)} ${RootShell.q("$BOOT/daemon-template.json")} && chmod 600 ${RootShell.q("$BOOT/tun-template.json")} ${RootShell.q("$BOOT/daemon-template.json")}" else "true",
                if (tetherPolicy != null) "cp ${RootShell.q(policy)} ${RootShell.q(policyPath)} && chmod 600 ${RootShell.q(policyPath)}" else "true",
                "printf '%s\\n' ${RootShell.q(generation)} > ${RootShell.q(ready + ".new")}",
                "mv -f ${RootShell.q(ready + ".new")} ${RootShell.q(ready)}",
            )
            if (result.ok) null else result.err.ifBlank { "Cannot publish ROOT boot config" }
        } finally {
            core.delete()
            daemon.delete()
            policy.delete()
            tun.delete()
            daemonTun.delete()
        }
    }

    /** Enabling arms only a subsequently verified session, not stale JSON. */
    @Synchronized
    fun apply(enable: Boolean): String? {
        if (!RootShell.isAvailable()) return "root is not available"
        if (!quiesceMonitor()) return "Cannot stop autonomous ROOT monitor transaction"
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
