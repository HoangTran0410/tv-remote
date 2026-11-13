package dev.tvremote.setup

import android.app.usage.UsageEvents
import dev.tvremote.setup.health.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stats.count: seconds per app from the day's usage events. */
class StatsCountTest {
    private val fg = UsageEvents.Event.MOVE_TO_FOREGROUND
    private val bg = UsageEvents.Event.MOVE_TO_BACKGROUND
    private fun ev(t: Int, p: String, at: Long) = Stats.Ev(t, p, at)

    @Test fun countsForegroundStretches() {
        val (total, open) = Stats.count(listOf(ev(fg, "a", 1000), ev(bg, "a", 4000), ev(fg, "b", 5000)), 0, 9000, true)
        assertEquals(3000L, total["a"])
        assertEquals(4000L, total["b"])                               // still open: counted up to now
        assertEquals("b", open)
    }

    @Test fun standbyClosesTheOpenApp() {
        val (total, open) = Stats.count(listOf(ev(fg, "a", 1000), ev(Stats.SCREEN_NON_INTERACTIVE, "", 3000)), 0, 9000, true)
        assertEquals(2000L, total["a"])
        assertNull(open)
    }

    @Test fun noEventsLeavesNothingOpen() {
        val (total, open) = Stats.count(emptyList(), 0, 9000, true)
        assertEquals(0, total.size)
        assertNull(open)
    }

    @Test fun wakeIsTheLastScreenOnEvent() {
        assertEquals(0L, Stats.lastWake(emptyList()))
        assertEquals(7000L, Stats.lastWake(listOf(ev(Stats.SCREEN_INTERACTIVE, "", 2000), ev(Stats.SCREEN_INTERACTIVE, "", 7000), ev(fg, "a", 8000))))
    }

    private fun days(vararg k: String) = org.json.JSONObject().also { o -> k.forEach { o.put(it, org.json.JSONObject().put("a", 60)) } }

    @Test fun pruneDropsFutureAndOddKeys() {                       // a box that wrote 2027-04-09 before the network set its clock
        val o = days("2026-11-10", "2026-11-12", "2027-04-09", "1970-01-01x", "junk")
        Stats.prune(o, "2026-11-12", "2026-08-14")
        assertEquals(setOf("2026-11-10", "2026-11-12"), o.keys().asSequence().toSet())
    }

    @Test fun pruneKeepsRealDaysWhenTheClockIsAhead() {            // today = 2027-04-09: "90 days ago" is after every real day, none may go
        val o = days("2026-11-10", "2026-11-12", "2027-04-09")
        Stats.prune(o, "2027-04-09", "2027-01-09")
        assertEquals(3, o.length())
    }

    @Test fun pruneKeepsRealDaysWhenTheClockIsBehind() {           // a box that booted in 2000: every real day is "after today", none may go
        val o = days("2026-11-10", "2026-11-12", "2000-01-01")
        Stats.prune(o, "2000-01-01", "1999-10-03")
        assertEquals(3, o.length())
    }

    @Test fun pruneCutsOldDaysOnlyPastNinetyFive() {
        val o = org.json.JSONObject(); for (i in 1..100) o.put("2026-%02d-%02d".format(1 + (i - 1) / 28, 1 + (i - 1) % 28), org.json.JSONObject())
        Stats.prune(o, "2026-12-31", "2026-02-01")
        assertEquals(72, o.length())                                // 28 days of January went, the 72 newer stay
    }
}
