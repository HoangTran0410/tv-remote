package dev.tvremote.setup.rules

import android.content.Context
import android.content.SharedPreferences
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.setup.runNow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scenes with a "start at" ({time, days} weekly or {time, date} once, see [Scenes]) kept and started here on the TV, so the phone and
 * the computer need not be on. No alarm, receiver or thread of its own: [Guard]'s check asks [tick] (it already runs while any rule is
 * on; [any] makes a timed scene one of them), and with no timed scene [any] is false and nothing here runs. Cases: dev/flows/scene-timer.md.
 *
 * A time cursor per scene, not a list of past runs: a scene starts when its next time after the cursor has come and is at most
 * [GRACE_MS] old. Whatever is older (the TV was off, or its clock jumped forward after a boot with a wrong year) is skipped, never
 * run late in a pile; a clock that jumps back moves the cursor back with it, so the scene is not stuck waiting for the old time.
 * A run that finds no ADB puts its cursor back ([retry]), so a later check tries again while still inside the grace.
 */
internal object SceneTimer {
    private const val PREFS = "scenetimer"
    const val GRACE_MS = 5 * 60_000L

    /** What [tick] decided. [fire]: scenes to run now; [due]: each one's start time; [wait]: ms until the next start (null: nothing timed); [cursors]: the new cursors to keep. */
    class Step(val fire: List<String>, val wait: Long?, val cursors: Map<String, Pair<String, Long>>, val due: Map<String, Long> = emptyMap())

    /**
     * Pure: [scenes] id to its "at" (as text), [cursors] id to (that text, last looked at ms) as kept, [now] the TV's clock. A scene whose "at"
     * changed (or is new) starts counting from [now]; a cursor in the future (the clock went back) is pulled back to [now].
     */
    fun step(scenes: Map<String, JSONObject>, cursors: Map<String, Pair<String, Long>>, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Step {
        val fire = ArrayList<String>()
        val dues = HashMap<String, Long>()
        val out = HashMap<String, Pair<String, Long>>()
        var wait: Long? = null
        for ((id, at) in scenes) {
            val sig = at.toString()
            var cursor = cursors[id]?.takeIf { it.first == sig }?.second ?: now
            if (cursor > now) cursor = now
            val due = Scenes.nextAt(at, cursor, zone)
            if (due != null && due <= now) {
                if (now - due <= GRACE_MS) { fire.add(id); dues[id] = due }
                cursor = now                                              // fired or too late to fire: either way it is behind us
            }
            out[id] = sig to cursor
            Scenes.nextAt(at, cursor, zone)?.let { n -> wait = minOf(wait ?: Long.MAX_VALUE, n - now) }
        }
        return Step(fire, wait, out, dues)
    }

    /**
     * Pure: the run of scene [id] due at [due] could not start (no ADB yet: the link in its backoff, or not back after standby). Its cursor
     * goes back to just before [due] while its "at" is still [sig], so [step] starts it again on a later check inside [GRACE_MS]; past
     * that it is skipped as any late run. Null: nothing to put back (the scene was changed or removed meanwhile).
     */
    fun retry(cursors: Map<String, Pair<String, Long>>, id: String, sig: String, due: Long): Map<String, Pair<String, Long>>? {
        val c = cursors[id] ?: return null
        if (c.first != sig) return null
        return cursors + (id to (sig to minOf(c.second, due - 1)))
    }

    @Synchronized
    private fun scenes(c: Context): JSONArray = try { JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("scenes", "[]")) } catch (e: Exception) { JSONArray() }

    private fun load(p: SharedPreferences): Map<String, Pair<String, Long>> {
        val kept = try { JSONObject(p.getString("cursors", "{}")) } catch (e: Exception) { JSONObject() }
        val cursors = HashMap<String, Pair<String, Long>>()
        for (id in kept.keys()) kept.optJSONObject(id)?.let { cursors[id] = it.optString("at") to it.optLong("t") }
        return cursors
    }

    private fun text(cursors: Map<String, Pair<String, Long>>): String {
        val o = JSONObject()
        for ((id, v) in cursors) o.put(id, JSONObject().put("at", v.first).put("t", v.second))
        return o.toString()
    }

    private val starting = HashSet<String>()                            // runs still waiting for ADB: a check meanwhile starts no second one

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
        val s = step(byId.mapValues { it.value.getJSONObject("at") }, load(p), now)
        val out = text(s.cursors)
        if (out != p.getString("cursors", "{}")) p.edit().putString("cursors", out).commit()
        for (id in s.fire) {
            val scene = byId[id] ?: continue
            val sig = s.cursors[id]?.first ?: continue
            val due = s.due[id] ?: continue
            if (!starting.add(id)) continue
            runNow(c, scene, started = { done(id) }) { noAdb(c, id, sig, due) }
        }
        return s.wait
    }

    @Synchronized
    private fun done(id: String) { starting.remove(id) }

    /** [runNow] found no ADB for scene [id]: its cursor goes back so a later check tries again. Answers whether another try will come. */
    @Synchronized
    private fun noAdb(c: Context, id: String, sig: String, due: Long): Boolean {
        starting.remove(id)
        if (System.currentTimeMillis() - due >= GRACE_MS) return false
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val back = retry(load(p), id, sig, due) ?: return false
        p.edit().putString("cursors", text(back)).commit()
        return true
    }
}
