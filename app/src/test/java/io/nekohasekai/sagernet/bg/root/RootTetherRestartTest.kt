package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTetherRestartTest {
    private val initial = RootDaemon.DownstreamSnapshot(
        names = listOf("ap0"),
        identity = listOf("15:ap0:", "15:ap0:192.168.43.1/24"),
        ipv6Enabled = true,
        ipv6Prefixes = listOf(TetherIpv6.Down("ap0", "2001:db8:1::/64")),
    )
    private val daemonModes = listOf(RootDaemon.Mode.TPROXY, RootDaemon.Mode.TUN2SOCKS, RootDaemon.Mode.BPF2SOCKS)

    @Test fun daemonOwnedModesHandleAddressAndLinkChangesNatively() {
        val recreated = initial.copy(identity = listOf("19:ap0:", "19:ap0:192.168.99.1/24"))
        for (mode in daemonModes) assertFalse(initial.requiresRestart(mode, recreated))
    }

    @Test fun tunHostAddressChangeWithinSameSubnetKeepsCore() {
        val changed = initial.copy(identity = listOf("15:ap0:", "15:ap0:192.168.43.2/24"))
        assertFalse(initial.requiresRestart(RootDaemon.Mode.TUN, changed))
    }

    @Test fun tunNewLinkOrSubnetStillNeedsConfigurationRefresh() {
        val recreated = initial.copy(identity = listOf("19:ap0:", "19:ap0:192.168.43.1/24"))
        val renumbered = initial.copy(identity = listOf("15:ap0:", "15:ap0:192.168.99.1/24"))
        assertTrue(initial.requiresRestart(RootDaemon.Mode.TUN, recreated))
        assertTrue(initial.requiresRestart(RootDaemon.Mode.TUN, renumbered))
    }

    @Test fun actualPublishedConfigChangesRequireRestartInEveryMode() {
        val added = initial.copy(names = listOf("ap0", "rndis0"))
        val removed = initial.copy(names = emptyList(), identity = emptyList(), ipv6Prefixes = emptyList())
        val newPrefix = initial.copy(ipv6Prefixes = listOf(TetherIpv6.Down("ap0", "2001:db8:2::/64")))
        for (mode in RootDaemon.Mode.values()) {
            assertTrue(initial.requiresRestart(mode, added))
            assertTrue(initial.requiresRestart(mode, removed))
            assertTrue(initial.requiresRestart(mode, newPrefix))
            assertFalse(initial.requiresRestart(mode, initial.copy()))
        }
    }
}
