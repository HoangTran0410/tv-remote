package dev.tvremote.setup.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightlyTest {
    @Test fun dueOnlyBetweenTwoAndFiveAndOncePerNight() {
        assertFalse(Nightly.due(1, "2026-10-21", null))
        assertTrue(Nightly.due(2, "2026-10-21", null))
        assertTrue(Nightly.due(4, "2026-10-21", "2026-10-20"))
        assertFalse(Nightly.due(5, "2026-10-21", null))                 // 05:00 is already out
        assertFalse(Nightly.due(3, "2026-10-21", "2026-10-21"))         // done tonight
    }

    @Test fun picksTheMostUsedInstalledAppsOnly() {
        val usage = mapOf("2026-10-20" to mapOf("a" to 600L, "b" to 100L, "gone" to 9000L, "tiny" to 5L),
            "2026-10-19" to mapOf("b" to 900L, "a" to 100L))
        assertEquals(listOf("b", "a"), Nightly.pick(usage, setOf("a", "b", "tiny")))   // b 1000, a 700; tiny is under a minute
        assertEquals(listOf("b"), Nightly.pick(usage, setOf("a", "b"), 1))
    }

    @Test fun oldDaysBeyondTheWindowDoNotCount() {
        val usage = (1..20).associate { "2026-09-%02d".format(it) to mapOf("old" to 1000L) } + ("2026-10-20" to mapOf("new" to 100L))
        assertEquals(listOf("old", "new"), Nightly.pick(usage, setOf("old", "new")))   // 13 days of "old" still beat "new"
        assertEquals(13_000L, usage.keys.sortedDescending().take(Nightly.USAGE_DAYS).drop(1).sumOf { 1000L })
    }

    @Test fun freeBytesFromDf() {
        val df = "Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-4  5000000 1000000   4000000  20% /data"
        assertEquals(4_000_000L * 1024, Nightly.freeBytes(df))
        assertNull(Nightly.freeBytes("df: /data: No such file"))
    }

    @Test fun playingStateFromMediaSession() {
        assertTrue(Nightly.playing("  state=PlaybackState {state=3, position=10, buffered position=0}"))
        assertTrue(Nightly.playing("PlaybackState {state=6, position=0"))
        assertFalse(Nightly.playing("PlaybackState {state=2, position=10"))
        assertFalse(Nightly.playing("Sessions Stack - have 0 sessions:"))
        assertTrue(Nightly.playing("state=PlaybackState {state=PLAYING(3), position=0"))         // Android 10+
        assertTrue(Nightly.playing("state=PlaybackState {state=BUFFERING(6), position=0"))
        assertFalse(Nightly.playing("state=PlaybackState {state=ERROR(7), position=0"))
        assertFalse(Nightly.playing("state=PlaybackState {state=PAUSED(2), position=0"))
    }
}
