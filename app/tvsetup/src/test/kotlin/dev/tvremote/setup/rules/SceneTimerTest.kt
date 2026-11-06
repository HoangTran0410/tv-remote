package dev.tvremote.setup.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SceneTimer.step: when a timed scene starts, and that a clock jump or a long gap never makes a backlog of runs. */
class SceneTimerTest {
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private fun ms(s: String) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply { timeZone = utc }.parse(s)!!.time
    private val daily = JSONObject("{time:'20:00',days:[1,2,3,4,5,6,7]}")
    private val sc = mapOf("a" to daily)
    private fun step(cur: Map<String, Pair<String, Long>>, now: String) = SceneTimer.step(sc, cur, ms(now), utc)

    @Test fun newSceneWaitsForItsTimeAndFiresOnce() {
        var s = step(emptyMap(), "2026-11-06 19:58:30")
        assertTrue(s.fire.isEmpty()); assertEquals(90_000L, s.wait)
        s = step(s.cursors, "2026-11-06 20:00:20")
        assertEquals(listOf("a"), s.fire)
        s = step(s.cursors, "2026-11-06 20:01:20")                                  // not again the same day
        assertTrue(s.fire.isEmpty())
    }

    @Test fun savingAtThePastTimeDoesNotRunIt() {
        assertTrue(step(emptyMap(), "2026-11-06 20:30:00").fire.isEmpty())
    }

    @Test fun aLongGapOrAClockJumpForwardRunsNothing() {
        val first = step(emptyMap(), "2026-11-06 19:00:00")
        assertTrue(step(first.cursors, "2026-11-10 20:02:00").fire.isEmpty())        // TV was off for days: no backlog, no late run
        assertTrue(step(first.cursors, "2026-11-06 20:06:00").fire.isEmpty())        // missed by more than the grace
        assertEquals(listOf("a"), step(first.cursors, "2026-11-06 20:04:59").fire)
    }

    @Test fun aClockThatGoesBackIsNotStuck() {
        val first = step(emptyMap(), "2026-11-06 19:00:00")
        val later = step(first.cursors, "2026-11-06 19:50:00")
        val back = step(later.cursors, "2026-11-06 18:00:00")                         // jumped back two hours
        assertEquals(ms("2026-11-06 18:00:00"), back.cursors["a"]!!.second)
        assertEquals(listOf("a"), step(back.cursors, "2026-11-06 20:00:05").fire)
    }

    @Test fun changedTimeStartsAgainAndOnceEndsWithNothingToWaitFor() {
        val first = step(emptyMap(), "2026-11-06 19:00:00")
        val moved = SceneTimer.step(mapOf("a" to JSONObject("{time:'19:30',days:[5]}")), first.cursors, ms("2026-11-06 19:20:00"), utc)   // Friday
        assertEquals(10 * 60_000L, moved.wait)
        val once = mapOf("o" to JSONObject("{time:'12:00',date:'2026-11-06'}"))
        val a = SceneTimer.step(once, emptyMap(), ms("2026-11-06 11:59:00"), utc)
        val b = SceneTimer.step(once, a.cursors, ms("2026-11-06 12:00:10"), utc)
        assertEquals(listOf("o"), b.fire); assertNull(b.wait)
    }
}
