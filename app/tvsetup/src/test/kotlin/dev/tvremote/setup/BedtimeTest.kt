package dev.tvremote.setup

import dev.tvremote.setup.rules.Rules
import java.util.Calendar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BedtimeTest {
    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply { set(2026, 9, 14, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun rules(on: Boolean, from: String, to: String) = JSONObject().put("bedtime", JSONObject().put("on", on).put("from", from).put("to", to))

    @Test fun windowPastMidnight() {
        val r = rules(true, "22:00", "06:30")
        assertTrue(Rules.inBedtime(r, at(22, 0)))
        assertTrue(Rules.inBedtime(r, at(23, 59)))
        assertTrue(Rules.inBedtime(r, at(3, 0)))
        assertFalse(Rules.inBedtime(r, at(6, 30)))
        assertFalse(Rules.inBedtime(r, at(21, 59)))
        assertFalse(Rules.inBedtime(r, at(12, 0)))
    }

    @Test fun windowWithinADay() {
        val r = rules(true, "13:00", "15:00")
        assertTrue(Rules.inBedtime(r, at(14, 0)))
        assertFalse(Rules.inBedtime(r, at(15, 0)))
        assertFalse(Rules.inBedtime(r, at(12, 59)))
    }

    @Test fun offOrEmptyNeverApplies() {
        assertFalse(Rules.inBedtime(rules(false, "00:00", "23:59"), at(12, 0)))
        assertFalse(Rules.inBedtime(rules(true, "10:00", "10:00"), at(10, 0)))
        assertFalse(Rules.inBedtime(JSONObject(), at(23, 0)))
    }

    @Test fun severalRanges() {                                       // the night and an afternoon nap
        val r = JSONObject().put("bedtime", JSONObject().put("on", true).put("ranges", org.json.JSONArray()
            .put(JSONObject().put("from", "22:00").put("to", "06:30")).put(JSONObject().put("from", "13:00").put("to", "14:00"))))
        assertTrue(Rules.inBedtime(r, at(23, 0)))
        assertEquals("13:00", Rules.bedNow(r, at(13, 30))?.optString("from"))
        assertFalse(Rules.inBedtime(r, at(14, 0)))
        assertFalse(Rules.inBedtime(r, at(10, 0)))
        assertEquals("22:00", Rules.bedtime(r).optString("from"))     // what older remotes read: the first range
    }

    @Test fun oneRangeFromAnOlderTvSetup() {
        val b = Rules.bedtime(rules(true, "21:30", "06:00"))
        assertEquals(1, b.getJSONArray("ranges").length())
        assertEquals("21:30", b.getJSONArray("ranges").getJSONObject(0).optString("from"))
    }

    @Test fun extraTimeCountsForTodayOnly() {
        val now = at(12, 0)
        val o = JSONObject().put("extra", JSONObject().put(Rules.day(now), JSONObject().put("com.a.b", 30)))
        assertEquals(30, Rules.extra(o, "com.a.b", now))
        assertEquals(0, Rules.extra(o, "com.a.b", now + 86_400_000L))
        assertEquals(0, Rules.extra(o, "com.other", now))
    }
}
