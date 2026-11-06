package dev.tvremote.home.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar

/** The minute's read keeps the widget row when [Widgets.drawn] says it would draw the same, and only then. */
class WidgetsDrawnTest {
    private val gb = 1024L * 1024 * 1024
    private fun facts(ram: Long = 2 * gb, up: Long = 3 * 86_400_000L, cpu: Float? = 0.12f, ip: String = "192.168.1.5") =
        Widgets.Facts(ram to 4 * gb, 10 * gb to 32 * gb, ip, false, -50, up, true, null, emptyMap(), null, null, cpu, 4)
    private fun at(h: Int, m: Int, day: Int = 7) = Calendar.getInstance().apply { clear(); set(2026, 10, day, h, m) }
    private val sys = listOf("system", "clock")

    @Test fun sameNumbersNextMinuteIsSkipped() {
        val a = Widgets.drawn(sys, facts(), at(20, 1), "look")
        val b = Widgets.drawn(sys, facts(ram = 2 * gb + 1000, up = 3 * 86_400_000L + 60_000), at(20, 2), "look")   // bytes and uptime moved, not their words
        assertEquals(a, b)
    }

    @Test fun whatIsDrawnChanging() {
        val a = Widgets.drawn(sys, facts(), at(20, 1), "look")
        assertNotEquals(a, Widgets.drawn(sys, facts(cpu = 0.30f), at(20, 1), "look"))
        assertNotEquals(a, Widgets.drawn(sys, facts(ram = 3 * gb), at(20, 1), "look"))
        assertNotEquals(a, Widgets.drawn(sys, facts(ip = "10.0.0.2"), at(20, 1), "look"))
        assertNotEquals(a, Widgets.drawn(sys, facts(), at(21, 1), "look"))             // the hour: weather's "as of", the calendar's day
        assertNotEquals(a, Widgets.drawn(sys, facts(), at(20, 1, 8), "look"))
        assertNotEquals(a, Widgets.drawn(sys, facts(), at(20, 1), "other"))            // a setting or the row's size
        assertNotEquals(a, Widgets.drawn(sys + "weather", facts(), at(20, 1), "look"))
    }

    @Test fun countdownsRedrawEachMinute() {
        for (k in listOf("timer", "prayer", "news"))
            assertNotEquals(k, Widgets.drawn(listOf(k), facts(), at(20, 1), "look"), Widgets.drawn(listOf(k), facts(), at(20, 2), "look"))
    }

    @Test fun uptimeUnderAnHourCountsMinutes() {
        assertNotEquals(Widgets.drawn(sys, facts(up = 30 * 60_000L), at(20, 1), "l"), Widgets.drawn(sys, facts(up = 31 * 60_000L), at(20, 1), "l"))
        assertEquals("3d 0h", Widgets.spanOf(3 * 86_400_000L + 59_000))
    }
}
