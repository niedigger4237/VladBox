package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.assertEquals
import org.junit.Test

class RootCleanupEvidenceTest {
    private val owner = "asteriskbox"
    private val clean = """{"schemaVersion":2,"owner":"asteriskbox","coreType":"sing-box","mode":"tun","phase":"stopped","children":{"core":null,"helper":null},"matcher":{"configured":false,"active":false},"rules":{"active":false,"generation":0,"categories":[]},"failure":null}"""
    private fun parse(raw: String, ok: Boolean = true) = RootCleanupEvidence.parse(ok, raw, owner)

    @Test fun stoppedRequiresAllResourcesInactive() {
        for (mode in listOf("tun", "tproxy", "tun2socks", "bpf2socks")) {
            assertEquals(RootCleanupEvidence.STOPPED, parse(clean.replace("\"tun\"", "\"$mode\"")))
        }
        assertEquals(RootCleanupEvidence.INCOMPLETE, parse(clean.replace("\"stopped\"", "\"failed\"")))
        assertEquals(RootCleanupEvidence.INCOMPLETE, parse(clean.replace("\"core\":null", "\"core\":{\"pid\":123}")))
        assertEquals(RootCleanupEvidence.INCOMPLETE, parse(clean.replace("\"helper\":null", "\"helper\":{\"pid\":456}")))
        assertEquals(RootCleanupEvidence.INCOMPLETE, parse(clean.replace("\"active\":false", "\"active\":true")))
        assertEquals(RootCleanupEvidence.INCOMPLETE, parse(clean.replace("\"generation\":0", "\"generation\":1")))
        assertEquals(RootCleanupEvidence.INCOMPLETE, parse(clean.replace("\"categories\":[]", "\"categories\":[\"tc\"]")))
    }

    @Test fun absenceAndUncertaintyAreDistinct() {
        assertEquals(RootCleanupEvidence.NO_STATE, parse("NF_STATE_ABSENT"))
        assertEquals(RootCleanupEvidence.UNKNOWN, parse("NF_STATE_ABSENT", false))
        assertEquals(RootCleanupEvidence.UNKNOWN, parse(""))
        assertEquals(RootCleanupEvidence.UNKNOWN, parse("{"))
        assertEquals(RootCleanupEvidence.UNKNOWN, parse(clean.replace(owner, "asteriskng")))
        assertEquals(RootCleanupEvidence.UNKNOWN, parse(clean.replace("\"schemaVersion\":2", "\"schemaVersion\":1")))
    }
}
