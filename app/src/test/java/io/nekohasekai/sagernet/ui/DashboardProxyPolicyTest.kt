package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class DashboardProxyPolicyTest {
    @Test fun realManagedSelectorPrecedesSyntheticOverview() {
        assertTrue(DashboardProxyPolicy.priority("proxy", false, true) <
            DashboardProxyPolicy.priority("GLOBAL", true, true))
        assertTrue(DashboardProxyPolicy.priority("other", false, false) <
            DashboardProxyPolicy.priority("GLOBAL", true, false))
        assertEquals(1, DashboardProxyPolicy.priority("proxy", false, false))
    }
}
