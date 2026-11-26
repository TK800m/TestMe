package nl.tapo.deurbel.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EventTypesTest {
    @Test fun ringIsRarestCandidate() {
        val stats = JSONObject()
        repeat(40) { EventTypes.record(stats, "2", 1000L + it) }
        repeat(25) { EventTypes.record(stats, "6", 1000L + it) }
        repeat(3) { EventTypes.record(stats, "10", 1000L + it) }
        assertEquals("10", EventTypes.pickRing(listOf("2", "6", "10"), stats, JSONObject()))
        assertEquals("Onbekende gebeurtenis (17)", EventTypes.name("17", JSONObject()))
        assertEquals("Aanbellen", EventTypes.name("10", JSONObject().put("10", "ring")))
    }

    @Test fun labelledMotionIsNotRing() {
        val stats = JSONObject().put("2", JSONObject().put("n", 1)).put("10", JSONObject().put("n", 5))
        assertEquals("10", EventTypes.pickRing(listOf("2", "10"), stats, JSONObject().put("2", "motion")))
    }
}
