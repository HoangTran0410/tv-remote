package dev.tvremote.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Support through the census worker (dev/census/src/support.js), the same for TV Setup, the Phone app and the Computer app (the
 * user, 2026-10-29: a phone that lost its TVs mid-way could not be looked at from afar). [send]: the app's facts go up on a
 * press and come back as an 8-digit code to read out; [start]: for [MINUTES] the owner may ask (`dev/census/support.py CODE
 * /api/...`), the app asks the worker every 2 s and answers each question through [answer], which decides what may be read
 * (read-only, its own). The app restarting (TV Setup updates itself quietly, a weak box or Android kills it) picks the same
 * code up again ([resume]); Stop, or the time up, ends it for good.
 */
class SupportRelay(
    private val app: String,                                         // "setup", "phone", "pc": the worker's list says which
    private val version: String,
    private val model: String,
    private val store: Census.Store,                                 // code, key, live_code, live_key, live_until
    private val report: () -> String,
    private val answer: (path: String) -> Pair<Int, String>,
) {
    /** Told when looking starts (also after a restart) and when it ends: TV Setup lists a pass among the paired devices meanwhile. */
    var onOpen: (code: String) -> Unit = {}
    var onEnd: () -> Unit = {}

    @Volatile private var thread: Thread? = null
    @Volatile var until = 0L; private set
    @Volatile var code = ""; private set
    @Volatile var asked = 0; private set
    @Volatile private var lastKey = ""
    @Volatile private var liveKey = ""                             // the open code's key: a later send must not make Stop close another
    /** Sends today has left after the last one, as the worker counts them (20 a day); -1 not known, 0 with no code: none left. */
    @Volatile var left = -1; private set

    fun open() = until > System.currentTimeMillis() && thread != null

    /** Minutes left while open, else 0. */
    fun minutesLeft() = if (open()) ((until - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(0) else 0L

    private fun base() = Census.url.trimEnd('/') + "/v1/support"

    /** Sends the report; the code to read out, or null when the worker cannot be reached (or today's sends are used up: [left] 0). */
    fun send(said: Said = Said.NONE): String? {
        val body = JSONObject().put("id", Census.id().ifEmpty { java.util.UUID.randomUUID().toString() }).put("app", app).put("v", version)
            .put("model", model).put("report", said.header() + fit(try { report() } catch (e: Exception) { "report: $e" }, 56_000, tail = true))
        left = -1
        val r = post(base(), body) ?: return null
        left = r.optInt("left", -1)
        val got = r.optString("code").takeIf { it.length == 8 } ?: return null
        store.put("code", got); store.put("key", r.optString("key"))
        lastKey = r.optString("key")
        if (!open()) code = got                                       // while support looks, Stop is about that code, not this one
        return got
    }

    /** Lets the owner read for [MINUTES], with a fresh report first (the facts of now with the code). Blocks: off the main thread. */
    fun start(said: Said = Said.NONE): String? {
        val code = send(said) ?: return null
        val key = lastKey
        val r = post("${base()}/live", JSONObject().put("code", code).put("key", key).put("minutes", MINUTES))?.takeIf { it.has("live") } ?: return null
        asked = 0
        run(code, key, ends(r, System.currentTimeMillis() + MINUTES * 60_000L))
        WireLog.all.add("·", "support open $code for $MINUTES min")
        return code
    }

    private fun run(code: String, key: String, end: Long) {
        stopThread()
        until = end; this.code = code; liveKey = key
        store.put("live_code", code); store.put("live_key", key); store.put("live_until", end.toString())
        onOpen(code)
        val t = Thread({ loop(code, key) }, "support").apply { isDaemon = true }
        thread = t; t.start()                                         // assigned first: the loop runs only while it is [thread]
    }

    /**
     * At start: support that was open when the app stopped goes on with the same code (an update mid-session left the owner
     * asking a TV that had quietly stopped); false when there was none. The worker's answer to the first poll sets the end again.
     */
    fun resume(): Boolean {
        if (open()) return true
        val code = store.get("live_code").orEmpty(); val key = store.get("live_key").orEmpty(); val end = store.get("live_until")?.toLongOrNull() ?: 0
        if (code.isEmpty() || end <= System.currentTimeMillis()) return false
        run(code, key, end)
        WireLog.all.add("·", "support goes on after a restart, $code")
        return true
    }

    /** Stops at once: the worker is told (best effort, its own thread). */
    fun stop() {
        val code = this.code.ifEmpty { store.get("code").orEmpty() }; val key = liveKey.ifEmpty { store.get("key").orEmpty() }
        until = 0
        stopThread()
        store.put("live_until", "0")
        onEnd()
        if (code.isNotEmpty()) Thread({ post("${base()}/live", JSONObject().put("code", code).put("key", key).put("minutes", 0)) }, "support-stop").apply { isDaemon = true }.start()
    }

    /** What the page and the apps show: open or not, the code, the minutes left. */
    fun state(): JSONObject = JSONObject().put("open", open()).put("code", if (open()) code else "").put("min", minutesLeft()).put("left", left)

    private fun stopThread() { thread?.interrupt(); thread = null }

    private fun loop(code: String, key: String) {
        val me = Thread.currentThread()
        try {
            while (thread === me && System.currentTimeMillis() < until) {
                val r = post("${base()}/poll", JSONObject().put("code", code).put("key", key))
                if (r != null && r.has("error")) break                // the code is gone (deleted, expired): nobody can ask any more
                if (r != null) {
                    if (r.has("live")) until = ends(r, until)
                    val asks = r.optJSONArray("asks")
                    for (i in 0 until (asks?.length() ?: 0)) {
                        val a = asks!!.getJSONObject(i)
                        val path = a.optString("path")
                        val (status, text) = try { answer(path) } catch (e: Exception) { 0 to (e.message ?: e.javaClass.simpleName) }
                        val body = fit(text, MAX_ANSWER, tail = path.contains("log"))
                        asked++
                        WireLog.all.add("·", "support GET ${path.take(120)} -> $status")
                        val out = JSONObject().put("code", code).put("key", key).put("id", a.optInt("id")).put("status", status).put("body", body)
                        // a slow link or a busy box lost one answer of 12 KB (a Viettel ZTE box, 2026-10-29): once more before giving up
                        val sent = post("${base()}/answer", out) ?: run { Thread.sleep(2000); post("${base()}/answer", out) }
                        if (sent == null || sent.has("error"))           // refused or lost: say so, or the owner waits for nothing
                            post("${base()}/answer", JSONObject().put("code", code).put("key", key).put("id", a.optInt("id")).put("status", 0).put("body", "answer not delivered ($status, ${body.length} chars)"))
                    }
                }
                Thread.sleep(2000)
            }
        } catch (e: InterruptedException) { return }
        if (thread === me) { thread = null; until = 0; store.put("live_until", "0"); onEnd() }
    }

    /** The end of support by this device's clock: the worker's `live` is its own time, and a TV's clock may be half an hour off. */
    private fun ends(r: JSONObject, or: Long): Long {
        val live = r.optLong("live"); val now = r.optLong("now")
        return if (live <= 0) 0 else if (now > 0) System.currentTimeMillis() + (live - now) else or
    }

    private fun post(to: String, body: JSONObject): JSONObject? = try {
        val c = URL(to).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 20000                  // an answer of 200 KB from a slow box
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode == 403 || c.responseCode == 404 || c.responseCode == 429) JSONObject().put("error", c.responseCode).put("left", 0)   // refused: not a network hiccup to wait out
            else if (c.responseCode !in 200..299) null
            else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    /**
     * What the person said went wrong, asked before a send (census 2026-11: 34 of the last 50 reports had no words): one of
     * [PROBLEMS] and a few words, both optional. It travels as the first lines of the report (the worker keeps the report as
     * text, so it needs no new field) and `dev/census/support.py` shows them first.
     */
    class Said(problem: String = "", text: String = "") {
        val problem = problem.takeIf { it in PROBLEMS } ?: ""
        val text = Scrub.text(text.replace(Regex("\\s+"), " ").trim().take(280), keepIps = true)
        fun header(): String = (if (problem.isNotEmpty()) "[said] problem: $problem\n" else "") + (if (text.isNotEmpty()) "[said] text: $text\n" else "")
        companion object {
            val NONE = Said()
            /** From a request's JSON (`problem`, `text`); missing = nothing said. */
            fun of(j: JSONObject?) = if (j == null) NONE else Said(j.optString("problem"), j.optString("text"))
        }
    }

    companion object {
        /** The ids of the ask (words `said.<id>`); the page's chips (js/core.js SAID_PROBLEMS) are the same list. */
        val PROBLEMS = listOf("connect", "key", "app", "media", "other")
        const val MINUTES = 30
        const val MAX_ANSWER = 200_000                                // bytes as sent; the worker keeps 256 KB (src/support.js MAX_ANSWER)

        /** [s] cut to at most [bytes] as it travels (JSON-escaped UTF-8: Vietnamese and quotes grow), keeping its end or its start. */
        fun fit(s: String, bytes: Int, tail: Boolean): String {
            var t = s
            while (JSONObject.quote(t).toByteArray().size > bytes) t = if (tail) t.takeLast(t.length * 4 / 5) else t.take(t.length * 4 / 5)
            return t
        }

        /** One GET on an app's own server, as its page would, with [auth] set on the request; the answer scrubbed. */
        fun get(url: String, auth: (HttpURLConnection) -> Unit): Pair<Int, String> = try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 3000; c.readTimeout = 25000
                auth(c)
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                code to Scrub.blank(text)
            } finally { c.disconnect() }
        } catch (e: Exception) { 0 to (e.message ?: e.javaClass.simpleName) }
    }
}
