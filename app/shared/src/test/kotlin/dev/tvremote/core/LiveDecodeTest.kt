package dev.tvremote.core

import dev.tvremote.core.picture.LatencyStats
import dev.tvremote.core.picture.LiveDecode
import dev.tvremote.core.picture.LiveGate
import org.junit.Assert.*
import org.junit.Test

class LiveDecodeTest {
    @Test fun tuningByVersion() {
        assertTrue(LiveDecode.tuning(29, true, false).containsKey("priority"))
        assertFalse(LiveDecode.tuning(29, true, false).containsKey("low-latency"))
        assertEquals(1, LiveDecode.tuning(30, true, false)["low-latency"])
        assertFalse(LiveDecode.tuning(30, false, false).containsKey("low-latency"))
        assertEquals(0, LiveDecode.tuning(34, true, false)["priority"])
        assertEquals(32767, LiveDecode.tuning(34, true, false)["operating-rate"])
        assertTrue(LiveDecode.tuning(34, true, true).keys.any { it.startsWith("vendor.") })
        assertTrue(LiveDecode.tuning(22, true, false).isEmpty())
    }

    @Test fun keyFrameDetection() {
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2)
        val p = byteArrayOf(0, 0, 0, 1, 0x41, 1, 2)
        val spsIdr = byteArrayOf(0, 0, 0, 1, 0x67, 5, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 9)
        assertTrue(LiveDecode.isKey(idr)); assertFalse(LiveDecode.isKey(p)); assertTrue(LiveDecode.isKey(spsIdr))
        assertFalse(LiveDecode.isKey(ByteArray(0)))
    }

    @Test fun oldFramesSkippedButNeverMoreThanARun() {
        val g = LiveGate()
        for (i in 0 until 10) g.fed(i.toLong(), 0)
        var drawn = 0; var run = 0; var maxRun = 0
        for (i in 0 until 10) {                                       // frames come out 200 ms late, more waiting behind
            val (age, draw) = g.out(i.toLong(), 200)
            assertEquals(200, age)
            if (draw) { drawn++; run = 0 } else { run++; maxRun = maxOf(maxRun, run) }
        }
        assertTrue(maxRun <= 3); assertTrue(drawn >= 3)
        g.fed(99, 0)
        assertTrue(g.out(99, 500).second)                             // nothing behind: always drawn
    }

    /** Frames out with pts unlike the ones fed (issue #16: a box's encoder) still empty the queue, one for one: no lag seen. */
    @Test fun framesOutWithOtherPtsStillCount() {
        val g = LiveGate()
        for (i in 0 until 50) { g.fed(1000L + i, i * 30L); g.out(i.toLong(), i * 30L + 20) }
        assertFalse(g.lagged(50 * 30L + 700))
        g.fed(5000, 2000); assertTrue(g.lagged(2700))                // nothing out: still lag
    }

    @Test fun freshFramesAlwaysDrawn() {
        val g = LiveGate()
        for (i in 0 until 5) { g.fed(i.toLong(), i * 16L); assertTrue(g.out(i.toLong(), i * 16L + 10).second) }
    }

    @Test fun catchUpSkipsToKeyFrameAndAsksOncePerWindow() {
        val g = LiveGate(600)
        g.fed(1, 0)
        assertFalse(g.lagged(599)); assertTrue(g.lagged(600))
        assertTrue(g.caughtUp(600))
        assertTrue(g.admit(true, false))                              // config passes
        assertFalse(g.admit(false, false))                            // frames wait for a key frame
        assertFalse(g.wantKey(1000))                                  // asked a moment ago
        assertTrue(g.wantKey(2600))
        assertTrue(g.admit(false, true)); assertFalse(g.skipping())
        assertTrue(g.admit(false, false))
        g.fed(2, 3000)
        assertTrue(g.lagged(3700))
        assertFalse(g.caughtUp(3700))                                 // asked at 2600: too soon
        assertEquals(2, g.catchUps)
    }

    @Test fun statsLine() {
        val s = LatencyStats(1000)
        s.fed(); s.fed()
        assertNull(s.out(10, true, 0, 0))
        val line = s.out(30, false, 1000, 2)!!
        assertTrue(line, line.contains("in=2 out=2 drawn=1 skipped=1 age avg=20ms max=30ms catchUps=2"))
    }
}
