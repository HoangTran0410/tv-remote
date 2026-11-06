package dev.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WolTest {
    @Test fun aMacWithLettersThatAreNotHexIsRefusedNotThrown() {      // toInt(16) threw on "GG": a 500 instead of "bad MAC"
        assertEquals(0, Wol.send("AA:BB:CC:DD:EE:GG"))
        assertEquals(0, Wol.send("AA:BB:CC:DD:EE"))
        assertEquals(0, Wol.send(""))
    }
}
