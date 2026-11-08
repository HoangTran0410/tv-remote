package dev.tvremote.setup.rules

import android.content.Context
import dev.tvremote.core.ontv.Notify
import dev.tvremote.setup.NotifyLayer
import dev.tvremote.setup.NotifySpeak
import org.json.JSONArray
import org.json.JSONObject

/**
 * A message the page asked to show later ("remind me in 10 min", `delay` in seconds on the notify request), kept here on the TV so the
 * phone and the computer need not be on. No alarm, receiver or thread of its own: [Guard]'s check asks [tick] and [any] makes a pending
 * one a reason for it to run; with none pending nothing here runs. Kept in prefs, so it survives TV Setup being killed.
 * Like a timed scene it is never shown late in a pile ([LATE_MS]), and one due further away than any delay can be (the clock went
 * back after a boot with a wrong year) is dropped. Cases: dev/flows/scene-timer.md (row 24).
 */
internal object NotifyLater {
    private const val PREFS = "notifylater"
    const val LATE_MS = 10 * 60_000L
    private const val MAX = 20                                           // pending at once: more is a mistake, not a reminder

    /** What [step] decided: the messages to show now, those still waiting (with their due time), and ms until the next one (null: none). */
    class Step(val fire: List<JSONObject>, val keep: List<Pair<Long, JSONObject>>, val wait: Long?)

    /** Pure: [items] are (due ms, message). */
    fun step(items: List<Pair<Long, JSONObject>>, now: Long): Step {
        val fire = ArrayList<JSONObject>()
        val keep = ArrayList<Pair<Long, JSONObject>>()
        for ((due, m) in items) {
            when {
                due <= now -> if (now - due <= LATE_MS) fire.add(m)         // older: the TV was off, skipped
                due - now <= Notify.MAX_DELAY * 1000L + 60_000L -> keep.add(due to m)
            }                                                                // else the clock went back: dropped
        }
        return Step(fire, keep, keep.minOfOrNull { it.first - now })
    }

    private fun load(c: Context): List<Pair<Long, JSONObject>> {
        val a = try { JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> o.optLong("due") to o.optJSONObject("msg")!! } }
    }

    private fun save(c: Context, items: List<Pair<Long, JSONObject>>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("due", it.first).put("msg", it.second)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", a.toString()).commit()
    }

    fun any(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]").orEmpty().length > 2

    /** Keeps [msg] (already [Notify.clean]ed, with its "delay" > 0) to show in that many seconds, and wakes [Guard]. */
    @Synchronized
    fun add(c: Context, msg: JSONObject, now: Long = System.currentTimeMillis()) {
        val m = JSONObject(msg.toString()).also { it.remove("delay") }
        val items = (load(c) + ((now + msg.optInt("delay") * 1000L) to m)).sortedBy { it.first }.takeLast(MAX)
        save(c, items)
        Guard.poke()
    }

    /** Called by [Guard]'s check: shows what is due; says in how many ms to look again, or null with nothing pending. */
    @Synchronized
    fun tick(c: Context, now: Long): Long? {
        if (!any(c)) return null
        val s = step(load(c), now)
        save(c, s.keep)
        for (m in s.fire) {
            NotifyLayer.show(c, m)
            if (m.optBoolean("speak")) try { NotifySpeak.speak(c, m.optString("title"), m.optString("text"), m.optString("lang")) { _, _ -> } } catch (e: Exception) { }
        }
        return s.wait
    }
}
