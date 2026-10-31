package dev.tvremote.setup.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLogTest {
    private val down = 0
    private val up = 1

    @Test fun commonKeysGetTheirWordsOthersAReadableName() {
        val word = { id: String -> "[$id]" }
        assertEquals("[keytest.k_ok]", KeyNames.label(23, "KEYCODE_DPAD_CENTER", word))
        assertEquals("[keytest.k_red]", KeyNames.label(183, "KEYCODE_PROG_RED", word))
        assertEquals("5", KeyNames.label(12, "KEYCODE_5", word))
        assertEquals("7", KeyNames.label(151, "KEYCODE_NUMPAD_7", word))
        assertEquals("Media top menu", KeyNames.label(226, "KEYCODE_MEDIA_TOP_MENU", word))
        assertEquals("Key 999", KeyNames.label(999, "999", word))
        assertEquals("Button 12", KeyNames.readable("KEYCODE_BUTTON_12"))
    }

    @Test fun everyWordIdIsKnown() {
        assertEquals(KeyNames.WORDS.values.toSet(), KeyNames.IDS.keys)
    }

    @Test fun aPressIsOneLineFromDownToUp() {
        val l = KeyLog()
        val p = l.feed(23, 353, "remote", down, 0, 1000, 1000)
        assertEquals(KeyPress.Phase.DOWN, p.phase)
        l.feed(23, 353, "remote", down, 1, 1000, 1500, longFlag = true)
        val held = l.feed(23, 353, "remote", down, 4, 1000, 1700)
        assertEquals(KeyPress.Phase.LONG, held.phase)
        assertEquals(4, held.repeats)
        val end = l.feed(23, 353, "remote", up, 0, 1000, 1900)
        assertEquals(1, l.items.size)
        assertEquals(KeyPress.Phase.UP, end.phase)
        assertEquals(900L, end.heldMs)
        assertTrue(end.long)
    }

    @Test fun aShortHoldIsNotLongUntilHalfASecond() {
        val l = KeyLog()
        l.feed(20, 0, "", down, 0, 0, 0)
        assertEquals(KeyPress.Phase.HELD, l.feed(20, 0, "", down, 1, 0, 100).phase)
        assertEquals(KeyPress.Phase.LONG, l.feed(20, 0, "", down, 9, 0, 600).phase)
    }

    @Test fun historyKeepsTheLastTwelveNewestFirst() {
        val l = KeyLog()
        for (c in 7..24) { l.feed(c, 0, "", down, 0, 0, c.toLong()); l.feed(c, 0, "", up, 0, 0, c.toLong() + 1) }
        assertEquals(12, l.items.size)
        assertEquals(24, l.items.first().code)
        assertEquals(13, l.items.last().code)
    }

    @Test fun anUpWithoutItsDownStillShows() {
        val l = KeyLog()
        assertEquals(KeyPress.Phase.UP, l.feed(4, 0, "", up, 0, 0, 50).phase)
    }

    @Test fun backLeavesWhenHeldTwoSecondsOrPressedTwiceQuickly() {
        val b = BackExit()
        assertFalse(b.onKey(down, 0, 1000, 1000))
        assertFalse(b.onKey(up, 0, 1000, 1100))
        assertFalse(b.onKey(down, 0, 5000, 5000))                    // a first press long after the last: no
        assertTrue(b.onKey(down, 0, 5400, 5400))                     // the second within the gap: out

        val h = BackExit()
        assertFalse(h.onKey(down, 0, 1000, 1000))
        assertFalse(h.onKey(down, 3, 1000, 2500))                    // held 1.5 s
        assertTrue(h.onKey(down, 9, 1000, 3100))                     // held 2.1 s
    }
}
