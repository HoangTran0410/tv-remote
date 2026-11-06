package dev.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** "What went wrong?" (SupportRelay.Said): the first lines of a support report; nothing chosen is nothing added. */
class SaidTest {
    @Test fun nothingSaidAddsNothing() = assertEquals("", SupportRelay.Said.NONE.header())

    @Test fun problemAndText() =
        assertEquals("[said] problem: key\n[said] text: OK does nothing\n", SupportRelay.Said("key", "  OK   does\nnothing ").header())

    @Test fun unknownProblemIsDropped() = assertEquals("", SupportRelay.Said("hack", "").header())

    @Test fun textIsCutAndScrubbed() {
        val h = SupportRelay.Said("", "a".repeat(500) + " me@x.com").header()
        assertEquals(true, h.length < 300 && !h.contains("@x.com"))
    }
}
