package dev.tvremote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A download that fails is told from the TV's ADB link failing (Manage answers 502 "net: …", not 503 "adb: …"). */
class ErrorsNetTest {
    private fun fail(f: () -> Unit): IOException = try { f(); throw AssertionError("no error") } catch (e: IOException) { e }

    @Test fun aDownloadIsTheInternet() {
        val e = fail { java.net.URL("http://no-such-host.invalid/x.apk").openStream().close() }
        assertTrue(e.toString(), Errors.fromInternet(e))
    }

    @Test fun aClosedPortIsNot() {                                         // what an ADB link to a TV that is off throws
        val free = java.net.ServerSocket(0).use { it.localPort }
        val e = fail { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", free), 2000) } }
        assertFalse(e.toString(), Errors.fromInternet(e))
    }

    @Test fun ownWordsAreNot() = assertFalse(Errors.fromInternet(IOException("home:missing")))
}
