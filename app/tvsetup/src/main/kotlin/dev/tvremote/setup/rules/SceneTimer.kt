package dev.tvremote.setup.rules

import android.content.Context
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.setup.runNow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scenes with a "start at" ({time, days} weekly or {time, date} once, see [Scenes]) kept and started here on the TV, so the phone and
 * the computer need not be on. No alarm, receiver or thread of its own: [Guard]'s check asks [tick] (it already runs while any rule is
 * on; [any] makes a timed scene one of them), and with no timed scene [any] is false and nothing here runs.
 *
 * A time cursor per scene, not a list of past runs: a scene starts when its next time after the cursor has come and is at most
 * [GRACE_MS] old. Whatever is older (the TV was off, or its clock jumped forward after a boot with a wrong year) is skipped, never
 * run late in a pile; a clock that jumps back moves the cursor back with it, so the scene is not stuck waiting for the old time.
 */
internal object SceneTimer {
    private const val PREFS = "scenetimer"
    const val GRACE_MS = 5 * 60_000L

    /** What [tick] decided. [fire]: scenes to run now; [wait]: ms until the next start (null: nothing timed); [cursors]: the new cursors to keep. */
    class Step(val fire: List<String>, val wait: Long?, val cursors: Map<String, Pair<String, Long>>)

    /**
     * Pure: [scenes] id to its "at" (as text), [cursors] id to (that text, last looked at ms) as kept, [now] the TV's clock. A scene whose "at"
     * changed (or is new) starts counting from [now]; a cursor in the future (the clock went back) is pulled back to [now].
     */
    fun step(scenes: Map<String, JSONObject>, cursors: Map<String, Pair<String, Long>>, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Step {
        val fire = ArrayList<String>()
        val out = HashMap<String, Pair<String, Long>>()
        var wait: Long? = null
        for ((id, at) in scenes) {
            val sig = at.toString()
            var cursor = cursors[id]?.takeIf { it.first == sig }?.second ?: now
            if (cursor > now) cursor = now
            val due = Scenes.nextAt(at, cursor, zone)
            if (due != null && due <= now) {
                if (now - due <= GRACE_MS) fire.add(id)
                cursor = now                                              // fired or too late to fire: either way it is behind us
            }
            out[id] = sig to cursor
            Scenes.nextAt(at, cursor, zone)?.let { n -> wait = minOf(wait ?: Long.MAX_VALUE, n - now) }
        }
        return Step(fire, wait, out)
    }

    @Synchronized
    private fun scenes(c: Context): JSONArray = try { JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("scenes", "[]")) } catch (e: Exception) { JSONArray() }

    /** Something here needs [Guard] to keep running. */
    fun any(c: Context): Boolean = scenes(c).length() > 0

    /** Keeps the scenes with a time out of [list] (cleaned) and drops the rest; the scenes that are gone lose their cursors. Answers whether any is timed. */
    @Synchronized
    fun set(c: Context, list: JSONArray): Boolean {
        val timed = JSONArray()
        for (i in 0 until list.length()) list.optJSONObject(i)?.takeIf { it.has("at") }?.let { timed.put(it) }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit().putString("scenes", timed.toString())
        if (timed.length() == 0) e.remove("cursors")
        e.commit()
        Guard.poke()
        return timed.length() > 0
    }

    /** Called by [Guard]'s check: starts the scenes that are due and says in how many ms to look again (null: nothing timed). */
    @Synchronized
    fun tick(c: Context, now: Long): Long? {
        val list = scenes(c)
        if (list.length() == 0) return null
        val byId = LinkedHashMap<String, JSONObject>()
        for (i in 0 until list.length()) list.optJSONObject(i)?.let { byId[it.optString("id")] = it }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kept = try { JSONObject(p.getString("cursors", "{}")) } catch (e: Exception) { JSONObject() }
        val cursors = HashMap<String, Pair<String, Long>>()
        for (id in kept.keys()) kept.optJSONObject(id)?.let { cursors[id] = it.optString("at") to it.optLong("t") }
        val s = step(byId.mapValues { it.value.getJSONObject("at") }, cursors, now)
        val save = JSONObject()
        for ((id, v) in s.cursors) save.put(id, JSONObject().put("at", v.first).put("t", v.second))
        if (save.toString() != kept.toString()) p.edit().putString("cursors", save.toString()).commit()
        for (id in s.fire) byId[id]?.let { runNow(c, it) }
        return s.wait
    }
}
