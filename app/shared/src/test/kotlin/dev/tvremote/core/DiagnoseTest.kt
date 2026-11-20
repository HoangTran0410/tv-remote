package dev.tvremote.core

import dev.tvremote.core.Diagnose.Net
import dev.tvremote.core.Diagnose.Port
import dev.tvremote.core.remote.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Which ports answer, what else was seen, and the one conclusion that follows (Diagnose.kt), with a fake network. */
class DiagnoseTest {
    private val A = Remote.Kind.ANDROID

    private fun facts(kind: String = A, open: List<Int> = emptyList(), refused: List<Int> = emptyList(), net: Net = Net.OK, vpn: Boolean = false,
                      adb: String? = null, tls: Boolean = false, moved: Diagnose.Found? = null, ip: String = "192.168.1.20"): Diagnose.Facts {
        val ports = Diagnose.ports(kind).associateWith { if (it in open) Port.OPEN else if (it in refused) Port.REFUSED else Port.SILENT }
        return Diagnose.Facts(kind, ip, ports, net, vpn, adb, tls, moved)
    }

    private fun check(want: String, f: Diagnose.Facts) = assertEquals(want, Diagnose.decide(f))

    @Test fun androidTvTable() {
        check(Diagnose.UP, facts(open = listOf(5555, 6466, 6467)))
        check(Diagnose.UP, facts(open = listOf(5555, 6466), adb = "ready"))
        check(Diagnose.ADB_OFF, facts(open = listOf(6466, 6467), refused = listOf(5555)))        // keys still work
        check(Diagnose.ADB_OFF, facts(open = listOf(6466)))
        check(Diagnose.ADB_OFF, facts(open = listOf(8765)))                                         // only TV Setup's page answers
        check(Diagnose.ALLOW, facts(open = listOf(5555, 6466), adb = "unauthorized"))
        check(Diagnose.ALLOW, facts(open = listOf(5555), adb = "waiting"))
        check(Diagnose.REMOTE_OFF, facts(open = listOf(5555), adb = "ready"))
        check(Diagnose.REMOTE_OFF, facts(open = listOf(5555)))
        check(Diagnose.ADB_STUCK, facts(open = listOf(5555, 6466, 6467), adb = "closed"))   // the port answers, the link gave up: not "momentary"
        check(Diagnose.ADB_STUCK, facts(open = listOf(5555), adb = "closed"))
        check(Diagnose.UP, facts(open = listOf(5555, 6466), adb = "unknown"))                 // still dialling
        check(Diagnose.ADB_OFF, facts(open = listOf(6466), adb = "closed"))                   // 5555 shut: ADB off, not stuck
    }

    @Test fun fireTvWithUnfinishedSetup() {
        assertTrue(Diagnose.setupIncomplete("Amazon\n0\n"))
        assertTrue(Diagnose.setupIncomplete("amazon\r\n0"))
        assertTrue(!Diagnose.setupIncomplete("Amazon\n1\n")); assertTrue(!Diagnose.setupIncomplete("Amazon\nnull\n"))
        assertTrue(!Diagnose.setupIncomplete("Sony\n0\n")); assertTrue(!Diagnose.setupIncomplete("")); assertTrue(!Diagnose.setupIncomplete("0"))
        val r = Diagnose.setup("Amazon\n0", "192.168.1.9", "en")!!
        assertEquals(Diagnose.SETUP_OFF, r.code); assertEquals(listOf("fixsetup"), Diagnose.actions(r.code))
        assertTrue(Diagnose.json(r, "vi").getString("title").isNotBlank()); assertTrue(Diagnose.setup("Amazon\n1", "x", "en") == null)
        assertEquals("settings put secure user_setup_complete 1", Diagnose.SETUP_FIX)
    }

    @Test fun nothingOpen() {
        check(Diagnose.OFF, facts())                                                                // all silent: off, asleep, other address
        check(Diagnose.CLOSED, facts(refused = listOf(5555, 6466, 6467, 8765)))                     // refused: the TV's card answers
        check(Diagnose.CLOSED, facts(refused = listOf(6466)))
        check(Diagnose.MOVED, facts(moved = Diagnose.Found("192.168.1.31", "Living room")))
        check(Diagnose.VPN, facts(vpn = true))
        check(Diagnose.OFF, facts(vpn = true, ip = "100.101.102.103"))                              // a VPN is no excuse for a Tailscale address
        check(Diagnose.MOVED, facts(vpn = true, moved = Diagnose.Found("192.168.1.31", "x")))       // a concrete find beats a guess
    }

    @Test fun networkRulesOutTheTv() {
        check(Diagnose.NO_WIFI, facts(net = Net.NONE))
        check(Diagnose.OTHER_NET, facts(net = Net.OTHER))
        check(Diagnose.TAILSCALE, facts(net = Net.TAILSCALE_OFF, ip = "100.90.1.2"))
        check(Diagnose.OTHER_NET, facts(net = Net.OTHER, vpn = true))
        check(Diagnose.UP, facts(open = listOf(5555, 6466), net = Net.OTHER))                       // it answers: the network is fine for it
        check(Diagnose.NO_WIFI, facts(net = Net.NONE, refused = listOf(5555)))
    }

    @Test fun clockNeedsAnAnswer() {
        check(Diagnose.CLOCK, facts(open = listOf(6466, 6467), tls = true))
        check(Diagnose.OFF, facts(tls = true))                                                      // a TLS error from a TV that does not answer now
        assertTrue(Diagnose.clockError("SSLHandshakeException: Chain validation failed"))
        assertTrue(Diagnose.clockError("CertificateNotYetValidException: not yet valid"))
        assertTrue(!Diagnose.clockError("ConnectException: Connection refused"))
    }

    @Test fun otherBrands() {
        check(Diagnose.UP, facts(Remote.Kind.LG, open = listOf(3001)))
        check(Diagnose.UP, facts(Remote.Kind.SAMSUNG, open = listOf(8002)))
        check(Diagnose.UP, facts(Remote.Kind.ROKU, open = listOf(8060)))
        check(Diagnose.UP, facts(Remote.Kind.VIDAA, open = listOf(36669)))
        check(Diagnose.OFF, facts(Remote.Kind.LG))
        check(Diagnose.CLOSED, facts(Remote.Kind.SAMSUNG, refused = listOf(8001, 8002)))
        check(Diagnose.CLOCK, facts(Remote.Kind.LG, open = listOf(3001), tls = true))
        assertEquals(listOf(3000, 3001), Diagnose.ports(Remote.Kind.LG))
        assertTrue(5555 !in Diagnose.ports(Remote.Kind.ROKU))                                       // no ADB on a Roku: never asked
    }

    @Test fun vpnOnlyMattersAtHome() {
        check(Diagnose.VPN, facts(vpn = true, ip = "10.0.0.7"))
        check(Diagnose.OFF, facts(vpn = true, ip = "8.8.8.8"))
    }

    @Test fun everyConclusionHasWordsInBothLanguagesAndAnAction() {
        for (code in Diagnose.CODES) {
            val f = facts(moved = Diagnose.Found("192.168.1.31", "Living room"))
            val (vt, vf) = Diagnose.say(code, f, "vi")
            val (et, ef) = Diagnose.say(code, f, "en")
            assertTrue(code, vt.isNotBlank() && vf.isNotBlank() && et.isNotBlank() && ef.isNotBlank())
            assertTrue(code, vt != et && vf != ef)
            assertTrue(code, Diagnose.actions(code).isNotEmpty() && ("retry" in Diagnose.actions(code) || code == Diagnose.SETUP_OFF))   // SETUP_OFF: a banner on a TV that answers, Fix only
        }
        assertTrue(Diagnose.say(Diagnose.MOVED, facts(moved = Diagnose.Found("192.168.1.31", "Living room")), "en").second.contains("192.168.1.31"))
    }

    @Test fun subnets() {
        fun l(ip: String, p: Int) = Diagnose.Local(InetAddress.getByName(ip), p)
        assertEquals(Net.OK, Diagnose.netOf("192.168.1.20", listOf(l("192.168.1.5", 24))))
        assertEquals(Net.OTHER, Diagnose.netOf("192.168.1.20", listOf(l("192.168.0.5", 24))))
        assertEquals(Net.OK, Diagnose.netOf("192.168.1.20", listOf(l("192.168.0.5", 16))))
        assertEquals(Net.NONE, Diagnose.netOf("192.168.1.20", emptyList()))
        assertEquals(Net.NONE, Diagnose.netOf("192.168.1.20", listOf(l("100.64.0.9", 10))))         // Tailscale's address is no LAN
        assertEquals(Net.OK, Diagnose.netOf("10.1.2.3", listOf(l("100.64.0.9", 10), l("10.1.0.4", 16))))
        assertEquals(Net.NA, Diagnose.netOf("127.0.0.1", emptyList()))
        assertEquals(Net.NA, Diagnose.netOf("tv.local", emptyList()))                               // a name is never looked up
        assertEquals(Net.NA, Diagnose.netOf("8.8.8.8", emptyList()))
    }

    /** The whole run through a fake network: the order of looking, the search only when nothing answers, the answer's shape. */
    private class Fake(val open: Set<Int> = emptySet(), val refused: Set<Int> = emptySet(), val net: Net = Net.OK, val vpn: Boolean = false,
                       val adb: String? = null, val found: Diagnose.Found? = null) : Diagnose.Probe {
        var searched = 0
        override fun port(host: String, port: Int, ms: Int) = if (port in open) Port.OPEN else if (port in refused) Port.REFUSED else Port.SILENT
        override fun net(host: String) = net
        override fun vpn() = vpn
        override fun adb(host: String) = adb
        override fun find(kind: String, name: String, not: String): Diagnose.Found? { searched++; return found }
    }

    @Test fun runLooksAroundOnlyWhenNothingAnswers() {
        val up = Fake(open = setOf(6466, 6467))
        val r1 = Diagnose.run(up, "192.168.1.20", A, "Living room", "", "vi")
        assertEquals(Diagnose.ADB_OFF, r1.code)
        assertEquals(0, up.searched)

        val gone = Fake(found = Diagnose.Found("192.168.1.31", "Living room"))
        val r2 = Diagnose.run(gone, "192.168.1.20", A, "Living room", "timed out", "en")
        assertEquals(Diagnose.MOVED, r2.code)
        assertEquals(1, gone.searched)
        assertEquals("192.168.1.31", r2.found?.ip)

        val nameless = Fake(found = Diagnose.Found("192.168.1.31", "x"))
        assertEquals(Diagnose.OFF, Diagnose.run(nameless, "192.168.1.20", A, "", "", "vi").code)   // no name saved: nothing to look for
        assertEquals(0, nameless.searched)

        val dead = Fake()
        assertEquals(Diagnose.OFF, Diagnose.run(dead, "10.255.255.1", A, "TV", "", "vi").code)
        assertEquals(Diagnose.CLOCK, Diagnose.run(Fake(open = setOf(6467)), "192.168.1.20", A, "TV", "SSLHandshakeException: not yet valid", "vi").code)
        assertEquals(Diagnose.ALLOW, Diagnose.run(Fake(open = setOf(5555), adb = "unauthorized"), "192.168.1.20", A, "TV", "", "vi").code)
    }

    @Test fun jsonCarriesTheConclusion() {
        val r = Diagnose.run(Fake(open = setOf(6466)), "192.168.1.20", A, "TV", "", "en")
        val j = Diagnose.json(r, "en")
        assertEquals("adb_off", j.getString("code"))
        assertTrue(j.getString("title").contains("ADB"))
        assertEquals("adbhelp", j.getJSONArray("actions").getString(0))
        assertNotNull(j.getJSONArray("checks"))
        assertTrue(j.getJSONArray("checks").length() >= 2)
        val vi = Diagnose.json(r, "vi")
        assertTrue(vi.getString("fix").contains("Gỡ lỗi ADB"))
    }
    @Test fun ispBoxSaysTheProviderLockedIt() {                     // an FPT Play: its Developer options are behind a password
        val fpt = Diagnose.json(Diagnose.run(Fake(open = setOf(6466)), "192.168.1.20", A, "FPT Play 650", "", "en"), "en").getString("fix")
        assertTrue(fpt.contains("FPT"))
        assertTrue(!fpt.contains("TCL"))
        val plain = Diagnose.json(Diagnose.run(Fake(open = setOf(6466)), "192.168.1.20", A, "TV", "", "en"), "en").getString("fix")
        assertTrue(!plain.contains("FPT"))
    }
}
