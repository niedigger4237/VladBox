package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = LATENCY_TEST_TIMEOUT_MS

    suspend fun doTest(profile: ProxyEntity): Int {
        if (DataStore.serviceState.connected && profile.id == DataStore.currentProfile && !profile.isRawCoreConfig()) {
            return activeProfileDelay(profile.id, link)
        }
        return LatencyPolicy.requirePositive(TestInstance(profile, link, timeout).doTest())
    }

}