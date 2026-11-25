package io.nekohasekai.sagernet.bg.root

import android.util.AtomicFile
import com.google.gson.Gson
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.net.InetAddress

/** App-private write-ahead ownership records. Only the VPN tether worker
 * reads/writes these files. Never replay stored shell commands. */
internal class TetherResourceJournal<T : Any>(name: String, private val type: Class<T>) {
    private val file by lazy {
        AtomicFile(File(SagerNet.application.noBackupFilesDir, "tether-resource-v1-$name.json"))
    }
    fun exists(): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    fun write(value: T): Boolean {
        return runCatching {
            val bytes = Gson().toJson(value).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 65536)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
            // AtomicFile can report some failures only in logcat; check content.
            file.readFully().contentEquals(bytes)
        }.getOrElse {
            Logs.w("VPN tether journal: write failed")
            false
        }
    }

    /** Null is not used to represent corruption. Caller must keep the record. */
    fun read(): T {
        val bytes = file.readFully()
        require(bytes.size in 1..65536)
        return requireNotNull(Gson().fromJson(String(bytes, Charsets.UTF_8), type))
    }

    fun clear(): Boolean = runCatching {
        file.delete()
        !exists()
    }.getOrDefault(false)

    companion object {
        fun validBoot(value: String): Boolean =
            value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        fun bootId(): String? = RootShell.run("cat /proc/sys/kernel/random/boot_id", timeoutMs = 5000)
            .takeIf { it.ok && validBoot(it.out) }?.out

        fun iface(value: String) = value.matches(Regex("[A-Za-z0-9_.-]{1,15}")) && value != "lo"
        fun suffix(value: String) = value.matches(Regex("[0-9a-f]{12}"))
        fun ids(table: Int, pref: Int) = table in 17900..17999 && pref in 17900..17998 && pref % 2 == 0
        fun ipv4(value: String): Boolean {
            val parts = value.split('.')
            return parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }
        }
        fun ipv6(value: String): Boolean = value.contains(':') && value.matches(Regex("[0-9a-fA-F:]+")) &&
            runCatching { InetAddress.getByName(value).address.size == 16 }.getOrDefault(false)
        fun cidr6(value: String): Boolean {
            val p = value.split('/')
            return p.size == 2 && ipv6(p[0]) && (p[1].toIntOrNull()?.let { it in 1..128 } == true)
        }
    }
}
