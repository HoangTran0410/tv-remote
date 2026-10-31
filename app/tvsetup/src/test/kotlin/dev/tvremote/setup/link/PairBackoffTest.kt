package dev.tvremote.setup.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairBackoffTest {
    @Test fun firstThreeAreFreeThenItGrowsAndCaps() {
        val b = PairBackoff()
        var t = 1000L
        repeat(3) { assertEquals(0, b.wait("a", t)); b.shown("a", t); t += 1000 }
        assertEquals(29000, b.wait("a", t))                       // 30 s from the third screen
        t += 30000; b.shown("a", t)
        assertEquals(60000, b.wait("a", t))
        repeat(10) { t += 400000; b.shown("a", t) }
        assertEquals(300000, b.wait("a", t))
    }

    @Test fun onePeerDoesNotLockOutAnother() {
        val b = PairBackoff()
        repeat(5) { b.shown("evil", 1000L + it) }
        assertTrue(b.wait("evil", 2000) > 0)
        assertEquals(0, b.wait("tv-owner", 2000))
    }

    @Test fun pairingResetsAndOldPeersAreForgotten() {
        val b = PairBackoff()
        repeat(5) { b.shown("a", 1000) }
        b.paired("a")
        assertEquals(0, b.wait("a", 1001))
        b.shown("x", 0)
        b.wait("y", PairBackoff.FORGET_MS + 1)
        assertEquals(0, b.size())
    }

    @Test fun mapIsCapped() {
        val b = PairBackoff()
        for (i in 0 until 200) b.shown("p$i", 1000L)
        assertEquals(PairBackoff.MAX, b.size())
    }

    @Test fun aClockSetBackLeavesNoWaitAndNoMark() {
        val b = PairBackoff()
        repeat(5) { b.shown("a", 10_000_000L) }
        assertEquals(0, b.wait("a", 10_000_000L - 5_000_000L))    // the marks are ahead of it: stale
        assertEquals(0, b.size())
    }
}
