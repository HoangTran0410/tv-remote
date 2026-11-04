package dev.tvremote.core.scenes
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.web.HttpServer
import dev.tvremote.core.remote.Inputs
import dev.tvremote.core.ontv.Notify
import dev.tvremote.core.adb.Pkg
import dev.tvremote.core.ontv.RulesClient
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.ontv.SetupNeed

/**
 * Scenes: one button that does several things on the TV ("Movie night": wake it, open YouTube, volume 12). The same JSON in
 * the three remotes; each keeps its own list.
 *
 *   { "id": "s1", "name": "Xem phim", "icon": "🎬", "steps": [ {"do": "wake"}, {"do": "app", "pkg": "com.google.android.youtube.tv"} ] }
 *
 * Steps: wake, sleep, key {key}, app {pkg}, link {url}, volume {level}, wait {s}, timer {min} (TV Setup's sleep timer, 0 cancels),
 * input {id, label} (an [Inputs] id; the label is only for showing).
 * notify {title, text, corner, seconds} (a card on the TV's screen, see [Notify]).
 */
object Scenes {
    /** What the steps need from the TV. ADB does all of it ([Shell]); the keys-only connection some. */
    interface Target {
        fun key(code: Int)
        fun app(pkg: String)
        fun link(url: String)
        fun volume(level: Int)
        fun timer(minutes: Int)
        /** Switches to the input [id] ([Inputs]). */
        fun input(id: String): Unit = throw IllegalArgumentException("this TV cannot switch inputs from here")
        /** [msg] is already [Notify.clean]ed. Only a TV with TV Setup and ADB can show one. */
        fun notify(msg: JSONObject) { throw IllegalArgumentException("a message on the TV needs ADB and TV Setup") }
    }

    /** The key names the pages use, to Android key codes. */
    val KEYS: Map<String, Int> = HashMap<String, Int>().apply {
        putAll(mapOf("UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23, "BACK" to 4, "HOME" to 3, "MENU" to 82, "POWER" to 26, "WAKE" to 224,
            "SLEEP" to 223, "VOL_UP" to 24, "VOL_DOWN" to 25, "MUTE" to 164, "PLAY_PAUSE" to 85, "NEXT" to 87, "PREV" to 88, "SETTINGS" to 176, "INPUT" to 178,
            "DEL" to 67, "ENTER" to 66, "ASSIST" to 219, "CH_UP" to 166, "CH_DOWN" to 167,
            "PAD_A" to 96, "PAD_B" to 97, "PAD_X" to 99, "PAD_Y" to 100, "PAD_L1" to 102, "PAD_R1" to 103, "PAD_START" to 108, "PAD_SELECT" to 109,
            "RED" to 183, "GREEN" to 184, "YELLOW" to 185, "BLUE" to 186, "YOUTUBE" to 190, "NETFLIX" to 191, "PRIME" to 195, "ALL_APPS" to 284,
            "INFO" to 165, "GUIDE" to 172, "LIVE" to 170, "SUBTITLE" to 175, "REWIND" to 89, "STOP" to 86, "FFWD" to 90))
        for (d in 0..9) put(d.toString(), 7 + d)
    }

    private val PKG = Pattern.compile(Pkg.PATTERN)
    private val APP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")   // an LG app ("netflix", "spotify-beehive") or a Samsung one ("3201907018807") too; [launch] still wants a package
    private val STEPS = setOf("wake", "sleep", "key", "app", "link", "volume", "wait", "timer", "input", "notify")

    /**
     * Runs the steps in order. A step that fails is skipped (the TV may simply not have that app) and named in the answer;
     * null when everything went through. [stop] is asked between steps.
     */
    fun run(steps: JSONArray, tv: Target, stop: () -> Boolean = { false }): String? {
        val failed = ArrayList<String>()
        for (i in 0 until minOf(steps.length(), 30)) {
            if (stop()) break
            val s = steps.optJSONObject(i) ?: continue
            try {
                when (s.optString("do")) {
                    "wake" -> tv.key(224)
                    "sleep" -> tv.key(223)
                    "key" -> tv.key(KEYS[s.optString("key").uppercase()] ?: throw IllegalArgumentException("unknown key"))
                    "app" -> tv.app(s.optString("pkg"))
                    "link" -> tv.link(s.optString("url"))
                    "volume" -> tv.volume(s.optInt("level").coerceIn(0, 100))
                    "wait" -> Thread.sleep((s.optDouble("s", 1.0).coerceIn(0.0, 30.0) * 1000).toLong())
                    "timer" -> tv.timer(s.optInt("min").coerceIn(0, 720))
                    "input" -> tv.input(s.optString("id"))
                    "notify" -> tv.notify(Notify.clean(s))
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                failed.add((i + 1).toString() + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        return if (failed.isEmpty()) null else failed.joinToString("; ")
    }

    /**
     * POST /api/scene/run for every core: [d] is the body, either `steps` of its own or the `id` of a saved scene ([find]: where
     * this core keeps them). Unknown scene: 404 before the TV is touched. [target] is null when the TV cannot be reached now:
     * 503 with [offline] (the words differ per core). A timer step on a TV Setup that is missing or too old is answered with
     * the 409 "setup:..." words every client turns into an Install / Update TV Setup question (needSetup in js/core.js),
     * instead of a failed step the person cannot act on. (TV Setup's own "remote" guests may run only a saved scene by id:
     * Scope.kt checks that before the request gets here.)
     */
    fun runRequest(d: JSONObject, find: (String) -> JSONObject?, offline: String, target: () -> Target?): HttpServer.Response {
        val steps = d.optJSONArray("steps") ?: find(d.optString("id"))?.optJSONArray("steps")
            ?: return HttpServer.Response.error(404, "no such scene")
        val tv = target() ?: return HttpServer.Response.error(503, offline)
        var missing: SetupNeed.Missing? = null
        val watched = object : Target by tv {
            override fun timer(minutes: Int) = try { tv.timer(minutes) } catch (e: SetupNeed.Missing) { missing = e; throw e }
        }
        val clean = clean(JSONArray().put(JSONObject().put("name", "x").put("steps", steps))).optJSONObject(0)?.optJSONArray("steps") ?: JSONArray()
        val failed = run(clean, watched)
        missing?.let { return HttpServer.Response.error(409, it.message.toString()) }
        return HttpServer.Response.json(JSONObject().put("ok", failed == null).put("failed", failed ?: ""))
    }

    /**
     * The scenes routes of every core, so a change reaches the phone, the TV and the computer at once: GET/POST `/api/scenes`
     * (`?download` is the backup file), POST `/api/scene/run`. Each core brings only where its list lives ([all], [save]), its
     * offline words and its [target]. A POST without a `scenes` list is a 400: a broken import must not wipe the saved ones.
     * Null from [handle]: not a scenes path.
     */
    class Routes(private val all: () -> JSONArray, private val save: (JSONArray) -> Unit, private val offline: () -> String, private val target: () -> Target?) {
        /** Set by a core that can offer a scene to TV Home ("Add to TV Home"): gets the cleaned scene, answers {ok} or {error}. Without it the route does not exist. */
        var pin: ((JSONObject) -> JSONObject)? = null
        /** Set by a client that has ADB: told the saved list after each save ([publishThroughSetup]); not for the TV's own core, whose [save] publishes. */
        var publish: ((JSONArray) -> Unit)? = null
        fun handle(r: HttpServer.Request): HttpServer.Response? = when {
            r.path == "/api/scenes" -> {
                val post = r.method == "POST"
                val list = if (post) r.json().optJSONArray("scenes") else null
                if (post && list == null) HttpServer.Response.error(400, "scenes required")
                else {
                    if (list != null) { val kept = clean(list); save(kept); publish?.invoke(kept) }
                    if (r.query.containsKey("download")) backup(all()) else HttpServer.Response.json(JSONObject().put("scenes", all()))
                }
            }
            r.path == "/api/scene/run" && r.method == "POST" ->
                runRequest(r.json(), { id -> all().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.firstOrNull { it.optString("id") == id } }, offline(), target)
            r.path == "/api/scene/pin" && r.method == "POST" && pin != null -> pinRequest(r.json(), pin!!)
            else -> null
        }

        /** "Add to TV Home": [pin] gets the cleaned scene (the editor's own `id` / `name` / `icon` / `steps`, else a saved one by `id`) and answers {ok} or {error}. */
        private fun pinRequest(d: JSONObject, pin: (JSONObject) -> JSONObject): HttpServer.Response {
            val saved = all().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.firstOrNull { !d.has("steps") && it.optString("id") == d.optString("id") }
            val one = clean(JSONArray().put(saved ?: d)).optJSONObject(0) ?: return HttpServer.Response.error(400, "no scene")
            return try {
                val out = pin(one)
                if (out.has("error")) HttpServer.Response.error(501, out.optString("error")) else HttpServer.Response.json(out)
            } catch (e: SetupNeed.Missing) { HttpServer.Response.error(409, e.message.toString()) }
            catch (e: Exception) { HttpServer.Response.error(503, e.message ?: "no answer") }
        }
    }

    /**
     * After a client saves its scenes: hands the list to TV Setup, which lists them in TV Home's + Add (ScenePin.publish). Best effort and
     * quiet: no ADB, no TV Setup or an old one (no "scenes" action) just means no list there; the save itself never fails for it.
     * Last save wins: this list replaces whatever TV Setup showed, even one saved on the TV's own page.
     */
    fun publishThroughSetup(sh: (String) -> String, saved: JSONArray) {
        try { RulesClient.call(sh, JSONObject().put("do", "scenes").put("scenes", saved)) } catch (e: Exception) { }
    }

    /** From a client with ADB: asks TV Setup to offer [scene] to TV Home as a pinned tile (RulesReceiver "scenepin"). Throws [SetupNeed.Missing] when it is missing or old. */
    fun pinThroughSetup(sh: (String) -> String, scene: JSONObject): JSONObject {
        val r = RulesClient.call(sh, JSONObject().put("do", "scenepin").put("scene", scene))
        if (r.has("setup")) throw SetupNeed.Missing("scenepin", r.optString("have"))
        return r
    }

    /** Keeps only what can be run: at most 24 scenes of 30 steps, known steps with sane values, short names. */
    fun clean(list: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until minOf(list.length(), 24)) {
            val sc = list.optJSONObject(i) ?: continue
            val steps = JSONArray()
            val raw = sc.optJSONArray("steps") ?: JSONArray()
            for (j in 0 until minOf(raw.length(), 30)) {
                val s = raw.optJSONObject(j) ?: continue
                val what = s.optString("do")
                if (what !in STEPS) continue
                val c = JSONObject().put("do", what)
                when (what) {
                    "key" -> { val k = s.optString("key").uppercase(); if (k !in KEYS) continue; c.put("key", k) }
                    "app" -> { val p = s.optString("pkg"); if (!APP_ID.matcher(p).matches()) continue; c.put("pkg", p) }
                    "link" -> { val u = s.optString("url").trim(); if (!u.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:\\S+$")) || u.length > 2000) continue; c.put("url", u) }
                    "volume" -> c.put("level", s.optInt("level").coerceIn(0, 100))
                    "wait" -> c.put("s", s.optDouble("s", 1.0).coerceIn(0.0, 30.0))
                    "timer" -> c.put("min", s.optInt("min").coerceIn(0, 720))
                    "input" -> { val i = s.optString("id"); if (!Inputs.valid(i)) continue; c.put("id", i).put("label", s.optString("label").take(60)) }
                    "notify" -> try { val n = Notify.clean(s); for (k in n.keys()) c.put(k, n.get(k)) } catch (e: IllegalArgumentException) { continue }   // nothing to say: no step
                }
                steps.put(c)
            }
            val name = sc.optString("name").trim().take(40)
            if (name.isEmpty() || steps.length() == 0) continue
            val id = sc.optString("id").let { if (it.matches(Regex("[A-Za-z0-9_-]{1,24}"))) it else "s" + System.nanoTime().toString(36) }
            out.put(JSONObject().put("id", id).put("name", name).put("icon", sc.optString("icon").take(8)).put("steps", steps))
        }
        return out
    }

    /** Opens an app on its own TV screen, or its phone-style one: the launcher activity is looked up first, `monkey` is slow and noisy. */
    const val FACTORY_APP = "factory app: not opened"

    fun launch(sh: (String) -> String, pkg: String) {
        if (!PKG.matcher(pkg).matches()) throw IllegalArgumentException("bad package name")
        if (dev.tvremote.core.ontv.Bloat.factory(pkg)) throw IllegalArgumentException(FACTORY_APP)
        for (cat in arrayOf("LEANBACK_LAUNCHER", "LAUNCHER")) {               // a TV app's own screen first, then a phone-style one
            val lines = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.$cat $pkg 2>/dev/null").trim().split("\n")
            val component = lines[lines.size - 1].trim()
            if (!component.matches(Regex("[\\w.]+/[\\w.\$]+")) || !component.startsWith("$pkg/")) continue
            if (!sh("am start -n " + Adb.q(component) + " 2>&1").contains("Error")) return
        }
        throw IllegalArgumentException("this app cannot be opened (no launcher screen)")
    }

    /** Sets the media volume ([stream] 3; the settings list sets the others too). `cmd media_session` is there from Android 10;
     *  older TVs have `media`. */
    fun volume(sh: (String) -> String, level: Int, stream: Int = 3) {
        val bad = { out: String -> out.contains("Exception") || out.contains("Error") || out.contains("Unknown") || out.contains("not found") }
        val now = { Regex("volume is (\\d+)").find(sh("cmd media_session volume --stream $stream --get 2>&1"))?.groupValues?.get(1)?.toIntOrNull() }
        if (bad(sh("cmd media_session volume --stream $stream --set $level 2>&1")) && bad(sh("media volume --stream $stream --set $level 2>&1")))
            throw IllegalArgumentException("volume command failed")
        // Some TVs take the command and keep their volume (HDMI-CEC, the Google TV emulator): then the keys, one step each
        val got = now() ?: return
        if (got == level) return
        if (stream != 3) throw IllegalArgumentException("this TV keeps that volume itself")   // the keys move the media volume only
        val code = if (level > got) 24 else 25
        sh((1..minOf(Math.abs(level - got), 30)).joinToString("; ") { "input keyevent $code" })
    }

    /** The scenes as a file to keep ("Export"): the page reads the same shape back ("Import"), also a bare list. */
    fun backup(scenes: JSONArray): HttpServer.Response {
        val body = JSONObject().put("app", "tv-remote").put("kind", "scenes").put("version", 1).put("scenes", scenes).toString(2)
        val out = HttpServer.Response.of("application/json; charset=utf-8", body.toByteArray())
        val name = "tv-remote-scenes-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"
        out.headers["Content-Disposition"] = "attachment; filename=\"$name\"; filename*=UTF-8''$name"
        return out
    }

    /** Everything through ADB shell commands. [timer] defaults to asking TV Setup over ADB (see [RulesClient]). */
    class Shell(val sh: (String) -> String, private val timer: ((Int) -> Unit)? = null) : Target {
        override fun key(code: Int) { sh("input keyevent $code") }
        override fun app(pkg: String) = launch(sh, pkg)
        override fun link(url: String) {
            val out = sh("am start -a android.intent.action.VIEW -d " + Adb.q(url) + " 2>&1")
            if (out.contains("Error") || out.lowercase().contains("unable to resolve")) throw IllegalArgumentException("the TV has no app that can open this")
        }
        override fun volume(level: Int) = volume(sh, level)
        override fun input(id: String) { Inputs.switch(sh, id) }
        override fun notify(msg: JSONObject) { Notify.send(sh, msg) }
        override fun timer(minutes: Int) {
            if (timer != null) { timer.invoke(minutes); return }
            val r = RulesClient.call(sh, JSONObject().put("do", "sleep").put("minutes", minutes))
            if (r.has("setup")) throw SetupNeed.Missing("rules", r.optString("have"))
            if (r.has("error")) throw IllegalArgumentException(r.optString("error"))
        }
    }
}
