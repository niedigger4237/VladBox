package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.root.RootShell
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Root-режимы запускают ядро отдельным процессом, поэтому счётчики и проверку связи
 * берём через Clash API этого ядра (127.0.0.1:[PORT]).
 */
object RootClashApi {
    const val PORT = 9090
    private const val BASE = "http://127.0.0.1:$PORT"

    /**
     * В TProxy-режиме правила перехвата ловят и loopback-трафик приложения (соединение с 127.0.0.1:9090 висит),
     * поэтому после первой неудачи ходим к API от имени root (curl или toybox nc), его трафик не перехватывается.
     */
    @Volatile
    var viaRoot = false

    private fun rootScript(path: String, seconds: Int): String =
        "if command -v curl >/dev/null 2>&1; then curl -s -N -m $seconds ${RootShell.q("$BASE$path")}; else " +
            "(printf 'GET $path HTTP/1.0\\r\\nHost: 127.0.0.1\\r\\n\\r\\n'; sleep $seconds) | nc 127.0.0.1 $PORT; fi"

    private fun rootGet(path: String, seconds: Int): String {
        // read until nc/curl closes stdout (the helper `sleep` keeps the shell alive longer, so don't wait for su itself)
        val p = ProcessBuilder("su", "-c", rootScript(path, seconds)).redirectErrorStream(true).start()
        val out = try {
            p.inputStream.bufferedReader().readText().trim()
        } finally {
            p.destroyForcibly()
        }
        if (out.isBlank()) error("no response from the core API")
        // nc returns the raw HTTP response, curl only the body
        return if (out.startsWith("HTTP/")) out.substringAfter("\r\n\r\n", out.substringAfter("\n\n", "")) else out
    }

    /** Задержка (мс) основного outbound до [url]; бросает исключение при ошибке. */
    fun urlTest(url: String, timeoutMs: Int): Int {
        if (viaRoot) return urlTestRoot(url, timeoutMs)
        return try {
            urlTestDirect(url, timeoutMs)
        } catch (e: java.io.IOException) {
            viaRoot = true
            urlTestRoot(url, timeoutMs)
        }
    }

    private fun urlTestRoot(url: String, timeoutMs: Int): Int {
        val path = "/proxies/$TAG_PROXY/delay?url=" + URLEncoder.encode(url, "UTF-8") + "&timeout=$timeoutMs"
        val j = JSONObject(rootGet(path, timeoutMs / 1000 + 2))
        if (!j.has("delay")) error(j.optString("message").ifBlank { "delay test failed" })
        return j.getInt("delay")
    }

    private fun urlTestDirect(url: String, timeoutMs: Int): Int {
        val u = "$BASE/proxies/$TAG_PROXY/delay?url=" + URLEncoder.encode(url, "UTF-8") + "&timeout=$timeoutMs"
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3000
            c.readTimeout = timeoutMs + 3000
            val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (c.responseCode !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("message") }.getOrNull()
                error(if (msg.isNullOrBlank()) "HTTP ${c.responseCode}" else msg)
            }
            return JSONObject(body).getInt("delay")
        } finally {
            c.disconnect()
        }
    }

    /** Поток {"up":N,"down":M} раз в секунду (байт/с); [onSample] вызывается на каждую строку. */
    fun streamTraffic(onSample: (up: Long, down: Long) -> Unit) {
        if (!viaRoot) {
            try {
                streamTrafficDirect(onSample)
                return
            } catch (e: java.io.IOException) {
                if (e is java.net.SocketTimeoutException && !e.message.orEmpty().contains("connect")) throw e
                viaRoot = true
            }
        }
        val p = ProcessBuilder("su", "-c", rootScript("/traffic", 3600)).redirectErrorStream(true).start()
        try {
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("{")) continue
                    val j = JSONObject(line)
                    onSample(j.optLong("up"), j.optLong("down"))
                }
            }
        } finally {
            p.destroyForcibly()
        }
    }

    private fun streamTrafficDirect(onSample: (up: Long, down: Long) -> Unit) {
        val c = URL("$BASE/traffic").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2000
            c.readTimeout = 15000
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val j = JSONObject(line)
                    onSample(j.optLong("up"), j.optLong("down"))
                }
            }
        } finally {
            c.disconnect()
        }
    }
}

class RootTrafficLooper(private val data: BaseService.Data, private val sc: CoroutineScope) {
    private var job: Job? = null

    fun start() {
        RootClashApi.viaRoot = false
        job = sc.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun loop() {
        if (DataStore.speedInterval == 0) return
        var txTotal = 0L
        var rxTotal = 0L
        while (sc.isActive) {
            try {
                RootClashApi.streamTraffic { up, down ->
                    txTotal += up
                    rxTotal += down
                    post(SpeedDisplayData(up, down, 0L, 0L, txTotal, rxTotal))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ядро ещё стартует или соединение оборвалось — повторяем
                Logs.d("root traffic: ${e.message}")
            }
            delay(1000)
        }
    }

    private fun post(speed: SpeedDisplayData) {
        sc.launch {
            if (data.state == BaseService.State.Connected
                && data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                    }
                }
            }
            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(speed)
            }
        }
    }
}
