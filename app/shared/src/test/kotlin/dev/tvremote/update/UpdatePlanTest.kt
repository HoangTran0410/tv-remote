package dev.tvremote.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePlanTest {
    private val now = 1_000_000_000_000L
    private val min = 60_000L

    @Test fun appStaysQuietOnlyWhileSetupCanAndTheGraceHolds() {
        assertTrue(UpdatePlan.quiet(true, now - 3600_000L, now))                       // seen an hour ago, TV Setup on
        assertFalse(UpdatePlan.quiet(true, now - UpdatePlan.GRACE, now))               // grace over: asks
        assertFalse(UpdatePlan.quiet(false, now, now))                                 // auto-update off / no ADB
        assertFalse(UpdatePlan.quiet(null, now, now))                                  // old or missing TV Setup: as before
    }

    @Test fun standbyAllowsAnything() {
        assertTrue(UpdatePlan.mayInstall(false, true, now, now, true))
        assertTrue(UpdatePlan.mayInstall(false, false, null, now, true))
    }

    @Test fun awakeNeverOverTheTopApp() {
        assertFalse(UpdatePlan.mayInstall(true, true, 0L, now, true))
        assertFalse(UpdatePlan.mayInstall(true, true, null, now, false))
    }

    @Test fun awakeHomeOnlyNeedsNotBeingOnTop() {
        assertTrue(UpdatePlan.mayInstall(true, false, now, now, false))
    }

    @Test fun awakeChannelsAndGamesWaitForTheyAreUnused() {
        assertFalse(UpdatePlan.mayInstall(true, false, now - 5 * min, now, true))      // left 5 minutes ago
        assertFalse(UpdatePlan.mayInstall(true, false, now - 29 * min, now, true))
        assertTrue(UpdatePlan.mayInstall(true, false, now - 30 * min, now, true))
        assertTrue(UpdatePlan.mayInstall(true, false, 0L, now, true))                  // not used today
        assertFalse(UpdatePlan.mayInstall(true, false, null, now, true))               // no usage access: unknown is not "unused"
    }

    @Test fun ownUpdateIsSpreadOverADayAndLongerWithoutRollback() {                    // setup-update-no-soak
        val day = UpdatePlan.SPREAD
        val ids = (0 until 200).map { java.util.UUID.nameUUIDFromBytes(byteArrayOf(it.toByte())).toString() }
        val spots = ids.map { UpdatePlan.soak(it, 34) }
        assertTrue(spots.all { it in 0 until day })
        assertTrue(spots.count { it < day / 4 } in 20..80)                                // spread, not all at once
        assertTrue(ids.all { UpdatePlan.soak(it, 29) == UpdatePlan.soak(it, 34) + UpdatePlan.NO_ROLLBACK })
        assertTrue(UpdatePlan.soak(ids[0], 34) == UpdatePlan.soak(ids[0], 34))           // the same spot every time
        val id = ids.first { UpdatePlan.soak(it, 34) > 3600_000L }
        val pub = 1_000_000_000_000L
        val spot = UpdatePlan.soak(id, 34)
        assertTrue(UpdatePlan.soakLeft(pub, 0, pub, id, 34) == spot)
        assertTrue(UpdatePlan.soakLeft(pub, 0, pub + spot, id, 34) == 0L)
        assertTrue(UpdatePlan.soakLeft(0, pub, pub + 60_000, id, 34) == spot - 60_000)  // no published_at: from when this TV saw it
        assertTrue(UpdatePlan.soakLeft(pub, 0, pub - day, id, 34) > spot)                // a clock behind the release waits, never early
        assertTrue(Release.time("1970-01-02T00:00:01Z") == 86_401_000L)                   // GitHub's published_at
        assertTrue(Release.time("") == 0L && Release.time(null) == 0L)
    }
}
