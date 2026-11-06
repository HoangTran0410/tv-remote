package dev.tvremote.setup.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdleOffTest {
    private val h = 3_600_000L
    private val now = 10 * h

    @Test fun off_reads_and_acts_on_nothing() {
        assertEquals(IdleOff.Step(IdleOff.Act.NONE, 0L, 0L), IdleOff.step(0, 99 * h, 0L, now))
    }

    @Test fun key_seen_recently_waits_exactly_the_rest_of_the_span() {
        val s = IdleOff.step(2, 30 * 60_000L, 0L, now)
        assertEquals(IdleOff.Act.NONE, s.act); assertEquals(90 * 60_000L, s.next)
    }

    @Test fun span_passed_starts_the_warning() {
        val s = IdleOff.step(1, h + 5, 0L, now)
        assertEquals(IdleOff.Act.WARN, s.act); assertEquals(now, s.warnedAt)
    }

    @Test fun no_key_during_the_minute_goes_to_standby() {
        val warned = now - 61_000
        assertEquals(IdleOff.Act.STANDBY, IdleOff.step(1, h + 70_000, warned, now).act)
        assertEquals(IdleOff.Act.NONE, IdleOff.step(1, h + 30_000, now - 30_000, now).act)   // still inside the minute
    }

    @Test fun a_key_after_the_warning_cancels_it() {
        val s = IdleOff.step(1, 4_000L, now - 20_000, now)
        assertEquals(IdleOff.Act.CANCELLED, s.act); assertEquals(0L, s.warnedAt)
    }

    @Test fun the_warning_screen_itself_touching_the_clock_is_not_a_key() {
        assertEquals(IdleOff.Act.NONE, IdleOff.step(1, 18_000L, now - 20_000, now).act)   // 2 s after the warning began
    }

    @Test fun unreadable_never_acts_and_keeps_the_mark() {
        val s = IdleOff.step(1, null, now - 5_000, now)
        assertEquals(IdleOff.Act.NONE, s.act); assertEquals(now - 5_000, s.warnedAt)
    }

    @Test fun a_warning_nobody_could_follow_up_is_a_fresh_one_not_an_instant_standby() {
        assertEquals(IdleOff.Act.WARN, IdleOff.step(1, 2 * h, now - 10 * 60_000L, now).act)
    }

    @Test fun reads_the_dumpsys_line_and_nothing_else() {
        assertEquals(64271L, IdleOff.parseAgo("  lastUserActivityTime=20731119 (64271 ms ago)"))
        assertNull(IdleOff.parseAgo("  mLastUserActivityTime(excludingAttention)=18971053"))
        assertNull(IdleOff.parseAgo("  lastUserActivityTimeNoChangeLights=0 (20266144 ms ago)"))
        assertNull(IdleOff.parseAgo(null))
    }

    @Test fun the_action_stores_hours_and_refuses_the_rest() {
        val o = JSONObject()
        Rules.apply(o, JSONObject("""{"do":"idle","hours":2}"""), now)
        assertEquals(2, IdleOff.hours(o))
        Rules.apply(o, JSONObject("""{"do":"idle","hours":0}"""), now)
        assertEquals(0, o.optInt("idle_h", -1))                     // off is stored as 0: nothing is read
        try { Rules.apply(o, JSONObject("""{"do":"idle","hours":7}"""), now); throw AssertionError("7 h accepted") } catch (e: IllegalArgumentException) { }
    }

    @Test fun only_one_to_four_hours_are_real() {
        assertEquals(0, IdleOff.hours(JSONObject()))
        assertEquals(0, IdleOff.hours(JSONObject().put("idle_h", 9)))
        assertEquals(4, IdleOff.hours(JSONObject().put("idle_h", 4)))
    }
}
