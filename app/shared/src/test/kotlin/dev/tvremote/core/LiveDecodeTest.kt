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

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun annexB(vararg nals: String) = nals.fold(ByteArray(0)) { a, n -> a + byteArrayOf(0, 0, 0, 1) + hex(n) }

    /** Real SPS: an FPT box's encoder and x264 say "nothing held"; Apple's VideoToolbox leaves it out, with or without a VUI. */
    @Test fun spsGetsTheRestrictionOnlyWhenItLacksIt() {
        for (sps in listOf("6742c02a8d680780227e59080808083c2211a8", "67f4000b919640507ec044000003000400000300503c50a920")) {
            val cfg = annexB(sps, "68ce3c80")
            val (out, said) = LiveDecode.fixConfig(cfg)
            assertSame(cfg, out); assertTrue(said, said.endsWith("restricted"))
        }
        for ((sps, refs) in listOf("2764000dac568141f9" to 1, "2764000dac568141fa6a04040204" to 1)) {
            val cfg = annexB(sps, "28ee3cb0")
            val (out, said) = LiveDecode.fixConfig(cfg)
            assertTrue(said, said.startsWith("profile 100 poc 0") && said.endsWith("restriction added"))
            assertTrue(out.size > cfg.size)
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x28, 0xee.toByte(), 0x3c, 0xb0.toByte()), out.copyOfRange(out.size - 8, out.size))   // the PPS untouched
            val (again, said2) = LiveDecode.fixConfig(out)            // read back: now it has one
            assertSame(out, again); assertTrue(said2, said2.endsWith("restricted"))
            System.getenv("TVR_SPS_OUT")?.let { java.io.File(it, "$sps.bin").writeBytes(out) }   // for ffmpeg's trace_headers
        }
        val junk = annexB("67ff")                                     // cut short: passed on as it came
        assertSame(junk, LiveDecode.fixConfig(junk).first)
    }
}
