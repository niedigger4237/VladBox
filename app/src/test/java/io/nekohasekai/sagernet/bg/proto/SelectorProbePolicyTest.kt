package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.*
import org.junit.Test

class SelectorProbePolicyTest {
    @Test fun reusesOnlyVerifiedSimpleMembers() {
        assertTrue(SelectorProbePolicy.eligible(true, false, true, false, false, true, true))
        assertFalse(SelectorProbePolicy.eligible(false, false, true, false, false, true, true))
        assertFalse(SelectorProbePolicy.eligible(true, true, true, false, false, true, true))
        assertFalse(SelectorProbePolicy.eligible(true, false, false, false, false, true, true))
        assertFalse(SelectorProbePolicy.eligible(true, false, true, true, false, true, true))
        assertFalse(SelectorProbePolicy.eligible(true, false, true, false, true, true, true))
        assertFalse(SelectorProbePolicy.eligible(true, false, true, false, false, false, true))
        assertFalse(SelectorProbePolicy.eligible(true, false, true, false, false, true, false))
    }
}
