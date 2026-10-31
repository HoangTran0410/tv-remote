package dev.tvremote.home.wall

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class NetVideoTest {
    private val hits = AtomicInteger()
    @Volatile private var now = 1_000_000L
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private fun url(p: String) = "http://127.0.0.1:${server.localPort}/$p"

    @Before fun up() {
        NetVideo.forgetRefused(); NetVideo.clock = { now }
        dir = java.nio.file.Files.createTempDirectory("netvideo").toFile()
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {                                                  // a bare HTTP server: the Android classpath has no com.sun one
            try { while (true) server.accept().use { s ->
                val path = s.getInputStream().bufferedReader().readLine().split(" ")[1]; hits.incrementAndGet()
                val o = s.getOutputStream()
                when (path) {
                    "/big" -> o.write("HTTP/1.1 200 OK\r\nContent-Length: ${200L * 1024 * 1024}\r\n\r\n".toByteArray())   // says 200 MB, sends none
                    "/busy" -> o.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    "/gone" -> o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    else -> { o.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".toByteArray()); o.write(ByteArray(1000)) }
                }
                o.flush()
            } } catch (e: Exception) { }
        }.apply { isDaemon = true }.start()
    }

    @After fun down() { NetVideo.clock = { System.currentTimeMillis() }; server.close(); dir.deleteRecursively() }

    @Test fun `a video too big is asked for once`() {
        repeat(5) { assertNull(NetVideo.load(dir, url("big"), Long.MAX_VALUE)) }
        assertEquals(1, hits.get())
    }

    @Test fun `a refused answer is asked for once`() {
        repeat(5) { assertNull(NetVideo.load(dir, url("gone"), Long.MAX_VALUE)) }
        assertEquals(1, hits.get())
    }

    @Test fun `no room is asked for once and again after the backoff`() {
        repeat(3) { assertNull(NetVideo.load(dir, url("ok"), 10L)) }   // the size comes with the answer, so the first request is the only one
        assertEquals(1, hits.get())
        now += 6 * 60_000                                              // room may have come back
        assertNotNull(NetVideo.load(dir, url("ok"), Long.MAX_VALUE))
        assertEquals(2, hits.get())
    }

    @Test fun `a server error is retried after a backoff, never for ever`() {
        repeat(3) { assertNull(NetVideo.load(dir, url("busy"), Long.MAX_VALUE)) }
        assertEquals(1, hits.get())
        now += 6 * 60_000
        assertNull(NetVideo.load(dir, url("busy"), Long.MAX_VALUE))
        assertEquals(2, hits.get())                                    // asked again (the old code never did)
    }

    @Test fun `a 404 stays refused after any time`() {
        assertNull(NetVideo.load(dir, url("gone"), Long.MAX_VALUE)); now += 24 * 3_600_000L
        assertNull(NetVideo.load(dir, url("gone"), Long.MAX_VALUE))
        assertEquals(1, hits.get())
    }

    @Test fun `a good one is kept`() {
        assertNotNull(NetVideo.load(dir, url("ok"), Long.MAX_VALUE))
    }

    @Test fun `one that cannot be made light is not kept, nor fetched again`() {
        repeat(3) { assertNull(NetVideo.load(dir, url("ok"), Long.MAX_VALUE) { _, _ -> false }) }
        assertEquals(1, hits.get())
        assertEquals(emptyList<String>(), dir.list()!!.toList())          // neither what came nor a half-made file
    }

    @Test fun `what is kept is what fit made`() {
        val f = NetVideo.load(dir, url("ok"), Long.MAX_VALUE) { got, out -> got.length() == 1000L && out.writeBytes(ByteArray(10)).let { true } }
        assertEquals(10L, f!!.length())
        assertEquals(listOf(f.name), dir.list()!!.toList())
    }
}
