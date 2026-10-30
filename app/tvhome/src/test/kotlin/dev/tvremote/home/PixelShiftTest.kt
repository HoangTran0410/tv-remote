package dev.tvremote.home

import dev.tvremote.home.ui.PixelShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelShiftTest {
    @Test fun staysInsideSixDpAndMovesOneStepAtATime() {
        var last = PixelShift.at(0)
        for (m in 1..100) {
            val p = PixelShift.at(m * 60_000L)
            assertTrue(Math.abs(p.first) <= 3 && Math.abs(p.second) <= 3)
            assertTrue("one dp at most per minute", Math.abs(p.first - last.first) <= 1 && Math.abs(p.second - last.second) <= 1)
            last = p
        }
    }

    @Test fun roundsAfter24MinutesAndSurvivesAClockJump() {
        assertEquals(PixelShift.at(5 * 60_000L), PixelShift.at(29 * 60_000L))
        assertEquals(PixelShift.at(0), PixelShift.at(24 * 60_000L))
        PixelShift.at(-1L); PixelShift.at(Long.MAX_VALUE / 2)
    }

    @Test fun waitIsUnderAMinute() {
        assertTrue(PixelShift.untilNext(1234_567L) in 1..60_100)
    }
}
