package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

internal object ClashApiSecret {
    val value: String by lazy {
        val dir = SagerNet.application.noBackupFilesDir
        val file = File(dir, "clash-api.secret")
        RandomAccessFile(File(dir, "clash-api.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val existing = if (file.exists()) file.readText().trim() else ""
                if (existing.matches(Regex("[0-9a-f]{64}"))) existing
                else {
                    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    val secret = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
                    val temp = File(dir, "clash-api.secret.tmp")
                    temp.writeText(secret)
                    check(temp.renameTo(file)) { "Cannot persist Clash API secret" }
                    secret
                }
            }
        }
    }
}
