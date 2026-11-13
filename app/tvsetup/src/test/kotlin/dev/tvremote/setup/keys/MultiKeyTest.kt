package dev.tvremote.setup.keys

import dev.tvremote.setup.keys.MultiKey.Do
import dev.tvremote.setup.keys.MultiKey.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Gate = MultiKey.Gate
private fun Gate.down(d: Boolean, h: Boolean, k: Int = 183, rep: Int = 0, t: Long = 0, at: Long = 0) = onKey(d, h, k, 0, rep, t, at)
private fun Gate.up(d: Boolean, h: Boolean, k: Int = 183) = onKey(d, h, k, 1, 0, 0, 0)

class MultiKeyTest {
    private val red = 183
    private val blue = 186


    @Test fun aKeyWithNothingPassesAtOnce() {
        val g = Gate()
        val o = g.down(false, false)
        assertFalse(o.eat); assertNull(o.fire); assertEquals(Timer.NONE, o.timer)
        assertFalse(g.up(false, false).eat)
    }

    @Test fun doubleOnlyWaitsThenPlainPress() {
        val g = Gate()
        assertTrue(g.down(true, false).eat)
        val up = g.up(true, false)
        assertTrue(up.eat); assertEquals(Timer.DOUBLE, up.timer)
        assertEquals(Do.SINGLE, g.timer(Timer.DOUBLE, red))
        assertNull(g.timer(Timer.DOUBLE, red))                        // once
    }

    @Test fun secondPressInTheWindowIsTheDouble() {
        val g = Gate()
        g.down(true, false); g.up(true, false)
        val o = g.down(true, false)
        assertTrue(o.eat); assertEquals(Do.DOUBLE, o.fire)
        assertNull(g.timer(Timer.DOUBLE, red))                        // the window timer of the first press does nothing now
        assertTrue(g.up(true, false).eat)                             // its up is eaten too
        assertFalse(g.down(false, false).eat)                         // and the next press is fresh
    }

    @Test fun holdFiresOnTheTimerAndEatsTheUp() {
        val g = Gate()
        assertEquals(Timer.HOLD, g.down(false, true).timer)
        assertEquals(Do.HOLD, g.timer(Timer.HOLD, red))
        assertNull(g.timer(Timer.HOLD, red))
        val up = g.up(false, true)
        assertTrue(up.eat); assertNull(up.fire)                       // no plain press after a hold
    }

    @Test fun holdOnlyKeyActsOnReleaseWhenShort() {
        val g = Gate()
        g.down(false, true)
        val up = g.up(false, true)
        assertEquals(Do.SINGLE, up.fire)
        assertNull(g.timer(Timer.HOLD, red))                          // the hold timer is late: nothing
    }

    @Test fun aRepeatingRemoteShowsTheHoldWithoutTheTimer() {
        val g = Gate()
        g.down(false, true, t = 1000, at = 1000)
        assertNull(g.down(false, true, rep = 1, t = 1000, at = 1300).fire)
        assertEquals(Do.HOLD, g.down(false, true, rep = 3, t = 1000, at = 1000 + MultiKey.HOLD_MS).fire)
        assertNull(g.down(false, true, rep = 4, t = 1000, at = 2000).fire)   // never twice
    }

    @Test fun holdAndDoubleOnTheSameKey() {
        val g = Gate()
        g.down(true, true); g.up(true, true)
        assertEquals(Do.DOUBLE, g.down(true, true).fire)
        g.up(true, true)
        g.down(true, true)
        assertEquals(Do.HOLD, g.timer(Timer.HOLD, red))
        assertNull(g.up(true, true).fire)
    }

    @Test fun anotherKeyEndsTheWaitWithThePlainPressFirst() {
        val g = Gate()
        g.down(true, false); g.up(true, false)
        val o = g.down(true, false, k = blue)
        assertEquals(red, o.flush); assertTrue(o.eat)                 // red's plain press first, then blue starts its own wait
        assertNull(g.timer(Timer.DOUBLE, red))
        assertEquals(Timer.DOUBLE, g.up(true, false, k = blue).timer)
        val p = g.down(false, false, k = 4)                           // a key with nothing, during blue's wait
        assertEquals(blue, p.flush); assertFalse(p.eat)
    }

    @Test fun aLostUpDoesNotStick() {
        val g = Gate()
        g.down(true, false)                                           // its up never came
        val o = g.down(true, false)
        assertTrue(o.eat); assertNull(o.fire)                         // a new first press, not a double
    }

    @Test fun anUpWeNeverSawPasses() {
        assertFalse(Gate().up(true, false).eat)
    }
}
