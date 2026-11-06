package dev.tvremote.core.ontv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same fault as OwnAppOpenTest on the other paths (support 11223000): a turned-off app answers `am start` like a missing one. */
class AppRestoreTest {
    private val unresolved = "Error: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW dat=tvchannels://url }"
    private val started = "Starting: Intent { }"
    private val url = "http://a.b/c.m3u8"

    /** A fake TV: [enabled] flips on `pm enable`; [knowsLink] false = an old TV Channels. [present] false = not installed at all. */
    private class Tv(val present: Boolean, var enabled: Boolean, val knowsLink: Boolean) {
        val ran = mutableListOf<String>()
        fun sh(c: String): String { ran += c
            return when {
                c.contains("pm enable") -> { if (present) enabled = true; "" }
                c.startsWith("am start") -> if (present && enabled && knowsLink) "Starting: Intent { }" else "Error: Activity not started, unable to resolve Intent"
                c.startsWith("pm path") -> if (present) "package:/data/app/x/base.apk" else ""
                c.startsWith("pm list packages -e") -> if (present && enabled) "package:" + c.substringAfterLast(' ') else ""
                else -> ""
            } }
    }

    @Test fun disabledChannelsIsBroughtBackAndPlays() {
        val tv = Tv(present = true, enabled = false, knowsLink = true)
        assertEquals(Channels.Play.OK, Channels.playResult(tv::sh, url))
        assertTrue(tv.ran.any { it.contains("install-existing --user current ${Channels.PKG}") })
    }

    @Test fun reallyOldChannelsStaysOld() {
        val tv = Tv(present = true, enabled = true, knowsLink = false)
        assertEquals(Channels.Play.OLD, Channels.playResult(tv::sh, url))
    }

    @Test fun missingChannelsStaysMissing() {
        assertEquals(Channels.Play.MISSING, Channels.playResult(Tv(present = false, enabled = false, knowsLink = false)::sh, url))
    }

    @Test fun anOkLinkIsNotRetried() {
        val tv = Tv(present = true, enabled = true, knowsLink = true)
        assertEquals(Channels.Play.OK, Channels.playResult(tv::sh, url))
        assertEquals(1, tv.ran.size)
    }

    @Test fun pinOpenIsRetried() {
        var on = false
        val ran = mutableListOf<String>()
        Home.openPin({ c -> ran += c; if (c.contains("pm enable")) { on = true; "" } else if (on) started else "Error: does not exist" }, "pin:a.b/c:d")
        assertEquals(3, ran.size)
    }

    @Test fun oldAndroidCommandChainsPlainerFallbacks() {
        val c = AppRestore.command("x.y")
        assertTrue(c.contains("install-existing x.y"))                         // without --user current
        assertTrue(c.contains("pm install-existing x.y"))                      // before cmd package
        assertTrue(c.contains("pm enable x.y"))                                // without --user current
        assertTrue(c.contains(">/dev/null 2>&1"))
    }
}
