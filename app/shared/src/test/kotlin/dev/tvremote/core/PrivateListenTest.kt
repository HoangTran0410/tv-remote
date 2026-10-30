package dev.tvremote.core

import dev.tvremote.core.picture.PrivateListen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "Only this device" on Android 13+: the TV's volume goes to 0 while listening and comes back exactly, also after a crash. */
class PrivateListenTest {
    /** A TV with a music volume and one file; [fixed]: it takes the volume commands and keeps its level (HDMI-CEC), keys included. */
    private class Tv(var vol: Int, var mark: String? = null, val fixed: Boolean = false) {
        val cmds = ArrayList<String>()
        fun sh(c: String): String {
            cmds.add(c)
            return when {
                c.contains("--stream 3 --set") -> { if (!fixed) vol = Regex("--set (\\d+)").find(c)!!.groupValues[1].toInt(); "" }
                c.contains("--stream 3 --get") -> "[V] volume is $vol in range [0..15]"
                c.startsWith("cat ") -> mark ?: ""
                c.startsWith("echo ") -> { mark = c.substringAfter("echo ").substringBefore(" >").trim(); "" }
                c.startsWith("rm -f") -> { mark = null; "" }
                c.startsWith("input keyevent") -> { if (!fixed) { val n = c.split(";").size; vol = (vol + n * (if (c.contains("24")) 1 else -1)).coerceIn(0, 15) }; "" }
                else -> ""
            }
        }
    }

    @Before fun fresh() { PrivateListen.reset() }

    @Test fun quietThenBackExactly() {
        val tv = Tv(9)
        assertTrue(PrivateListen.engage(tv::sh))
        assertEquals(0, tv.vol); assertEquals("9", tv.mark)           // the way it was is on the TV before it goes quiet
        PrivateListen.release(tv::sh)
        assertEquals(9, tv.vol); assertNull(tv.mark)
    }

    @Test fun aVolumeTheCrashLeftBehindIsUndoneByTheNextConnection() {
        val tv = Tv(9)
        PrivateListen.engage(tv::sh)                                  // ...and the phone goes away: no release
        PrivateListen.reset()                                         // a new process
        PrivateListen.recover(tv::sh, PrivateListen.left(tv.sh("cat x")))
        assertEquals(9, tv.vol); assertNull(tv.mark)
    }

    @Test fun anotherListenerStillHereKeepsTheTvQuiet() {
        val tv = Tv(6)
        PrivateListen.engage(tv::sh)
        PrivateListen.recover(tv::sh, PrivateListen.left(tv.sh("cat x")))   // a second session connecting meanwhile
        assertEquals(0, tv.vol); assertEquals("6", tv.mark)
        PrivateListen.release(tv::sh)
        assertEquals(6, tv.vol)
    }

    @Test fun whatThePersonSetMeanwhileIsTheirs() {
        val tv = Tv(9)
        PrivateListen.engage(tv::sh)
        tv.vol = 4                                                    // they turned it up on the remote
        PrivateListen.release(tv::sh)
        assertEquals(4, tv.vol); assertNull(tv.mark)
    }

    @Test fun aSecondRunKeepsTheTrueBefore() {
        val tv = Tv(0, mark = "8")                                    // left at 0 by a run that died; a new listener starts before any recover
        assertTrue(PrivateListen.engage(tv::sh))
        assertEquals("8", tv.mark)                                    // not overwritten with 0
        PrivateListen.release(tv::sh)
        assertEquals(8, tv.vol)
    }

    @Test fun aTvThatKeepsItsVolumeIsNotLeftChanged() {
        val tv = Tv(7, fixed = true)
        assertFalse(PrivateListen.engage(tv::sh))                     // the caller then says the TV keeps playing
        assertEquals(7, tv.vol); assertNull(tv.mark)
    }

    @Test fun alreadyQuietNeedsNothingBack() {
        val tv = Tv(0)
        assertTrue(PrivateListen.engage(tv::sh)); assertNull(tv.mark)
        PrivateListen.release(tv::sh)
        assertEquals(0, tv.vol)
    }

    @Test fun owedOnlyWhileStillAtZero() {
        assertEquals(9, PrivateListen.owed(9, 0))
        assertNull(PrivateListen.owed(9, 3)); assertNull(PrivateListen.owed(9, null)); assertNull(PrivateListen.owed(null, 0)); assertNull(PrivateListen.owed(0, 0))
        assertEquals(5, PrivateListen.left(" 5\n")); assertNull(PrivateListen.left("")); assertNull(PrivateListen.left("nope"))
    }
}
