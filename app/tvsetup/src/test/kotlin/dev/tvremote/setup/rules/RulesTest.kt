package dev.tvremote.setup.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Rules.apply: what every remote's action does to the stored rules (the part of Rules.change that needs no Context). */
class RulesTest {
    private val now = 1_800_000_000_000L
    private fun d(s: String) = JSONObject(s)
    private fun bad(o: JSONObject, s: String) {
        try { Rules.apply(o, d(s), now); fail("accepted $s") } catch (e: IllegalArgumentException) { }
    }

    @Test fun readChangesNothing() {
        val o = JSONObject()
        assertFalse(Rules.apply(o, d("""{"do":""}"""), now))
        assertFalse(Rules.apply(o, d("""{"do":"get"}"""), now))
        assertEquals(0, o.length())
    }

    @Test fun appLocksNeedAPin() {
        val o = JSONObject()
        bad(o, """{"do":"lock","pkg":"com.netflix.ninja","on":true}""")          // no PIN yet
        bad(o, """{"do":"pin","pin":"12"}""")
        bad(o, """{"do":"pin","pin":"12ab"}""")
        Rules.apply(o, d("""{"do":"pin","pin":"2580"}"""), now)
        assertFalse(o.toString().contains("2580"))                                // kept as a hash only
        assertTrue(Rules.pinOk(o, "2580"))
        assertFalse(Rules.pinOk(o, "0000"))
        Rules.apply(o, d("""{"do":"lock","pkg":"com.netflix.ninja","on":true}"""), now)
        Rules.apply(o, d("""{"do":"lock","pkg":"com.netflix.ninja","on":true}"""), now)
        assertEquals(listOf("com.netflix.ninja"), Rules.locked(o))
        bad(o, """{"do":"lock","pkg":"dev.tvremote.setup","on":true}""")        // its own PIN screen
        Rules.apply(o, d("""{"do":"pin","pin":"13579"}"""), now)                 // a new PIN keeps the apps
        assertTrue(Rules.pinOk(o, "13579"))
        assertFalse(Rules.pinOk(o, "2580"))
        assertEquals(listOf("com.netflix.ninja"), Rules.locked(o))
        Rules.apply(o, d("""{"do":"lock","pkg":"com.netflix.ninja","on":false}"""), now)
        assertEquals(emptyList<String>(), Rules.locked(o))
    }

    @Test fun sleepSetsAndCancels() {
        val o = JSONObject()
        Rules.apply(o, d("""{"do":"sleep","minutes":30}"""), now)
        assertEquals(now + 30 * 60_000L, o.getLong("sleep_at"))
        Rules.apply(o, d("""{"do":"sleep","minutes":0}"""), now)
        assertEquals(0L, o.getLong("sleep_at"))
    }

    @Test fun sleepAddStacksOnALaterTimerButNotOnAPastOne() {
        val o = JSONObject().put("sleep_at", now + 10 * 60_000L)
        Rules.apply(o, d("""{"do":"sleep_add","minutes":15}"""), now)
        assertEquals(now + 25 * 60_000L, o.getLong("sleep_at"))
        val old = JSONObject().put("sleep_at", now - 5_000L)
        Rules.apply(old, d("""{"do":"sleep_add","minutes":15}"""), now)
        assertEquals(now + 15 * 60_000L, old.getLong("sleep_at"))
    }

    @Test fun minutesAreCapped() {
        val o = JSONObject()
        Rules.apply(o, d("""{"do":"sleep","minutes":99999}"""), now)
        assertEquals(now + 24 * 60 * 60_000L, o.getLong("sleep_at"))
    }

    @Test fun limitSetsAndZeroRemoves() {
        val o = JSONObject()
        Rules.apply(o, d("""{"do":"limit","pkg":"com.a.b","minutes":60}"""), now)
        assertEquals(60, o.getJSONObject("limits").getInt("com.a.b"))
        Rules.apply(o, d("""{"do":"limit","pkg":"com.a.b","minutes":0}"""), now)
        assertFalse(o.getJSONObject("limits").has("com.a.b"))
    }

    @Test fun extraAddsUpAndDropsOtherDays() {
        val o = JSONObject().put("extra", JSONObject().put("2020-01-01", JSONObject().put("com.a.b", 10)))
        Rules.apply(o, d("""{"do":"extra","pkg":"com.a.b","minutes":10}"""), now)
        Rules.apply(o, d("""{"do":"extra","pkg":"com.a.b","minutes":5}"""), now)
        val extra = o.getJSONObject("extra")
        assertEquals(listOf(Rules.day(now)), extra.keys().asSequence().toList())
        assertEquals(15, Rules.extra(o, "com.a.b", now))
        Rules.apply(o, d("""{"do":"extra","pkg":"com.a.b","minutes":0,"set":true}"""), now)
        assertEquals(0, Rules.extra(o, "com.a.b", now))
    }

    @Test fun packageNamesAreChecked() {
        for (p in listOf("", "nodots", "com.a;reboot", "com.a b", "com.a.", "1com.a", "com.1a.b"))
            bad(JSONObject(), """{"do":"limit","pkg":"$p","minutes":5}""")
    }

    @Test fun bedtimeRangesPaddedCappedAndChecked() {
        val o = JSONObject()
        Rules.apply(o, d("""{"do":"bedtime","on":true,"ranges":[{"from":"9:05","to":"6:30"}]}"""), now)
        val b = Rules.bedtime(o)
        assertTrue(b.getBoolean("on"))
        assertEquals("09:05", b.getJSONArray("ranges").getJSONObject(0).getString("from"))
        assertEquals("06:30", b.getString("to"))
        val ten = (1..10).joinToString(",") { """{"from":"22:00","to":"06:00"}""" }
        Rules.apply(o, d("""{"do":"bedtime","on":true,"ranges":[$ten]}"""), now)
        assertEquals(8, Rules.bedtime(o).getJSONArray("ranges").length())
        bad(o, """{"do":"bedtime","on":true,"ranges":[{"from":"25:00","to":"06:00"}]}""")
        bad(o, """{"do":"bedtime","on":true,"ranges":[{"from":"2200","to":"06:00"}]}""")
        bad(o, """{"do":"bedtime","on":true,"ranges":[{"from":"22:00","to":"06:60"}]}""")
    }

    @Test fun bedtimeWithNoRangesIsOff() {
        val o = JSONObject()
        Rules.apply(o, d("""{"do":"bedtime","on":true,"ranges":[]}"""), now)
        assertFalse(Rules.bedtime(o).getBoolean("on"))
    }

    @Test fun volumeRangesClampMaxAndCheckTimes() {
        val o = JSONObject()
        Rules.apply(o, d("""{"do":"volume","ranges":[{"from":"22:00","to":"06:00","max":250},{"from":"13:00","to":"14:00"}]}"""), now)
        val v = o.getJSONArray("volume")
        assertEquals(100, v.getJSONObject(0).getInt("max"))
        assertEquals(30, v.getJSONObject(1).getInt("max"))
        bad(o, """{"do":"volume","ranges":[{"from":"x","to":"06:00","max":10}]}""")
        Rules.apply(o, d("""{"do":"volume","ranges":[]}"""), now)
        assertEquals(0, o.getJSONArray("volume").length())
    }

    @Test fun snoozeAndUnknown() {
        val o = JSONObject()
        Rules.apply(o, d("""{"do":"snooze","minutes":20}"""), now)
        assertEquals(now + 20 * 60_000L, o.getLong("snooze_until"))
        bad(o, """{"do":"explode"}""")
    }

    // ---- schedules: 2026-10-23 is a Friday
    private fun at(day: Int, h: Int, m: Int) = java.util.Calendar.getInstance().apply { clear(); set(2026, java.util.Calendar.OCTOBER, day, h, m) }.timeInMillis
    private fun sched(vararg json: String): JSONObject = JSONObject().also { o -> json.forEach { Rules.apply(o, d(it), now) } }
    private val school = """{"do":"sched","pkg":"com.google.android.youtube.tv","days":[1,2,3,4,5],"from":"16:00","to":"18:00"}"""
    private val yt = "com.google.android.youtube.tv"

    @Test fun scheduleDaysAndWindow() {
        val o = sched(school)
        assertTrue(Rules.allowedNow(o, yt, at(23, 16, 0)))                        // Friday, the window opens
        assertTrue(Rules.allowedNow(o, yt, at(23, 17, 59)))
        assertFalse(Rules.allowedNow(o, yt, at(23, 18, 0)))                       // the end is outside
        assertFalse(Rules.allowedNow(o, yt, at(23, 15, 59)))
        assertFalse(Rules.allowedNow(o, yt, at(24, 17, 0)))                       // Saturday
        assertFalse(Rules.allowedNow(o, yt, at(25, 17, 0)))                       // Sunday
        assertTrue(Rules.allowedNow(o, yt, at(26, 17, 0)))                        // Monday
        assertTrue(Rules.allowedNow(o, "com.netflix.ninja", at(24, 3, 0)))        // no schedule: always
    }

    @Test fun scheduleAcrossMidnightBelongsToTheDayItBeginsOn() {
        val o = sched("""{"do":"sched","pkg":"$yt","days":[5],"from":"22:00","to":"02:00"}""")   // Fridays only
        assertTrue(Rules.allowedNow(o, yt, at(23, 23, 0)))                        // Friday night
        assertTrue(Rules.allowedNow(o, yt, at(24, 1, 30)))                        // still Friday's window, on Saturday
        assertFalse(Rules.allowedNow(o, yt, at(24, 22, 30)))                      // Saturday night is not
        assertFalse(Rules.allowedNow(o, yt, at(23, 1, 30)))                       // Friday 01:30 is Thursday's window
        assertFalse(Rules.allowedNow(o, yt, at(24, 2, 0)))
        val sun = sched("""{"do":"sched","pkg":"$yt","days":[7],"from":"23:00","to":"01:00"}""")   // Sunday into Monday: the week wraps
        assertTrue(Rules.allowedNow(sun, yt, at(26, 0, 30)))
    }

    @Test fun scheduleMinutesCountOnlyInsideTheWindowAndMeetTheDailyLimit() {
        val o = sched(school.replace("\"18:00\"}", "\"18:00\",\"max\":30}"))
        assertEquals(30, Rules.budget(o, yt, at(23, 17, 0)))
        assertEquals(null, Rules.budget(o, yt, at(24, 17, 0)))                    // closed anyway: Guard closes it, no minutes to count
        Rules.apply(o, d("""{"do":"limit","pkg":"$yt","minutes":20}"""), now)
        assertEquals(20, Rules.budget(o, yt, at(23, 17, 0)))                      // the lower of the two
        assertEquals(20, Rules.budget(o, yt, at(24, 17, 0)))                      // outside the window only the daily limit is left
        Rules.apply(o, d("""{"do":"limit","pkg":"$yt","minutes":90}"""), now)
        assertEquals(30, Rules.budget(o, yt, at(23, 17, 0)))
    }

    @Test fun scheduleBlockAndRemoval() {
        val o = sched("""{"do":"sched","pkg":"$yt","block":true,"days":[1],"from":"1:00","to":"2:00"}""")
        assertEquals("""{"block":true}""", o.getJSONObject("sched").getJSONObject(yt).toString())   // days and window are dropped
        assertFalse(Rules.allowedNow(o, yt, at(23, 17, 0)))
        Rules.apply(o, d("""{"do":"sched","pkg":"$yt","on":false}"""), now)
        assertTrue(Rules.allowedNow(o, yt, at(23, 17, 0)))
        assertEquals(emptyList<String>(), Rules.schedApps(o))
    }

    @Test fun scheduleIsCheckedAndPadded() {
        bad(JSONObject(), """{"do":"sched","pkg":"$yt","days":[],"from":"16:00","to":"18:00"}""")          // no days
        bad(JSONObject(), """{"do":"sched","pkg":"$yt","days":[0],"from":"16:00","to":"18:00"}""")
        bad(JSONObject(), """{"do":"sched","pkg":"$yt","days":[8],"from":"16:00","to":"18:00"}""")
        bad(JSONObject(), """{"do":"sched","pkg":"$yt","days":[1],"from":"25:00","to":"18:00"}""")
        bad(JSONObject(), """{"do":"sched","pkg":"$yt","days":[1],"from":"16:00","to":"16:00"}""")        // an empty window
        bad(JSONObject(), """{"do":"sched","pkg":"dev.tvremote.setup","days":[1],"from":"16:00","to":"18:00"}""")
        bad(JSONObject(), """{"do":"sched","pkg":"no spaces","days":[1],"from":"16:00","to":"18:00"}""")
        val s = Rules.clean(d("""{"days":[5,1,1,3],"from":"6:05","to":"7:00","max":99999}"""))
        assertEquals("[1,3,5]", s.getJSONArray("days").toString())
        assertEquals("06:05", s.getString("from"))
        assertEquals(1440, s.getInt("max"))
    }

    // ---- several windows in one schedule
    private val two = """{"do":"sched","pkg":"$yt","windows":[{"days":[1,2,3,4,5],"from":"16:00","to":"18:00"},{"days":[6,7],"from":"8:00","to":"22:00"}]}"""

    @Test fun severalWindowsAllowTheAppInAnyOne() {
        val o = sched(two)                                                        // 2026-10-23 Fri, 24 Sat, 25 Sun
        assertTrue(Rules.allowedNow(o, yt, at(23, 17, 0)))                        // weekday window
        assertFalse(Rules.allowedNow(o, yt, at(23, 10, 0)))                       // the weekend window does not apply on a Friday
        assertFalse(Rules.allowedNow(o, yt, at(24, 7, 59)))                    // Saturday before 08:00
        assertTrue(Rules.allowedNow(o, yt, at(24, 8, 0)))
        assertTrue(Rules.allowedNow(o, yt, at(25, 21, 59)))
        assertFalse(Rules.allowedNow(o, yt, at(25, 22, 0)))
        assertEquals("08:00", Rules.windows(o.getJSONObject("sched").getJSONObject(yt)).getJSONObject(1).getString("from"))   // "8:00" was padded
    }

    @Test fun severalWindowsCrossingMidnightEachBelongToTheirOwnDay() {
        val o = sched("""{"do":"sched","pkg":"$yt","windows":[{"days":[5],"from":"22:00","to":"02:00"},{"days":[6],"from":"01:00","to":"03:00"}]}""")
        assertTrue(Rules.allowedNow(o, yt, at(24, 1, 30)))                        // Friday's night window, and Saturday's own one, both open
        assertTrue(Rules.allowedNow(o, yt, at(24, 2, 30)))                        // only Saturday's own one is left
        assertFalse(Rules.allowedNow(o, yt, at(24, 3, 0)))
        assertTrue(Rules.allowedNow(o, yt, at(23, 23, 0)))
        assertFalse(Rules.allowedNow(o, yt, at(25, 1, 30)))                       // Sunday 01:30: Saturday's night window was never set
    }

    @Test fun oneWindowIsKeptAlsoAsDaysFromTo() {
        val s = Rules.clean(d(two))
        assertEquals(2, s.getJSONArray("windows").length())
        assertEquals("[1,2,3,4,5]", s.getJSONArray("days").toString())            // what a TV Setup before windows reads
        assertEquals("16:00", s.getString("from"))
        assertEquals("08:00", s.getJSONArray("windows").getJSONObject(1).getString("from"))
    }

    @Test fun oldSavedDataReadsAsOneWindow() {
        val old = JSONObject("""{"sched":{"$yt":{"days":[1,2,3],"from":"16:00","to":"18:00","max":0}}}""")   // as saved before windows
        assertEquals("""[{"days":[1,2,3],"from":"16:00","to":"18:00"}]""", Rules.windows(old.getJSONObject("sched").getJSONObject(yt)).toString())
        assertTrue(Rules.allowedNow(old, yt, at(21, 17, 0)))                      // Wednesday 21st
        assertFalse(Rules.allowedNow(old, yt, at(23, 17, 0)))                     // Friday
        assertEquals(0, Rules.windows(JSONObject("""{"block":true}""")).length())
        assertTrue(Rules.windowsText(JSONObject("""{"days":[1,2],"from":"16:00","to":"18:00"}""")).endsWith(" 16:00–18:00"))   // the words of the days come from the language files
    }

    @Test fun windowsAreChecked() {
        bad(JSONObject(), """{"do":"sched","pkg":"$yt","windows":[]}""")
        bad(JSONObject(), """{"do":"sched","pkg":"$yt","windows":[{"days":[1],"from":"16:00","to":"18:00"},{"days":[],"from":"16:00","to":"18:00"}]}""")
        bad(JSONObject(), """{"do":"sched","pkg":"$yt","windows":[{"days":[1],"from":"16:00","to":"18:00"},{"days":[2],"from":"9:00","to":"09:00"}]}""")
        val nine = (1..9).joinToString(",") { """{"days":[1],"from":"0$it:00","to":"0$it:30"}""" }
        bad(JSONObject(), """{"do":"sched","pkg":"$yt","windows":[$nine]}""")        // more than 8
        val eight = (1..8).joinToString(",") { """{"days":[1],"from":"0$it:00","to":"0$it:30"}""" }
        assertEquals(8, Rules.clean(d("""{"windows":[$eight]}""")).getJSONArray("windows").length())
    }
}
