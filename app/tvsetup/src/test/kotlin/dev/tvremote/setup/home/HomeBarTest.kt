package dev.tvremote.setup.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBarTest {
    private val homes = setOf("dev.tvremote.home", "com.google.android.tvlauncher")

    @Test fun anAppInFrontGetsTheBar() = assertTrue(HomeBar.opens("com.netflix.ninja", homes))

    @Test fun aHomeScreenInFrontLetsHomeThrough() {                  // TV Home itself, and the bar (it is TV Home's)
        assertFalse(HomeBar.opens("dev.tvremote.home", homes))
        assertFalse(HomeBar.opens("com.google.android.tvlauncher", homes))
    }

    @Test fun nothingKnownOrTheSystemsBarsLetHomeThrough() {
        assertFalse(HomeBar.opens("", homes))
        assertFalse(HomeBar.opens("com.android.systemui", homes))
    }
}
