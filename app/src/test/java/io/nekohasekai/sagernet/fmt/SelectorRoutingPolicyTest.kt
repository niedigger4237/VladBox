package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class SelectorRoutingPolicyTest {
    @Test fun singleHopMembersNeverShareLogicalEntry() {
        assertFalse(SelectorRoutingPolicy.shareIngress(true, 0, 0))
        assertTrue(SelectorRoutingPolicy.shareIngress(true, 2, 2))
        assertFalse(SelectorRoutingPolicy.shareIngress(true, 0, 2))
        assertTrue(SelectorRoutingPolicy.shareIngress(false, 0, 0))
    }

    @Test fun explicitRoutesRemainPinnedAfterSelection() {
        val tags = mapOf(1L to "A", 2L to "B")
        assertEquals("A", SelectorRoutingPolicy.explicitRouteTag(true, 1, 1, tags))
        assertEquals("A", SelectorRoutingPolicy.explicitRouteTag(true, 2, 1, tags))
        assertEquals("B", SelectorRoutingPolicy.explicitRouteTag(true, 1, 2, tags))
        assertEquals("", SelectorRoutingPolicy.explicitRouteTag(true, 1, 3, tags))
        assertEquals("proxy", SelectorRoutingPolicy.explicitRouteTag(false, 1, 1, tags))
    }

    @Test fun changedWholeConfigOverrideRequiresRestart() {
        assertTrue(SelectorRoutingPolicy.sameConfigOverride(null, " "))
        assertTrue(SelectorRoutingPolicy.sameConfigOverride("{}", " {} "))
        assertFalse(SelectorRoutingPolicy.sameConfigOverride("{}", "{\"dns\":{}}"))
    }
}
