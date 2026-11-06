package dev.tvremote.core

import dev.tvremote.core.FleetStatus.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FleetStatusTest {
    // what FleetStatus.CMD printed on the Google TV emulator (Android 14): on the home screen, in Settings, after the power key
    private val home = "  mWakefulness=Awake\n#~#\n  mCurrentFocus=Window{71d7193 u0 com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity}\n#~#\n" +
        "454.40 1768.39\nMemTotal:        2025912 kB\nMemAvailable:     805432 kB\n#~#\nGoogle\nsdk_google_atv64_amati_arm64\n14"
    private val settings = home.replace("com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity", "com.android.tv.settings/com.android.tv.settings.MainSettings")
    private val standby = "  mWakefulness=Asleep\n#~#\n  mCurrentFocus=null\n#~#\n458.65 1781.89\nMemTotal:        2025912 kB\nMemAvailable:     812456 kB\n#~#\nGoogle\nsdk_google_atv64_amati_arm64\n14"

    @Test fun onTheHomeScreen() {
        val s = FleetStatus.parse("h", home, now = 1000)
        assertEquals(FleetStatus.ON, s.state)
        assertEquals("Google sdk_google_atv64_amati_arm64", s.model)
        assertEquals("14", s.android)
        assertTrue(s.home)
        assertEquals("", s.appName)                                   // the page says "home screen" in the person's language
        assertEquals(454, s.uptime)
        assertEquals(39, s.memFree)
    }

    @Test fun inAnApp() {
        val s = FleetStatus.parse("h", settings)
        assertEquals(FleetStatus.ON, s.state)
        assertEquals("com.android.tv.settings", s.app)
        assertEquals("Settings", s.appName)
        assertFalse(s.home)
    }

    @Test fun standbyShowsNoApp() {
        val s = FleetStatus.parse("h", standby)
        assertEquals(FleetStatus.STANDBY, s.state)
        assertEquals("", s.app)
        assertEquals("14", s.android)
    }

    @Test fun aTvThatSaysLessShowsLess() {                            // no power line (a vendor's dumpsys): reachable, never an error
        assertEquals(FleetStatus.UP, FleetStatus.parse("h", "").state)
        assertEquals(FleetStatus.UP, FleetStatus.parse("h", "#~#\n#~#\n#~#\n").state)
        val s = FleetStatus.parse("h", "  mWakefulness=Dreaming\n#~#\n#~#\n#~#\nSony\nSony BRAVIA\n11")
        assertEquals(FleetStatus.ON, s.state)
        assertEquals("Sony BRAVIA", s.model)                          // the make is not said twice
        assertEquals(-1, s.memFree)
    }

    @Test fun appNames() {
        assertEquals("YouTube", FleetStatus.appName("com.google.android.youtube.tv"))
        assertEquals("Hbo", FleetStatus.appName("com.hbo.hbonow"))
        assertEquals("TV Channels", FleetStatus.appName("dev.tvremote.channels"))
        assertEquals("Foo", FleetStatus.appName("tv.foo.player"))
        assertTrue(FleetStatus.isHome("com.google.android.tvlauncher"))
        assertFalse(FleetStatus.isHome("com.netflix.ninja"))
    }

    @Test fun jsonKeepsOnlyWhatIsKnown() {
        val j = Status("h", FleetStatus.OFF, at = 900).json(now = 1000)
        assertEquals(setOf("host", "state", "age"), j.keys().asSequence().toSet())
        assertEquals(100, j.getLong("age"))
    }

    @Test fun noAnswerIsOffAndTheOthersArePorts() {
        val none = { _: Int -> false }
        assertEquals(FleetStatus.OFF, FleetStatus.read("h", "android", none, null).state)
        assertEquals(FleetStatus.UP, FleetStatus.read("h", "android", { it == 6466 }, null).state)     // keys only: ADB off
        assertEquals(FleetStatus.UP, FleetStatus.read("h", "android", { it == 5555 }, null).state)     // ADB on, no link to ask on
        assertEquals(FleetStatus.UP, FleetStatus.read("h", "lg", { it == 3001 }, null).state)
        assertEquals(FleetStatus.OFF, FleetStatus.read("h", "samsung", none, null).state)
    }

    @Test fun oneSlowTvDoesNotHoldTheOthers() {
        val reads = AtomicInteger()
        val board = FleetStatus.Board({ h ->
            reads.incrementAndGet()
            if (h == "slow") Thread.sleep(2500)                       // a TV that hangs
            Status(h, FleetStatus.ON, at = System.currentTimeMillis())
        })
        try {
            val t0 = System.nanoTime()
            val out = board.round(listOf("a", "slow", "b"), waitMs = 400)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("round took $ms ms", ms < 1500)
            assertEquals(listOf(FleetStatus.ON, FleetStatus.UNKNOWN, FleetStatus.ON), out.map { it.state })
            board.round(listOf("slow"), waitMs = 100)                 // still being read: not started a second time
            assertEquals(3, reads.get())
            Thread.sleep(2600)
            assertEquals(FleetStatus.ON, board.round(listOf("slow"), waitMs = 100)[0].state)   // the slow reading landed
        } finally { board.close() }
    }

    @Test fun aFreshReadingIsNotAskedAgain() {
        val reads = AtomicInteger()
        val board = FleetStatus.Board({ h -> reads.incrementAndGet(); Status(h, FleetStatus.STANDBY, at = System.currentTimeMillis()) }, maxAgeMs = 60_000)
        try {
            repeat(3) { board.round(listOf("a", "b")) }
            assertEquals(2, reads.get())
            board.forget("a")
            board.round(listOf("a", "b"))
            assertEquals(3, reads.get())
        } finally { board.close() }
    }

    @Test fun aReaderThatThrowsIsReachableNotAnError() {
        val board = FleetStatus.Board({ throw RuntimeException("boom") })
        try { assertEquals(FleetStatus.UP, board.round(listOf("a"))[0].state) } finally { board.close() }
    }

    @Test fun aLiveOnlyReadingHasNoModelForTheCacheToFill() {        // CMD_LIVE: the getprops are sent once per connection
        val s = FleetStatus.parse("h", "  mWakefulness=Awake\n#~#\n  mCurrentFocus=Window{1 u0 com.netflix.ninja/.MainActivity}\n#~#\n12.5 3.1\nMemTotal: 1000 kB\nMemAvailable: 250 kB\n")
        assertEquals(FleetStatus.ON, s.state); assertEquals("com.netflix.ninja", s.app); assertEquals(25, s.memFree)
        assertEquals("", s.model)
        org.junit.Assert.assertTrue(FleetStatus.CMD.startsWith(FleetStatus.CMD_LIVE))
    }
}
