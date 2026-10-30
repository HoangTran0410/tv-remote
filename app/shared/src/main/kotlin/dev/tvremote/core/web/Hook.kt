package dev.tvremote.core.web
import dev.tvremote.core.scenes.Scenes
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * The home-network hook for Home Assistant and iPhone Shortcuts (Siri): `/api/hook?k=<token>&key=HOME`, one action per call,
 * and `/api/hook/state` for a small JSON. It has a token of its own, separate from the pairing code and the computer app's key:
 * a leaked link can press the TV's buttons and nothing else (no files, no apps installed, no picture), and "new token" cuts it off.
 * Off until the owner turns it on in Settings. Not for the Internet: away from home, use Tailscale like everything else.
 *
 * Nothing here knows how a key is pressed: each call becomes the request the page itself would have sent (`/api/key`...), and
 * [run] hands it to the core's own router, so LG / Samsung / Roku / ADB-less TVs work exactly where the page's buttons do.
 */
class Hook(
    private val store: Store,
    private val routes: Routes,
    /** The core's own router, called with a request built here (never the caller's). */
    private val run: (HttpServer.Request) -> HttpServer.Response,
    /** What /api/state says. */
    private val state: () -> JSONObject,
    /** The saved scenes, to find one by name. */
    private val scenes: () -> JSONArray,
    /** The address a Shortcut / Home Assistant should use, as `http://192.168.1.20:8766`; "" when unknown. */
    private val base: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Where the hook's switch and token live (the computer's tvs.json, TV Setup's prefs). */
    interface Store {
        var enabled: Boolean
        var token: String
    }

    /** The paths that differ between cores. */
    class Routes(
        val text: String = "/api/type",             // the computer types without ADB through /api/text
        val wake: String? = null,                    // the computer also sends Wake-on-LAN before the WAKE key
        val volume: String? = null,                  // /api/volume where the core has it; otherwise a one-step scene sets the level
    )

    /** Called after the switch or the token changed (the computer opens or closes its listener). */
    @Volatile var onChange: (() -> Unit)? = null

    private class Fails(var count: Int, var since: Long, var until: Long)
    private val fails = HashMap<String, Fails>()

    /** null when the path is not the hook's. [r] arrives with no cookie or pairing: the token is the only credential. */
    fun handle(r: HttpServer.Request): HttpServer.Response? {
        if (r.path != "/api/hook" && r.path != "/api/hook/state") return null
        if (!store.enabled || store.token.isEmpty()) return HttpServer.Response.error(404, "not found")   // off: as if it were not there
        val now = clock()
        synchronized(fails) {
            val f = fails[r.peer]
            if (f != null && now < f.until) return HttpServer.Response.error(429, "too many wrong tokens").also { it.headers["Retry-After"] = ((f.until - now) / 1000 + 1).toString() }
        }
        if (!same(given(r), store.token)) {
            synchronized(fails) {
                if (fails.size > 256) fails.entries.removeAll { now - it.value.since > WINDOW && now >= it.value.until }
                val f = fails.getOrPut(r.peer) { Fails(0, now, 0) }
                if (now - f.since > WINDOW) { f.count = 0; f.since = now }
                if (++f.count >= MAX_FAILS) { f.until = now + LOCK; f.count = 0; f.since = now }
            }
            return HttpServer.Response.error(403, "bad token")
        }
        synchronized(fails) { fails.remove(r.peer) }
        return try {
            if (r.path == "/api/hook/state") HttpServer.Response.json(brief(state())) else act(r)
        } catch (e: IllegalArgumentException) {
            HttpServer.Response.error(400, e.message ?: "bad request")
        }
    }

    /** /api/hook/config (a paired owner's page; the core checks that before calling): the switch, the token, the examples. */
    fun config(r: HttpServer.Request): HttpServer.Response {
        if (r.method == "POST") {
            when (r.json().optString("do")) {
                "on" -> { if (store.token.isEmpty()) store.token = newToken(); store.enabled = true }
                "off" -> store.enabled = false
                "new" -> { store.token = newToken(); synchronized(fails) { fails.clear() } }
                else -> return HttpServer.Response.error(400, "do: on, off or new")
            }
            onChange?.invoke()
        }
        val on = store.enabled && store.token.isNotEmpty()
        val o = JSONObject().put("on", on)
        if (on) {
            val b = base().ifEmpty { "http://TV-REMOTE-IP:PORT" }
            val t = store.token
            o.put("token", t).put("base", b).put("shortcut", "$b/api/hook?k=$t&key=HOME").put("yaml", yaml(b, t))
        }
        return HttpServer.Response.json(o)
    }

    // ---------------------------------------------------------------- what a call does
    private fun act(r: HttpServer.Request): HttpServer.Response {
        val p = params(r)
        val named = ACTIONS.filter { p.optString(it).isNotEmpty() }
        if (named.size != 1) throw IllegalArgumentException(if (named.isEmpty()) "say what to do: key, scene, app, text, notify, volume or power" else "one thing per call: " + named.joinToString(", "))
        val out = perform(named[0], p.optString(named[0]), p, run)
        return if (out.status in 200..299) HttpServer.Response.json(JSONObject().put("ok", true)) else out
    }

    /**
     * One action of [ACTIONS], with no token check: the callers have their own (the hook's token; the Telegram bot's paired chat).
     * [via] is the router that runs the request it builds, so a caller with less right than the owner passes one that checks
     * its scope (RemoteService). An IllegalArgumentException is a bad value; a 404 for a scene carries the names that exist.
     */
    fun perform(what: String, value: String, extra: JSONObject? = null, via: (HttpServer.Request) -> HttpServer.Response): HttpServer.Response {
        val v = value.trim()
        return when (what) {
            "key" -> {
                val k = v.uppercase(Locale.ROOT)
                if (k !in Scenes.KEYS) throw IllegalArgumentException("unknown key")
                send(via, "/api/key", JSONObject().put("key", k))
            }
            "scene" -> {
                val list = scenes().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                val sc = list.firstOrNull { it.optString("id") == v } ?: list.firstOrNull { it.optString("name").trim().equals(v, ignoreCase = true) }
                    ?: return HttpServer.Response.json(404, JSONObject().put("error", "no such scene").put("scenes", JSONArray(list.map { it.optString("name") })))
                send(via, "/api/scene/run", JSONObject().put("id", sc.optString("id")))
            }
            "app" -> {
                if (!PKG.matches(v)) throw IllegalArgumentException("app: a package name, like com.netflix.ninja")
                send(via, "/api/app", JSONObject().put("link", v))
            }
            "text" -> send(via, routes.text, JSONObject().put("text", v.take(500)))
            "notify" -> {                                          // a card on the TV's screen (the page's "Send a message"); title and seconds are clamped by Notify.clean on the way
                if (v.isEmpty()) throw IllegalArgumentException("notify: the message")
                val m = JSONObject().put("text", v)
                extra?.optString("title")?.takeIf { it.isNotBlank() }?.let { m.put("title", it) }
                extra?.optString("seconds")?.takeIf { it.isNotBlank() }?.let { m.put("seconds", it) }
                send(via, "/api/notify", m)
            }
            "volume" -> volume(via, v)
            else -> power(via, v)
        }
    }

    private fun volume(via: (HttpServer.Request) -> HttpServer.Response, v: String): HttpServer.Response {
        val key = mapOf("up" to "VOL_UP", "down" to "VOL_DOWN", "mute" to "MUTE")[v.lowercase(Locale.ROOT)]
        if (key != null) return send(via, "/api/key", JSONObject().put("key", key))
        val level = v.toIntOrNull()?.takeIf { it in 0..100 } ?: throw IllegalArgumentException("volume: 0 to 100, up, down or mute")
        return if (routes.volume != null) send(via, routes.volume, JSONObject().put("level", level))
        else send(via, "/api/scene/run", JSONObject().put("steps", JSONArray().put(JSONObject().put("do", "volume").put("level", level))))
    }

    private fun power(via: (HttpServer.Request) -> HttpServer.Response, v: String): HttpServer.Response = when (v.lowercase(Locale.ROOT)) {
        "on" -> {                                                  // Wake-on-LAN for a TV in deep standby, then the key for one that only sleeps
            if (routes.wake != null) try { send(via, routes.wake, JSONObject()) } catch (e: Exception) { }
            send(via, "/api/key", JSONObject().put("key", "WAKE"))
        }
        "off" -> send(via, "/api/key", JSONObject().put("key", "SLEEP"))
        "toggle" -> send(via, "/api/key", JSONObject().put("key", "POWER"))
        else -> throw IllegalArgumentException("power: on, off or toggle")
    }

    /** [path] with [body] as the request the page itself would have sent, run by [via] (never the caller's own request). */
    fun send(via: (HttpServer.Request) -> HttpServer.Response, path: String, body: JSONObject, method: String = "POST", query: Map<String, String> = emptyMap()): HttpServer.Response {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val q = HttpServer.Request()
        q.method = method; q.path = path; q.peer = "127.0.0.1"; q.query.putAll(query)
        q.headers["content-type"] = "application/json"
        q.body = ByteArrayInputStream(bytes); q.length = bytes.size.toLong()
        return try { via(q) } catch (e: IllegalArgumentException) { HttpServer.Response.error(400, e.message ?: "bad request") }
    }

    /** Query first, then a JSON body: Home Assistant's rest_command sends either. */
    private fun params(r: HttpServer.Request): JSONObject {
        val o = JSONObject()
        if (r.method == "POST" && r.length in 1..4096) r.jsonOrNull(4096)?.let { b -> for (k in ACTIONS + EXTRA) if (b.has(k)) o.put(k, b.get(k).toString()) }
        for (k in ACTIONS + EXTRA) r.query[k]?.let { o.put(k, it) }
        return o
    }

    /** The token from the query (Shortcuts), or a header (Home Assistant: out of its logs). */
    private fun given(r: HttpServer.Request): String =
        r.query["k"] ?: r.headers["x-tvr-hook"] ?: r.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: ""

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** What a home-automation rule needs: on or off, the volume, what is open. Never the TV's name, address or anything stored. */
    fun brief(s: JSONObject): JSONObject {
        val o = JSONObject().put("ok", true).put("connected", s.optBoolean("connected", true))
        o.put("on", if (s.isNull("is_on") || !s.has("is_on")) JSONObject.NULL else s.optBoolean("is_on"))
        val v = s.optJSONObject("volume")
        o.put("volume", if (v == null) JSONObject.NULL else JSONObject().put("level", v.opt("level")).put("max", v.opt("max")).put("muted", v.optBoolean("muted")))
        o.put("app", if (s.isNull("app") || !s.has("app")) JSONObject.NULL else s.optString("app"))
        if (s.has("app_label") && !s.isNull("app_label")) o.put("app_label", s.optString("app_label"))
        return o
    }

    companion object {
        private val ACTIONS = listOf("key", "scene", "app", "text", "notify", "volume", "power")
        private val EXTRA = listOf("title", "seconds")                // options of notify, not actions of their own
        private val PKG = Regex("[A-Za-z0-9_.]{1,200}")
        private const val MAX_FAILS = 5                               // wrong tokens from one address in a minute: then a minute of 429
        private const val WINDOW = 60_000L
        private const val LOCK = 60_000L

        fun newToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** A rest_command for Home Assistant's configuration.yaml: `service: rest_command.tv_key` with `data: { key: HOME }`. */
        fun yaml(base: String, token: String) = """rest_command:
  tv_key:
    url: "$base/api/hook"
    method: post
    headers:
      x-tvr-hook: "$token"
    content_type: "application/json"
    payload: '{"key": "{{ key }}"}'"""
    }
}
