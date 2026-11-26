package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RootHealthTest {
    private fun parse(json: String) =
        RootHealth.parse(JsonParser.parseString(json).asJsonObject, "asteriskbox")

    @Test fun acceptsOnlyExplicitTerminalStates() {
        assertEquals(RootHealth.ABSENT, parse("""{"code":"not_running"}"""))
        assertEquals(RootHealth.FAILED, parse("""{"code":"ok","snapshot":{"owner":"asteriskbox","supervisorPid":123,"phase":"failed"}}"""))
        assertEquals(RootHealth.INACTIVE, parse("""{"code":"ok","snapshot":{"owner":"asteriskbox","supervisorPid":123,"phase":"stopped"}}"""))
        assertEquals(RootHealth.RUNNING, parse("""{"code":"ok","snapshot":{"owner":"asteriskbox","supervisorPid":123,"phase":"running"}}"""))
    }
    @Test fun distinguishesPolicyPauseFromTransitions() {
        assertEquals(RootHealth.INACTIVE, parse("""{"code":"ok","snapshot":{"owner":"asteriskbox","supervisorPid":123,"phase":"paused"}}"""))
        assertEquals(RootHealth.TRANSITIONING, parse("""{"code":"ok","snapshot":{"owner":"asteriskbox","supervisorPid":123,"phase":"stopping"}}"""))
        assertEquals(RootHealth.UNKNOWN, parse("""{"code":"ok","snapshot":{"owner":"asteriskbox","supervisorPid":123,"phase":"other"}}"""))
        val c = RootHealthConfirmation()
        repeat(4) { assertFalse(c.observe(RootHealth.INACTIVE)) }
    }
    @Test fun doesNotActOnUnknownOrForeignStatus() {
        assertEquals(RootHealth.UNKNOWN, RootHealth.parse(null, "asteriskbox"))
        assertEquals(RootHealth.UNKNOWN, parse("""{"code":"ok","snapshot":{"owner":"other","supervisorPid":123,"phase":"failed"}}"""))
        assertEquals(RootHealth.UNKNOWN, parse("""{"code":"ok","snapshot":{"owner":"asteriskbox","supervisorPid":"bad","phase":"failed"}}"""))
        assertEquals(RootHealth.UNKNOWN, parse("""{"code":"permission_denied"}"""))
    }
    @Test fun requiresConsecutiveTerminalObservations() {
        val c = RootHealthConfirmation()
        assertFalse(c.observe(RootHealth.FAILED))
        assertFalse(c.observe(RootHealth.UNKNOWN))
        assertFalse(c.observe(RootHealth.ABSENT))
        assertFalse(c.observe(RootHealth.RUNNING))
        assertFalse(c.observe(RootHealth.FAILED))
        assertTrue(c.observe(RootHealth.ABSENT))
        c.reset()
        assertFalse(c.observe(RootHealth.ABSENT))
    }
}
