package dev.tvremote.core

import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The reply thread of the support worker: asked with the newest code and key, a 403 forgets them, an unreachable worker is just null. */
class SupportThreadTest {
    private val mem = HashMap<String, String>()
    private var status = 200
    private var reply = """{"now":1,"msgs":[{"id":3,"who":"us","text":"try <b>this</b>","at":2}]}"""
    private val bodies = ArrayList<String>()
    private lateinit var server: ServerSocket
    private var oldUrl = ""

    private val store = object : Census.Store {
        override fun get(key: String) = mem[key]
        override fun put(key: String, value: String) { mem[key] = value }
    }
    private fun relay() = SupportRelay("setup", "1", "m", store, { "" }, { 200 to "" })

    @Before fun setUp() {
        server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val srv = server
        Thread {                                                      // a tiny HTTP/1.0 answer: read the head and the body, reply, close
            try { while (true) srv.accept().use { s ->
                val inp = s.getInputStream().buffered(); var len = 0; var line: String
                while (true) { line = readLine(inp); if (line.isEmpty()) break; if (line.lowercase().startsWith("content-length:")) len = line.substringAfter(':').trim().toInt() }
                bodies.add(String(inp.readNBytes(len), Charsets.UTF_8))
                val b = if (status == 200) reply.toByteArray() else ByteArray(0)
                s.getOutputStream().write(("HTTP/1.0 $status X\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray() + b)
            } } catch (e: Exception) { }
        }.apply { isDaemon = true }.start()
        oldUrl = Census.url
        Census.url = "http://127.0.0.1:${server.localPort}"
    }

    private fun readLine(i: java.io.InputStream): String {
        val sb = StringBuilder(); while (true) { val c = i.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString()
    }

    @After fun tearDown() { server.close(); Census.url = oldUrl }

    @Test fun noCodeAsksNothing() {
        assertNull(relay().thread(0)); assertNull(relay().pageUrl()); assertEquals(0, bodies.size)
    }

    @Test fun asksWithNewestCodeAndAfter() {
        mem["code"] = "12345678"; mem["key"] = "abc"
        val r = relay().thread(2)!!
        assertEquals("us", r.getJSONArray("msgs").getJSONObject(0).getString("who"))
        val sent = org.json.JSONObject(bodies[0])
        assertEquals("12345678", sent.getString("code")); assertEquals("abc", sent.getString("key")); assertEquals(2, sent.getInt("after"))
        assertEquals("https://tvremote.pages.dev/support#12345678.abc", relay().pageUrl())
    }

    @Test fun forbiddenForgetsCodeAndKey() {
        mem["code"] = "12345678"; mem["key"] = "abc"; status = 403
        assertEquals(403, relay().thread(0)!!.optInt("error"))
        assertEquals("", mem["code"]); assertEquals("", mem["key"])
        assertNull(relay().pageUrl()); assertNull(relay().thread(0))
    }

    @Test fun serverErrorKeepsTheCode() {
        mem["code"] = "12345678"; mem["key"] = "abc"; status = 500
        assertNull(relay().thread(0)); assertEquals("12345678", mem["code"])
    }

    @Test fun unreachableIsNull() {
        mem["code"] = "12345678"; mem["key"] = "abc"; server.close()
        assertNull(relay().thread(0)); assertEquals("12345678", mem["code"])
    }
}
