package dev.tvremote.setup.keys

import dev.tvremote.setup.keys.HoldKeys.Step
import dev.tvremote.setup.rules.Rules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldKeysTest {
    private val down = 0
    private val up = 1
    private val input = 178

    @Test fun aShortPressIsEatenBothWaysAndNeverActs() {
        val g = HoldKeys.Gate()
        assertEquals(Step.ARM, g.onKey(true, input, down, 0, 1000, 1000))
        assertEquals(Step.EAT, g.onKey(true, input, up, 0, 1000, 1150))   // its up is eaten too
        assertFalse(g.timer(input))                                       // the timer that comes later finds the key let go
    }

    @Test fun aHoldActsOnceWhateverComesAfter() {
        val g = HoldKeys.Gate()
        assertEquals(Step.ARM, g.onKey(true, input, down, 0, 1000, 1000))
        assertTrue(g.timer(input))
        assertFalse(g.timer(input))                                       // a second timer
        assertEquals(Step.EAT, g.onKey(true, input, down, 5, 1000, 3500)) // repeats after the hold are eaten, not acted on
        assertEquals(Step.EAT, g.onKey(true, input, up, 0, 1000, 3600))
    }

    @Test fun repeatsThatShowALongHoldFireOnceWithoutTheTimer() {
        val g = HoldKeys.Gate()
        g.onKey(true, input, down, 0, 1000, 1000)
        assertEquals(Step.EAT, g.onKey(true, input, down, 1, 1000, 1500))
        assertEquals(Step.FIRE, g.onKey(true, input, down, 9, 1000, 3000))
        assertEquals(Step.EAT, g.onKey(true, input, down, 10, 1000, 3100))
        assertFalse(g.timer(input))                                       // the timer after a repeat's fire does not act again
    }

    @Test fun nextPressStartsOver() {
        val g = HoldKeys.Gate()
        g.onKey(true, input, down, 0, 0, 0); g.timer(input); g.onKey(true, input, up, 0, 0, 2500)
        assertEquals(Step.ARM, g.onKey(true, input, down, 0, 5000, 5000))
        assertTrue(g.timer(input))
    }

    @Test fun aLostUpDoesNotStickTheGate() {
        val g = HoldKeys.Gate()
        g.onKey(true, input, down, 0, 0, 0)
        assertEquals(Step.ARM, g.onKey(true, input, down, 0, 900, 900))   // a down with repeat 0 is a new press
        assertTrue(g.timer(input))
    }

    @Test fun switchOffPassesEverything() {
        val g = HoldKeys.Gate()
        for (k in HoldKeys.KEYS) {
            assertEquals(Step.PASS, g.onKey(false, k, down, 0, 0, 0))
            assertEquals(Step.PASS, g.onKey(false, k, up, 0, 0, 100))
        }
    }

    @Test fun switchedOffInTheMiddleStillEatsTheUpOfTheEatenDown() {
        val g = HoldKeys.Gate()
        g.onKey(true, input, down, 0, 0, 0)
        assertEquals(Step.EAT, g.onKey(false, input, up, 0, 0, 100))
        assertEquals(Step.PASS, g.onKey(false, input, down, 0, 500, 500))
    }

    @Test fun keysNotInTheListPass() {
        val g = HoldKeys.Gate()
        for (k in listOf(23, 4, 3, 82, 183, 190, 191, 195, 284, 24, 66)) assertEquals(k.toString(), Step.PASS, g.onKey(true, k, down, 0, 0, 0))
    }

    @Test fun theListHasInputTvSettingsAndTheDirectInputKeys() {
        assertTrue(listOf(170, 178, 176, 243, 244, 245, 246).all { it in HoldKeys.KEYS })
        assertFalse(183 in HoldKeys.KEYS)
    }

    @Test fun anotherKeyDuringAHoldIsNotTheHeldOne() {
        val g = HoldKeys.Gate()
        g.onKey(true, input, down, 0, 0, 0)
        assertEquals(Step.ARM, g.onKey(true, 176, down, 0, 300, 300))     // Settings replaces Input as the key in progress
        assertFalse(g.timer(input))
        assertTrue(g.timer(176))
    }

    @Test fun theRuleIsOffUntilTurnedOn() {
        val o = JSONObject()
        assertFalse(o.optBoolean("holdkeys"))
        Rules.apply(o, JSONObject().put("do", "holdkeys").put("on", true), 0L)
        assertTrue(o.optBoolean("holdkeys"))
        Rules.apply(o, JSONObject().put("do", "holdkeys").put("on", false), 0L)
        assertFalse(o.optBoolean("holdkeys"))
    }
}
