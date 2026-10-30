package dev.tvremote.core

import dev.tvremote.core.web.Hook
import dev.tvremote.core.web.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookTest {
    private class Mem : Hook.Store { override var enabled = true; override var token = "t0ken" }

    private val sent = ArrayList<Pair<String, String>>()
    private var now = 1_000_000L
    private val store = Mem()
    private val scenes = JSONArray("""[{"id":"s1","name":"Xem phim","steps":[{"do":"wake"}]}]""")
    private val hook = Hook(store, Hook.Routes(), { r -> sent.add(r.path to r.json().toString()); HttpServer.Response.ok() },
        { JSONObject("""{"is_on":true,"volume":{"level":7,"max":15,"muted":false},"app":"com.netflix.ninja","name":"secret","host":"10.0.0.9"}""") },
        { scenes }, { "http://192.168.1.20:8766" }, { now })

    private fun get(path: String, vararg q: Pair<String, String>, peer: String = "10.0.0.2", headers: Map<String, String> = emptyMap()): HttpServer.Response? {
        val r = HttpServer.Request(); r.method = "GET"; r.path = path; r.peer = peer
        for ((k, v) in q) r.query[k] = v
        r.headers.putAll(headers)
        return hook.handle(r)
    }

    @Test fun otherPathsAreNotTheHooks() { assertNull(get("/api/key")); assertNull(get("/api/hook/config")) }

    @Test fun offIsNotFound() {
        store.enabled = false
        assertEquals(404, get("/api/hook", "k" to "t0ken", "key" to "HOME")!!.status)
        assertTrue(sent.isEmpty())
    }

    @Test fun tokenFromQueryHeaderOrBearer() {
        assertEquals(200, get("/api/hook", "k" to "t0ken", "key" to "home")!!.status)
        assertEquals(200, get("/api/hook", "key" to "HOME", headers = mapOf("x-tvr-hook" to "t0ken"))!!.status)
        assertEquals(200, get("/api/hook", "key" to "HOME", headers = mapOf("authorization" to "Bearer t0ken"))!!.status)
        assertEquals(3, sent.size)
        assertEquals("/api/key" to """{"key":"HOME"}""", sent[0])
    }

    @Test fun wrongTokenIsRefusedThenLockedOut() {
        repeat(4) { assertEquals(403, get("/api/hook", "k" to "nope$it", "key" to "HOME")!!.status) }
        assertEquals(403, get("/api/hook", "k" to "x", "key" to "HOME")!!.status)       // the fifth
        assertEquals(429, get("/api/hook", "k" to "t0ken", "key" to "HOME")!!.status)     // even the right one waits
        assertEquals(200, get("/api/hook", "k" to "t0ken", "key" to "HOME", peer = "10.0.0.3")!!.status)   // another address is not held back
        now += 61_000
        assertEquals(200, get("/api/hook", "k" to "t0ken", "key" to "HOME")!!.status)
        assertEquals(2, sent.size)
    }

    @Test fun oneActionPerCallAndOnlyKnownThings() {
        assertEquals(400, get("/api/hook", "k" to "t0ken")!!.status)
        assertEquals(400, get("/api/hook", "k" to "t0ken", "key" to "HOME", "app" to "a.b")!!.status)
        assertEquals(400, get("/api/hook", "k" to "t0ken", "key" to "NOPE")!!.status)
        assertEquals(400, get("/api/hook", "k" to "t0ken", "app" to "a b; rm")!!.status)
        assertEquals(400, get("/api/hook", "k" to "t0ken", "volume" to "200")!!.status)
        assertEquals(400, get("/api/hook", "k" to "t0ken", "power" to "maybe")!!.status)
        assertTrue(sent.isEmpty())
    }

    @Test fun actions() {
        get("/api/hook", "k" to "t0ken", "scene" to "xem PHIM")
        get("/api/hook", "k" to "t0ken", "app" to "com.netflix.ninja")
        get("/api/hook", "k" to "t0ken", "text" to "hello")
        get("/api/hook", "k" to "t0ken", "volume" to "up")
        get("/api/hook", "k" to "t0ken", "volume" to "12")
        get("/api/hook", "k" to "t0ken", "power" to "off")
        get("/api/hook", "k" to "t0ken", "power" to "on")
        assertEquals(listOf(
            "/api/scene/run" to """{"id":"s1"}""", "/api/app" to """{"link":"com.netflix.ninja"}""", "/api/type" to """{"text":"hello"}""",
            "/api/key" to """{"key":"VOL_UP"}""", "/api/scene/run" to """{"steps":[{"level":12,"do":"volume"}]}""",
            "/api/key" to """{"key":"SLEEP"}""", "/api/key" to """{"key":"WAKE"}"""), sent)
        assertEquals(404, get("/api/hook", "k" to "t0ken", "scene" to "none")!!.status)
    }

    @Test fun notifyBecomesThePagesMessage() {
        assertEquals(200, get("/api/hook", "k" to "t0ken", "notify" to "Someone at the door", "title" to "Doorbell", "seconds" to "10")!!.status)
        assertEquals(200, get("/api/hook", "k" to "t0ken", "notify" to "Only text")!!.status)
        assertEquals("/api/notify", sent[0].first)
        JSONObject(sent[0].second).let { assertEquals("Someone at the door", it.getString("text")); assertEquals("Doorbell", it.getString("title")); assertEquals("10", it.getString("seconds")) }
        JSONObject(sent[1].second).let { assertEquals("Only text", it.getString("text")); assertTrue(!it.has("title") && !it.has("seconds")) }
        assertEquals(400, get("/api/hook", "k" to "t0ken", "title" to "alone")!!.status)       // a title is no action
        assertEquals(2, sent.size)
    }

    @Test fun stateSaysLittle() {
        val o = JSONObject(String(get("/api/hook/state", "k" to "t0ken")!!.body))
        assertEquals(true, o.getBoolean("on")); assertEquals(7, o.getJSONObject("volume").getInt("level")); assertEquals("com.netflix.ninja", o.getString("app"))
        assertTrue(!o.has("name") && !o.has("host"))
    }

    @Test fun configSwitchAndNewToken() {
        store.enabled = false; store.token = ""
        fun cfg(d: String?): JSONObject {
            val r = HttpServer.Request(); r.method = if (d == null) "GET" else "POST"
            if (d != null) { val b = d.toByteArray(); r.body = java.io.ByteArrayInputStream(b); r.length = b.size.toLong() }
            return JSONObject(String(hook.config(r).body))
        }
        assertEquals(false, cfg(null).getBoolean("on"))
        val on = cfg("""{"do":"on"}""")
        assertTrue(on.getBoolean("on")); assertEquals(32, on.getString("token").length)
        assertTrue(on.getString("shortcut").startsWith("http://192.168.1.20:8766/api/hook?k=" + on.getString("token")))
        assertTrue(on.getString("yaml").contains("x-tvr-hook: \"" + on.getString("token") + "\""))
        val again = cfg("""{"do":"new"}""")
        assertNotEquals(on.getString("token"), again.getString("token"))
        assertEquals(403, get("/api/hook", "k" to on.getString("token"), "key" to "HOME")!!.status)
        assertEquals(false, cfg("""{"do":"off"}""").getBoolean("on"))
    }
}
