package dev.tvremote.setup

import dev.tvremote.setup.MicVoice.Step
import dev.tvremote.setup.rules.Rules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mic button's filter: off or no recognizer = every key passes untouched; on = tap-to-talk, or hold-to-talk when the remote sends a long press. */
class MicVoiceTest {
    private fun s(ready: Boolean, listening: Boolean, code: Int, action: Int, repeat: Int = 0, down: Long = 0, at: Long = 0) = MicVoice.step(ready, listening, code, action, repeat, down, at)

    @Test fun offOrNoRecognizerPassesEverything() {
        for (k in MicVoice.KEYS) for (a in 0..1) assertEquals(Step.PASS, s(false, false, k, a))
        assertEquals(Step.PASS, s(true, false, 24, 0))                  // another key is never ours
    }

    @Test fun aTapStartsAndAnotherTapStops() {
        assertEquals(Step.START, s(true, false, 231, 0))
        assertEquals(Step.EAT, s(true, true, 231, 1, down = 0, at = 120))      // the up of a short press: nothing, it keeps listening
        assertEquals(Step.STOP, s(true, true, 231, 0))
    }

    @Test fun holdingTalksAndLettingGoStops() {
        assertEquals(Step.START, s(true, false, 219, 0))
        assertEquals(Step.EAT, s(true, true, 219, 0, repeat = 3))
        assertEquals(Step.RELEASE, s(true, true, 219, 1, down = 1000, at = 1000 + MicVoice.HOLD_MS))
    }

    @Test fun theKeysAreAskedAbout() {
        assertTrue(dev.tvremote.setup.keys.KeysProtocol.INTEREST.containsAll(MicVoice.KEYS))
        assertTrue(listOf(84, 219, 231).all { it in MicVoice.KEYS })
    }

    @Test fun aFailureSendsTheKeyBackForAWhile() {
        MicVoice.fixed(); assertFalse(MicVoice.broken())
        MicVoice.broke(); assertTrue(MicVoice.broken()); assertFalse(MicVoice.broken(System.currentTimeMillis() + 11 * 60_000L))
        MicVoice.fixed(); assertFalse(MicVoice.broken())
    }

    @Test fun theRuleIsOffUntilTurnedOn() {
        val o = JSONObject()
        assertFalse(o.optBoolean("mic"))
        Rules.apply(o, JSONObject().put("do", "mic").put("on", true), 0L)
        assertTrue(o.optBoolean("mic"))
        Rules.apply(o, JSONObject().put("do", "mic").put("on", false), 0L)
        assertFalse(o.optBoolean("mic"))
    }
}
