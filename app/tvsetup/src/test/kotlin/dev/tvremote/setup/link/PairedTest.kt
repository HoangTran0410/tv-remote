package dev.tvremote.setup.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which browsers Paired lets go (Paired.stale): a wrong clock must not forget them. */
class PairedTest {
    private val t = 1_800_000_000_000L
    private val day = 24 * 3600_000L
    private fun e(kind: String, created: Long, seen: Long) = Paired.Entry("i", "h", "n", "", created, seen, kind, false)

    @Test fun unseenForThirtyDaysGoes() {
        assertTrue(Paired.stale(e("web", t, t), t + 31 * day))
        assertFalse(Paired.stale(e("web", t, t), t + 29 * day))
        assertFalse(Paired.stale(e("app", t, t), t + 400 * day))
    }

    @Test fun aClockBeforeItsCreationForgetsNothing() {
        assertFalse(Paired.stale(e("web", t, t), t - 40 * day))
        assertFalse(Paired.stale(e("web", t, t + 5 * day), t + day))   // seen "ahead": not stale either
    }

    private fun l() = mutableListOf(e("web", t - 50 * day, t - 2 * day), e("web", t - 50 * day, t - 40 * day), e("app", t - 50 * day, t - 40 * day))

    @Test fun noReadingClockAYearAheadKeepsWebEntries() {
        val l = l()
        assertFalse(Paired.dropStale(l, t + 365 * day, false, 0L))
        assertTrue(l.size == 3)
        assertTrue(Paired.dropStale(l, t - day, false, 0L))               // a clock near the newest sighting prunes as before
        assertTrue(l.size == 2)
    }

    @Test fun aReadingADayOffKeepsThemToo() {
        val l = l()
        assertFalse(Paired.dropStale(l, t + 365 * day, true, 365 * day))
        assertTrue(l.size == 3)
    }

    @Test fun futureSeenDoesNotStick() {
        val l = l(); val a = l[0]
        val after = Paired.seenAfter(l, a, t + 365 * day, false, 0L)
        assertTrue(after == a.seen)                                    // not a year ahead, not even a day a request
        assertTrue(Paired.seenAfter(l, a.also { it.seen = after }, t + 365 * day, false, 0L) == a.seen)   // nor after many requests
        assertTrue(Paired.seenAfter(l, a, t - day, false, 0L) == t - day)          // a right clock still sets it to now
        assertTrue(Paired.seenAfter(l, a, t - 5 * day, false, 0L) == a.seen)   // never lowered
    }
}
