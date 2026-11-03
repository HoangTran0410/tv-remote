package dev.tvremote.tvui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvScrollTest {
    // a 500 px window with 10 px padding over 2000 px of content: scrolls 0..1520
    private fun to(a: Int, b: Int, now: Int = 0) = centered(a, b, now, 500, 10, 1520)?.let { it + now }

    @Test fun aRowMidListLandsInTheMiddle() = assertEquals(1000 + 50 - 10 - 250, to(1000, 1100))
    @Test fun theFirstRowsStayAtTheTop() = assertEquals(0, to(20, 80))
    @Test fun theLastRowsStayAtTheBottom() = assertEquals(1520, to(1950, 2000))
    @Test fun itIsAChangeFromWhereItIs() = assertEquals(790 - 300, centered(1000, 1100, 300, 500, 10, 1520))
    @Test fun aRowTallerThanTheViewKeepsAndroidsWay() = assertNull(to(100, 700))
    @Test fun notLaidOutYet() = assertNull(centered(0, 10, 0, 0, 0, 0))

    // the glide: 60 frames a second toward 600 px from 0
    private fun run(frames: Int, dt: Float = 1f / 60, from: Float = 0f, speed: Float = 0f, to: Float = 600f): List<Float> {
        var x = from; var v = speed; val out = ArrayList<Float>()
        repeat(frames) { val s = spring(x, v, to, dt); x = s[0]; v = s[1]; out.add(x) }
        return out
    }
    @Test fun theGlideStartsSoftAndNeverPassesTheRow() {
        val xs = run(40)
        assert(xs[0] < 60f) { "first frame jumped ${xs[0]}" }                   // not a jump
        assert(xs.zipWithNext().all { (a, b) -> b >= a }) { "went back" }
        assert(xs.all { it <= 600f }) { "overshot" }
    }
    @Test fun theGlideIsThereInUnderHalfASecond() {
        val xs = run(40)
        assert(600f - xs[13] < 60f) { "slow: ${xs[13]} at 230 ms" }             // 90 % there
        assert(600f - xs[23] < 6f) { "far: ${xs[23]} at 400 ms" }               // 99 %: what is left the eye does not see
        assert(600f - xs[30] < 1f) { "not settled: ${xs[30]} at 520 ms" }        // Glide stops under a pixel
    }
    @Test fun aSlowBoxFrameDoesNotOvershoot() = assert(run(12, dt = 0.05f).all { it <= 600f })
    @Test fun theNextKeyKeepsTheSpeed() {
        val s = spring(300f, 2000f, 900f, 1f / 60)                                // moving down, target moved further down
        assert(s[1] > 1500f) { "lost its speed: ${s[1]}" }
    }
}
