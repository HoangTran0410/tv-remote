package dev.tvremote.core

import dev.tvremote.core.ontv.AdbAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parsers of "Who may use ADB", fed what `dumpsys adb` and /proc/net/tcp print. */
class AdbAccessTest {
    private val k1 = "QAAAA" + "A".repeat(40) + "=="
    private val k2 = "QAAAA" + "B".repeat(40) + "=="
    private val dump = """
        ADB MANAGER STATE (dumpsys adb):
        {
          debugging_manager={
            connected_to_adb=true
            user_keys=$k1 alex@laptop
        $k2 tvremote@phone
            keystore=<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
        <keyStore version="1">
          <adbKey key="$k1 alex@laptop" lastConnection="1700000000000" />
        </keyStore>
          }
        }
    """.trimIndent()

    @Test fun keysWithNamesAndLastSeen() {
        val l = AdbAccess.keys(dump)!!
        assertEquals(2, l.size)
        assertEquals("alex@laptop", l[0].name)
        assertEquals(1700000000000L, l[0].lastSeen)
        assertEquals("tvremote@phone", l[1].name)
        assertNull(l[1].lastSeen)
        assertTrue(l[0].fingerprint.matches(Regex("(?:[0-9A-F]{2}:){15}[0-9A-F]{2}")))
    }

    @Test fun aTvThatWillNotSayIsNullNotEmpty() {
        assertNull(AdbAccess.keys("Can't find service: adb"))
        assertEquals(0, AdbAccess.keys("{ debugging_manager={ connected_to_adb=false } }")!!.size)
    }

    @Test fun connections() {
        // 192.168.1.20 (little-endian 1401A8C0) and the TV itself on 5555; one not ESTABLISHED; one on another port
        val proc = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0100007F:15B3 0100007F:C350 01 00000000:00000000 00:00000000 00000000  2000        0 1 1
               1: 0A01A8C0:15B3 1401A8C0:D2F0 01 00000000:00000000 00:00000000 00000000  2000        0 2 1
               2: 0A01A8C0:15B3 1501A8C0:D2F1 06 00000000:00000000 00:00000000 00000000  2000        0 3 1
               3: 0A01A8C0:1F90 1601A8C0:D2F2 01 00000000:00000000 00:00000000 00000000  2000        0 4 1
        """.trimIndent()
        val l = AdbAccess.clients(proc, setOf(5555))!!
        assertEquals(listOf("127.0.0.1", "192.168.1.20"), l.map { it.address })
        assertTrue(l[0].loopback)
        assertNull(AdbAccess.clients("nothing here", setOf(5555)))
    }

    @Test fun mappedIpv6AndLoopback() {
        assertEquals("192.168.1.20", AdbAccess.address("0000000000000000FFFF00001401A8C0"))
        assertEquals("::1", AdbAccess.address("00000000000000000000000001000000"))
        assertNull(AdbAccess.address("zz"))
    }

    @Test fun snapshotMarksOurKey() {
        val ours = AdbAccess.fingerprint(k2)!!
        val o = AdbAccess.snapshot(dump + "\n=====TVR=====\n\n5555\n=====TVR=====\n(none)\n=====TVR=====\n34", ours)
        assertEquals(true, o.getJSONArray("keys").getJSONObject(1).getBoolean("ours"))
        assertTrue(o.getBoolean("canRevoke"))
        assertTrue(o.isNull("clients"))
        assertNotNull(o.getJSONArray("keys"))
    }

    @Test fun revokeAnswers() {
        assertTrue(AdbAccess.revoked("Result: Parcel(00000000    '....')"))
        assertTrue(AdbAccess.revoked("Result: Parcel(\n  0x00000000: 00000000                                '....')"))
        assertTrue(AdbAccess.revoked(""))
        assertTrue(!AdbAccess.revoked("Result: Parcel(\n  0x00000000: ffffffff 00000001 '....')"))
        assertTrue(!AdbAccess.revoked("Error: Permission denied"))
    }
}
