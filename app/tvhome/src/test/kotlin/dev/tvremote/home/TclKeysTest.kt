package dev.tvremote.home

import dev.tvremote.home.keys.TclKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TclKeysTest {
    @Test fun tclNetflixButtonOpensNetflix() = assertEquals(listOf("com.netflix.ninja"), TclKeys.apps(4062, "TCL", "TCL"))

    @Test fun brandAloneIsEnough() = assertEquals("com.amazon.amazonvideo.livingroom", TclKeys.apps(4099, "unknown", "tcl").first())

    @Test fun otherMakersKeepTheirOwnMeaning() = assertTrue(TclKeys.apps(4062, "Hisense", "Hisense").isEmpty())

    @Test fun ordinaryKeysAreNotTaken() = assertTrue(TclKeys.apps(23, "TCL", "TCL").isEmpty())
}
