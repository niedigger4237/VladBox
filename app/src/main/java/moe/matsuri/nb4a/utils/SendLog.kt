package moe.matsuri.nb4a.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.LogRedactor
import io.nekohasekai.sagernet.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.IOException

object SendLog {
    // Create full log and send
    fun createLogFile(title: String): File {
        val logFile = File.createTempFile(
            "$title ",
            ".log",
            File(app.cacheDir, "log").also { it.mkdirs() })

        var report = CrashHandler.buildReportHeader()

        report += "Logcat: \n\n"

        logFile.writeText(report)

        try {
            // Restrict the exported logcat to this app UID. Never fall back to raw system logs.
            val process = ProcessBuilder("logcat", "-d", "--uid=${android.os.Process.myUid()}")
                .redirectErrorStream(true).start()
            try {
                val redactor = LogRedactor.Stream()
                java.io.FileOutputStream(logFile, true).bufferedWriter(Charsets.UTF_8).use { output ->
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.take(20000).forEach { output.appendLine(redactor.redact(it)) }
                    }
                }
            } finally { process.destroy() }
            logFile.appendText("\n")
        } catch (e: IOException) {
            Logs.w(e)
            logFile.appendText("Export logcat error: " + CrashHandler.formatThrowable(e))
        }

        logFile.appendText("\n")
        logFile.appendBytes(getNekoLog(0))

        return logFile
    }

    fun sendLog(context: Context, title: String) {
        val logFile = createLogFile(title)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/x-log")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            context, BuildConfig.APPLICATION_ID + ".cache", logFile
                        )
                    ), context.getString(R.string.abc_shareactionprovider_share_with)
            )
        )
    }

    // Get log bytes from neko.log
    fun getNekoLog(max: Long): ByteArray {
        return try {
            val file = File(
                SagerNet.application.cacheDir,
                "neko.log"
            )
            // Process the available log from its beginning. Starting at an
            // arbitrary byte can bypass a credential/PEM marker and cut UTF-8.
            val redactor = LogRedactor.Stream()
            val tail = java.util.ArrayDeque<ByteArray>()
            var size = 0L
            val full = if (max <= 0) java.io.ByteArrayOutputStream() else null
            FileInputStream(file).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val safe = (redactor.redact(line) + "\n").toByteArray(Charsets.UTF_8)
                    if (full != null) full.write(safe)
                    else {
                        tail.addLast(safe)
                        size += safe.size
                        while (size > max && tail.isNotEmpty()) size -= tail.removeFirst().size
                    }
                }
            }
            if (full != null) full.toByteArray()
            else java.io.ByteArrayOutputStream().apply { tail.forEach { write(it) } }.toByteArray()
        } catch (e: Exception) {
            LogRedactor.text(e.stackTraceToString()).toByteArray(Charsets.UTF_8)
        }
    }
}
