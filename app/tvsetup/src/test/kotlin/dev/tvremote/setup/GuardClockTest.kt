package dev.tvremote.setup

import dev.tvremote.setup.rules.Guard
import dev.tvremote.setup.rules.Marks
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guard's marks after the clock went back: none stays ahead of it (Guard.sane). */
class GuardClockTest {
    private val now = 1_000_000_000L
    private val hour = 3_600_000L

    @Test fun warningsComeAgainAfterTheClockWentBack() {
        val m = Guard.sane(Marks(now + hour, now + hour, now + hour), HashMap(), now)
        assertEquals(0L, m.bedWarnedAt)
        assertEquals(0L, m.totalWarnedAt)
        assertEquals(0L, m.ramQuietUntil)
    }

    @Test fun honestMarksStay() {
        val m = Guard.sane(Marks(now - 5_000, now - 5_000, now + 20 * 60_000L), HashMap(), now)
        assertEquals(now - 5_000, m.bedWarnedAt)
        assertEquals(now - 5_000, m.totalWarnedAt)
        assertEquals(now + 20 * 60_000L, m.ramQuietUntil)
    }

    @Test fun aStopMarkAheadOfNowIsForgottenSoTheAppIsClosed() {
        val stops = hashMapOf("a" to now + hour, "b" to now - 20_000)
        Guard.sane(Marks(0, 0, 0), stops, now)
        assertEquals(setOf("b"), stops.keys)
    }
}
