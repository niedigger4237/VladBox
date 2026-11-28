package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*

class TrafficLooper(val data: BaseService.Data, private val sc: CoroutineScope) {
    private var job: Job? = null
    private var updater: TrafficUpdater? = null
    private var ledger: TrafficLedger? = null
    private var preparedOwner: ProxyInstance? = null
    private val counters = linkedMapOf<String, TrafficUpdater.TrafficLooperData>()

    // All counter reads belong to the polling job or to stop(), after joining it.
    fun prepare(proxy: ProxyInstance) {
        if (preparedOwner != null || DataStore.speedInterval <= 0) return
        val mapping = proxy.config.trafficMap.mapValues { (_, profiles) -> profiles.map { it.id }.distinct() }
        val bases = proxy.config.trafficMap.values.flatten().associate { it.id to TrafficLedger.Bytes(it.rx, it.tx) }
        ledger = TrafficLedger(mapping, bases)
        val tags = mapping.keys + setOf(TAG_PROXY, TAG_BYPASS)
        for (tag in tags) counters[tag] = TrafficUpdater.TrafficLooperData(tag = tag)
        proxy.box.enableStats(tags.joinToString("\n"))
        updater = TrafficUpdater(proxy.box, counters.values.toList())
        preparedOwner = proxy
        Logs.d("VPN traffic: native stats enabled for ${tags.size} tags")
    }

    private fun totals() = counters.mapValues { (_, item) -> TrafficLedger.Bytes(item.rx, item.tx) }

    suspend fun stop() {
        val running = job
        job = null
        running?.cancelAndJoin()
        if (running == null) return
        try {
            updater?.updateAll() // last pending deltas, before native shutdown
        } catch (e: Exception) {
            Logs.w("Final traffic sample failed", e)
        }
        if (!DataStore.profileTrafficStatistics) return
        val deltas = ledger?.profileDeltas(totals()).orEmpty()
        val updates = mutableListOf<TrafficData>()
        for ((id, bytes) in deltas) {
            if (bytes.rx == 0L && bytes.tx == 0L) continue
            // Update only counters; never overwrite a profile edited during the session.
            if (SagerDatabase.proxyDao.addTraffic(id, bytes.rx, bytes.tx) == 0) continue
            val current = SagerDatabase.proxyDao.getById(id) ?: continue
            ProfileManager.postUpdate(current)
            updates.add(TrafficData(id = id, rx = current.rx, tx = current.tx))
        }
        data.binder.broadcast { callback -> updates.forEach { callback.cbTrafficUpdate(it) } }
        Logs.d("finally traffic post done")
    }

    fun start() {
        if (job != null || updater == null) return
        job = sc.launch { loop() }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong()
        val activeUpdater = updater ?: return
        val activeLedger = ledger ?: return
        if (delayMs <= 0) return
        while (currentCoroutineContext().isActive && data.proxy === preparedOwner) {
            activeUpdater.updateAll()
            currentCoroutineContext().ensureActive()
            val bytes = totals()
            val session = activeLedger.sessionTotal(bytes)
            val rates = activeLedger.sessionTotal(counters.mapValues { (_, item) ->
                TrafficLedger.Bytes(item.rxRate, item.txRate)
            })
            val bypass = counters[TAG_BYPASS]
            val speed = SpeedDisplayData(
                rates.tx, rates.rx,
                if (DataStore.showDirectSpeed) bypass?.txRate ?: 0L else 0L,
                if (DataStore.showDirectSpeed) bypass?.rxRate ?: 0L else 0L,
                session.tx, session.rx
            )
            val profileTotals = if (DataStore.profileTrafficStatistics) activeLedger.profileTotals(bytes) else emptyMap()
            if (data.state == BaseService.State.Connected &&
                data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)) {
                data.binder.broadcast { callback ->
                    if (data.binder.callbackIdMap[callback] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        callback.cbSpeedUpdate(speed)
                        profileTotals.forEach { (id, total) ->
                            callback.cbTrafficUpdate(TrafficData(id = id, rx = total.rx, tx = total.tx))
                        }
                    }
                }
            }
            data.notification?.apply { if (listenPostSpeed) postNotificationSpeedUpdate(speed) }
            delay(delayMs)
        }
    }
}
