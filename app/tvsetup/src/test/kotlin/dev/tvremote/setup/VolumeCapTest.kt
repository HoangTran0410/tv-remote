package dev.tvremote.setup

import dev.tvremote.setup.rules.Rules
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which volume limit holds at a time of day (Rules.volumeCap), what Guard then enforces: the setter is RulesTest's. */
class VolumeCapTest {
    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply { set(2026, 9, 14, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun rules(vararg r: Triple<String, String, Int>) =
        JSONObject().put("volume", JSONArray().apply { r.forEach { put(JSONObject().put("from", it.first).put("to", it.second).put("max", it.third)) } })
    private fun max(o: JSONObject, h: Int, m: Int) = Rules.volumeCap(o, at(h, m))?.optInt("max")

    @Test fun pastMidnight() {
        val o = rules(Triple("22:00", "06:00", 30))
        assertEquals(30, max(o, 22, 0))
        assertEquals(30, max(o, 2, 15))
        assertNull(max(o, 6, 0))                                      // the end is free
        assertNull(max(o, 21, 59))
    }

    @Test fun overlappingTakesTheLowest() {
        val o = rules(Triple("20:00", "23:00", 50), Triple("22:00", "06:00", 30))
        assertEquals(50, max(o, 21, 0))
        assertEquals(30, max(o, 22, 30))
        assertEquals(30, max(o, 23, 30))
    }

    @Test fun nothingSetOrBrokenIsNoLimit() {
        assertNull(Rules.volumeCap(JSONObject(), at(23, 0)))
        assertNull(max(rules(Triple("22:00", "22:00", 30)), 22, 0))   // the same from and to: never
        assertNull(max(rules(Triple("25:00", "06:00", 30)), 23, 0))
        assertNull(max(rules(Triple("", "06:00", 30)), 2, 0))
    }
}
