package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.nekohasekai.sagernet.database.ProxyEntity

class UrlTest(private val service: ISagerNetService? = null) {

    val link = DataStore.connectionTestURL
    private val timeout = LATENCY_TEST_TIMEOUT_MS

    suspend fun doTest(profile: ProxyEntity): Int {
        if (DataStore.serviceState.connected && service != null) {
            val session = readService { service.selectorSessionId } ?: 0L
            if (session != 0L) {
                val tag = readService { service.getSelectorProbeTag(session, profile.id) }.orEmpty()
                if (tag.isNotBlank()) {
                    suspend fun verify() {
                        val valid = readService {
                            service.selectorSessionId == session &&
                                service.getSelectorProbeTag(session, profile.id) == tag
                        } == true
                        if (!valid) throw ActiveProfileChangedException()
                    }
                    verify()
                    // A member probe addresses the outbound directly, never the
                    // mutable top-level selector. No PUT or profile selection.
                    val delay = try {
                        clashDelay(tag, link)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        verify()
                        throw e
                    }
                    verify()
                    return delay
                }
            }
        }
        if (DataStore.serviceState.connected && profile.id == DataStore.currentProfile && !profile.isRawCoreConfig()) {
            // Even the same profile ID can reconnect while a probe is running.
            val binder = service
            val session = if (binder != null) readService { binder.selectorSessionId } else null
            if (binder != null && (session == null || session == 0L))
                throw ActiveProfileChangedException()
            return activeProfileDelay(profile.id, link) {
                binder == null || readService { binder.selectorSessionId == session } == true
            }
        }
        return LatencyPolicy.requirePositive(TestInstance(profile, link, timeout).doTest())
    }

    private suspend fun <T> readService(block: () -> T): T? =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: android.os.RemoteException) {
                null
            }
        }

}