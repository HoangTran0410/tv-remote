package dev.tvremote.phone.ir

import dev.tvremote.phone.remote.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrAutoTest {
    // the devices `getevent` lists on a BRAVIA (MediaTek); the event lines in the format getevent -l prints
    private val BRAVIA = """
        add device 1: /dev/input/event4
          name:     "virtual-remote"
        add device 2: /dev/input/event2
          name:     "SONY_MAIN_UNIT_KEY"
        add device 3: /dev/input/event0
          name:     "mtkinp_ir_events"
    """.trimIndent().lines()

    private fun reader(head: List<String>) = IrEvents.Reader().also { r -> head.forEach { assertFalse(it, r.key(it)) } }

    @Test fun aKeyFromTheIrEyeIsHeard() {
        val r = reader(BRAVIA)
        assertTrue(r.key("/dev/input/event0: EV_KEY       KEY_VOLUMEUP         DOWN"))
        assertFalse(r.key("/dev/input/event0: EV_KEY       KEY_VOLUMEUP         UP"))       // one press, not two
        assertFalse(r.key("/dev/input/event0: EV_SYN       SYN_REPORT           00000000"))
    }

    @Test fun aScanCodeTheTvMapsToNothingIsHeardToo() {
        assertTrue(reader(BRAVIA).key("/dev/input/event0: EV_MSC       MSC_SCAN             00000018\r"))
    }

    @Test fun keysSentOverTheNetworkAreNot() {
        assertFalse(reader(BRAVIA).key("/dev/input/event4: EV_KEY       KEY_VOLUMEUP         DOWN"))
    }

    @Test fun aDeviceNotListedStillCounts() {                       // added after getevent started (a USB remote)
        assertTrue(reader(BRAVIA).key("/dev/input/event7: EV_KEY       KEY_ENTER            DOWN"))
    }

    @Test fun theScanStopsAtTheCodeTheTvHeardTwice() {
        var last = 0L
        val sent = ArrayList<String>()
        val hit = irScan(listOf("a", "b", "c"), { it }, { c -> sent.add(c); if (c == "b") last = irNow() + 1 }, { last }, { false }, { }, waitMs = 60)
        assertEquals("b", hit)
        assertEquals(listOf("a", "b", "b"), sent)
    }

    @Test fun oneStrayKeyIsNotAMatch() {                            // someone pressed the real remote once, during "a"
        var last = 0L
        var stray = true
        val hit = irScan(listOf("a", "b"), { it }, { if (stray) { stray = false; last = irNow() + 1 } }, { last }, { false }, { }, waitMs = 60)
        assertNull(hit)
    }

    @Test fun stopEndsIt() {
        var n = 0
        assertNull(irScan(listOf("a", "b", "c"), { it }, { n++ }, { 0L }, { n >= 1 }, { }, waitMs = 60))
        assertEquals(1, n)
    }

    // the box that answered "LG" to the first NEC frame: its decoder reports a scancode for any NEC key, mapped or not
    @Test fun aBareScancodeOfAnotherRemoteIsNotAMatch() {
        val lg = "NEC 4 2"
        assertEquals(0x0402L, IrEvents.scanOf("/dev/input/event0: EV_MSC       MSC_SCAN             00000402\r"))
        assertTrue(0x0402L in IrEvents.scans(lg)!!)
        assertTrue(0xFD02FB04L and 0xFFFFFFFFL in IrEvents.scans(lg)!!)                        // the frame as sent: 04 FB 02 FD
        assertFalse(0x00BF08L in IrEvents.scans(lg)!!)                                          // the box's own remote (another address)
        assertTrue(0x7F00E11EL in IrEvents.scans("NECext 7F00 E11E")!!)
        assertNull(IrEvents.scans("RAW 38000 9000 4500"))
    }

    @Test fun onlyAScanOfTheCodeSentCounts() {
        val r = reader(BRAVIA)
        assertTrue(r.key("/dev/input/event0: EV_MSC       MSC_SCAN             00BF08"))
        assertFalse(r.mapped)
        assertTrue(r.key("/dev/input/event0: EV_KEY       KEY_VOLUMEUP         DOWN"))
        assertTrue(r.mapped)
    }

    @Test fun theScanSendsWhatTheButtonSends() {
        val all = IrCodes.merge(IrCodes.parse(java.io.File("src/main/assets/ir/codes.json").readText()))
        for ((p, _) in IrCodes.distinct(all, "VOL_UP")) {
            val code = p.keys.getValue("VOL_UP")
            assertEquals(code, p.keys["VOL_UP"])                            // the screen's button reads the same entry
            assertTrue(code, Ir.encode(code) != null)
        }
        val nec = Ir.encode("NEC 4 2")!!                                    // 9 ms + 4.5 ms leader, then 04 FB 02 FD low bit first
        assertEquals(38000, nec.hz)
        assertEquals(listOf(9000, 4500, 560, 560), nec.pattern.take(4))
        assertEquals(67, nec.pattern.size)
    }
}
