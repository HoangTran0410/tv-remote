package dev.tvremote.phone.ir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrLearnTest {
    // NEC address 0x00 (a box's usual): the 12 keys with commands as a remote would have them
    private val CMD = IrLearn.KEYS.mapIndexed { i, k -> k to (0x10 + i * 7) }.toMap()

    /** The 32 bits a NEC frame carries, as a driver that reports the frame would give them. */
    private fun wire(a: Int, c: Int) = ((a and 0xFF) or ((a.inv() and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL
    private fun rc32(a: Int, c: Int) = java.lang.Long.reverseBytes(wire(a, c)) ushr 32
    private fun rc16(a: Int, c: Int) = ((a shl 8) or c).toLong()

    @Test fun everyReadingOfTheScancodeBuildsTheSameSet() {
        for (addr in listOf(0x00, 0x04, 0xA5)) for (f in listOf(::wire, ::rc32, ::rc16)) {
            val set = IrLearn.build(CMD.mapValues { f(addr, it.value) })
            assertNotNull(set)
            for ((k, c) in CMD) assertEquals("NEC %X %X".format(addr, c), set!![k])
        }
    }

    @Test fun theBoxWouldReportTheScancodeOfEveryCodeWeSend() {
        // the closing check of step 4: encode the learned code, read the frame back, it is the scancode the box gave
        val scans = CMD.mapValues { wire(0x40, it.value) }
        val set = IrLearn.build(scans)!!
        for ((k, code) in set) {
            val sig = Ir.encode(code)!!
            var w = 0L
            for (i in 0 until 32) if (sig.pattern[3 + 2 * i] > 1000) w = w or (1L shl i)         // a long space is a 1, low bit first
            assertEquals(k, scans.getValue(k), w)
            assertTrue(IrEvents.scans(code)!!.contains(scans.getValue(k)))
        }
    }

    @Test fun keysOfTwoAddressesAreNotOneRemote() {
        val m = CMD.mapValues { wire(0x40, it.value) }.toMutableMap()
        m["POWER"] = wire(0x41, CMD.getValue("POWER"))
        assertNull(IrLearn.build(m))
    }

    @Test fun tooFewKeysOrADuplicateScancodeIsRefused() {
        assertNull(IrLearn.build(mapOf("POWER" to wire(0, 1), "UP" to wire(0, 2))))
        assertNull(IrLearn.build(CMD.mapValues { wire(0, 5) }))
    }

    @Test fun aFrameWithoutInvertedBytesIsNotNec() {
        assertNull(IrLearn.build(CMD.mapValues { 0x12345678L + it.value * 0x10000L }))
    }
}
