package dev.tvremote.setup.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReoptimizeTest {
    private val none: (String) -> Boolean = { false }

    @Test fun onlyListedAppsThatAreOnAgain() {
        val got = Reoptimize.plan(listOf("a.x", "b.y", "c.z", "a.x"), setOf("a.x", "b.y", "c.z", "d.w"), setOf("b.y"), none, emptySet())
        assertEquals(listOf("a.x", "c.z"), got)                       // b.y still off, d.w never listed, a.x once
    }

    @Test fun notInstalledKeptGivenUpAndWizardComponentsAreSkipped() {
        val got = Reoptimize.plan(listOf("gone.app", "keep.me", "tired.of", "w.z/.Home", "ok.app"),
            setOf("keep.me", "tired.of", "ok.app"), emptySet(), { it == "keep.me" }, setOf("tired.of"))
        assertEquals(listOf("ok.app"), got)
    }

    @Test fun emptyFileMeansNothing() {
        assertTrue(Reoptimize.plan(emptyList(), setOf("a.x"), emptySet(), none, emptySet()).isEmpty())
    }

    @Test fun givesUpAfterTwoTurnOffsWithinADay() {
        val now = 100 * Reoptimize.DAY_MS
        assertFalse(Reoptimize.fightsBack(listOf(now - 1000), now))
        assertTrue(Reoptimize.fightsBack(listOf(now - 1000, now - 3_600_000), now))
        assertFalse(Reoptimize.fightsBack(listOf(now - 2 * Reoptimize.DAY_MS, now - 1000), now))   // the old one does not count
    }
}
