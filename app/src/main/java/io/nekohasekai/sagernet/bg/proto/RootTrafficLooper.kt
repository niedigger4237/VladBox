package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
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

    /** Задержка (мс) основного outbound до [url]; бросает исключение при ошибке. */
    fun urlTest(url: String, timeoutMs: Int): Int {
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
