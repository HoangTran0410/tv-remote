package dev.tvremote.phone.ir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The encoders against the protocols' own timings: frames read back bit by bit, and the two Manchester ones against their known shapes. */
class IrTest {
    private fun sig(code: String, toggle: Boolean = false) = Ir.encode(code, toggle)!!

    /** The bits of a distance-coded frame after [skip] header numbers: a long space (>= [limit]) is a 1, low bit first. */
    private fun lsb(p: IntArray, skip: Int, bits: Int, limit: Int): Long {
        var v = 0L
        for (i in 0 until bits) if (p[skip + 2 * i + 1] >= limit) v = v or (1L shl i)
        return v
    }

    /** The pattern as one character per [unit]: 1 on, 0 off. */
    private fun units(p: IntArray, unit: Int): String {
        val sb = StringBuilder()
        p.forEachIndexed { i, d -> repeat(Math.round(d / unit.toFloat())) { sb.append(if (i % 2 == 0) '1' else '0') } }
        return sb.toString()
    }

    @Test fun necIsTheStandardFrame() {                              // LG power: address 04, command 08 = 0x20DF10EF as written, high bit first
        val s = sig("NEC 4 8")
        assertEquals(38000, s.hz)
        assertEquals(67, s.pattern.size)
        assertEquals(9000, s.pattern[0]); assertEquals(4500, s.pattern[1]); assertEquals(560, s.pattern.last())
        assertEquals(0xF708FB04L, lsb(s.pattern, 2, 32, 1000))
        assertTrue(s.micros < 80_000)
    }

    @Test fun necExtKeepsBothBytesOfAddressAndCommand() {            // Hisense power as Flipper lists it: BF00 / F20D
        val s = sig("NECext BF00 F20D")
        assertEquals(0xF20DBF00L, lsb(s.pattern, 2, 32, 1000))
    }

    @Test fun samsung32RepeatsTheAddressAndInvertsTheCommand() {      // Samsung power: 07, 02 = E0E040BF as written
        val s = sig("Samsung32 7 2")
        assertEquals(4500, s.pattern[0]); assertEquals(4500, s.pattern[1]); assertEquals(550, s.pattern[2])
        assertEquals(0xFD020707L, lsb(s.pattern, 2, 32, 1000))
        assertEquals(1650, s.pattern[2 + 2 * 8 + 1])                 // address bit 8 is the copy's bit 0: 1
    }

    @Test fun sircTwelveBitsSentThreeTimes() {                       // Sony TV power: command 21, device 1 = 0xA90 as written, high bit first
        val s = sig("SIRC 1 15")
        assertEquals(40000, s.hz)
        assertEquals(3 * (2 + 24), s.pattern.size)
        val frame = 26
        assertEquals(2400, s.pattern[0]); assertEquals(600, s.pattern[1])
        for (n in 0 until 3) {                                       // in the order sent: command low bit first, then the device: 0xA90 as written
            val sent = (0 until 12).joinToString("") { if (s.pattern[n * frame + 2 + 2 * it] == 1200) "1" else "0" }
            assertEquals("101010010000", sent)
        }
        assertEquals(45000, s.pattern.take(frame).sum())             // the next frame begins 45 ms after this one
        assertEquals(45000, s.pattern.drop(frame).take(frame).sum())
    }

    @Test fun sircFifteenAndTwentyBitsAreLonger() {
        assertEquals(3 * (2 + 2 * 15), sig("SIRC15 97 23").pattern.size)
        assertEquals(3 * (2 + 2 * 20), sig("SIRC20 1 2").pattern.size)
        val s20 = sig("SIRC20 1FFF 7F")                              // all ones: every bit a 1200 us mark
        assertEquals(20, s20.pattern.take(2 + 40).filterIndexed { i, _ -> i >= 2 && i % 2 == 0 }.count { it == 1200 })
    }

    @Test fun rc5IsManchesterWithAFieldBit() {                       // Philips power: address 0, command 12; bits 1 1 0 00000 001100, 1 = off-on
        val s = sig("RC5 0 C")
        assertEquals(36000, s.hz)
        val bits = "11000000001100"
        val want = bits.map { if (it == '1') "01" else "10" }.joinToString("").dropWhile { it == '0' }
        assertEquals(want, units(s.pattern, 889))
        assertEquals(889, s.pattern[0])
    }

    @Test fun rc5TheSeventhCommandBitFlipsTheFieldBitAndTheToggleShows() {
        val low = units(sig("RC5X 0 10").pattern, 889)               // command 0x10: field bit 1
        val high = units(sig("RC5X 0 50").pattern, 889)              // command 0x50 has bit 6: field bit 0
        assertTrue(low != high)
        assertTrue(units(sig("RC5 0 C", true).pattern, 889) != units(sig("RC5 0 C", false).pattern, 889))
    }

    @Test fun rc6ModeZeroHasTheLeaderAndADoubleWidthToggle() {        // Philips power: address 0, command 12
        val s = sig("RC6 0 C")
        assertEquals(2664, s.pattern[0]); assertEquals(888, s.pattern[1])
        fun m(b: Char) = if (b == '1') "10" else "01"
        // leader, start bit 1, mode 000, the toggle bit (0, double width), address 00, command 0C
        val want = "111111" + "00" + m('1') + m('0').repeat(3) + "0011" + m('0').repeat(8) + "01010101" + "1010" + "0101"
        assertEquals(want, units(s.pattern, 444))
    }

    @Test fun rcaCarriesTheInvertedCopy() {                           // TCL: address F, command 54
        val s = sig("RCA F 54")
        assertEquals(4000, s.pattern[0]); assertEquals(4000, s.pattern[1])
        var v = 0
        for (i in 0 until 24) v = (v shl 1) or (if (s.pattern[2 + 2 * i + 1] == 2000) 1 else 0)
        assertEquals(0xF54_0AB, v)                                   // F 54, then ~F = 0, ~54 = AB
    }

    @Test fun rawIsSentAsRecorded() {
        val s = sig("RAW 38000 1000 500 600 500 700")
        assertEquals(listOf(1000, 500, 600, 500, 700), s.pattern.toList())
        assertEquals(38000, s.hz)
    }

    @Test fun noCodeNoSignalAndNeverBeyondTwoSeconds() {
        assertNull(Ir.encode("Kaseikyo 80 D0"))
        assertNull(Ir.encode("NEC zz 1"))
        assertNull(Ir.encode(""))
        assertNull(Ir.encode("RAW 38000 1 2"))
        assertNull(Ir.encode("RAW 38000 " + "30000 30000 ".repeat(40)))
        assertNotNull(Ir.encode("RC6 FF FF"))
    }

    @Test fun everyPatternStartsWithOn() {                           // a pattern starts with a mark: even-indexed entries are "on"
        for (c in listOf("RC5 1F 3F", "RC5 0 0", "RC6 0 0", "RC6 FF FF", "NEC 0 0", "SIRC 0 0")) assertTrue(c, sig(c).pattern.all { it > 0 })
    }

    @Test fun heldSendsWhatARemoteSends() {                         // a held OK must read as one long press, not presses again and again
        assertEquals(listOf(9000, 2250, 560), Ir.held("NEC 4 8", false)!!.pattern.toList())
        assertEquals(listOf(9000, 2250, 560), Ir.held("NECext 7F00 15", false)!!.pattern.toList())
        assertNull(Ir.held("NEC zz 1", false))
        assertEquals(sig("Samsung32 7 68").pattern.toList(), Ir.held("Samsung32 7 68", false)!!.pattern.toList())
        assertEquals(Ir.encode("RC5 0 C", true)!!.pattern.toList(), Ir.held("RC5 0 C", true)!!.pattern.toList())   // same toggle: same press
        assertEquals(108, Ir.period("NEC 4 8"))
        for (c in listOf("NEC 4 8", "Samsung32 7 68", "SIRC 1 15", "RC5 0 C", "RC6 0 C", "RCA F 54"))
            assertTrue(c, Ir.period(c) * 1000L > sig(c).micros)        // the next frame never starts before this one ended
    }
}
