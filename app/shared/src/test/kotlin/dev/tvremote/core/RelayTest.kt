package dev.tvremote.core

import dev.tvremote.core.picture.CatchUp
import dev.tvremote.core.picture.Tv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tv's relay (the picture from TV Setup's /api/video, touches to its /api/touch) against a made-up TV Setup on 127.0.0.1 (a free port)
 * (a pass with no fingerprint is sent over plain HTTP, as to an old TV Setup), and the way back to ADB when no picture comes.
 */
class RelayTest {
    private val server: ServerSocket = ServerSocket().apply {        // a free port: 8765 is the Computer app's on a developer's machine
        reuseAddress = true; bind(java.net.InetSocketAddress("127.0.0.1", 0), 50)
        dev.tvremote.core.adb.AdbPrompt.plainPort = localPort
    }
    private val requests = CopyOnWriteArrayList<String>()             // request line, cookie, body
    private val dir: File = Files.createTempDirectory("relay").toFile()

    @After fun close() { server.close(); dir.deleteRecursively(); dev.tvremote.core.adb.AdbPrompt.plainPort = 8765 }

    /** Answers each connection with [answer] (given the request line), on its own thread. */
    private fun serve(answer: (String, Socket) -> Unit) {
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        val inp = s.getInputStream().bufferedReader()
                        val line = inp.readLine() ?: return@Thread
                        var len = 0; var cookie = ""
                        while (true) {
                            val h = inp.readLine() ?: break
                            if (h.isEmpty()) break
                            if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toInt()
                            if (h.lowercase().startsWith("cookie:")) cookie = h.substringAfter(':').trim()
                        }
                        val body = CharArray(len).also { var got = 0; while (got < len) { val n = inp.read(it, got, len - got); if (n < 0) break; got += n } }
                        requests.add("$line|$cookie|${String(body)}")
                        answer(line, s)
                    } catch (ignored: Exception) { } finally { try { s.close() } catch (ignored: Exception) { } }
                }.start()
            }
        }.start()
    }

    private class Seen : Tv.Screen {
        val calls = CopyOnWriteArrayList<String>()
        override fun start(w: Int, h: Int) { calls.add("start $w x $h") }
        override fun feed(config: Boolean, pts: Long, data: ByteArray): Boolean { calls.add(if (config) "config ${data.size}" else "frame ${data.size}"); return true }
        override fun release() { calls.add("release") }
    }

    private class Heard : Tv.Listener {
        val said = CopyOnWriteArrayList<String>()
        val details = CopyOnWriteArrayList<String>()
        val live = CountDownLatch(1)
        val over = CountDownLatch(1)
        override fun onStatus(key: String, detail: String) { said.add(key); details.add("$key $detail"); if (key == "live") live.countDown(); if (key == "closed" || key == "error") over.countDown() }
        override fun onVideoSize(w: Int, h: Int) { said.add("size $w x $h") }
        override fun onNetwork(mac: String, lan: String, tailscale: String) {}
        override fun wantsNetwork() = false
        @Volatile var refused = false
        override fun onPassRefused() { refused = true }
    }

    private fun tv(screen: Seen, l: Heard) = Tv(dir, { ByteArray(0) }, "127.0.0.1", screen, 1, 960, false, l, relay = "secretpass")

    private fun ok(s: Socket, vararg packets: ByteArray) {
        val o = s.getOutputStream()
        o.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
        for (p in packets) o.write(p)
        o.flush()
    }

    @Test fun showsTvSetupsPictureAndSendsTouchesThere() {
        val hold = CountDownLatch(1)
        serve { line, s ->
            if (line.startsWith("GET /api/video")) {
                ok(s, CatchUp.packet(0, ByteBuffer.allocate(8).putInt(960).putInt(544).array()), CatchUp.packet(1, ByteArray(20)),
                    CatchUp.packet(2, ByteArray(3000)), CatchUp.packet(4, ByteArray(0)), CatchUp.packet(3, ByteArray(400)))
                hold.await(5, TimeUnit.SECONDS)                       // the stream stays open while the test touches
            } else s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
        }
        val screen = Seen(); val l = Heard()
        val t = tv(screen, l)
        assertTrue("live", l.live.await(5, TimeUnit.SECONDS))
        assertTrue(t.ready())
        t.touch(0, 0.25f, 0.5f); t.touch(2, 0.3f, 0.5f); t.touch(1, 0.3f, 0.5f)   // a drag: down, the move, up
        var until = System.currentTimeMillis() + 3000
        while (requests.count { it.startsWith("POST /api/touch") } < 2 && System.currentTimeMillis() < until) Thread.sleep(20)
        Thread.sleep(300)
        t.touch(0, 0.6f, 0.4f); Thread.sleep(60); t.touch(1, 0.6f, 0.4f)   // a quick tap: one request, not a down and an up apart
        until = System.currentTimeMillis() + 3000
        while (requests.none { it.contains("\"tap\"") } && System.currentTimeMillis() < until) Thread.sleep(20)
        hold.countDown()
        assertTrue("closed once the stream ends", l.over.await(5, TimeUnit.SECONDS))
        t.stop()

        val video = requests.first { it.startsWith("GET /api/video") }
        assertTrue(video, video.startsWith("GET /api/video?q=1 "))
        assertEquals("tvr=secretpass", video.split('|')[1])           // no fingerprint: the old plain-HTTP cookie
        assertEquals(listOf("start 960 x 544", "config 20", "frame 3000", "frame 400"), screen.calls.filter { it != "release" }.take(4))
        assertTrue(l.said.contains("size 960 x 544"))
        val touches = requests.filter { it.startsWith("POST /api/touch") }.map { org.json.JSONObject(it.split('|')[2]) }
        assertEquals("down", touches.first().getString("a"))
        assertEquals(0.25, touches.first().getDouble("x"), 1e-6)
        assertEquals(listOf("tap"), touches.drop(touches.indexOfFirst { it.getString("a") == "up" } + 1).map { it.getString("a") })
        assertEquals(0.6, touches.last().getDouble("x"), 1e-6)
        assertEquals("closed", l.said.last())
    }

    @Test fun noFrameEndsWithoutAStartOverAdb() {
        val hold = CountDownLatch(1)
        serve { _, s ->                                               // answers, then only "nothing new": its encoder is stuck
            ok(s, CatchUp.packet(0, ByteBuffer.allocate(8).putInt(960).putInt(544).array()), CatchUp.packet(1, ByteArray(20)))
            repeat(12) { if (!hold.await(1, TimeUnit.SECONDS)) { s.getOutputStream().write(CatchUp.packet(4, ByteArray(0))); s.getOutputStream().flush() } }
        }
        val screen = Seen(); val l = Heard()
        val from = System.nanoTime()
        val t = tv(screen, l)
        assertTrue(l.over.await(15, TimeUnit.SECONDS))
        val took = (System.nanoTime() - from) / 1_000_000
        hold.countDown(); t.stop()
        assertTrue("gave up after about 8 s, took $took ms", took in 7000..12000)
        assertFalse(l.said.contains("live"))
        assertEquals("error TV Setup sent no picture", l.details.last())   // TV Setup's error, not the ADB session's
        assertFalse("no ADB start", l.said.contains("starting"))
    }

    @Test fun aKeyFrameIsAskedForAtMostOnceIn5s() {
        val hold = CountDownLatch(1)
        serve { line, s ->
            if (!line.startsWith("GET /api/video")) return@serve
            ok(s, CatchUp.packet(0, ByteBuffer.allocate(8).putInt(960).putInt(544).array()), CatchUp.packet(1, ByteArray(20)), CatchUp.packet(2, ByteArray(300)))
            hold.await(10, TimeUnit.SECONDS)
        }
        val screen = Seen(); val l = Heard()
        val t = tv(screen, l)
        assertTrue(l.live.await(5, TimeUnit.SECONDS))
        repeat(5) { t.resetVideo(); Thread.sleep(100) }               // a decoder that keeps asking
        Thread.sleep(1500)
        val joins = requests.count { it.startsWith("GET /api/video") }
        hold.countDown(); t.stop()
        assertEquals("the first join and one again", 2, joins)
        assertEquals("one size, one decoder", 1, screen.calls.count { it.startsWith("start") })
    }

    @Test fun refusedGoesOnOverAdb() {
        // needs nothing on 127.0.0.1:5555; a running emulator listens there (emulator-5554), so the case cannot be made then
        val taken = try { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 300) }; true } catch (e: Exception) { false }
        org.junit.Assume.assumeFalse("127.0.0.1:5555 is taken (an emulator?)", taken)
        serve { _, s -> s.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\n\r\n{}".toByteArray()) }
        val screen = Seen(); val l = Heard()
        val t = tv(screen, l)
        assertTrue(l.over.await(20, TimeUnit.SECONDS))                // nothing listens for ADB on 127.0.0.1:5555 here
        t.stop()
        assertEquals(1, requests.size)
        assertFalse(l.said.contains("live"))
        assertEquals("error", l.said.last())                          // the ADB session's own error, as without a relay
        assertTrue("a refused pass is said, so the app drops it", l.refused)
    }
}
