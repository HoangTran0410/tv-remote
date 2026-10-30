package dev.tvremote.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The sun maths against sunrise / sunset times from an independent library (astral), solstices, to within 3 minutes. */
class SunTest {
    private fun utc(s: String) = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun near(expect: String, got: Long) = assertEquals(expect, 0.0, Math.abs(got - utc(expect)) / 60_000.0, 3.0)

    private fun check(lat: Double, lon: Double, y: Int, m: Int, d: Int, rise: String, set: String) {
        val (r, s) = Sun.riseSet(lat, lon, y, m, d)!!
        near(rise, r); near(set, s)
    }

    @Test fun hanoi() {
        check(21.0285, 105.8542, 2026, 6, 21, "2026-06-20T22:16:00", "2026-06-21T11:40:00")     // 05:16 / 18:40 local
        check(21.0285, 105.8542, 2026, 12, 21, "2026-12-20T23:29:00", "2026-12-21T10:19:00")
    }

    @Test fun london() {
        check(51.5074, -0.1278, 2026, 6, 21, "2026-06-21T03:43:00", "2026-06-21T20:21:00")
        check(51.5074, -0.1278, 2026, 12, 21, "2026-12-21T08:04:00", "2026-12-21T15:53:00")
    }

    @Test fun sydney() {
        check(-33.8688, 151.2093, 2026, 6, 21, "2026-06-20T21:00:00", "2026-06-21T06:53:00")
        check(-33.8688, 151.2093, 2026, 12, 21, "2026-12-20T18:41:00", "2026-12-21T09:05:00")
    }

    @Test fun polarNightHasNoSunrise() {
        assertNull(Sun.riseSet(78.2, 15.6, 2026, 12, 21))
    }

    @Test fun elevationCrossesTheHorizonAtSunrise() {
        val (r, s) = Sun.riseSet(21.0285, 105.8542, 2026, 6, 21)!!
        assertEquals(-0.83, Sun.elevation(21.0285, 105.8542, r), 0.3)
        assertEquals(-0.83, Sun.elevation(21.0285, 105.8542, s), 0.3)
        assertTrue(Sun.rising(105.8542, r - 10 * 60_000)); assertTrue(!Sun.rising(105.8542, s))
        assertTrue(Sun.elevation(21.0285, 105.8542, (r + s) / 2) > 60)
    }

    @Test fun skyGoesFromNightToDayAndBack() {
        val night = Sun.sky(-30.0, true); val day = Sun.sky(40.0, true)
        assertNotEquals(night[0], day[0])
        assertTrue(night.all { it ushr 24 == 0xFF })
        assertEquals(night.toList(), Sun.sky(-30.0, false).toList())                 // deep night looks the same on both sides
        assertEquals(day.toList(), Sun.sky(40.0, false).toList())
        assertNotEquals(Sun.sky(0.0, true)[2], Sun.sky(0.0, false)[2])                // the horizon differs: dawn pinker, dusk redder
        val a = Sun.sky(-2.0, true); val b = Sun.sky(-1.0, true)
        assertTrue("no jump between close heights", (0..2).all { Math.abs((a[it] and 255) - (b[it] and 255)) < 16 })
    }

    @Test fun guessUsesTheZone() {
        assertEquals(105.0, Sun.guess(7 * 3_600_000L).second, 0.01)
    }
}
