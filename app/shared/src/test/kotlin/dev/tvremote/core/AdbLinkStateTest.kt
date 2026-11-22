package dev.tvremote.core

import dev.tvremote.core.adb.AdbLink
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** A redial after a failure reads "unknown" (connecting), not "closed": a page opened then said "needs ADB" a second before ADB was back. */
class AdbLinkStateTest {
    private fun settle(l: AdbLink): String {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) { val s = l.state(); if (s == "closed") return s; Thread.sleep(50) }
        return l.state()
    }

    @Test fun redialIsUnknown() {
        val l = AdbLink(Files.createTempDirectory("keys").toFile(), "127.0.0.1:1")   // port 1: refused at once
        assertEquals("unknown", l.state())                                // the first dial
        assertEquals("closed", settle(l))                                 // it failed, nothing under way
        AdbLink::class.java.getDeclaredField("lastTry").apply { isAccessible = true }.setLong(l, 0L)   // 10 s later
        assertEquals("unknown", l.state())                                // dialing again: not "closed"
    }

    /** TV Setup's own jobs do not ask the TV again right after a refusal (Cancel brought the question straight back); "Try again" does. */
    @Test fun quietWaitsAfterAFailure() {
        val l = AdbLink(Files.createTempDirectory("keys").toFile(), "127.0.0.1:1")
        try { l.get() } catch (e: java.io.IOException) { }                 // refused
        val msg = try { l.getQuiet(); "dialed" } catch (e: java.io.IOException) { e.message }
        org.junit.Assert.assertTrue(msg, msg!!.startsWith(AdbLink.STILL + ", last try: ") && msg.length > (AdbLink.STILL + ", last try: ").length)   // no new dial within QUIET_MS; names why the last failed
        AdbLink::class.java.getDeclaredField("lastTry").apply { isAccessible = true }.setLong(l, 0L)   // a minute later
        val again = try { l.getQuiet(); "dialed" } catch (e: java.io.IOException) { e.message }
        org.junit.Assert.assertFalse(again, again!!.startsWith(AdbLink.STILL))   // it dials (and fails on port 1 for its own reason)
    }

    /** The self-check: a redial after a failure is a TV whose ADB did not let us in (ADB_STUCK), not "ADB answers" (support 38864996). */
    @Test fun selfCheckSeesARedialAsStuck() {
        val l = AdbLink(Files.createTempDirectory("keys").toFile(), "127.0.0.1:1")
        try { l.get() } catch (e: java.io.IOException) { }                 // failed
        AdbLink::class.java.getDeclaredField("lastTry").apply { isAccessible = true }.setLong(l, 0L)
        assertEquals("closed", Diagnose.Real(l).adb("127.0.0.1:1"))      // dialing again, the page's "unknown"; the self-check's "closed"
    }

    private fun set(l: AdbLink, name: String, v: Any) = AdbLink::class.java.getDeclaredField(name).apply { isAccessible = true }.set(l, v)

    /** The wait grows for a TV that never lets us in, and stops after questions nobody answered (LauncherGuard re-asked every ~4 min). */
    @Test fun quietGrowsAndStops() {
        assertEquals(60_000L, AdbLink.quietMs(0, 0))
        assertEquals(60_000L, AdbLink.quietMs(1, 0))
        assertEquals(120_000L, AdbLink.quietMs(2, 0))
        assertEquals(480_000L, AdbLink.quietMs(4, 0))
        assertEquals(AdbLink.MAX_QUIET_MS, AdbLink.quietMs(5, 0))
        assertEquals(AdbLink.MAX_QUIET_MS, AdbLink.quietMs(500, 0))
        assertEquals(60_000L, AdbLink.quietMs(1, 1))                      // one Cancel: the minute, as before
        assertEquals(AdbLink.ASKED_QUIET_MS, AdbLink.quietMs(2, 2))
        assertEquals(Long.MAX_VALUE, AdbLink.quietMs(3, AdbLink.ASK_STOP))
    }

    @Test fun stoppedAskingUntilSomeoneUsesIt() {
        val l = AdbLink(Files.createTempDirectory("keys").toFile(), "127.0.0.1:1")
        try { l.get() } catch (e: java.io.IOException) { }                 // failed
        set(l, "fails", 3); set(l, "unanswered", 3); set(l, "lastTry", 0L)  // three questions nobody answered, long ago
        val quiet = try { l.getQuiet(); "dialed" } catch (e: java.io.IOException) { e.message }
        org.junit.Assert.assertTrue(quiet, quiet!!.startsWith(AdbLink.STILL))
        l.letAsk()                                                         // TV Setup opened, or the web page used
        val again = try { l.getQuiet(); "dialed" } catch (e: java.io.IOException) { e.message }
        org.junit.Assert.assertFalse(again, again!!.startsWith(AdbLink.STILL))
    }

    /** close() is for good: a waiting caller or a late "Try again" does not dial (and leak) a new connection; reset() is not. */
    @Test fun closedStaysClosed() {
        val l = AdbLink(Files.createTempDirectory("keys").toFile(), "127.0.0.1:1")
        l.close()
        assertEquals(AdbLink.CLOSED, try { l.get(); "dialed" } catch (x: java.io.IOException) { x.message })
        assertEquals(AdbLink.CLOSED, try { l.getQuiet(); "dialed" } catch (x: java.io.IOException) { x.message })
        assertEquals("closed", l.state())
        assertEquals("closed", l.reconnect())
        val r = AdbLink(Files.createTempDirectory("keys").toFile(), "127.0.0.1:1")
        r.reset()                                                          // a TV restarting: the next call dials again
        assertEquals("unknown", r.state())
    }

    @Test fun quickRetryOnlyForOwnConnectTimeout() {
        val connect = java.net.SocketTimeoutException("connect timed out")
        org.junit.Assert.assertTrue(AdbLink.quickRetry("127.0.0.1", connect))
        org.junit.Assert.assertFalse(AdbLink.quickRetry("127.0.0.1", java.net.SocketTimeoutException("Read timed out")))   // a hung adbd
        org.junit.Assert.assertFalse(AdbLink.quickRetry("192.168.1.20", connect))   // another TV: the person's own Try again
    }
}
