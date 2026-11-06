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

    @Test fun tvNamesASavedTvAndAnUnknownOneListsThem() {
        val other = ArrayList<String>()
        val many = Hook(store, Hook.Routes(), { r -> sent.add(r.path to r.json().toString()); HttpServer.Response.ok() }, { JSONObject("{\"is_on\":true}") },
            { scenes }, { "" }, { now }, object : Hook.Tvs {
                override fun find(tv: String) = if (tv.equals("bedroom", true) || tv == "192.168.1.7")
                    Hook.Target({ r -> other.add(r.path); HttpServer.Response.ok() }, { JSONObject("{\"is_on\":false}") }) else null
                override fun names() = listOf("Living room", "Bedroom")
            })
        fun call(path: String, vararg q: Pair<String, String>) = many.handle(HttpServer.Request().apply { method = "GET"; this.path = path; peer = "10.0.0.2"; q.forEach { query[it.first] = it.second } })!!
        assertEquals(200, call("/api/hook", "k" to "t0ken", "key" to "HOME", "tv" to "Bedroom").status)
        assertEquals(200, call("/api/hook", "k" to "t0ken", "power" to "off", "tv" to "192.168.1.7").status)
        assertEquals(listOf("/api/key", "/api/key"), other)
        assertTrue(sent.isEmpty())                                    // the selected TV was not touched
        assertEquals(200, call("/api/hook", "k" to "t0ken", "key" to "HOME").status)   // no tv: the selected one, as before
        assertEquals(1, sent.size)
        val no = call("/api/hook", "k" to "t0ken", "key" to "HOME", "tv" to "kitchen")
        assertEquals(404, no.status)
        assertEquals(listOf("Living room", "Bedroom"), JSONObject(String(no.body)).getJSONArray("tvs").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(false, JSONObject(String(call("/api/hook/state", "k" to "t0ken", "tv" to "bedroom").body)).getBoolean("on"))
        assertEquals(403, call("/api/hook", "k" to "bad", "key" to "HOME", "tv" to "kitchen").status)   // the token first: no names to a stranger
    }

    @Test fun aHookOnTheTvItselfRefusesATvName() {
        assertEquals(400, get("/api/hook", "k" to "t0ken", "key" to "HOME", "tv" to "Bedroom")!!.status)
        assertTrue(sent.isEmpty())
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

    @Test fun minutesAddToTodaysLimits() {
        assertEquals(200, get("/api/hook", "k" to "t0ken", "minutes" to "15", "for" to "com.google.android.youtube.tv")!!.status)
        assertEquals(200, get("/api/hook", "k" to "t0ken", "minutes" to "30")!!.status)
        assertEquals(listOf("/api/rules", "/api/rules"), sent.map { it.first })
        JSONObject(sent[0].second).let { assertEquals("extra", it.getString("do")); assertEquals("com.google.android.youtube.tv", it.getString("pkg")); assertEquals(15, it.getInt("minutes")) }
        JSONObject(sent[1].second).let { assertEquals("total_extra", it.getString("do")); assertEquals(30, it.getInt("minutes")); assertTrue(!it.has("pkg")) }
        assertEquals(400, get("/api/hook", "k" to "t0ken", "minutes" to "0")!!.status)
        assertEquals(400, get("/api/hook", "k" to "t0ken", "minutes" to "9999")!!.status)
        assertEquals(400, get("/api/hook", "k" to "t0ken", "minutes" to "5", "for" to "a b")!!.status)
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
