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

    @Test fun aRunWithNoAdbIsTriedAgainInsideTheGraceOnly() {                        // timed-scene-lost-without-adb
        val first = step(emptyMap(), "2026-11-06 19:00:00")
        val fired = step(first.cursors, "2026-11-06 20:00:10")
        assertEquals(listOf("a"), fired.fire)
        val due = fired.due["a"]!!
        assertEquals(ms("2026-11-06 20:00:00"), due)
        val back = SceneTimer.retry(fired.cursors, "a", fired.cursors["a"]!!.first, due)!!
        assertEquals(listOf("a"), step(back, "2026-11-06 20:01:00").fire)            // ADB came back a minute later: runs then
        assertEquals(listOf("a"), step(back, "2026-11-06 20:05:00").fire)
        assertTrue(step(back, "2026-11-06 20:05:01").fire.isEmpty())                 // still none past the grace: skipped, no pile
        assertNull(SceneTimer.retry(fired.cursors, "a", "{changed}", due))           // edited meanwhile: its new time counts
        assertNull(SceneTimer.retry(emptyMap(), "a", "x", due))                       // removed meanwhile
    }

    @Test fun aNewTimezoneCountsFromTheNextLook() {
        val first = step(emptyMap(), "2026-11-06 18:00:00")
        val plusOne = java.util.TimeZone.getTimeZone("GMT+01:00")                      // 20:00 there is 19:00 UTC
        assertEquals(listOf("a"), SceneTimer.step(sc, first.cursors, ms("2026-11-06 19:00:30"), plusOne).fire)
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

    // ---- scenes that start on an event (an app coming to the front, the TV turning on)
    private val yt = "com.google.android.youtube.tv"
    private val apps = mapOf("v" to yt)
    private fun open(pkg: String, ts: Long) = SceneTimer.Open(pkg, ts)

    @Test fun anAppComingToTheFrontStartsItsSceneOnce() {
        val d = SceneTimer.decideApps(apps, listOf(open(yt, 1000), open(yt, 2000), open(yt, 3000)), "home", emptyMap(), 5000)
        assertEquals(listOf("v"), d.fire); assertEquals(yt, d.front)           // other screens of the same app are no new start
        assertTrue(SceneTimer.decideApps(apps, listOf(open(yt, 6000)), d.front, emptyMap(), 7000).fire.isEmpty())   // it stays in front: not again
        val back = SceneTimer.decideApps(apps, listOf(open("home", 8000), open(yt, 9000)), d.front, emptyMap(), 10_000)
        assertEquals(listOf("v"), back.fire)                                   // left and came back: a new start
    }

    @Test fun anotherAppOrAnOldEventStartsNothing() {
        assertTrue(SceneTimer.decideApps(apps, listOf(open("other", 1000)), "", emptyMap(), 2000).fire.isEmpty())
        assertTrue(SceneTimer.decideApps(apps, listOf(open(yt, 1000)), "", emptyMap(), 1000 + SceneTimer.GRACE_MS + 1).fire.isEmpty())
    }

    @Test fun aSceneThatOpensItsOwnAppDoesNotStartItself() {
        val quiet = mapOf("v" to (4000L..Long.MAX_VALUE))                      // running since 4000
        assertTrue(SceneTimer.decideApps(apps, listOf(open(yt, 4500)), "home", quiet, 5000).fire.isEmpty())
        val ended = mapOf("v" to (4000L..9000L))                               // over at 9000 (+ the small margin)
        assertTrue(SceneTimer.decideApps(apps, listOf(open(yt, 8000)), "home", ended, 10_000).fire.isEmpty())
        assertEquals(listOf("v"), SceneTimer.decideApps(apps, listOf(open(yt, 9500)), "home", ended, 10_000).fire)
    }

    @Test fun twoScenesOnTheSameAppBothStartAndPowerSkipsARunningOne() {
        val two = mapOf("a" to yt, "b" to yt)
        assertEquals(listOf("a", "b"), SceneTimer.decideApps(two, listOf(open(yt, 1)), "", emptyMap(), 2).fire)
        assertEquals(listOf("p"), SceneTimer.decidePower(listOf("p", "q"), mapOf("q" to (0L..Long.MAX_VALUE)), 100))
    }
}
