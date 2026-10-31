package dev.tvremote.setup.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import dev.tvremote.core.WireLog
import dev.tvremote.core.Words
import dev.tvremote.core.ontv.Foreground
import dev.tvremote.core.ontv.Setup
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.health.Stats
import dev.tvremote.setup.rules.Guard
import dev.tvremote.setup.rules.Rules
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live widgets TV Home draws in its app grid at the size of an app tile, provided here through the HomeInfo provider (its wire names say "tile": TV Home and older TV Setups already speak them):
 *   "tiles"    the list [{id, title, icon, kind}] (the key in TV Home's config is "tile:dev.tvremote.setup/<id>")
 *   "tile"     one live widget's state now (arg = id): {id, mode: live|working|done|failed, big, small, progress 0..1, accent: ok|warn|bad,
 *              icon?, tone? (how TV Home draws it), note? (a shorter line than small, for the outcome),
 *              result?: {before, after, freed_mb, none, text}}; answers at once, a binder call never waits on ADB
 *   "tile_do"  runs its action (arg = id) on a thread of its own and answers {started}; the outcome shows in "tile" as mode done / failed
 * Each live widget has a "layout" (how TV Home draws it): "gauge" (a ring and a line) or "toggle" (a value to cycle). Three live widgets:
 * "ram" (gauge): RAM used, a bar and the free MB; pressed, it closes what waits in the background and shows the measured
 * before -> after. "sleep" (toggle): the sleep timer from Rules; pressed, it adds 15 minutes, up to 120, then off ([nextSleep]). "watch" (gauge): the hours on the screen today (the usage widget's count), yesterday under it.
 * The pure parts (numbers, words, which packages may be closed) live here and are tested without a TV.
 */
internal object LiveWidgets {
    const val RAM = "ram"
    const val SLEEP = "sleep"
    const val WATCH = "watch"
    const val WATCH_FULL_H = 8                                         // the ring is full at 8 h; amber from 6 h, red from 10 h
    const val SLEEP_STEP = 15                                          // minutes a press adds (30/60/90 jumped too far for "a bit longer")
    const val SLEEP_MAX = 120                                          // past it a press switches the timer off
    const val DONE_MS = 6000L                                          // how long the before -> after stays, then the live value again
    const val MIN_FREED_MB = 16L                                       // less than this is noise: "nothing to free", said honestly
    private const val SETTLE_MS = 1500L                                // Android takes a moment to hand the memory back before it is read again

    fun list(lang: String): JSONArray = JSONArray()
        .put(JSONObject().put("id", RAM).put("title", Words.get(lang, "tile.ram_title")).put("icon", "ram").put("kind", "meter").put("layout", "gauge"))
        .put(JSONObject().put("id", SLEEP).put("title", Words.get(lang, "tile.sleep_title")).put("icon", "moon").put("kind", "toggle").put("layout", "toggle"))
        .put(JSONObject().put("id", WATCH).put("title", Words.get(lang, "tile.watch_title")).put("icon", "tv").put("kind", "meter").put("layout", "gauge"))

    // ---- the watched-today live widget ----

    /** Seconds on screen per day (today first) in apps one opens: the sum the usage widget's columns hold, counted by the same Stats; not TV Home / the launcher nor this app. */
    fun dayTotals(c: Context, perDay: List<JSONObject>): List<Long> {
        val pm = c.packageManager
        val homes = try {                                              // a home screen (Google TV's, TV Home) is where people pass, not what they watch
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
        } catch (e: Exception) { emptySet() }
        val opens = { p: String -> (if (android.os.Build.VERSION.SDK_INT >= 21) pm.getLeanbackLaunchIntentForPackage(p) else null) ?: pm.getLaunchIntentForPackage(p) }
        val counted = { k: String -> k != c.packageName && k !in homes && opens(k) != null }
        return perDay.map { o -> o.keys().asSequence().filter(counted).sumOf { o.optLong(it) } }
    }

    /** "3 h 12 min" / "45 min" / "0 min", in the TV's words. */
    fun duration(lang: String, sec: Long): String {
        val m = ((sec + 30) / 60).toInt()
        return if (m >= 60) Words.get(lang, "tile.watch_hm", "h" to m / 60, "m" to m % 60) else Words.get(lang, "tile.watch_m", "m" to m)
    }

    /** The ring's colour: amber from 6 h, red from 10 h on the screen in a day. */
    fun watchAccent(sec: Long) = if (sec >= 10 * 3600) "bad" else if (sec >= 6 * 3600) "warn" else "ok"

    /** The state: "gauge" layout, big = hours and minutes inside the ring, small = yesterday. */
    fun watchState(lang: String, today: Long, yesterday: Long): JSONObject {
        val m = ((today + 30) / 60).toInt()
        val big = if (m >= 60) Words.get(lang, "tile.watch_big", "h" to m / 60, "m" to "%02d".format(m % 60)) else Words.get(lang, "tile.watch_big_m", "m" to m)
        return JSONObject().put("id", WATCH).put("title", Words.get(lang, "tile.watch_title")).put("layout", "gauge").put("mode", "live")
            .put("icon", "tv").put("tone", "watch").put("big", big).put("accent", watchAccent(today))
            .put("hand", "manage:health")                             // a press: the day's apps on Health (TV Home starts it, HomeControl)
            .put("progress", (today / 3600.0 / WATCH_FULL_H).coerceIn(0.0, 1.0))
            .put("small", Words.get(lang, "tile.watch_yesterday", "t" to duration(lang, yesterday)))
    }

    // ---- the sleep timer live widget ----

    /** Whole minutes left on the timer (rounded up, so "1 min" shows until it fires); 0 = none set. */
    fun sleepLeft(sleepAt: Long, now: Long): Int = if (sleepAt > now) ((sleepAt - now + 59_999L) / 60_000L).toInt() else 0

    /**
     * What a press sets, given the minutes left: off -> 15 -> 30 -> ... -> 120 -> off. A timer running down counts as the step it is
     * under (25 left is "30"), so a press always adds 15; at 120 or more (set longer elsewhere too) it goes off.
     */
    fun nextSleep(left: Int): Int {
        if (left <= 0) return SLEEP_STEP
        val under = (left + SLEEP_STEP - 1) / SLEEP_STEP * SLEEP_STEP
        return (under + SLEEP_STEP).takeIf { it <= SLEEP_MAX } ?: 0
    }

    /** The state: "toggle" layout, big = minutes left or "Off", small = what a press does next. */
    fun sleepState(lang: String, left: Int): JSONObject {
        val o = JSONObject().put("id", SLEEP).put("title", Words.get(lang, "tile.sleep_title")).put("layout", "toggle").put("mode", "live")
            .put("icon", "moon").put("accent", "ok").put("on", left > 0).put("tone", if (left > 0) "night" else "off")
        val next = nextSleep(left)
        return o.put("big", if (left > 0) Words.get(lang, "tile.sleep_left", "n" to left) else Words.get(lang, "tile.sleep_off"))
            .put("small", Words.get(lang, if (next > 0) "tile.sleep_next" else "tile.sleep_next_off", "n" to next))
    }

    /** What one measure (total, available, in MB) says. */
    class Mem(val total: Long, val avail: Long) {
        val used get() = (total - avail).coerceAtLeast(0)
        val pct: Int get() = if (total <= 0) 0 else Math.round(100f * used / total).coerceIn(0, 100)
    }

    /** How full the bar is: amber from 70 %, red from 85 % (the memory notice of Rules counts the same way, its default being 90). */
    fun accent(pct: Int) = if (pct >= 85) "bad" else if (pct >= 70) "warn" else "ok"

    /** "43% → 31% · −280 MB"; [none]: nothing came back, said as such with the number it stays at. */
    fun doneText(lang: String, before: Mem, after: Mem, freedMb: Long): String =
        if (freedMb < MIN_FREED_MB) Words.get(lang, "tile.ram_nothing", "pct" to after.pct)
        else Words.get(lang, "tile.ram_done", "from" to before.pct, "to" to after.pct, "mb" to freedMb)

    /** The short line the live widget shows for the press's outcome instead of its live line: "−37 MB", or "Already lean" when nothing came back. */
    fun noteText(lang: String, freedMb: Long): String =
        if (freedMb < MIN_FREED_MB) Words.get(lang, "tile.ram_lean") else Words.get(lang, "tile.ram_freed", "mb" to freedMb)

    /** The outcome of one press. */
    class Result(val before: Mem, val after: Mem, val at: Long) {
        val freedMb get() = (after.avail - before.avail).coerceAtLeast(0)
        val none get() = freedMb < MIN_FREED_MB
    }

    /** The state to answer: the live value, or the last press's outcome for [DONE_MS], or "working" while it runs. */
    fun state(lang: String, now: Mem, working: Boolean, last: Result?, failed: Boolean, at: Long): JSONObject {
        val o = JSONObject().put("id", RAM).put("title", Words.get(lang, "tile.ram_title"))
        val live = o.put("big", "${now.pct}%").put("small", Words.get(lang, "tile.ram_free", "mb" to now.avail))
            .put("progress", now.pct / 100.0).put("accent", accent(now.pct)).put("mode", "live").put("layout", "gauge")
            .put("icon", "memory").put("tone", "clean")            // how TV Home draws it: a drawable name of its own and the gradient family
        if (working) return live.put("mode", "working").put("small", Words.get(lang, "tile.ram_working"))
        if (failed) return live.put("mode", "failed").put("small", Words.get(lang, "tile.ram_noadb"))
        if (last != null && at - last.at in 0..DONE_MS) {
            val text = doneText(lang, last.before, last.after, last.freedMb)
            return live.put("mode", "done").put("big", "${last.after.pct}%").put("small", text).put("progress", last.after.pct / 100.0)
                .put("accent", accent(last.after.pct)).put("note", noteText(lang, last.freedMb))
                .put("result", JSONObject().put("before", last.before.pct).put("after", last.after.pct).put("freed_mb", last.freedMb)
                    .put("none", last.none).put("text", text).put("at", last.at))
        }
        return live
    }

    // ---- which packages the press may close ----

    private val PKG = Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")

    /** Package names in `ps -A -o NAME` text: a process "com.x.app:service" belongs to "com.x.app"; kernel threads ("[kworker]") and daemons have no dot. */
    fun packagesOf(ps: String): List<String> =
        ps.lines().map { it.trim().substringBefore(':') }.filter { PKG.matches(it) }.distinct()

    /**
     * Never closed, whatever is running: TV Home, TV Setup (TV Channels and TV Games close like any app), Android itself and the brands' must-keep
     * apps ([Setup.mustKeep], the Apps tab's own list), the app on screen, launchers, keyboards and accessibility services.
     */
    fun protectedPkg(pkg: String, front: String, safe: Set<String>) =
        Setup.mustKeep(pkg) || pkg == dev.tvremote.home.HomeConfig.PKG || pkg == front || pkg in safe || pkg.startsWith("android.")   // android.process.acore & co: Android's shared processes, not apps

    /** The packages to close out of those running; `am kill` itself only takes a cached process, so this is a second lock, not the only one. */
    fun targets(running: List<String>, front: String, safe: Set<String>): List<String> = running.filter { !protectedPkg(it, front, safe) }

    /** The launchers, keyboards and accessibility services of this TV. */
    fun safeSet(c: Context): Set<String> {
        val out = HashSet<String>()
        out.add(c.packageName)
        try {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).forEach { out.add(it.activityInfo.packageName) }
        } catch (e: Exception) { }
        try { (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.forEach { out.add(it.packageName) } } catch (e: Exception) { }
        for (s in (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").split(':'))
            s.substringBefore('/').takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private fun installed(c: Context, pkg: String) = try { c.packageManager.getApplicationInfo(pkg, 0); true } catch (e: Exception) { false }

    // ---- the state kept between binder calls ----

    @Volatile private var working = false
    @Volatile private var failed = false
    @Volatile private var failedAt = 0L
    @Volatile private var last: Result? = null

    private fun mem(): Mem = Guard.memory().let { Mem(it.first, it.second) }

    /** "tile" (wire name): the state now, from /proc/meminfo (apps may read it): no ADB, no wait. */
    fun tile(c: Context, lang: String, id: String): JSONObject? {
        val now = System.currentTimeMillis()
        if (id == SLEEP) return sleepState(lang, sleepLeft(Rules.load(c).optLong("sleep_at"), now))
        if (id == WATCH) return watchNow(c, lang, now)
        if (id != RAM) return null
        val bad = failed && now - failedAt <= DONE_MS
        if (failed && !bad) failed = false
        return state(lang, mem(), working, last, bad, now)
    }

    /** Today from Stats (usage events, as the usage widget), the earlier days from its usage.json. */
    private fun watchNow(c: Context, lang: String, now: Long): JSONObject {
        val token = android.os.Binder.clearCallingIdentity()           // our own permissions (usage access)
        try {
            val on = (c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
            val today = try { Stats(c).today(now, on).first } catch (e: Exception) { JSONObject() }
            val all = try { JSONObject(java.io.File(c.getExternalFilesDir(null), "stats/usage.json").readText()) } catch (e: Exception) { JSONObject() }
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val per = dayTotals(c, (0..6).map { d -> if (d == 0) today else all.optJSONObject(day.format(java.util.Date(now - d * 86_400_000L))) ?: JSONObject() })
            return watchState(lang, per[0], per[1])
        } finally { android.os.Binder.restoreCallingIdentity(token) }
    }

    /** "tile_do": starts the action once at a time (a second press while it runs is the same run); {started} or {busy}. */
    fun act(c: Context, id: String): JSONObject? {
        if (id == SLEEP) {                                             // the timer itself is Rules' own: one write, at once
            val left = sleepLeft(Rules.load(c).optLong("sleep_at"), System.currentTimeMillis())
            Rules.change(c, JSONObject().put("do", "sleep").put("minutes", nextSleep(left)))
            return JSONObject().put("started", true)
        }
        if (id == WATCH) return JSONObject().put("started", false)   // nothing to press: a reading only
        if (id != RAM) return null
        val app = c.applicationContext
        synchronized(this) {
            if (working) return JSONObject().put("started", false).put("busy", true)
            working = true; failed = false
        }
        Thread({
            try {
                val before = mem()
                val sh = { cmd: String -> RemoteService.shell(cmd) }
                val front = Foreground.parse(sh(Foreground.CMD).orEmpty())
                val ps = sh("ps -A -o NAME")
                if (ps == null) { failed = true; failedAt = System.currentTimeMillis(); return@Thread }   // no ADB link: nothing to say about memory
                val named = packagesOf(ps)
                val running = named.filter { installed(app, it) }   // a process name that is no package (a daemon's) is not an app to close
                // an Android without `ps -A` (7 and older) gives no list: the same call the Free RAM button makes
                val mine = targets(running, front, safeSet(app))
                if (named.isEmpty()) sh("am kill-all")
                else if (mine.isNotEmpty()) sh(mine.joinToString("; ") { "am kill $it" })
                Thread.sleep(SETTLE_MS)
                last = Result(before, mem(), System.currentTimeMillis())
                WireLog.all.add("·", "live widget ram: ${before.pct}% -> ${last!!.after.pct}% (${last!!.freedMb} MB)")
            } catch (e: Exception) {
                failed = true; failedAt = System.currentTimeMillis()
            } finally { working = false }
        }, "home-live-widget").apply { isDaemon = true }.start()
        return JSONObject().put("started", true)
    }
}
