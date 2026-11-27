package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.*

class TrafficLooper
    (
    val data: BaseService.Data, private val sc: CoroutineScope
) {

    private var job: Job? = null
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data

    suspend fun stop() {
        val running = job ?: return
        job = null
        running.cancelAndJoin()
        // finally traffic post
        if (!DataStore.profileTrafficStatistics) return
        val traffic = mutableMapOf<Long, TrafficData>()
        data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
            for (ent in ents) {
                val item = idMap[ent.id] ?: continue
                if (traffic.containsKey(ent.id)) continue
                ent.rx = item.rx
                ent.tx = item.tx
                ProfileManager.updateProfile(ent) // update DB
                traffic[ent.id] = TrafficData(
                    id = ent.id,
                    rx = ent.rx,
                    tx = ent.tx,
                )
            }
        }
        data.binder.broadcast { b ->
            for (t in traffic) {
                b.cbTrafficUpdate(t.value)
            }
        }
        Logs.d("finally traffic post done")
    }

    fun start() {
        if (job != null) return
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    fun selectMain(id: Long) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics) {
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    runOnDefaultDispatcher {
                        ProfileManager.updateProfile(it) // update DB
                    }
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        Logs.d("VPN traffic: interval=${delayMs}ms, profileStats=$profileTrafficStatistics, directSpeed=$showDirectSpeed")
        if (delayMs <= 0L) return

        var trafficUpdater: TrafficUpdater? = null
        var proxy: ProxyInstance?

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

        while (currentCoroutineContext().isActive) {
            proxy = data.proxy
            if (proxy == null) {
                delay(delayMs)
                continue
            }

            if (trafficUpdater == null) {
                if (!proxy.isInitialized()) {
                    delay(50)
                    continue
                }
                idMap.clear()
                idMap[-1] = itemBypass
                //
                val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                proxy.config.trafficMap.forEach { (tag, ents) ->
                    tags.add(tag)
                    for (ent in ents) {
                        val item = TrafficUpdater.TrafficLooperData(
                            tag = tag,
                            rx = ent.rx,
                            tx = ent.tx,
                            rxBase = ent.rx,
                            txBase = ent.tx,
                            ignore = proxy.config.selectorGroupId >= 0L,
                        )
                        idMap[ent.id] = item
                        tagMap[tag] = item
                        Logs.d("traffic count $tag to ${ent.id}")
                    }
                }
                if (proxy.config.selectorGroupId >= 0L) {
                    selectMain(proxy.config.mainEntId)
                }
                //
                trafficUpdater = TrafficUpdater(
                    box = proxy.box, items = idMap.values.toList()
                )
                proxy.box.enableStats(tags.joinToString("\n"))
                Logs.d("VPN traffic: native stats enabled for ${tags.size} tags")
            }

            trafficUpdater.updateAll()
            currentCoroutineContext().ensureActive()

            // add all non-bypass to "main"
            var mainTxRate = 0L
            var mainRxRate = 0L
            var mainTx = 0L
            var mainRx = 0L
            tagMap.forEach { (_, it) ->
                if (!it.ignore) {
                    mainTxRate += it.txRate
                    mainRxRate += it.rxRate
                }
                mainTx += it.tx - it.txBase
                mainRx += it.rx - it.rxBase
            }

            // speed
            val speed = SpeedDisplayData(
                mainTxRate,
                mainRxRate,
                if (showDirectSpeed) itemBypass.txRate else 0L,
                if (showDirectSpeed) itemBypass.rxRate else 0L,
                mainTx,
                mainRx
            )

            // broadcast (MainActivity)
            if (data.state == BaseService.State.Connected
                && data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                        if (profileTrafficStatistics) {
                            idMap.forEach { (id, item) ->
                                b.cbTrafficUpdate(
                                    TrafficData(id = id, rx = item.rx, tx = item.tx) // display
                                )
                            }
                        }
                    }
                }
            }

            // ServiceNotification
            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(speed)
            }

            delay(delayMs)
        }
    }
}