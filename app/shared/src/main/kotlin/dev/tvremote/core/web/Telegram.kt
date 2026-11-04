package dev.tvremote.core.web
import dev.tvremote.core.WireLog
import dev.tvremote.core.Words
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * The TV from a Telegram chat: the owner makes a bot with @BotFather and pastes its token in Settings; TV Setup then asks
 * Telegram for the bot's messages (long polling: `getUpdates` held open for 50 s) and answers them. No webhook and no open port,
 * so it works behind any router and costs nothing when switched off. Not Hook.kt's job (that one is a link inside the home
 * network); this one goes out, to api.telegram.org only.
 *
 * Who may command it: a chat that sent `/pair 123456` with the code the TV showed (one at a time, five minutes, five wrong
 * guesses cancel it and lock pairing, as [Hook] does for tokens). Anyone else is ignored silently, so a stranger who finds the bot
 * learns nothing, not even that it listens. A paired chat has a scope like a shared browser (Scope.kt in TV Setup): REMOTE by
 * default, FULL when the owner raises it.
 *
 * Nothing here presses a key: every command becomes the request the page would have sent and goes to the core's own router
 * ([route], with the chat's scope, so a REMOTE chat is refused exactly where a REMOTE browser is), through [Hook.perform] for the
 * actions the hook has too.
 */
class Telegram(
    private val store: Store,
    private val api: Api,
    private val hook: Hook,
    /** The core's router, called with a request built here and the scope it may use ("remote" / "full"). */
    private val route: (HttpServer.Request, String) -> HttpServer.Response,
    /** What /api/state says. */
    private val state: () -> JSONObject,
    /** The apps on the TV's home screen as (package, label), to open one by its name. */
    private val apps: () -> List<Pair<String, String>>,
    /** Shows the pairing code on the TV's own screen (the Notify card). */
    private val showCode: (String) -> Unit = { },
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: java.util.Random = SecureRandom(),
    /** false in tests: they call [step] themselves. */
    private val background: Boolean = true,
    /** A message's own time (ms, Telegram's clock), read the moment it arrived: TV Setup compares it with the TV's clock (TOTP needs the right time). */
    private val onDate: (Long) -> Unit = { },
    /** How far ahead of the real time the TV's clock is (ms; negative: behind), so a TV whose clock is minutes out still hears fresh commands. */
    private val skew: () -> Long = { 0L },
) {
    /** Where the switch, the token and the paired chats live (TV Setup's prefs). The token is never written anywhere else. */
    interface Store {
        var enabled: Boolean
        var token: String
        var offset: Long                    // the next update to ask for: kept, so a restart never runs an old command again
        var lang: String                    // "" = the TV's language
        var bot: String                     // the bot's @name, from getMe
        var chats: String                   // JSON array of the paired chats
        var history: String get() = "[]"; set(@Suppress("UNUSED_PARAMETER") v) { }   // JSON array of what the bot did (see [History]); a store without room for it keeps none
    }

    /** One call to Telegram. [status] is the HTTP status (0 never: a network failure throws), [body] Telegram's JSON. */
    class Reply(val status: Int, val body: JSONObject) {
        val ok get() = status == 200 && body.optBoolean("ok")
        val retryAfterMs: Long get() = body.optJSONObject("parameters")?.optLong("retry_after", 0)?.times(1000) ?: 0L
    }

    interface Api {
        /** `https://api.telegram.org/bot<token>/<method>`; [timeoutS]: how long to wait for the answer (getUpdates holds 50 s). */
        @Throws(IOException::class) fun call(token: String, method: String, params: JSONObject, timeoutS: Int = 15): Reply
        @Throws(IOException::class) fun photo(token: String, chat: Long, png: ByteArray, caption: String): Reply
        /** Cuts the call in flight (the switch went off, the token changed). */
        fun abort()
    }

    /** [alerts]: the kinds of [ALERTS] this chat asked the TV to send by itself (/alerts). */
    data class Chat(val id: Long, val name: String, val scope: String, val at: Long, val alerts: Set<String> = emptySet(), val type: String = "", val seen: Long = 0L)
    class Cmd(val name: String, val arg: String)

    @Volatile var status = "off"                    // off | connecting | ok | badtoken | error
        private set
    @Volatile var lastError = ""
        private set
    @Volatile var lastOk = 0L                       // when Telegram last answered a poll
        private set

    private var code: String? = null
    private var codeUntil = 0L
    private var wrong = 0
    private var lockedUntil = 0L
    private val limit = Limiter(MIN_GAP)
    private val history by lazy { History(store, clock) }
    private val limitTap = Limiter(MIN_GAP_TAP)

    // ---------------------------------------------------------------- the loop
    private val wake = Object()
    private var worker: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var readyFor = ""                       // token + language the webhook was cleared and the commands set for
    private var backoff = 0L
    @Volatile private var quiet = false
    @Volatile private var kicked = false

    /** Starts the loop when it is wanted and stops it when not; call after the switch, the token or the language changed. */
    fun sync() {
        synchronized(wake) {
            if (store.enabled && store.token.isNotEmpty()) {
                if (background && worker?.isAlive != true) {
                    stopping = false
                    worker = Thread({ loop() }, "telegram").apply { isDaemon = true; start() }
                }
            } else { status = "off" }
            readyFor = ""                           // a new token or language: clear the webhook and set the commands again
            kicked = true
            wake.notifyAll()
        }
        api.abort()
    }

    /** The service ends: no more calls. */
    fun stop() {
        synchronized(wake) { stopping = true; wake.notifyAll() }
        api.abort()
    }

    private fun loop() {
        while (!stopping) {
            val t0 = System.nanoTime()
            quiet = false
            var w = try { step() } catch (e: Exception) { fail("loop", e.message ?: e.javaClass.simpleName) }
            if (kicked) { kicked = false; if (status == "error") { status = "connecting"; lastError = ""; backoff = 0L }; w = 0 }   // our own abort ended the call: not Telegram's fault
            if (w == 0L && quiet && System.nanoTime() - t0 < 500_000_000L) w = 1000   // an answer with nothing in it, at once: a proxy that does not hold the call must not make this spin
            synchronized(wake) {
                if (stopping) return
                if (w < 0) { wake.wait() } else if (w > 0) wake.wait(w)
            }
        }
    }

    /**
     * One round: ask Telegram for updates and answer them. Returns the milliseconds to wait before the next round (0: at once;
     * the long poll itself was the wait), or -1 to sleep until [sync] (switched off, or the token was refused).
     */
    fun step(): Long {
        val token = store.token
        if (!store.enabled || token.isEmpty()) { status = "off"; return -1 }
        try {
            val key = token + "/" + lang()
            if (readyFor != key) {
                if (status != "ok") status = "connecting"
                val me = api.call(token, "getMe", JSONObject())
                if (refused(me)) return -1
                if (!me.ok) return fail("getMe", describe(me), me)
                me.body.optJSONObject("result")?.optString("username")?.takeIf { it.isNotEmpty() }?.let { store.bot = "@$it" }
                val dw = api.call(token, "deleteWebhook", JSONObject().put("drop_pending_updates", false))   // a webhook would stop getUpdates
                if (!dw.ok) return fail("deleteWebhook", describe(dw), dw)
                api.call(token, "setMyCommands", JSONObject().put("commands", commandList()))   // best effort: the bot works without the "/" menu
                readyFor = key
            }
            if (store.offset <= 0L) {                // first start: what was sent while nobody listened is not for now
                val last = api.call(token, "getUpdates", JSONObject().put("offset", -1).put("timeout", 0))
                if (refused(last)) return -1
                if (!last.ok) return fail("getUpdates", describe(last), last)
                val a = last.body.optJSONArray("result")
                store.offset = (if (a != null && a.length() > 0) a.getJSONObject(a.length() - 1).optLong("update_id") + 1 else 1L)
            }
            val p = JSONObject().put("timeout", POLL_S).put("offset", store.offset).put("allowed_updates", JSONArray(ALLOWED))
            val r = api.call(token, "getUpdates", p, POLL_S + 15)
            if (refused(r)) return -1
            if (r.status == 409) { readyFor = ""; return fail("getUpdates", "another listener (webhook?)", r) }
            if (!r.ok) return fail("getUpdates", describe(r), r)
            status = "ok"; lastError = ""; backoff = 0; lastOk = clock()
            val got = r.body.optJSONArray("result") ?: JSONArray()
            quiet = got.length() == 0
            handle(got)
            return 0
        } catch (e: IOException) {
            if (stopping) return -1
            val w = fail("poll", clean(e.message ?: e.javaClass.simpleName))
            status = "offline"                          // Telegram could not be reached at all (no internet, DNS, a blocked address), unlike an answer that said no
            return w
        }
    }

    /** 401 / 404: Telegram does not know this token. Asking again would only repeat it: wait for a new one. */
    private fun refused(r: Reply): Boolean {
        if (r.status != 401 && r.status != 404) return false
        status = "badtoken"; lastError = describe(r)
        WireLog.all.add("·", "telegram: token refused (${r.status})")
        return true
    }

    private fun fail(what: String, why: String, r: Reply? = null): Long {
        status = "error"; lastError = clean(why)
        backoff = maxOf(next(backoff), r?.retryAfterMs ?: 0L)
        WireLog.all.add("·", "telegram $what: ${lastError} (retry in ${backoff / 1000}s)")
        return backoff
    }

    private fun describe(r: Reply) = "${r.status} " + r.body.optString("description")

    // ---------------------------------------------------------------- updates
    /**
     * [updates] as getUpdates gave them. The offset moves past all of them and is kept BEFORE any is answered: a command that
     * crashes the loop (or a restart in the middle) must not come back and run twice; "/off" twice is the worse fault.
     */
    fun handle(updates: JSONArray) {
        var top = store.offset
        for (i in 0 until updates.length()) top = maxOf(top, updates.getJSONObject(i).optLong("update_id") + 1)
        if (top != store.offset) store.offset = top
        for (i in 0 until updates.length()) {
            try { onUpdate(updates.getJSONObject(i)) } catch (e: Exception) { WireLog.all.add("·", "telegram update: " + clean(e.message ?: e.javaClass.simpleName)) }
        }
    }

    fun onUpdate(u: JSONObject) {
        u.optJSONObject("callback_query")?.let { return onTap(it) }
        val m = u.optJSONObject("message") ?: return
        val text = m.optString("text")
        if (text.isEmpty()) return
        val sent = m.optLong("date")
        if (sent > 0 && clock() / 1000 - sent < 600) try { onDate(sent * 1000) } catch (e: Exception) { }   // a reading of the real time, if not a message that waited in the queue
        if ((clock() - skew()) / 1000 - sent > STALE_S) return       // a command that waited in Telegram's queue for minutes is not for now
        val chat = m.optJSONObject("chat") ?: return
        val id = chat.optLong("id")
        val cmd = parse(text, store.bot) ?: return onText(id, chat)
        val known = chats().firstOrNull { it.id == id }
        // t.me/<bot>?start=<code> sends "/start <code>": the same code, the same limits as /pair
        if (known == null) { if ((cmd.name == "pair" || (cmd.name == "start" && cmd.arg.isNotEmpty())) && pair(id, nameOf(chat), cmd.arg, chat.optString("type"))) send(id, w("telegram.paired", "scope" to REMOTE) + "\n\n" + help(REMOTE)); return }   // anyone else: nothing, not even an answer
        if (!limit.ok(id, clock())) return
        touch(known)
        val secret = cmd.name in HIDDEN                                  // what was typed or said is the family's, not the log's
        WireLog.all.add("←", "telegram ${known.name}: /${cmd.name} ${if (secret) "…(${cmd.arg.length})" else cmd.arg}".trim())
        outcome.set("ok")
        try { command(known, cmd, m.optJSONObject("from")?.optString("language_code") ?: "") } catch (e: Exception) { outcome.set("error"); throw e }
        history.add(clock(), known, "/" + cmd.name + (if (secret || cmd.arg.isEmpty()) "" else " " + cmd.arg.take(40)), outcome.get())
    }

    /** A plain sentence in a private chat that is paired: the way to the commands. Groups and strangers: nothing. */
    private fun onText(id: Long, chat: JSONObject) {
        if (chat.optString("type") != "private" || chats().none { it.id == id } || !limit.ok(id, clock())) return
        send(id, w("telegram.plain"))
    }

    private fun onTap(cb: JSONObject) {
        val data = cb.optString("data")
        val msg = cb.optJSONObject("message")
        val id = msg?.optJSONObject("chat")?.optLong("id") ?: return
        val known = chats().firstOrNull { it.id == id } ?: return
        val qid = cb.optString("id")
        if (!limitTap.ok(id, clock())) { answer(qid, ""); return }
        val rest = data.substringAfter(':', "")
        WireLog.all.add("←", "telegram ${known.name}: tap ${data.take(40)}")
        outcome.set("ok")
        try { tap(known, data, rest, qid, msg, id) } finally { history.add(clock(), known, "tap " + data.substringBefore(':') + (if (data.startsWith("k:") || data.startsWith("u:")) " $rest" else ""), outcome.get()) }
    }

    private fun tap(known: Chat, data: String, rest: String, qid: String, msg: JSONObject?, id: Long) {
        when (data.substringBefore(':')) {
            "k" -> {
                if (rest !in PAD) return answer(qid, "")
                val out = perform(known, "key", rest)
                answer(qid, if (out.status in 200..299) "" else say(out, known))
            }
            "a" -> {                                                      // an app of /apps
                if (!PKG.matches(rest) || (known.scope != FULL && apps().none { it.first == rest })) return answer(qid, "")
                val out = perform(known, "app", rest)
                answer(qid, if (out.status in 200..299) w("telegram.open_done", "app" to (apps().firstOrNull { it.first == rest }?.second ?: rest)) else say(out, known))
            }
            "s" -> {                                                      // a scene of /scenes
                val out = perform(known, "scene", rest)
                answer(qid, if (out.status in 200..299) w("telegram.scene_done", "name" to sceneName(known, rest)) else say(out, known))
            }
            "i" -> { answer(qid, ""); if (rest == "shot") shot(known) }  // the /now screenshot button
            "l" -> {                                                      // an alert switch of /alerts
                if (rest !in ALERTS) return answer(qid, "")
                val on = toggleAlert(id, rest)
                answer(qid, "")
                msg?.optLong("message_id")?.let { edit(id, it, alertsMarkup(chats().firstOrNull { c -> c.id == id } ?: known)) }
                WireLog.all.add("·", "telegram: alert $rest ${if (on) "on" else "off"} for ${known.name}")
            }
            "r" -> { answer(qid, ""); change(known, rest) }
            "z" -> { answer(qid, ""); undo(known, rest) }
            "u" -> grant(known, rest, qid, msg, id)                       // a parent's answer to "ask a parent" (kids mode)
            else -> answer(qid, "")
        }
    }

    // ---------------------------------------------------------------- pairing
    /** A fresh code (the old one dies), also on the TV's screen. Asking for it is the owner's act: it lifts a lock too. */
    @Synchronized fun newCode(): String {
        val c = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
        code = c; codeUntil = clock() + CODE_MS; wrong = 0; lockedUntil = 0
        try { showCode(c) } catch (e: Exception) { }
        return c
    }

    @Synchronized fun cancelCode() { code = null }
    @Synchronized fun codeLeftS(): Int = if (code != null && clock() < codeUntil) ((codeUntil - clock() + 999) / 1000).toInt() else 0
    @Synchronized fun lockedLeftS(): Int = if (clock() < lockedUntil) ((lockedUntil - clock() + 999) / 1000).toInt() else 0
    @Synchronized private fun codeNow(): String? = if (clock() < codeUntil) code else null

    /** `/pair 123456` from a chat nobody has let in. true when it was let in. */
    @Synchronized fun pair(id: Long, name: String, given: String, type: String = ""): Boolean {
        val now = clock()
        if (now < lockedUntil) return false
        val want = codeNow() ?: return false
        val got = given.replace(Regex("\\D"), "")
        if (!MessageDigest.isEqual(want.toByteArray(), got.toByteArray())) {
            if (++wrong >= MAX_WRONG) { code = null; wrong = 0; lockedUntil = now + LOCK_MS; WireLog.all.add("·", "telegram: pairing locked after $MAX_WRONG wrong codes") }
            return false
        }
        code = null; wrong = 0
        val list = chats().filter { it.id != id } + Chat(id, name, REMOTE, now, type = type, seen = now)
        save(list)
        WireLog.all.add("·", "telegram: paired $name (scope $REMOTE)")
        return true
    }

    // ---------------------------------------------------------------- commands
    private fun command(c: Chat, cmd: Cmd, lang: String = "") {    // [lang]: the sender's Telegram language, for /speak's voice
        val a = cmd.arg
        when (cmd.name) {
            "start", "help" -> send(c.id, help(c.scope))
            "status" -> send(c.id, statusText())
            "off" -> send(c.id, done(perform(c, "power", "off"), c, w("telegram.off_done")))
            "on" -> send(c.id, done(perform(c, "power", "on"), c, w("telegram.on_done")))
            "vol" -> {
                if (a.isEmpty()) return send(c.id, w("telegram.usage_vol"))
                // a level goes through a scene step, which a REMOTE browser may not send; the level is checked 0..100 and is no more than
                // the volume keys can do, so the chat is let do exactly this
                val n = a.toIntOrNull()
                send(c.id, done(perform(c, "volume", a, if (n != null) FULL else c.scope), c, w("telegram.vol_done", "v" to a)))
            }
            "key" -> {
                if (a.isEmpty()) return send(c.id, w("telegram.usage_key"))
                send(c.id, done(perform(c, "key", a), c, w("telegram.key_done", "k" to a.uppercase(Locale.ROOT))))
            }
            "open" -> open(c, a)
            "scene" -> {
                if (a.isEmpty()) return send(c.id, w("telegram.usage_scene"))
                val out = perform(c, "scene", a)
                if (out.status == 404) {
                    val names = JSONObject(String(out.body)).optJSONArray("scenes")
                    val list = (0 until (names?.length() ?: 0)).joinToString(", ") { names!!.optString(it) }
                    return send(c.id, if (list.isEmpty()) w("telegram.no_scenes") else w("telegram.no_scene", "list" to list))
                }
                send(c.id, done(out, c, w("telegram.scene_done", "name" to a)))
            }
            "timer" -> {
                val n = a.toIntOrNull()?.takeIf { it in 0..720 } ?: return send(c.id, w("telegram.usage_timer"))
                val out = post(c, "/api/rules", JSONObject().put("do", "sleep").put("minutes", n))
                send(c.id, done(out, c, if (n == 0) w("telegram.timer_off") else w("telegram.timer_set", "n" to n)))
            }
            "say" -> {
                if (a.isEmpty()) return send(c.id, w("telegram.usage_say"))
                send(c.id, done(post(c, "/api/notify", JSONObject().put("title", "Telegram · " + c.name.take(30)).put("text", a)), c, w("telegram.say_done")))
            }
            "speak" -> {                                                  // like /say, and the TV reads it aloud
                if (a.isEmpty()) return send(c.id, w("telegram.usage_speak"))
                val out = post(c, "/api/notify", JSONObject().put("title", "Telegram · " + c.name.take(30)).put("text", a).put("speak", true).apply { if (lang.isNotEmpty()) put("lang", lang) })
                send(c.id, done(out, c, speakAnswer(out)))
            }
            "shot" -> shot(c)
            "remote" -> send(c.id, w("telegram.remote_title"), keypad())
            "alerts" -> send(c.id, w("telegram.alerts_title"), alertsMarkup(c))
            "apps" -> appsMenu(c)
            "usage" -> usage(c)
            "now" -> now(c)
            "channel" -> channel(c, a)
            "type" -> {
                if (a.isEmpty()) return send(c.id, w("telegram.usage_type"))
                send(c.id, done(perform(c, "text", a), c, w("telegram.type_done")))
            }
            "scenes" -> scenesMenu(c)
            "bedtime" -> bedtime(c, a)
            "limits" -> limits(c)
            "schedule" -> schedule(c)
            "unlock" -> unlock(c, a)
            "lock" -> if (c.scope != FULL) send(c.id, w("telegram.rules_readonly")) else send(c.id, done(post(c, "/api/rules", JSONObject().put("do", "unlock").put("minutes", 0)), c, w("tg.lock_done")))
            "time" -> time(c)
            "more" -> more(c, a)
            "pair" -> send(c.id, w("telegram.already"))
            else -> send(c.id, w("telegram.unknown", "c" to cmd.name))
        }
    }

    private fun open(c: Chat, a: String) {
        if (a.isEmpty()) return send(c.id, w("telegram.usage_open"))
        val list = apps()
        val q = a.trim().lowercase(Locale.ROOT)
        val hit = list.firstOrNull { it.second.lowercase(Locale.ROOT) == q || it.first.lowercase(Locale.ROOT) == q }
            ?: list.filter { it.second.lowercase(Locale.ROOT).contains(q) }.singleOrNull()
        val pkg = hit?.first ?: if (c.scope == FULL && PKG.matches(a.trim())) a.trim() else null   // a FULL chat may name any package; REMOTE only what the home screen shows
        if (pkg == null) {
            val near = list.filter { it.second.lowercase(Locale.ROOT).contains(q) }
            return send(c.id, if (near.size > 1) w("telegram.open_many", "list" to near.joinToString(", ") { it.second }) else w("telegram.open_none"))
        }
        send(c.id, done(perform(c, "app", pkg), c, w("telegram.open_done", "app" to (hit?.second ?: pkg))))
    }

    private fun shot(c: Chat) {
        val out = get(c, "/api/screen")
        if (out.status !in 200..299 || out.body.isEmpty()) return send(c.id, if (out.status == 403) w("telegram.scope_denied") else w("telegram.shot_failed"))
        try {
            val r = api.photo(store.token, c.id, out.body, if (looksBlack(out.body)) w("telegram.shot_black") else "")
            if (!r.ok) send(c.id, w("telegram.shot_failed"))
        } catch (e: IOException) { WireLog.all.add("·", "telegram photo: " + clean(e.message ?: "")); }
    }

    /** A screenshot that is (nearly) all black: YouTube, Netflix and other copy-protected video come out black (the TV hides
     *  them from screenshots), and a person reading it in Telegram thinks the bot is broken. A black 1080p PNG is ~10 KB, a
     *  real screen ~2.5 MB (FPT box, 2026-11-03): under 0.04 bytes a pixel is black, give or take a small overlay. */
    internal fun looksBlack(png: ByteArray): Boolean {
        if (png.size < 24 || png[1] != 'P'.code.toByte() || png[2] != 'N'.code.toByte() || png[3] != 'G'.code.toByte()) return false
        val bb = java.nio.ByteBuffer.wrap(png, 16, 8)
        val px = bb.int.toLong() * bb.int.toLong()
        return px > 0 && png.size < px * 4 / 100
    }

    private fun statusText(): String {
        val b = hook.brief(try { state() } catch (e: Exception) { JSONObject() })
        val on = if (b.isNull("on")) w("telegram.st_unknown") else if (b.optBoolean("on")) w("telegram.st_on") else w("telegram.st_off")
        val v = b.optJSONObject("volume")
        val vol = if (v == null) "" else "\n" + w("telegram.st_vol", "v" to v.opt("level"), "max" to v.opt("max")) + (if (v.optBoolean("muted")) " " + w("telegram.st_muted") else "")
        fun str(k: String) = if (b.isNull(k)) "" else b.optString(k)                                   // JSON null reads as the word "null"
        val app = if (str("app").isEmpty()) "" else "\n" + w("telegram.st_app", "app" to str("app_label").ifEmpty { str("app") })
        return w("telegram.st_tv", "on" to on) + vol + app
    }

    private fun help(scope: String) = w("telegram.help", "scope" to scope) + (if (scope == FULL) "\n" + w("tg.help_kids") else "")

    /** The answer for a finished command: [ok] words when it went through, else why not. */
    private fun done(out: HttpServer.Response, c: Chat, ok: String) = if (out.status in 200..299) ok else say(out, c)

    /** The answer to /speak: read aloud, or shown with the reason it was not (nothing to read it with / no voice for the language). */
    private fun speakAnswer(out: HttpServer.Response): String {
        val b = body(out)
        return when (b?.optString("speak")) {
            "noengine" -> w("telegram.speak_noengine")
            "nolang" -> w("telegram.speak_nolang", "lang" to b.optString("lang"))
            else -> w("telegram.speak_done")
        }
    }

    private fun say(out: HttpServer.Response, c: Chat): String {
        val e = try { JSONObject(String(out.body)).optString("error") } catch (x: Exception) { "" }
        return when {
            out.status == 403 -> w("telegram.scope_denied")
            e.contains("channels:missing") -> w("telegram.channel_missing")
            e.startsWith("adb:") || out.status == 503 -> w("telegram.tv_not_ready")
            e == "unknown key" -> w("telegram.bad_key")
            e.contains("setup:") -> w("telegram.needs_setup")
            e.isNotEmpty() -> "❌ " + e.take(200)
            else -> "❌ ${out.status}"
        }
    }

    // ---------------------------------------------------------------- alerts: the TV speaks first
    private val alertAt = HashMap<String, Long>()
    private val speaker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "telegram-alert").apply { isDaemon = true } } }

    /**
     * TV Setup says something happened ([kind] of [ALERTS]; `why` in [args] picks the words when one kind has several). Goes to the chats that
     * switched that kind on with /alerts, at most one of a kind a minute; sent from a thread of its own, so the Guard that calls this never waits for Telegram.
     */
    // Words picked by name at run time, listed here so the id check (tests/i18n.test.js) sees them used:
    // "telegram.al_name_on" "telegram.al_name_off" "telegram.al_name_limit" "telegram.al_name_bed" "telegram.al_name_sleep" "telegram.al_name_pair"
    // "telegram.al_on" "telegram.al_off" "telegram.al_limit" "telegram.al_sched" "telegram.al_bed" "telegram.al_sleep" "telegram.al_pair"
    // "telegram.now_playing" "telegram.now_paused" "telegram.now_buffering"
    fun alert(kind: String, vararg args: Pair<String, Any?>) {
        if (kind !in ALERTS || !store.enabled || store.token.isEmpty()) return
        val to = chats().filter { kind in it.alerts }
        if (to.isEmpty()) return                                    // nobody asked: no stamp either, so a chat that switches it on a second later still hears the next one
        val now = clock()
        synchronized(alertAt) {
            val before = alertAt[kind]
            if (before != null && now >= before && now - before < ALERT_GAP) return
            alertAt[kind] = now
        }
        val text = w("telegram.al_" + (args.firstOrNull { it.first == "why" }?.second ?: kind), *args)
        WireLog.all.add("→", "telegram alert $kind → ${to.size} chat(s)")
        history.add(now, null, "alert $kind", "ok", to.map { it.name })
        val job = Runnable { for (c in to) send(c.id, text) }
        if (background) speaker.execute { try { job.run() } catch (e: Exception) { WireLog.all.add("·", "telegram alert: " + clean(e.message ?: "")) } } else job.run()
    }

    private fun toggleAlert(id: Long, kind: String): Boolean {
        var on = false
        save(chats().map { if (it.id != id) it else { on = kind !in it.alerts; it.copy(alerts = if (on) it.alerts + kind else it.alerts - kind) } })
        return on
    }

    private fun alertsMarkup(c: Chat): JSONObject =
        keyboard(ALERTS.map { listOf(button((if (it in c.alerts) "✅ " else "▫️ ") + w("telegram.al_name_$it"), "l:$it")) })

    private fun button(text: String, data: String) = JSONObject().put("text", text).put("callback_data", data)
    private fun keyboard(rows: List<List<JSONObject>>) = JSONObject().put("inline_keyboard", JSONArray(rows.map { JSONArray(it) }))
    private fun grid(buttons: List<JSONObject>, per: Int = 2) = buttons.chunked(per)

    // ---------------------------------------------------------------- what is on, what was used, what to open
    private fun dayKey() = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(clock()))
    private fun appLabel(pkg: String, labels: JSONObject? = null) =
        labels?.optString(pkg)?.takeIf { it.isNotEmpty() } ?: apps().firstOrNull { it.first == pkg }?.second ?: pkg.substringAfterLast('.')

    private fun secondsOf(day: JSONObject?): Map<String, Long> = day?.keys()?.asSequence()?.associateWith { day.optLong(it) }?.filterValues { it > 0 } ?: emptyMap()

    private fun minutes(sec: Long): String {
        val m = maxOf(1L, (sec + 30) / 60)
        return if (m >= 60) w("telegram.dur_hm", "h" to m / 60, "m" to m % 60) else w("telegram.dur_m", "m" to m)
    }

    /** The most used apps (Stats, the last week) plus the ones used today, one tap each. Without Stats (a REMOTE chat may not read them): the home screen's first apps. */
    private fun appsMenu(c: Chat) {
        val list = apps().filter { it.first != SELF && PKG.matches(it.first) && it.first.length <= 56 }
        val usage = body(get(c, "/api/stats", mapOf("days" to "7")))?.optJSONObject("usage")
        val week = HashMap<String, Long>()
        if (usage != null) for (d in usage.keys()) for ((p, s) in secondsOf(usage.optJSONObject(d))) week[p] = (week[p] ?: 0L) + s
        val today = secondsOf(usage?.optJSONObject(dayKey()))
        val top = list.filter { (week[it.first] ?: 0L) >= 60 }.sortedByDescending { week[it.first] }.take(6)
        val recent = list.filter { it !in top && (today[it.first] ?: 0L) > 0 }.sortedByDescending { today[it.first] }.take(4)
        val chosen = (top + recent).ifEmpty { list.take(8) }
        if (chosen.isEmpty()) return send(c.id, w("telegram.apps_none"))
        send(c.id, w("telegram.apps_title"), keyboard(grid(chosen.map { button(it.second, "a:${it.first}") })))
    }

    private fun usage(c: Chat) {
        val out = get(c, "/api/stats", mapOf("days" to "1"))
        if (out.status == 403) return send(c.id, w("telegram.scope_denied"))
        val st = body(out) ?: return send(c.id, say(out, c))
        val day = secondsOf(st.optJSONObject("usage")?.optJSONObject(dayKey()))
        if (day.isEmpty()) return send(c.id, w("telegram.usage_none"))
        val labels = st.optJSONObject("labels")
        val lines = day.entries.sortedByDescending { it.value }.take(5).joinToString("\n") { "• " + appLabel(it.key, labels) + " – " + minutes(it.value) }
        send(c.id, w("telegram.usage_title", "total" to minutes(day.values.sum())) + "\n" + lines)
    }

    private fun now(c: Chat) {
        val p = body(get(c, "/api/playing"))
        val st = p?.optString("state") ?: "none"
        val shot = keyboard(listOf(listOf(button(w("telegram.btn_shot"), "i:shot"))))
        if (p != null && st in listOf("playing", "paused", "buffering")) {
            val app = p.optString("app").ifEmpty { p.optString("pkg") }
            val title = if (p.isNull("title")) "" else p.optString("title")
            val what = listOf(app, title).filter { it.isNotEmpty() }.joinToString(" – ")
            return send(c.id, w("telegram.now_$st", "what" to what), shot)
        }
        val b = hook.brief(try { state() } catch (e: Exception) { JSONObject() })
        val open = if (b.isNull("app")) "" else b.optString("app_label").ifEmpty { b.optString("app") }
        send(c.id, if (open.isEmpty()) w("telegram.now_none") else w("telegram.now_idle", "app" to open), shot)
    }

    private fun channel(c: Chat, a: String) {
        if (a.isEmpty()) return send(c.id, w("telegram.usage_channel"))
        send(c.id, done(post(c, "/api/channels/open", JSONObject().put("q", a.take(100))), c, w("telegram.channel_done", "name" to a.take(100))))
    }

    private fun scenesMenu(c: Chat) {
        val list = scenes(c).filter { it.second.length <= 56 }
        if (list.isEmpty()) return send(c.id, w("telegram.no_scenes"))
        send(c.id, w("telegram.scenes_title"), keyboard(grid(list.map { button(it.first, "s:${it.second}") })))
    }

    /** (name, id) of the saved scenes. */
    private fun scenes(c: Chat): List<Pair<String, String>> {
        val a = body(get(c, "/api/scenes"))?.optJSONArray("scenes") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotEmpty() }.map { it.optString("name").ifEmpty { it.optString("id") } to it.optString("id") }
    }
    private fun sceneName(c: Chat, id: String) = scenes(c).firstOrNull { it.second == id }?.first ?: id

    // ---------------------------------------------------------------- the rules: bedtime, limits, schedules (TV Setup's /api/rules; changing them is FULL's)
    private class Undo(val chat: Long, val steps: List<JSONObject>, val at: Long)
    private val undos = LinkedHashMap<String, Undo>()

    private fun rules(c: Chat): JSONObject? = body(get(c, "/api/rules"))

    private fun remember(c: Chat, steps: List<JSONObject>): JSONObject {
        val id = Integer.toString(random.nextInt(Int.MAX_VALUE), 36)
        synchronized(undos) {
            undos[id] = Undo(c.id, steps, clock())
            while (undos.size > 30) undos.remove(undos.keys.first())
        }
        return keyboard(listOf(listOf(button(w("telegram.btn_undo"), "z:$id"))))
    }

    private fun undo(c: Chat, id: String) {
        if (c.scope != FULL) return send(c.id, w("telegram.rules_readonly"))
        val u = synchronized(undos) { undos[id]?.takeIf { it.chat == c.id && clock() - it.at < UNDO_MS }?.also { undos.remove(id) } }
            ?: return send(c.id, w("telegram.undo_gone"))
        for (s in u.steps) { val out = post(c, "/api/rules", s); if (out.status !in 200..299) return send(c.id, say(out, c)) }
        send(c.id, w("telegram.undone"))
    }

    // ---------------------------------------------------------------- kids mode: the lock, the day's total, asking a parent (changing them is FULL's)
    /** `/unlock 30`: everything on the TV needs no code for that many minutes (30 by default); `/lock` ends it. */
    private fun unlock(c: Chat, a: String) {
        if (c.scope != FULL) return send(c.id, w("telegram.rules_readonly"))
        val n = if (a.isEmpty()) 30 else a.toIntOrNull()?.takeIf { it in 1..1440 } ?: return send(c.id, w("tg.usage_unlock"))
        send(c.id, done(post(c, "/api/rules", JSONObject().put("do", "unlock").put("minutes", n)), c, w("tg.unlock_done", "n" to n)))
    }

    /** `/time`: the minutes used and left of today's total. Reading it is every chat's. */
    private fun time(c: Chat) {
        val st = rules(c) ?: return send(c.id, w("telegram.tv_not_ready"))
        val t = st.optJSONObject("total")
        val min = t?.optInt("min") ?: 0
        if (t == null || min <= 0) return send(c.id, w("tg.time_off"))
        val all = min + t.optInt("extra")
        val unlocked = st.optLong("unlock_until") - st.optLong("now", clock())
        send(c.id, w("tg.time_line", "used" to t.optInt("used"), "all" to all, "left" to maxOf(0, all - t.optInt("used")), "extra" to (if (t.optInt("extra") > 0) " (+" + t.optInt("extra") + ")" else ""))
            + (if (unlocked > 0) "\n" + w("tg.time_unlocked", "n" to (unlocked + 59_999) / 60_000) else ""))
    }

    /** `/more 30`: 30 minutes more today on top of the total. */
    private fun more(c: Chat, a: String) {
        if (c.scope != FULL) return send(c.id, w("telegram.rules_readonly"))
        val n = a.toIntOrNull()?.takeIf { it in 1..240 } ?: return send(c.id, w("tg.usage_more"))
        val before = rules(c)?.optJSONObject("total")?.optInt("extra") ?: 0
        val out = post(c, "/api/rules", JSONObject().put("do", "total_extra").put("minutes", n))
        if (out.status !in 200..299) return send(c.id, say(out, c))
        send(c.id, w("tg.more_done", "n" to n), remember(c, listOf(JSONObject().put("do", "total_extra").put("minutes", before).put("set", true))))
    }

    @Volatile private var askedAt = 0L

    /** A code screen opened by [how] ("pin", "totp", "recovery"; a parent's tap and the clients' unlock are in the history by their own). */
    fun unlocked(how: String, pkg: String) { history.add(clock(), null, "unlock $how " + pkg.take(40), "ok") }

    @Volatile private var wrongAt = 0L

    /** Three wrong codes in a row over [app] on [tv]: every Full chat hears it, whatever its alerts (it is about the child, not the TV), at most once in 5 minutes. */
    fun wrongCodes(app: String, tv: String) {
        if (!hasParent()) return
        val now = clock()
        synchronized(this) { if (wrongAt != 0L && now >= wrongAt && now - wrongAt < WRONG_GAP) return; wrongAt = now }
        val to = chats().filter { it.scope == FULL }
        val text = w("tg.wrong3", "tv" to tv, "time" to java.text.SimpleDateFormat("HH:mm").format(java.util.Date(now)), "app" to app)
        history.add(now, null, "alert wrong3", "ok", to.map { it.name })
        val job = Runnable { for (c in to) send(c.id, text) }
        if (background) speaker.execute { try { job.run() } catch (e: Exception) { } } else job.run()
    }

    /** Whether a chat could answer "ask a parent": the bot is on and a chat with the Full scope is paired. */
    fun hasParent(): Boolean = store.enabled && store.token.isNotEmpty() && chats().any { it.scope == FULL }

    /**
     * A child asks, on the TV's code screen, to open [pkg] ([app] by name; empty pkg: more time on the TV's total). Every Full chat gets the
     * question with buttons for 30 min / 1 h / today / no. "sent", "wait" (asked less than two minutes ago: a child pressing the button
     * ten times must not flood the parents) or "nochat". Blocks on the network: not for the main thread.
     */
    fun askParent(pkg: String, app: String, tv: String): String {
        if (!hasParent()) return "nochat"
        val now = clock()
        synchronized(this) { if (askedAt != 0L && now >= askedAt && now - askedAt < ASK_GAP) return "wait"; askedAt = now }
        val to = chats().filter { it.scope == FULL }
        val p = pkg.takeIf { it.length <= 50 && PKG.matches(it) } ?: ""
        val markup = keyboard(listOf(listOf(button(w("tg.btn_30"), "u:30:$p"), button(w("tg.btn_60"), "u:60:$p"), button(w("tg.btn_today"), "u:d:$p"), button(w("tg.btn_no"), "u:n"))))
        val text = if (p.isEmpty()) w("tg.ask_time", "tv" to tv) else w("tg.ask_app", "app" to app.ifEmpty { pkg }, "tv" to tv)
        history.add(now, null, "ask " + pkg.take(40), "ok", to.map { it.name })
        WireLog.all.add("→", "telegram ask a parent → ${to.size} chat(s)")
        for (c in to) send(c.id, text, markup)
        return "sent"
    }

    /** A tap on the question: "30:<pkg>", "60:<pkg>", "d:<pkg>" (until midnight) or "n". An empty pkg opens the whole TV for that long. */
    private fun grant(c: Chat, rest: String, qid: String, msg: JSONObject?, chat: Long) {
        if (c.scope != FULL) { answer(qid, ""); return send(c.id, w("telegram.rules_readonly")) }
        val what = rest.substringBefore(':'); val pkg = rest.substringAfter(':', "")
        val body = when (what) {
            "30", "60" -> JSONObject().put("do", "unlock").put("minutes", what.toInt())
            "d" -> JSONObject().put("do", "unlock").put("today", true)
            else -> null
        }
        msg?.optLong("message_id")?.let { edit(chat, it, JSONObject().put("inline_keyboard", JSONArray())) }   // answered once: the buttons go
        if (body == null) return answer(qid, w("tg.ask_declined"))
        if (pkg.isNotEmpty() && PKG.matches(pkg)) body.put("pkg", pkg)
        val out = post(c, "/api/rules", body)
        answer(qid, if (out.status in 200..299) w(if (what == "d") "tg.granted_today" else "tg.granted", "n" to what) else say(out, c))
        WireLog.all.add("·", "telegram ${c.name}: unlock $what ${pkg.ifEmpty { "(all)" }}")
    }

    private fun rangesText(r: JSONArray?) = (0 until (r?.length() ?: 0)).joinToString(", ") { r!!.getJSONObject(it).let { x -> x.optString("from") + "–" + x.optString("to") } }

    private fun bedtime(c: Chat, a: String) {
        val st = rules(c) ?: return send(c.id, w("telegram.tv_not_ready"))
        val b = st.optJSONObject("bedtime") ?: JSONObject()
        val on = b.optBoolean("on")
        val ranges = (b.optJSONArray("ranges") ?: JSONArray()).let { if (it.length() > 0) it else JSONArray().put(JSONObject().put("from", "22:00").put("to", "06:30")) }
        val want = when (a.lowercase(Locale.ROOT)) { "on" -> true; "off" -> false; else -> null }
        if (want != null) return if (c.scope != FULL) send(c.id, w("telegram.rules_readonly")) else setBedtime(c, want, on, ranges)
        val text = w(if (on) "telegram.bed_on" else "telegram.bed_off", "ranges" to rangesText(ranges))
        if (c.scope != FULL) return send(c.id, text + "\n" + w("telegram.rules_readonly"))
        send(c.id, text, keyboard(listOf(listOf(if (on) button(w("telegram.btn_bed_off"), "r:bed:off") else button(w("telegram.btn_bed_on"), "r:bed:on")))))
    }

    private fun setBedtime(c: Chat, want: Boolean, was: Boolean, ranges: JSONArray) {
        val out = post(c, "/api/rules", JSONObject().put("do", "bedtime").put("on", want).put("ranges", ranges))
        if (out.status !in 200..299) return send(c.id, say(out, c))
        send(c.id, w(if (want) "telegram.bed_now_on" else "telegram.bed_now_off", "ranges" to rangesText(ranges)),
            remember(c, listOf(JSONObject().put("do", "bedtime").put("on", was).put("ranges", ranges))))
    }

    private fun limits(c: Chat) {
        val st = rules(c) ?: return send(c.id, w("telegram.tv_not_ready"))
        val l = st.optJSONArray("limits") ?: JSONArray()
        val items = (0 until l.length()).mapNotNull { l.optJSONObject(it) }
        val text = if (items.isEmpty()) w("telegram.limits_none") else w("telegram.limits_title") + "\n" + items.joinToString("\n") {
            w("telegram.limit_line", "app" to it.optString("label").ifEmpty { appLabel(it.optString("pkg")) }, "min" to it.optInt("minutes"), "used" to it.optLong("used"),
                "extra" to (if (it.optInt("extra") > 0) " +" + it.optInt("extra") else ""))
        }
        rulesReply(c, st, text, items.map { it.optString("pkg") })
    }

    private fun schedule(c: Chat) {
        val st = rules(c) ?: return send(c.id, w("telegram.tv_not_ready"))
        val items = schedules(st)
        val text = if (items.isEmpty()) w("telegram.sched_none") else w("telegram.sched_title") + "\n" + items.joinToString("\n") {
            val name = it.optString("label").ifEmpty { appLabel(it.optString("pkg")) }
            if (it.optBoolean("block")) w("telegram.sched_blocked", "app" to name)
            else w("telegram.sched_line", "app" to name, "when" to windowsText(it), "cap" to (if (it.optInt("max") > 0) w("telegram.sched_cap", "n" to it.optInt("max")) else ""))
        }
        rulesReply(c, st, text, items.map { it.optString("pkg") })
    }

    private fun schedules(st: JSONObject): List<JSONObject> = st.optJSONArray("sched")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    private fun windowsText(s: JSONObject): String {
        val list = s.optJSONArray("windows") ?: return ""
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.joinToString(" · ") { x ->
            val d = x.optJSONArray("days")
            val days = if (d == null || d.length() == 7) w("telegram.every_day") else (0 until d.length()).joinToString(", ") { w("auto.day_${d.optInt(it)}") }
            "$days ${x.optString("from")}–${x.optString("to")}"
        }
    }

    /** The reply of /limits and /schedule: the words, then (FULL only) a block / unblock button for each app named and for the app on screen. */
    private fun rulesReply(c: Chat, st: JSONObject, text: String, named: List<String>) {
        if (c.scope != FULL) return send(c.id, text + "\n" + w("telegram.rules_readonly"))
        val blocked = schedules(st).filter { it.optBoolean("block") }.map { it.optString("pkg") }.toSet()
        val labels = HashMap<String, String>()
        for (k in listOf("limits", "sched")) st.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { labels[it.optString("pkg")] = it.optString("label") } }
        val b = hook.brief(try { state() } catch (e: Exception) { JSONObject() })
        val front = if (b.isNull("app")) "" else b.optString("app")
        if (front.isNotEmpty() && front != SELF) labels.getOrPut(front) { b.optString("app_label").ifEmpty { appLabel(front) } }
        val pkgs = (named + blocked + front).filter { it.isNotEmpty() && it != SELF && PKG.matches(it) && it.length <= 56 }.distinct()
        val rows = pkgs.map { p ->
            val name = labels[p]?.ifEmpty { null } ?: appLabel(p)
            if (p in blocked) button(w("telegram.btn_unblock", "app" to name), "r:unb:$p") else button(w("telegram.btn_block", "app" to name), "r:blk:$p")
        }
        send(c.id, text, if (rows.isEmpty()) null else keyboard(rows.map { listOf(it) }))
    }

    /** A tap on a rules button: "bed:on", "bed:off", "blk:<pkg>" or "unb:<pkg>". Each answers with what changed and an Undo button. */
    private fun change(c: Chat, what: String) {
        if (c.scope != FULL) return send(c.id, w("telegram.rules_readonly"))
        val kind = what.substringBefore(':'); val arg = what.substringAfter(':', "")
        val st = rules(c) ?: return send(c.id, w("telegram.tv_not_ready"))
        when (kind) {
            "bed" -> {
                val b = st.optJSONObject("bedtime") ?: JSONObject()
                val ranges = (b.optJSONArray("ranges") ?: JSONArray()).let { if (it.length() > 0) it else JSONArray().put(JSONObject().put("from", "22:00").put("to", "06:30")) }
                if (arg == "on" || arg == "off") setBedtime(c, arg == "on", b.optBoolean("on"), ranges)
            }
            "blk", "unb" -> {
                if (!PKG.matches(arg) || arg == SELF) return
                val before = schedules(st).firstOrNull { it.optString("pkg") == arg }
                val name = before?.optString("label")?.ifEmpty { null } ?: st.optJSONArray("limits")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("pkg") == arg }?.optString("label") }?.ifEmpty { null } ?: appLabel(arg)
                val block = kind == "blk"
                if (!block && before?.optBoolean("block") != true) return send(c.id, w("telegram.not_blocked", "app" to name))
                val body = if (block) JSONObject().put("do", "sched").put("pkg", arg).put("block", true) else JSONObject().put("do", "sched").put("pkg", arg).put("on", false)
                val out = post(c, "/api/rules", body)
                if (out.status !in 200..299) return send(c.id, say(out, c))
                val back = if (before == null) JSONObject().put("do", "sched").put("pkg", arg).put("on", false)
                    else if (before.optBoolean("block")) JSONObject().put("do", "sched").put("pkg", arg).put("block", true)
                    else JSONObject().put("do", "sched").put("pkg", arg).put("windows", before.optJSONArray("windows") ?: JSONArray()).put("max", before.optInt("max"))
                WireLog.all.add("·", "telegram ${c.name}: ${if (block) "blocked" else "unblocked"} $arg")
                send(c.id, w(if (block) "telegram.blocked_done" else "telegram.unblocked_done", "app" to name), remember(c, listOf(back)))
            }
        }
    }

    // ---------------------------------------------------------------- reaching the TV (in-process, with the chat's scope)
    private fun perform(c: Chat, what: String, value: String, scope: String = c.scope): HttpServer.Response =
        try { hook.perform(what, value) { route(it, scope) } } catch (e: IllegalArgumentException) { HttpServer.Response.error(400, e.message ?: "bad request") }
            .also { WireLog.all.add("→", "telegram $what ${if (what == "text") "…" else value} → ${it.status}"); judge(it) }

    private fun post(c: Chat, path: String, body: JSONObject): HttpServer.Response = hook.send({ route(it, c.scope) }, path, body).also { judge(it) }
    private fun get(c: Chat, path: String, query: Map<String, String> = emptyMap()): HttpServer.Response = hook.send({ route(it, c.scope) }, path, JSONObject(), "GET", query).also { judge(it) }

    /** What the command being answered came to, for the history: the worst answer it got from the core (one command at a time per thread). */
    private val outcome = object : ThreadLocal<String>() { override fun initialValue() = "ok" }   // withInitial is API 26: Android 7 TVs died here
    private fun judge(r: HttpServer.Response) { if (r.status == 403) outcome.set("scope") else if (r.status !in 200..299 && outcome.get() == "ok") outcome.set("error") }
    private fun body(out: HttpServer.Response): JSONObject? = if (out.status !in 200..299) null else try { JSONObject(String(out.body)) } catch (e: Exception) { null }

    // ---------------------------------------------------------------- talking to Telegram
    private fun edit(chat: Long, message: Long, markup: JSONObject) {
        try { api.call(store.token, "editMessageReplyMarkup", JSONObject().put("chat_id", chat).put("message_id", message).put("reply_markup", markup)) }
        catch (e: IOException) { WireLog.all.add("·", "telegram edit: " + clean(e.message ?: "")) }
    }

    private fun send(chat: Long, text: String, markup: JSONObject? = null) {
        val p = JSONObject().put("chat_id", chat).put("text", text.take(4000))
        if (markup != null) p.put("reply_markup", markup)
        try { api.call(store.token, "sendMessage", p) } catch (e: IOException) { WireLog.all.add("·", "telegram send: " + clean(e.message ?: "")) }
    }

    private fun answer(id: String, text: String) {
        try { api.call(store.token, "answerCallbackQuery", JSONObject().put("callback_query_id", id).put("text", text)) }
        catch (e: IOException) { WireLog.all.add("·", "telegram answer: " + clean(e.message ?: "")) }
    }

    private fun keypad(): JSONObject {
        fun b(t: String, k: String) = JSONObject().put("text", t).put("callback_data", "k:$k")
        val rows = listOf(
            listOf(b("🏠", "HOME"), b("⬆️", "UP"), b("↩️", "BACK")),
            listOf(b("⬅️", "LEFT"), b("OK", "OK"), b("➡️", "RIGHT")),
            listOf(b("🔉", "VOL_DOWN"), b("⬇️", "DOWN"), b("🔊", "VOL_UP")),
            listOf(b("🔇", "MUTE")),
        )
        return JSONObject().put("inline_keyboard", JSONArray(rows.map { JSONArray(it) }))
    }

    private fun commandList(): JSONArray = JSONArray(listOf(
        "help" to w("telegram.cmd_help"), "status" to w("telegram.cmd_status"), "off" to w("telegram.cmd_off"), "on" to w("telegram.cmd_on"),
        "vol" to w("telegram.cmd_vol"), "key" to w("telegram.cmd_key"), "open" to w("telegram.cmd_open"), "scene" to w("telegram.cmd_scene"),
        "timer" to w("telegram.cmd_timer"), "say" to w("telegram.cmd_say"), "speak" to w("telegram.cmd_speak"), "shot" to w("telegram.cmd_shot"), "remote" to w("telegram.cmd_remote"),
        "now" to w("telegram.cmd_now"), "apps" to w("telegram.cmd_apps"), "usage" to w("telegram.cmd_usage"), "channel" to w("telegram.cmd_channel"),
        "type" to w("telegram.cmd_type"), "scenes" to w("telegram.cmd_scenes"), "bedtime" to w("telegram.cmd_bedtime"), "limits" to w("telegram.cmd_limits"),
        "schedule" to w("telegram.cmd_schedule"), "alerts" to w("telegram.cmd_alerts"),
        "unlock" to w("tg.cmd_unlock"), "lock" to w("tg.cmd_lock"), "time" to w("tg.cmd_time"), "more" to w("tg.cmd_more"),
    ).map { JSONObject().put("command", it.first).put("description", it.second) })

    // ---------------------------------------------------------------- words, chats
    private fun lang() = Words.pick(store.lang.ifEmpty { Words.lang })
    private fun w(id: String, vararg a: Pair<String, Any?>) = Words.get(lang(), id, *a)

    fun chats(): List<Chat> = synchronized(this) {
        try {
            val a = JSONArray(store.chats.ifEmpty { "[]" })
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Chat(it.optLong("id"), it.optString("name"), scopeOf(it.optString("scope")), it.optLong("at"), alertsOf(it.optJSONArray("alerts")), it.optString("type"), it.optLong("seen")) }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(list: List<Chat>) = synchronized(this) {
        store.chats = JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("scope", it.scope).put("at", it.at).put("alerts", JSONArray(it.alerts.toList())).put("type", it.type).put("seen", it.seen) }).toString()
    }

    fun setScope(id: Long, scope: String): Boolean {
        if (scope !in SCOPES) return false
        val list = chats()
        if (list.none { it.id == id }) return false
        save(list.map { if (it.id == id) it.copy(scope = scope) else it })
        return true
    }

    /** The time of the chat's last command, kept for the owner's list; written at most every 30 s (prefs). */
    private fun touch(c: Chat) {
        val now = clock()
        if (now - c.seen < 30_000) return
        synchronized(this) { save(chats().map { if (it.id == c.id) it.copy(seen = now) else it }) }
    }

    fun forget(id: Long): Boolean { val list = chats(); save(list.filter { it.id != id }); return list.any { it.id == id } }

    private fun nameOf(chat: JSONObject): String =
        chat.optString("title").ifEmpty { listOf(chat.optString("first_name"), chat.optString("last_name")).filter { it.isNotEmpty() }.joinToString(" ") }
            .ifEmpty { chat.optString("username") }.ifEmpty { chat.optLong("id").toString() }.take(40)

    // ---------------------------------------------------------------- the owner's page (/api/telegram; Scope.FULL only, the router checks)
    /** The state for the page: the token is only ever shown masked. */
    fun json(): JSONObject {
        val t = store.token
        val o = JSONObject().put("on", store.enabled && t.isNotEmpty()).put("has_token", t.isNotEmpty()).put("status", status).put("lang", store.lang)
        if (t.isNotEmpty()) o.put("masked", mask(t)).put("bot", store.bot).put("error", lastError)
        val left = codeLeftS()
        val bot = store.bot.removePrefix("@")
        if (bot.isNotEmpty()) o.put("bot_link", "https://t.me/$bot")
        if (left > 0) { val c = codeNow() ?: ""; o.put("code", c).put("code_left", left); if (bot.isNotEmpty()) o.put("link", "https://t.me/$bot?start=$c") }   // /start <code>: pairs like /pair
        o.put("locked", lockedLeftS()).put("now", clock()).put("last_ok", lastOk)
        o.put("chats", JSONArray(chats().map { JSONObject().put("id", it.id).put("name", it.name).put("scope", it.scope).put("at", it.at).put("alerts", JSONArray(it.alerts.toList())).put("type", it.type).put("seen", it.seen) }))
        return o
    }

    fun config(r: HttpServer.Request): HttpServer.Response {
        if (r.method != "POST" && r.query["history"] != null) return HttpServer.Response.json(history.page(clock(), r.q("chat").toLongOrNull(), r.q("offset").toIntOrNull() ?: 0, HISTORY_PAGE))
        if (r.method == "POST") {
            val d = r.json()
            when (d.optString("do")) {
                "token" -> {
                    val t = d.optString("token").trim()
                    if (!TOKEN.matches(t)) return HttpServer.Response.error(400, "telegram: bad token")
                    val me = try { api.call(t, "getMe", JSONObject(), 10) } catch (e: IOException) { null }   // checked now so a typo is told at once; no network: kept, and tried again
                    if (me != null && (me.status == 401 || me.status == 404)) return HttpServer.Response.error(400, "telegram: bad token")
                    if (t != store.token) { store.chats = "[]"; store.offset = 0 }   // another bot: the old chats never met it
                    store.token = t
                    store.bot = me?.body?.optJSONObject("result")?.optString("username")?.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: ""
                    store.enabled = true
                }
                "on" -> { if (store.token.isEmpty()) return HttpServer.Response.error(400, "no token"); store.enabled = true }
                "off" -> { store.enabled = false; cancelCode() }
                "remove" -> { store.enabled = false; store.token = ""; store.bot = ""; store.chats = "[]"; store.offset = 0; cancelCode(); history.clear() }
                "code" -> { if (store.token.isEmpty() || !store.enabled) return HttpServer.Response.error(400, "off"); newCode() }
                "forget" -> if (!forget(d.optLong("id"))) return HttpServer.Response.error(400, "no such chat")
                "scope" -> if (!setScope(d.optLong("id"), d.optString("scope"))) return HttpServer.Response.error(400, "no such chat")
                "alerts" -> {                                       // {id, kind, on}: mute or unmute one kind of one chat, or all with kind ""
                    val id = d.optLong("id"); val kind = d.optString("kind")
                    if (chats().none { it.id == id } || (kind.isNotEmpty() && kind !in ALERTS)) return HttpServer.Response.error(400, "no such chat")
                    save(chats().map { if (it.id != id) it else it.copy(alerts = if (kind.isEmpty()) (if (d.optBoolean("on")) ALERTS.toSet() else emptySet()) else if (d.optBoolean("on")) it.alerts + kind else it.alerts - kind) })
                }
                "clear_history" -> history.clear()
                "lang" -> store.lang = d.optString("lang").takeIf { it == "vi" || it == "en" } ?: ""
                else -> return HttpServer.Response.error(400, "do: token, on, off, remove, code, forget, scope, alerts, clear_history or lang")
            }
            if (d.optString("do") in setOf("token", "on", "off", "remove", "lang")) sync()   // a code, a scope or a forgotten chat leaves the long poll alone
        }
        return HttpServer.Response.json(json())
    }

    private fun clean(s: String) = WireLog.clean(s).replace(Regex("\\d{6,}:[A-Za-z0-9_-]{20,}"), "…")

    companion object {
        const val REMOTE = "remote"
        const val FULL = "full"
        const val VIEW = "view"                         // /status and /shot only; the router refuses the rest
        private val SCOPES = listOf(FULL, REMOTE, VIEW)
        private fun scopeOf(s: String) = if (s in SCOPES) s else REMOTE
        /** What the TV may tell a chat by itself, in the order /alerts lists them: on, off (the screen), limit (an app closed: its minutes, a schedule or a block), bed, sleep (the timer), pair (a new device). */
        val ALERTS = listOf("on", "off", "limit", "bed", "sleep", "pair")
        private const val ALERT_GAP = 60_000L                  // one alert of a kind a minute: a flapping screen or a restarting app must not flood a chat
        private const val UNDO_MS = 60 * 60_000L
        private const val WRONG_GAP = 5 * 60_000L
        private const val ASK_GAP = 2 * 60_000L                  // "ask a parent": one question in two minutes
        private fun alertsOf(a: JSONArray?): Set<String> = if (a == null) emptySet() else (0 until a.length()).map { a.optString(it) }.filter { it in ALERTS }.toSet()
        const val POLL_S = 50
        private const val STALE_S = 120L
        private const val CODE_MS = 5 * 60_000L
        private const val MAX_WRONG = 5
        private const val LOCK_MS = 15 * 60_000L
        private const val MIN_GAP = 1000L                   // one command a second from one chat
        private const val MIN_GAP_TAP = 400L                // a keypad is tapped faster, but not without end
        private val ALLOWED = listOf("message", "callback_query")
        private val HIDDEN = setOf("say", "speak", "type", "pair", "start")      // commands whose words are never kept in a log or the history
        const val HISTORY_MAX = 200
        const val HISTORY_DAYS = 7
        const val HISTORY_PAGE = 20
        private val PAD = setOf("HOME", "UP", "BACK", "LEFT", "OK", "RIGHT", "VOL_DOWN", "DOWN", "VOL_UP", "MUTE")
        private val PKG = Regex("[A-Za-z0-9_.]{1,200}")
        private const val SELF = "dev.tvremote.setup"        // TV Setup itself: never offered to open or block
        val TOKEN = Regex("\\d{6,12}:[A-Za-z0-9_-]{30,60}")

        /** 5 s, 10, 20, 40, then 60 s at most; reset by the next good answer. */
        fun next(prev: Long): Long = if (prev <= 0) 5_000L else minOf(prev * 2, 60_000L)

        /** "/vol@MyBot 30" → vol, "30". null: not a command, or one for another bot. */
        fun parse(text: String, bot: String): Cmd? {
            val t = text.trim()
            if (!t.startsWith("/") || t.length < 2) return null
            val head = t.substring(1).substringBefore(' ').substringBefore('\n')
            val arg = t.substring(1 + head.length).trim()
            val name = head.substringBefore('@').lowercase(Locale.ROOT)
            val to = head.substringAfter('@', "")
            if (to.isNotEmpty() && bot.isNotEmpty() && !to.equals(bot.removePrefix("@"), ignoreCase = true)) return null
            if (!name.matches(Regex("[a-z0-9_]{1,32}"))) return null
            return Cmd(name, arg)
        }

        /** "123456789:••••wXyZ": enough to tell which bot, never the secret. */
        fun mask(t: String) = t.substringBefore(':') + ":••••" + t.takeLast(4)
    }

    /**
     * What the bot did, newest last in storage: {t, chat (id, 0 for the TV's own alerts), name, cmd, res: ok | scope | error, to (names, alerts)}. At most
     * [HISTORY_MAX] entries and [HISTORY_DAYS] days, kept in [Store.history]. A command's words are not here (see [HIDDEN]): /type and /say are only named.
     */
    class History(private val store: Store, private val clock: () -> Long) {
        private var cache: JSONArray? = null
        @Synchronized private fun load(): JSONArray = cache ?: (try { JSONArray(store.history.ifEmpty { "[]" }) } catch (e: Exception) { JSONArray() }).also { cache = it }

        @Synchronized fun add(now: Long, chat: Chat?, cmd: String, res: String, to: List<String> = emptyList()) {
            val e = JSONObject().put("t", now).put("chat", chat?.id ?: 0L).put("name", chat?.name ?: "").put("cmd", cmd.take(60)).put("res", res)
            if (to.isNotEmpty()) e.put("to", JSONArray(to))
            val keep = ArrayList<JSONObject>()
            val a = load()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (now - it.optLong("t") < HISTORY_DAYS * 86_400_000L) keep.add(it) }
            keep.add(e)
            val out = JSONArray(keep.takeLast(HISTORY_MAX))
            cache = out
            store.history = out.toString()
        }

        /** Newest first, [size] from [offset], for one chat when [chat] is given; {total, items}. Expired entries are left out even before the next write. */
        @Synchronized fun page(now: Long, chat: Long?, offset: Int, size: Int): JSONObject {
            val a = load()
            val all = (a.length() - 1 downTo 0).mapNotNull { a.optJSONObject(it) }.filter { now - it.optLong("t") < HISTORY_DAYS * 86_400_000L && (chat == null || it.optLong("chat") == chat) }
            return JSONObject().put("total", all.size).put("items", JSONArray(all.drop(offset.coerceAtLeast(0)).take(size)))
        }

        @Synchronized fun clear() { cache = JSONArray(); store.history = "[]" }
    }

    /** At most one thing per [gap] ms per chat. */
    class Limiter(private val gap: Long) {
        private val last = HashMap<Long, Long>()
        @Synchronized fun ok(key: Long, now: Long): Boolean {
            if (last.size > 512) last.entries.removeAll { now - it.value > 60_000 }
            val before = last[key]
            if (before != null && now - before < gap && now >= before) return false
            last[key] = now
            return true
        }
    }
}
