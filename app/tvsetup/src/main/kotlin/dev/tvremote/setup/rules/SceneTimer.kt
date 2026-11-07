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
 *
 * A scene with "on" ({app} / {power}) starts on an event instead of a time. An app coming to the front is read by the same [tick] from
 * the usage events since its last look ([Usage.events]: the one reader that survives a wrong clock), only while such a scene exists
 * and the screen is on; the TV turning on is [onPower], called from the screen-on receiver TV Setup already has. [decideApps] is the
 * pure part: one run per coming to the front, never for events inside the scene's own run (a scene that opens its own app).
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

    /** An app that came to the front ([ts] ms). */
    class Open(val pkg: String, val ts: Long)

    /** [fire]: scenes to run; [front]: the app in front after the events. */
    class Decision(val fire: List<String>, val front: String)

    /**
     * Pure: [apps] scene id to the app that starts it, [opens] the foreground events in order, [front] the app in front before them.
     * An event of the app already in front (another screen of it) is no new start; one older than [GRACE_MS] is skipped (the TV was off);
     * one inside [quiet] (the scene's own run, ms range) never starts that scene again. A scene runs at most once per call.
     */
    fun decideApps(apps: Map<String, String>, opens: List<Open>, front: String, quiet: Map<String, LongRange>, now: Long): Decision {
        val fire = ArrayList<String>()
        var f = front
        for (e in opens) {
            if (e.pkg == f) continue
            f = e.pkg
            if (now - e.ts > GRACE_MS) continue
            for ((id, pkg) in apps) if (pkg == e.pkg && id !in fire && quiet[id]?.contains(e.ts) != true) fire.add(id)
        }
        return Decision(fire, f)
    }

    /** Pure: of the scenes that start when the TV turns on, those not inside their own run ([quiet]). */
    fun decidePower(ids: Collection<String>, quiet: Map<String, LongRange>, now: Long): List<String> = ids.filter { quiet[it]?.contains(now) != true }

    private const val QUIET_AFTER_MS = 5_000L                          // a scene's own events can be a moment behind its last step
    private val quiet = HashMap<String, LongRange>()                   // scene id to when its own run is going on (wall ms; open-ended while it runs)
    private var evTo = 0L                                              // usage events up to here are read (0: not yet looked)
    private var front = ""

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

    /** Keeps the scenes with a time or an event out of [list] (cleaned) and drops the rest; the scenes that are gone lose their cursors. Answers whether any is kept. */
    @Synchronized
    fun set(c: Context, list: JSONArray): Boolean {
        val timed = JSONArray()
        for (i in 0 until list.length()) list.optJSONObject(i)?.takeIf { it.has("at") || it.has("on") }?.let { timed.put(it) }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit().putString("scenes", timed.toString())
        if (timed.length() == 0) { e.remove("cursors"); evTo = 0L }
        e.commit()
        Guard.poke()
        return timed.length() > 0
    }

    /**
     * Called by [Guard]'s check: starts the scenes that are due (a time) or whose app came to the front, and says in how many ms to
     * look again (null: nothing to wait for). [screenOn] false: the events are not read (the screen is off); they are, when it is on again.
     */
    @Synchronized
    fun tick(c: Context, now: Long, screenOn: Boolean = true): Long? {
        val list = scenes(c)
        if (list.length() == 0) return null
        val byId = LinkedHashMap<String, JSONObject>()
        for (i in 0 until list.length()) list.optJSONObject(i)?.let { byId[it.optString("id")] = it }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = step(byId.filter { it.value.has("at") }.mapValues { it.value.getJSONObject("at") }, load(p), now)
        val out = text(s.cursors)
        if (out != p.getString("cursors", "{}")) p.edit().putString("cursors", out).commit()
        for (id in s.fire) {
            val scene = byId[id] ?: continue
            val sig = s.cursors[id]?.first ?: continue
            val due = s.due[id] ?: continue
            if (!starting.add(id)) continue
            runNow(c, scene, started = { done(id) }) { noAdb(c, id, sig, due) }
        }
        var wait = s.wait
        val apps = byId.mapNotNull { (id, sc) -> sc.optJSONObject("on")?.optString("app")?.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
        if (apps.isEmpty()) { evTo = 0L; return wait }                  // off: forgets where it was reading
        if (!screenOn) return wait
        wait = minOf(wait ?: Long.MAX_VALUE, EVENTS_EVERY_MS)
        try {
            if (evTo > now) evTo = 0L                                  // the clock went back: start again from now
            if (evTo == 0L) { evTo = now; front = Usage.front(c); return wait }   // what is open now is no start
            val opens = ArrayList<Open>()
            var last = evTo
            Usage.events(c, evTo + 1, now) { e ->
                if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) opens.add(Open(e.packageName ?: "", e.timeStamp))
                last = maxOf(last, e.timeStamp)
            }
            evTo = maxOf(last, evTo)
            val d = decideApps(apps, opens, front, quiet, now)
            front = d.front
            for (id in d.fire) byId[id]?.let { launch(c, it, now) }
        } catch (e: Exception) { android.util.Log.w("tvr-guard", "scene events: $e") }
        return wait
    }

    /** The TV was turned on from standby (the screen-on receiver of [dev.tvremote.setup.WelcomeWatch]). With no such scene it returns at once. */
    fun onPower(c: Context) {
        val list = scenes(c)
        if (list.length() == 0) return
        val byId = LinkedHashMap<String, JSONObject>()
        for (i in 0 until list.length()) list.optJSONObject(i)?.takeIf { it.optJSONObject("on")?.optString("power") == "on" }?.let { byId[it.optString("id")] = it }
        if (byId.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            for (id in decidePower(byId.keys, quiet, now)) launch(c, byId[id]!!, now)
        }
        Guard.poke()                                                   // an app scene reads its events soon, not after standby's slow look
    }

    /** Runs [scene] for an event; its own events are ignored while it runs and a moment after ([quiet]). */
    private fun launch(c: Context, scene: JSONObject, now: Long) {
        val id = scene.optString("id")
        if (!starting.add(id)) return
        quiet[id] = now..Long.MAX_VALUE
        runNow(c, scene, started = { done(id) }, noAdb = { true }, finished = {   // no ADB: silent, not "cannot run" on every app opened
            synchronized(this) { starting.remove(id); quiet[id] = now..(System.currentTimeMillis() + QUIET_AFTER_MS) }
        })
    }

    private const val EVENTS_EVERY_MS = 10_000L

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
