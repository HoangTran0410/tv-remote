package dev.tvremote.core

import dev.tvremote.core.web.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/** One host on the network must not hold every slot of TV Setup's server (slow senders), and a JSON body has a whole deadline. */
class HttpServerSlowTest {
    private val savedJson = HttpServer.JSON_MS
    private val savedTrusted = HttpServer.trusted
    private var server: HttpServer? = null

    @After fun after() { server?.stop(); HttpServer.JSON_MS = savedJson; HttpServer.trusted = savedTrusted }

    private fun answer(s: Socket): String = s.getInputStream().bufferedReader().readLine().orEmpty()

    @Test fun aJsonBodyTooSlowIsNone() {
        HttpServer.JSON_MS = 300
        server = HttpServer { r -> HttpServer.Response.json(org.json.JSONObject().put("got", r.jsonOrNull() != null)) }
        val port = server!!.start(0, true)
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10000
            val o = s.getOutputStream()
            o.write("POST /x HTTP/1.1\r\nHost: a\r\nContent-Length: 20\r\n\r\n{\"a\"".toByteArray()); o.flush()
            Thread.sleep(500)
            o.write(":".toByteArray()); o.flush()                        // more, but past the whole deadline
            val all = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(all, all.contains("\"got\":false"))
        }
    }

    @Test fun oneHostGetsSixteen() {
        HttpServer.trusted = { false }                                   // count 127.0.0.1 like a host on the network
        server = HttpServer { HttpServer.Response.ok() }
        val port = server!!.start(0, true)
        val held = (1..HttpServer.PER_PEER).map { Socket("127.0.0.1", port) }   // each sends nothing: held until its head times out
        try {
            Thread.sleep(300)
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                assertEquals("the 17th was served", -1, s.getInputStream().read())   // closed at once
            }
        } finally { held.forEach { it.close() } }
        Thread.sleep(300)
        Socket("127.0.0.1", port).use { s ->                              // freed again when they go
            s.soTimeout = 5000
            s.getOutputStream().write("GET /x HTTP/1.1\r\nHost: a\r\n\r\n".toByteArray())
            assertTrue(answer(s).startsWith("HTTP/1.1 200"))
        }
    }
}

/** The same work asked twice while it runs is one job (two tabs pressing Install); after it ends, a new one. */
class JobsOnceTest {
    @Test fun sameWorkIsOneJob() {
        val jobs = dev.tvremote.core.manage.Jobs()
        val go = java.util.concurrent.CountDownLatch(1)
        val a = jobs.job("app Kodi", null, null, "app Kodi") { go.await(); "ok" }
        val b = jobs.job("app Kodi", null, null, "app Kodi") { "second" }
        assertEquals(a.getString("id"), b.getString("id"))
        val c = jobs.job("app VLC", null, null, "app VLC") { "ok" }
        assertTrue(c.getString("id") != a.getString("id"))
        go.countDown()
        repeat(50) { if (jobs[a.getString("id")]!!.optString("status") == "running") Thread.sleep(20) }
        val d = jobs.job("app Kodi", null, null, "app Kodi") { "again" }
        assertTrue(d.getString("id") != a.getString("id"))
    }

    @Test fun anErrorEndsTheJob() {
        val jobs = dev.tvremote.core.manage.Jobs()
        val a = jobs.job("x", null, null) { throw OutOfMemoryError("big") }
        repeat(50) { if (jobs[a.getString("id")]!!.optString("status") == "running") Thread.sleep(20) }
        assertEquals("error", jobs[a.getString("id")]!!.optString("status"))
    }
}
