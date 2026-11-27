package dev.tvremote.core

import dev.tvremote.core.picture.Tv
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone sends one letter at a time as it is typed, but a pasted selection must go as one paste. */
class TvTextTest {
    @Test fun oneTypedLetterIsAKeyEvent() {
        assertFalse(Tv.needsClipboard("a"))
    }

    @Test fun aPastedSelectionUsesTheClipboard() {
        assertTrue(Tv.needsClipboard("abcdefghij"))
    }

    @Test fun anAccentUsesTheClipboardOnItsOwn() {
        assertTrue(Tv.needsClipboard("ở"))
    }

    @Test fun anEmojiUsesTheClipboardOnItsOwn() {
        assertTrue(Tv.needsClipboard("👍"))
    }
}
