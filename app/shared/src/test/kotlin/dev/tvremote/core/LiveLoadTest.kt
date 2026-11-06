package dev.tvremote.core

import dev.tvremote.core.ontv.LiveLoad
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLoadTest {
    // the Google TV emulator (Android 14), two readings half a second apart (LiveLoad.FIRST)
    private val two = "cpu  614 166 2536 13769 523 678 65 0 0 0\ncpu0 110 25 314 3649 87 338 55 0 0 0\ncpu1 136 37 1303 2868 133 110 3 0 0 0\n" +
            "cpu  664 166 2556 13919 523 678 65 0 0 0\ncpu0 150 25 314 3659 87 338 55 0 0 0\ncpu1 146 37 1323 2958 133 110 3 0 0 0\n"

    @Test fun twoReadingsGiveTheLoadSplitAndEachCore() {
        val r = LiveLoad.cpuLines(two)
        assertEquals(2, r.size)
        val o = JSONObject(); LiveLoad.cpu(r[0], r[1], o)
        assertEquals(32, o.getInt("cpu"))                                 // 70 busy of 220
        assertEquals(23, o.getInt("apps")); assertEquals(9, o.getInt("system")); assertEquals(0, o.getInt("io"))
        assertEquals(80, o.getJSONArray("cores").getInt(0)); assertEquals(25, o.getJSONArray("cores").getInt(1))
    }

    @Test fun aCoreThatWentOfflineHasNoFigure() {
        val r = LiveLoad.cpuLines(two)
        val o = JSONObject(); LiveLoad.cpu(r[0].filterKeys { it != "cpu1" }, r[1], o)
        assertTrue(o.getJSONArray("cores").isNull(1))
    }

    @Test fun thePartsJoinIntoOneCommand() {                         // a part without its "; " glued the next one onto its last word
        for (part in listOf(LiveLoad.FIRST, LiveLoad.FAST, LiveLoad.CONNS)) assertTrue(part, part.endsWith("; "))
        assertTrue(LiveLoad.TRAFFIC.endsWith("fi"))
    }

    @Test fun memoryInBytes() {
        val m = LiveLoad.mem("MemTotal:         997308 kB\nMemFree:          117244 kB\nMemAvailable:     163336 kB\nSwapTotal:        747976 kB\nSwapFree:         159780 kB\n")
        assertEquals(997308L * 1024, m.getLong("total")); assertEquals(163336L * 1024, m.getLong("avail")); assertEquals(159780L * 1024, m.getLong("swap_free"))
        assertEquals(117244L * 1024, m.getLong("free"))
        assertFalse(m.has("used"))                                        // the page's split: total - avail, avail - free, free
    }

    @Test fun clocksOnlyWhenTheyMeanSomething() {
        assertNull(LiveLoad.clocks("1\n2\n1\n1\n"))                       // the emulator
        assertEquals(1800, LiveLoad.clocks("1800000\n1200000\n")!!.getInt(0))
        assertNull(LiveLoad.clocks(""))
    }

    @Test fun appsByTrafficWithTheirConnections() {
        val traffic = LiveLoad.traffic("10084 308000 157722\n1051 180 276\n2000 72777 15492\n10179 244943 27824\n110084 1000 0\n")
        val conns = LiveLoad.conns("10084 4\n10179 8\n10200 1\n")
        val pkgs = LiveLoad.uids("package:com.google.android.youtube.tv uid:10084\npackage:org.videolan.vlc uid:10179\npackage:com.x uid:10200\n")
        val a = LiveLoad.apps(traffic, conns, pkgs) { if (it == "org.videolan.vlc") "VLC" else null }
        assertEquals("com.google.android.youtube.tv", a.getJSONObject(0).getString("pkg"))
        assertEquals(309000L, a.getJSONObject(0).getLong("rx"))           // user 11's copy counts as the app
        assertEquals(4, a.getJSONObject(0).getInt("conns"))
        assertEquals("VLC", a.getJSONObject(1).getString("label"))
        assertEquals("android", a.getJSONObject(2).getString("pkg"))      // root, the shell and the system's uids together
        assertEquals(1L * 72777 + 180, a.getJSONObject(2).getLong("rx"))
        assertEquals("com.x", a.getJSONObject(3).getString("pkg"))        // only connections so far
    }
}
