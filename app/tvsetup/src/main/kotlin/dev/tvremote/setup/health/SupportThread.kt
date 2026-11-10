package dev.tvremote.setup.health

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.tvremote.core.T
import dev.tvremote.core.SupportAndroid
import dev.tvremote.core.ontv.Notify
import dev.tvremote.setup.NotifyLayer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Support's replies to a "Send to support" (the census worker's thread, `SupportRelay.thread`). Runs only while a code is stored:
 * no code, no timer. Asks 2 minutes after start and then every 6 hours, by the looper's own clock (a TV's wall clock jumps); the
 * worker's 403 (the code is gone, 14 days after the last word) ends it for good. A new reply shows a card ([NotifyLayer]) once and
 * stays unread for the support screen (Connection → Support replies) until it is opened. The thread is kept in the `support` prefs
 * so a restart neither forgets it nor shows a card again.
 */
object SupportThread {
    private const val FIRST = 2 * 60_000L
    private const val EVERY = 6 * 3600_000L
    private const val KEEP = 60                                       // messages kept on the TV
    private val ui by lazy { Handler(Looper.getMainLooper()) }       // lazy: the pure parts run in unit tests
    @Volatile private var app: Context? = null
    @Volatile private var scheduled = false

    private val tick = Runnable { go() }

    private fun store(c: Context) = SupportAndroid.store(c)

    /** The code stored: asking is on. */
    fun on(c: Context) = !store(c).get("code").isNullOrEmpty()

    /** At start and after a send: schedules the first ask when there is a code, else nothing runs. */
    fun start(c: Context) {
        app = c.applicationContext
        if (!on(c) || scheduled) return
        scheduled = true
        ui.postDelayed(tick, FIRST)
    }

    fun stop() { scheduled = false; ui.removeCallbacks(tick) }

    private fun go() {
        val c = app ?: return
        Thread({
            val again = try { once(c) } catch (e: Exception) { true }
            ui.post { if (again && scheduled) ui.postDelayed(tick, EVERY) else scheduled = false }
        }, "support-thread").apply { isDaemon = true }.start()
    }

    /** One ask; false when asking is over (no code, or the worker forgot it). Offline: true, the next tick tries again. */
    internal fun once(c: Context): Boolean {
        val relay = Support.relay(c)
        if (!on(c)) return false
        val s = store(c)
        val r = relay.thread(lastId(c)) ?: return true
        if (r.optInt("error") == 403) { forget(c); return false }
        val got = merge(msgs(c), r.optJSONArray("msgs"))
        s.put("thread", got.toString())
        val fresh = lastUs(got, s.get("carded")?.toIntOrNull() ?: 0)
        if (fresh != null) {
            s.put("carded", fresh.optInt("id").toString())
            NotifyLayer.show(c, Notify.clean(JSONObject().put("title", T("support.replied")).put("seconds", 25)
                .put("text", cut(fresh.optString("text").replace(Regex("\\s+"), " ")) + "\n" + T("support.replied_where"))))
        }
        return true
    }

    private fun forget(c: Context) { store(c).let { it.put("thread", ""); it.put("carded", ""); it.put("read", "") } }

    /** The messages kept, oldest first; each `{id, who, text, at}`. */
    fun msgs(c: Context): JSONArray = try { JSONArray(store(c).get("thread").orEmpty().ifEmpty { "[]" }) } catch (e: Exception) { JSONArray() }

    private fun lastId(c: Context): Int = msgs(c).let { if (it.length() == 0) 0 else it.getJSONObject(it.length() - 1).optInt("id") }

    /** The unread replies: any "us" message after the last one the support screen showed. */
    fun unread(c: Context): Boolean = lastUs(msgs(c), store(c).get("read")?.toIntOrNull() ?: 0) != null

    /** The support screen was opened: everything shown is read. */
    fun markRead(c: Context) {
        val last = msgs(c).let { m -> (0 until m.length()).map { m.getJSONObject(it) }.filter { it.optString("who") == "us" }.maxOfOrNull { it.optInt("id") } } ?: return
        store(c).put("read", last.toString())
    }

    fun pageUrl(c: Context): String? = Support.relay(c).pageUrl()

    /** The newest "us" message with an id above [above], or null. */
    internal fun lastUs(m: JSONArray, above: Int): JSONObject? =
        (0 until m.length()).map { m.getJSONObject(it) }.filter { it.optString("who") == "us" && it.optInt("id") > above }.maxByOrNull { it.optInt("id") }

    /** [old] plus the ones of [new] with a higher id, the last [KEEP]; the text only, as plain text (never read as HTML). */
    internal fun merge(old: JSONArray, new: JSONArray?): JSONArray {
        val list = ArrayList<JSONObject>()
        for (i in 0 until old.length()) list.add(old.getJSONObject(i))
        var top = list.maxOfOrNull { it.optInt("id") } ?: 0
        for (i in 0 until (new?.length() ?: 0)) {
            val m = new!!.getJSONObject(i)
            if (m.optInt("id") <= top) continue
            top = m.optInt("id")
            list.add(JSONObject().put("id", top).put("who", if (m.optString("who") == "us") "us" else "tv").put("text", m.optString("text").take(4000)).put("at", m.optLong("at")))
        }
        return JSONArray(list.takeLast(KEEP))
    }

    /** About 110 characters of a reply for the card (NotifyLayer shows 6 lines: the words after it say where to read the rest), cut at a space where there is one. */
    internal fun cut(s: String): String {
        val t = s.trim()
        if (t.length <= 110) return t
        val at = t.lastIndexOf(' ', 110).takeIf { it > 70 } ?: 110
        return t.take(at).trimEnd() + "…"
    }
}
