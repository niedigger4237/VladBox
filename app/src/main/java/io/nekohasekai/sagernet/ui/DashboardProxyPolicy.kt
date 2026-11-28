package io.nekohasekai.sagernet.ui

internal object DashboardProxyPolicy {
    data class Outcome(val delay: Int?, val historyKey: String)

    /** The synthetic GLOBAL overview is not an actual selector/failover. */
    fun priority(name: String, synthetic: Boolean, managedSelector: Boolean): Int =
        when {
            managedSelector && name == "proxy" && !synthetic -> 0
            !synthetic -> 1
            else -> 2
        }
}
