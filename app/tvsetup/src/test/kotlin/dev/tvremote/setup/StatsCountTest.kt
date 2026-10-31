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
}
