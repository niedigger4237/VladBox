package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.SagerNet
import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    // the :throne process hosts a different Go runtime and must never load libcore
    private fun emit(line: String) {
        if (SagerNet.application.process.endsWith(":throne")) android.util.Log.w("VladBox", line)
        else Libcore.nekoLogPrintln(line)
    }


    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // level int use logrus.go

    fun d(message: String) {
        emit("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        emit("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        emit("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        emit("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        emit("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        emit("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        emit("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        emit("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        emit("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        emit("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}