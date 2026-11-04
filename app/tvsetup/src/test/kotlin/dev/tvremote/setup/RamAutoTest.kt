package dev.tvremote.setup

import dev.tvremote.setup.rules.Guard
import dev.tvremote.setup.rules.Rules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The memory check shared by the notice and the automatic free (Guard.ramStep) and the setting's shape (Rules.ram). */
class RamAutoTest {
    private fun step(over: Boolean = true, high: Int = 1, notice: Boolean = false, auto: Boolean = false, noticeQuiet: Long = 0, tick: Long = 1_000, freeQuiet: Long = 0) =
        Guard.ramStep(over, high, notice, auto, 5_000, noticeQuiet, tick, freeQuiet)

    @Test fun offDoesNothingEvenWhenFull() {
        assertFalse(step(auto = false, notice = false).free); assertFalse(step(auto = false, notice = false).notice)
    }

    @Test fun needsTwoChecksInARow() {
        assertFalse(step(high = 0, auto = true).free)                      // the first one is only counted
        assertEquals(1, step(high = 0, auto = true).high)
        assertTrue(step(high = 1, auto = true).free)
        assertEquals(0, step(over = false, high = 1, auto = true).high)    // a peak that went away starts over
    }

    @Test fun freesAtMostOncePerQuietTime() {
        assertTrue(step(auto = true, tick = 10, freeQuiet = 10).free)
        assertFalse(step(auto = true, tick = 9, freeQuiet = 10).free)
    }

    @Test fun noticeWaitsForTheFreeButStillComesIfMemoryStaysFull() {
        assertTrue(step(auto = true, notice = true).free); assertFalse(step(auto = true, notice = true).notice)
        val s = step(auto = true, notice = true, tick = 1, freeQuiet = 10)  // the free ran a moment ago and it is still full
        assertFalse(s.free); assertTrue(s.notice)
        assertFalse(step(notice = true, noticeQuiet = 6_000).notice)       // the 30 minutes of quiet still hold
    }

    @Test fun settingDefaultsOffAndOldOnesKeepWorking() {
        assertFalse(Rules.ram(JSONObject()).getBoolean("auto"))
        val old = JSONObject().put("ram", JSONObject().put("on", true).put("pct", 85))   // saved before "auto"
        assertFalse(Rules.ram(old).getBoolean("auto")); assertEquals(85, Rules.ram(old).getInt("pct")); assertTrue(Rules.ram(old).getBoolean("on"))
    }
}
