package dev.tvremote.home.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The times against the published ones (api.aladhan.com for the same method, place and day, fetched 2026-10-30): each within 2 minutes. */
class PrayerTest {
    private class Case(val place: String, val lat: Double, val lon: Double, val tz: Double, val method: String, val y: Int, val m: Int, val d: Int, val expect: String)

    private val cases = listOf(
        Case("mecca", 21.4225, 39.8262, 3.0, "ummalqura", 2026, 6, 21, "04:11 05:39 12:22 15:42 19:06 20:36"),
        Case("mecca", 21.4225, 39.8262, 3.0, "ummalqura", 2026, 12, 21, "05:32 06:54 12:19 15:23 17:44 19:14"),
        Case("mecca", 21.4225, 39.8262, 3.0, "ummalqura", 2026, 3, 20, "05:09 06:25 12:28 15:52 18:32 20:02"),
        Case("kl", 3.139, 101.6869, 8.0, "jakim", 2026, 6, 21, "05:41 07:06 13:15 16:42 19:24 20:40"),
        Case("kl", 3.139, 101.6869, 8.0, "jakim", 2026, 12, 21, "05:49 07:13 13:11 16:35 19:09 20:24"),
        Case("kl", 3.139, 101.6869, 8.0, "jakim", 2026, 3, 20, "06:01 07:18 13:21 16:26 19:24 20:33"),
        Case("jakarta", -6.2088, 106.8456, 7.0, "kemenag", 2026, 6, 21, "04:38 06:01 11:54 15:16 17:47 19:02"),
        Case("jakarta", -6.2088, 106.8456, 7.0, "kemenag", 2026, 12, 21, "04:11 05:36 11:51 15:18 18:05 19:21"),
        Case("jakarta", -6.2088, 106.8456, 7.0, "kemenag", 2026, 3, 20, "04:40 05:57 12:00 15:11 18:03 19:13"),
        Case("cairo", 30.0444, 31.2357, 3.0, "egypt", 2026, 6, 21, "04:08 05:54 12:57 16:32 19:59 21:33"),
        Case("cairo", 30.0444, 31.2357, 2.0, "egypt", 2026, 12, 21, "05:14 06:47 11:53 14:41 16:59 18:23"),
        Case("cairo", 30.0444, 31.2357, 2.0, "egypt", 2026, 3, 20, "04:32 05:59 12:03 15:29 18:06 19:24"),
        Case("london", 51.5074, -0.1278, 1.0, "mwl", 2026, 6, 21, "02:31 04:43 13:02 17:25 21:22 23:27"),
        Case("london", 51.5074, -0.1278, 0.0, "mwl", 2026, 12, 21, "05:59 08:04 11:59 13:38 15:53 17:51"),
        Case("london", 51.5074, -0.1278, 0.0, "mwl", 2026, 3, 20, "04:10 06:03 12:08 15:25 18:14 20:00"),
        Case("karachi", 24.8607, 67.0011, 5.0, "karachi", 2026, 6, 21, "04:14 05:43 12:34 15:55 19:24 20:53"),
        Case("karachi", 24.8607, 67.0011, 5.0, "karachi", 2026, 12, 21, "05:51 07:12 12:30 15:28 17:48 19:09"),
        Case("karachi", 24.8607, 67.0011, 5.0, "karachi", 2026, 3, 20, "05:20 06:36 12:40 16:05 18:43 19:59"),
        Case("newyork", 40.7128, -74.006, -4.0, "isna", 2026, 6, 21, "03:45 05:25 12:58 16:58 20:31 22:11"),
        Case("newyork", 40.7128, -74.006, -5.0, "isna", 2026, 12, 21, "05:54 07:17 11:54 14:14 16:32 17:54"),
        Case("newyork", 40.7128, -74.006, -4.0, "isna", 2026, 3, 20, "05:44 06:59 13:03 16:29 19:08 20:24"),
        Case("istanbul", 41.0082, 28.9784, 3.0, "turkey", 2026, 6, 21, "03:24 05:25 13:11 17:11 20:47 22:38"),
        Case("istanbul", 41.0082, 28.9784, 3.0, "turkey", 2026, 12, 21, "06:46 08:18 13:07 15:25 17:46 19:13"),
        Case("istanbul", 41.0082, 28.9784, 3.0, "turkey", 2026, 3, 20, "05:36 07:01 13:17 16:40 19:23 20:43"),
        Case("tehran", 35.6892, 51.389, 3.5, "tehran", 2026, 6, 21, "03:02 04:49 12:06 15:55 19:45 20:44"),
        Case("tehran", 35.6892, 51.389, 3.5, "tehran", 2026, 12, 21, "05:40 07:10 12:02 14:37 17:15 18:06"),
        Case("tehran", 35.6892, 51.389, 3.5, "tehran", 2026, 3, 20, "04:45 06:08 12:12 15:38 18:34 19:21"),
        Case("singapore", 1.3521, 103.8198, 8.0, "singapore", 2026, 6, 21, "05:36 07:00 13:06 16:33 19:13 20:28"),
        Case("singapore", 1.3521, 103.8198, 8.0, "singapore", 2026, 12, 21, "05:37 07:01 13:03 16:28 19:04 20:19"),
        Case("singapore", 1.3521, 103.8198, 8.0, "singapore", 2026, 3, 20, "05:52 07:09 13:12 16:14 19:16 20:24"),
    )
    private val names = listOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")

    @Test fun publishedTimes() {
        val bad = ArrayList<String>()
        for (c in cases) {
            val t = Prayer.times(c.y, c.m, c.d, c.lat, c.lon, c.tz, Prayer.METHODS.getValue(c.method), false).all()
            c.expect.split(" ").forEachIndexed { i, s ->
                val want = s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()
                val got = Math.round(t[i] * 60).toInt()
                if (Math.abs(got - want) > 2) bad += "${c.place} ${c.y}-${c.m}-${c.d} ${names[i]}: ${Prayer.clock(t[i])} want $s"
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun hanafiAsrIsLater() {                                     // Mecca, 21 June: the shadow twice the object
        val m = Prayer.METHODS.getValue("mwl")
        val a = Prayer.times(2026, 6, 21, 21.4225, 39.8262, 3.0, m, false).asr
        val b = Prayer.times(2026, 6, 21, 21.4225, 39.8262, 3.0, m, true).asr
        assertTrue("$a $b", b - a in 0.4..1.5)
    }

    @Test fun polarNightHasNoSunriseButDoesNotCrash() {                // Tromsø in December: the sun does not rise
        val t = Prayer.times(2026, 12, 21, 69.65, 18.96, 1.0, Prayer.METHODS.getValue("mwl"), false)
        assertTrue(t.sunrise.isNaN())
        assertEquals("--:--", Prayer.clock(t.sunrise))
    }

    @Test fun hijriTabular() {
        assertEquals(Prayer.Hijri(24, 9, 1420), Prayer.hijri(1, 1, 2000))      // civil calendar: 1 January 2000 = 24 Ramadan 1420
        assertEquals(Prayer.Hijri(25, 9, 1420), Prayer.hijri(1, 1, 2000, 1))   // the adjust moves a day
        assertEquals(Prayer.Hijri(23, 9, 1420), Prayer.hijri(1, 1, 2000, -1))
        val h = Prayer.hijri(21, 6, 2026)                                     // Umm al-Qura says 6 Muharram 1448; the tabular is within a day
        assertEquals(1, h.month); assertEquals(1448, h.year); assertTrue("$h", h.day in 5..7)
    }

    @Test fun everyDayIsTheNextDay() {
        var d = java.time.LocalDate.of(2026, 1, 1)
        var prev = Prayer.hijri(d.dayOfMonth, d.monthValue, d.year)
        repeat(700) {
            d = d.plusDays(1)
            val h = Prayer.hijri(d.dayOfMonth, d.monthValue, d.year)
            val next = (h.day == prev.day + 1 && h.month == prev.month && h.year == prev.year) || (h.day == 1 && prev.day >= 29 && h.month == prev.month % 12 + 1)
            assertTrue("$d $prev -> $h", next)
            prev = h
        }
    }

    @Test fun nextPrayer() {
        val m = Prayer.METHODS.getValue("jakim")
        val t = Prayer.times(2026, 6, 21, 3.139, 101.6869, 8.0, m, false)
        val tomorrow = Prayer.times(2026, 6, 22, 3.139, 101.6869, 8.0, m, false)
        val n = Prayer.next(t, tomorrow, 12.0)!!                                // before the Dhuhr at about 13:15; the sunrise is not a prayer
        assertEquals(2, n.index); assertEquals(false, n.tomorrow); assertTrue("${n.minutes}", n.minutes in 74..76)
        assertEquals(0, Prayer.next(t, tomorrow, 5.0)!!.index)
        val late = Prayer.next(t, tomorrow, 22.0)!!                             // after the Isha: tomorrow's Fajr
        assertEquals(0, late.index); assertTrue(late.tomorrow); assertTrue("${late.minutes}", late.minutes in 455..470)
        assertNotNull(Prayer.next(t, null, 1.0))
        assertNull(Prayer.next(t, null, 22.0))
    }

    @Test fun defaultMethodFromZone() {
        assertEquals("kemenag", Prayer.defaultMethod("Asia/Jakarta", "ID"))
        assertEquals("jakim", Prayer.defaultMethod("Asia/Kuala_Lumpur", ""))
        assertEquals("ummalqura", Prayer.defaultMethod("Asia/Riyadh", "SA"))
        assertEquals("isna", Prayer.defaultMethod("America/Chicago", ""))
        assertEquals("mwl", Prayer.defaultMethod("Europe/London", "GB"))
        assertEquals("mwl", Prayer.defaultMethod("", ""))
        assertEquals("mwl", Prayer.defaultMethod("Asia/Ho_Chi_Minh", "US"))   // a box in Hanoi on en_US: the zone wins (it was ISNA)
        assertEquals("kemenag", Prayer.defaultMethod("Asia/Jakarta", "US"))
        assertEquals("isna", Prayer.defaultMethod("Etc/UTC", "US"))           // no place in the zone: the country
        assertEquals("ummalqura", Prayer.defaultMethod("UTC", "SA"))
    }

    @Test fun methodsMatchTheSettings() {                              // the options the page and Customize offer are the methods worked out here
        assertEquals(dev.tvremote.home.HomeConfig.PRAYER_METHODS, Prayer.IDS)
        assertEquals(listOf("auto") + Prayer.IDS, dev.tvremote.home.HomeConfig.options("prayer").first { it.id == "method" }.values)
    }

    @Test fun clockText() {
        assertEquals("05:41", Prayer.clock(5.6833))
        assertEquals("1:15", Prayer.clock(13.25, false))
        assertEquals("12:00", Prayer.clock(0.0, false))
        assertEquals("00:30", Prayer.clock(24.5))                                // an Isha past midnight
    }
}
