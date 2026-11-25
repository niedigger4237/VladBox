package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import java.io.File

/**
 * «Запуск при загрузке»: скрипт в /data/adb/service.d (Magisk / KernelSU) запускает `asteriskd boot`
 * с последним конфигом. Путь к нативным библиотекам приложения меняется при обновлении, поэтому
 * при включённой опции asteriskd, помощники и встроенное ядро копируются в [BIN].
 */
object RootBoot {
    private const val SCRIPT = "/data/adb/service.d/niefigger-hub.sh"
    private val BIN get() = "${RootCore.BASE}/bin"
    private val libDir get() = SagerNet.application.applicationInfo.nativeLibraryDir

    /** a file from the app's lib dir, or its persistent copy when start-on-boot is on */
    fun exec(f: File): File =
        if (DataStore.configurationStore.getBoolean("startOnBoot", false) && f.parent == libDir) File(BIN, f.name) else f

    /** copies the executables (called on every start while the option is on) */
    fun stage(): String? {
        if (!DataStore.configurationStore.getBoolean("startOnBoot", false)) return null
        val names = listOf("libasteriskd.so", "libhevtun.so", "libbpf2socks.so", "libbpf-matcher.so", "libsingbox.so")
        val cmds = mutableListOf("set -e", "mkdir -p ${RootShell.q(BIN)}")
        for (n in names) {
            val src = File(libDir, n)
            if (!src.isFile) continue
            val target = RootShell.q("$BIN/$n")
            val temp = RootShell.q("$BIN/.$n.new")
            // Never overwrite a running executable in place; preserve the old file if copying fails.
            cmds += "if ! cmp -s ${RootShell.q(src)} $target; then rm -f $temp; cp ${RootShell.q(src)} $temp; chmod 755 $temp; mv -f $temp $target; else chmod 755 $target; fi"
        }
        val result = RootShell.run(*cmds.toTypedArray(), timeoutMs = 60_000)
        return if (result.ok) null else result.err.ifBlank { "Cannot stage ROOT executables" }
    }

    private fun script(): String = """#!/system/bin/sh
# NieFigger Hub: root service at boot (written by the app, do not edit)
BASE=${RootCore.BASE}
until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 5
[ -f "${'$'}BASE/run/asteriskd.json" ] || exit 0
mkdir -p "${'$'}BASE/run/logs"
nohup "${'$'}BASE/bin/libasteriskd.so" boot --config "${'$'}BASE/run/asteriskd.json" </dev/null >"${'$'}BASE/run/logs/boot.out" 2>&1 &
"""

    /** @return null on success, otherwise a readable error */
    fun apply(enable: Boolean): String? {
        if (!RootShell.isAvailable()) return "root is not available"
        // stage() / exec() read the flag from the store, which is not updated yet when the switch is toggled
        val previous = DataStore.configurationStore.getBoolean("startOnBoot", false)
        DataStore.configurationStore.putBoolean("startOnBoot", enable)
        if (!enable) {
            val result = RootShell.run("rm -f ${RootShell.q(SCRIPT)}")
            if (!result.ok) {
                DataStore.configurationStore.putBoolean("startOnBoot", previous)
                return result.err.ifBlank { "Cannot remove boot script" }
            }
            return null
        }
        stage()?.let {
            DataStore.configurationStore.putBoolean("startOnBoot", previous)
            return it
        }
        val tmp = File(SagerNet.application.noBackupFilesDir, "niefigger-hub.sh").apply { writeText(script()) }
        try {
            val r = RootShell.run(
                "set -e",
                "mkdir -p /data/adb/service.d",
                "cp -f ${RootShell.q(tmp)} ${RootShell.q(SCRIPT)}",
                "chmod 755 ${RootShell.q(SCRIPT)}",
                // an existing config still points into the app's lib dir: redirect it to the copies
                "if [ -f ${RootShell.q(RootDaemon.daemonConfigPath)} ]; then sed -i 's#${libDir}/#${BIN}/#g' ${RootShell.q(RootDaemon.daemonConfigPath)}; fi",
            )
            return if (r.ok) null else r.err.ifBlank { "cannot write $SCRIPT" }
        } finally {
            tmp.delete()
        }
    }
}
