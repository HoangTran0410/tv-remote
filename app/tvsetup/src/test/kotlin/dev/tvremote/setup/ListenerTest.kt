package dev.tvremote.setup

import dev.tvremote.setup.rules.Guard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The volume limit leaves the sound alone while it goes to a 🎧 listener (Guard.toListener), and never throws finding out. */
class ListenerTest {
    private val none: () -> Boolean = { throw AssertionError("not asked") }

    @Test fun theSubmixBitMeansAListener() {
        assertTrue(Guard.toListener({ 0x8000 or 0x2 }, none))
        assertFalse(Guard.toListener({ 0x2 }, none))                  // the speakers only: the limit holds
    }

    @Test fun hiddenMethodGoneAsksWhoRecords() {
        assertTrue(Guard.toListener({ throw NoSuchMethodException() }, { true }))
        assertFalse(Guard.toListener({ throw NoSuchMethodException() }, { false }))
    }

    @Test fun bothWaysGoneIsNoListenerNotACrash() {                   // Android 5-6: the fallback threw out of capVolume
        assertFalse(Guard.toListener({ throw NoSuchMethodException() }, { throw NoSuchMethodError() }))
    }
}
