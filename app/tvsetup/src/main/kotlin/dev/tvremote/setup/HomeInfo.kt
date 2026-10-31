package dev.tvremote.setup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import dev.tvremote.core.Words
import dev.tvremote.setup.health.Stats
import dev.tvremote.setup.home.HomeApp
import dev.tvremote.setup.home.HomeControl
import dev.tvremote.setup.home.HomeKeys
import dev.tvremote.setup.home.HomePower
import dev.tvremote.setup.home.LiveWidgets
import dev.tvremote.setup.keys.KeyFix
import dev.tvremote.setup.rules.Guard
import dev.tvremote.setup.rules.Kids
import dev.tvremote.setup.rules.Rules
import dev.tvremote.setup.rules.Usage
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * What TV Home's "usage" widget shows (HomeConfig.USAGE), counted here so it is the same number the app limits hold to:
 *   {week: [{pkg, sec}] the most used apps of the last 7 days with today, days: [sec] each day's total (today first), limits: [{pkg, min (today's, extra included), used (sec)}]}
 * the marks on the app tiles ("badges"): {limits: [{pkg, min, used}] as above, locked: [pkg] behind a PIN, bed: bedtime is on now}, TV Home needs none of it,
 * its "timer" widget: {sleep_at, bedtime: {on, ranges}, in_bedtime, snooze_until} (Rules.state's),
 * and the system widget's CPU ring, "cpu": {busy (0..1 over one second), cores}; Android 8+ keeps /proc/stat from apps, the shell reads it.
 * and "tiles" / "tile" / "tile_do" (LiveWidgets: live widgets drawn in TV Home's app grid; the RAM one is the first; the wire names keep "tile" so TV Home and older TV Setups still agree),
 * and "telegram" (the bot's state for TV Home's Telegram widget: {state: none | token_missing | ready, bot, link, code, code_left}; RemoteService.telegramForHome),
 * and "app" (HomeApp: one app's lock, daily limit, force stop and cache, for TV Home's long-press menu; `arg` {pkg, op, ...}),
 * and "capture" (`arg` "1" / "0": while "1", the remote keys of the map go to TV Home's screen unopened, for its assign-a-key page),
 * and "power" (HomePower: screen off, standby, restart, power off; `arg` the action, "result" ok / noadb / failed),
 * and "control" (HomeControl: the Control Center's sleep timer and kids mode on; `arg` "" | "sleep:<min>" | "kids:on"; {sleep_at, kids, pin, error?}).
 * and "webview" (to TV Home, Channels and Games by their key: no WebView on the TV, switch it back on; "result" fine / fixed / none / noadb / failed),
 * and "updates" (to TV Home, Channels and Games by their key, like "census": {silent} = auto-update is on and ADB is ready, so TV Setup installs their updates itself),
 * and "tvapps" (to TV Apps by its key: `arg` {op: install | job | cancel, id}; one app of the signed list installed over our ADB link, RemoteService.tvApps; no answer = no ADB here),
 * Which apps a family watches is nobody else's business: only TV Home, signed by this project's key (or by this app's own,
 * a build signed with one key for both), gets an answer.
 */
class HomeInfo : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return null
        if (method == "census")                                        // read-only: the stats switch + install id, for TV Home, Channels and Games (own keys)
            return if (censusCaller(c, Binder.getCallingUid())) Bundle().apply { putString("json", RemoteService.censusState(c).toString()) } else null
        if (method == "updates")                                       // read-only: will TV Setup install their updates by itself? (SelfUpdate waits for it, UpdatePlan.quiet)
            return if (censusCaller(c, Binder.getCallingUid()) || Binder.getCallingUid() == android.os.Process.SHELL_UID)   // shell: the phone / computer page asks over ADB (Manage)
                Bundle().apply { putString("json", RemoteService.updatesState(c).toString()) } else null
        if (method == "tvapps") {                                      // TV Apps: install one app of the signed list over our ADB link; `arg` {op, id}
            if (!censusCaller(c, Binder.getCallingUid())) return null
            val a = try { JSONObject(arg.orEmpty()) } catch (e: Exception) { return null }
            val token = Binder.clearCallingIdentity()
            try {
                val out = try { RemoteService.tvApps(a.optString("op"), a.optString("id")) } catch (e: Exception) { JSONObject().put("status", "error").put("msg", e.message ?: "") }
                return Bundle().apply { if (out != null) putString("json", out.toString()) }   // nothing: no ADB here, the app uses Android's installer
            } finally { Binder.restoreCallingIdentity(token) }
        }
        if (method == "webview") {                                     // TV Home / Channels / Games found no WebView: turn it back on (WebViewFix), may wait on ADB
            if (!censusCaller(c, Binder.getCallingUid())) return null
            val token = Binder.clearCallingIdentity()
            try { return Bundle().apply { putString("result", WebViewFix.run(c)) } } finally { Binder.restoreCallingIdentity(token) }
        }
        if (method !in setOf("usage", "timer", "cpu", "badges", "power", "keys", "tiles", "tile", "tile_do", "telegram", "app", "capture", "control", "recent")) return null
        // TV Home's package name signed by another key (built elsewhere, a fork): said as such, nothing else given, so the
        // button tells the person to install both from one place instead of "update TV Setup", which would not help
        if (!trusted(c, Binder.getCallingUid())) return if (fromHome(c, Binder.getCallingUid())) Bundle().apply { putString("result", "untrusted") } else null
        if (method == "power") {                                       // the power button: `arg` is one of HomePower's three words, nothing else is run
            val token = Binder.clearCallingIdentity()
            try { return Bundle().apply { putString("result", HomePower.run(c, arg.orEmpty())) } } finally { Binder.restoreCallingIdentity(token) }
        }
        if (method == "control") {                                     // saving the rules and starting the watcher: our own identity
            val token = Binder.clearCallingIdentity()
            try {
                val over = extras?.getBoolean("hand") == true                // a TV Home that starts the screen itself (HomeControl.handBack)
                return Bundle().apply {
                    putString("json", HomeControl.run(c, arg, over).toString())
                    if (over) HomeControl.handBack(c, arg.orEmpty())?.let { putParcelable("go", it) }
                }
            } finally { Binder.restoreCallingIdentity(token) }
        }
        if (method == "keys") return keys(c, arg)
        if (method == "capture") { KeysBridge.capture(arg == "1"); return Bundle().apply { putString("json", "{}") } }   // TV Home's "assign a key" page is open: the keys reach it instead of opening their apps
        if (method == "app") {                                         // one app's lock, limit and maintenance (HomeApp); it may wait on ADB, never on the main thread
            val token = Binder.clearCallingIdentity()
            try { return Bundle().apply { putString("json", HomeApp.handle(c, arg).toString()) } } finally { Binder.restoreCallingIdentity(token) }
        }
        if (method == "telegram")                                      // the Telegram widget: `arg` "code" starts a fresh pairing code, "" only asks
            return Bundle().apply { putString("json", RemoteService.telegramForHome(c, arg == "code").toString()) }
        if (method.startsWith("tile")) {                               // live widgets (LiveWidgets): the state at once, the action on a thread of its own
            val lang = extras?.getString("lang")?.let { Words.pick(it) } ?: Words.lang
            val json = when (method) {
                "tiles" -> LiveWidgets.list(lang).toString()
                "tile" -> LiveWidgets.tile(c, lang, arg.orEmpty())?.toString()
                else -> LiveWidgets.act(c, arg.orEmpty())?.toString()
            }
            return Bundle().apply { if (json != null) putString("json", json) }
        }
        val now = System.currentTimeMillis()
        if (method == "cpu") {                                         // the last measure at once, the next one started: a binder call never waits on ADB
            measure()
            return cpu?.let { Bundle().apply { putString("json", it) } }
        }
        if (method == "timer") {
            val o = Rules.load(c)
            return Bundle().apply { putString("json", JSONObject().put("sleep_at", o.optLong("sleep_at").let { if (it > now) it else 0L })
                .put("bedtime", Rules.bedtime(o)).put("in_bedtime", Rules.inBedtime(o, now))
                .put("snooze_until", o.optLong("snooze_until").let { if (it > now) it else 0L }).toString()) }
        }
        if (method == "recent") return recent(c, now)
        if (method == "badges") {                                      // the app tiles' marks: little to ask, answered each minute
            val rules = Rules.load(c)
            val token = Binder.clearCallingIdentity()
            try {
                val on = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                val today = try { Stats(c).today(now, on).first } catch (e: Exception) { JSONObject() }
                val limits = JSONArray()
                rules.optJSONObject("limits")?.let { l ->
                    for (pkg in l.keys()) limits.put(JSONObject().put("pkg", pkg).put("min", l.optInt(pkg) + Rules.extra(rules, pkg, now)).put("used", today.optLong(pkg)))
                }
                // TV Setup asks its own PIN while a code is set (MainActivity, Guard.RULE_SCREENS), in kids mode or not: its tile had no lock
                val self = Rules.hasCode(rules) && Rules.unlockUntil(rules, now) == 0L && !Guard.isOpen(c.packageName)
                val out = JSONObject().put("limits", limits).put("locked", JSONArray(Rules.locked(rules) + (if (self) listOf(c.packageName) else emptyList())))
                    .put("bed", Rules.inBedtime(rules, now))
                // kids mode locks every app but these (Rules.needsPin): without it a TV Home showed no lock at all while each app asked the PIN
                if (Rules.kidsMode(rules) && Rules.unlockUntil(rules, now) == 0L) {
                    val open = HashSet(Rules.allow(rules) + Kids.free(c, now) + (if (self) emptyList() else listOf(c.packageName)))
                    rules.optJSONObject("unlock_apps")?.let { u -> for (k in u.keys()) if (u.optLong(k) > now) open.add(k) }
                    out.put("kids", true).put("open", JSONArray(open.toList()))
                }
                return Bundle().apply { putString("json", out.toString()) }
            } finally { Binder.restoreCallingIdentity(token) }
        }
        val token = Binder.clearCallingIdentity()                      // our own permissions from here (usage access)
        try {
            val on = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val today = try { Stats(c).today(now, on).first } catch (e: Exception) { JSONObject() }
            val all = try { JSONObject(File(c.getExternalFilesDir(null), "stats/usage.json").readText()) } catch (e: Exception) { JSONObject() }
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val perDay = (0..6).map { d -> if (d == 0) today else all.optJSONObject(day.format(Date(now - d * 86_400_000L))) ?: JSONObject() }
            val week = HashMap<String, Long>()
            for (o in perDay) for (k in o.keys()) week[k] = (week[k] ?: 0L) + o.optLong(k)
            val homes = try {                                          // a home screen (Google TV's, TV Home) is where people pass, not what they watch
                c.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
                    .map { it.activityInfo.packageName }.toSet()
            } catch (e: Exception) { emptySet() }
            val pm = c.packageManager
            val opens = { p: String -> (if (Build.VERSION.SDK_INT >= 21) pm.getLeanbackLaunchIntentForPackage(p) else null) ?: pm.getLaunchIntentForPackage(p) }
            val top = JSONArray()                                      // apps one opens: not the system's screens (systemui) nor a home screen
            for ((pkg, s) in week.entries.filter { it.key != c.packageName && it.key !in homes && it.value >= 60 && opens(it.key) != null }.sortedByDescending { it.value }.take(5).map { it.key to it.value })
                top.put(JSONObject().put("pkg", pkg).put("sec", s))
            val days = JSONArray()                                     // each day's total, today first: the usage widget's columns
            for (t in LiveWidgets.dayTotals(c, perDay)) days.put(t)
            val rules = Rules.load(c)
            val limits = JSONArray()
            rules.optJSONObject("limits")?.let { l ->
                for (pkg in l.keys()) limits.put(JSONObject().put("pkg", pkg).put("min", l.optInt(pkg) + Rules.extra(rules, pkg, now)).put("used", today.optLong(pkg)))
            }
            return Bundle().apply { putString("json", JSONObject().put("week", top).put("days", days).put("limits", limits).toString()) }
        } finally { Binder.restoreCallingIdentity(token) }
    }

    /**
     * "recent": {apps: [pkg]} the apps opened last, newest first, at most 8 (TV Home shows up to 6). The order is the order of Android's
     * usage events, never their times: a box can boot with a wrong year (Usage). Not TV Home, TV Setup, a home screen or the system's
     * screens; only what one can open (a leanback or launcher entry).
     */
    private fun recent(c: Context, now: Long): Bundle {
        val token = Binder.clearCallingIdentity()                      // our own permissions from here (usage access)
        try {
            val seen = LinkedHashSet<String>()                         // oldest first: a later open moves the app to the end
            try {
                Usage.events(c, now - 7 * 86_400_000L, now) { e ->
                    if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) e.packageName?.let { seen.remove(it); seen.add(it) }
                }
            } catch (e: Exception) { }
            val pm = c.packageManager
            val homes = try {
                pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
            } catch (e: Exception) { emptySet() }
            val out = JSONArray()
            for (p in seen.toList().asReversed()) {                    // not reversed(): JDK 21's member is missing on Android
                if (p == c.packageName || p == HOME || p in homes || p in SYSTEM_SCREENS) continue
                val opens = (if (Build.VERSION.SDK_INT >= 21) pm.getLeanbackLaunchIntentForPackage(p) else null) ?: pm.getLaunchIntentForPackage(p)
                if (opens == null) continue
                out.put(p); if (out.length() >= 8) break
            }
            return Bundle().apply { putString("json", JSONObject().put("apps", out).toString()) }
        } finally { Binder.restoreCallingIdentity(token) }
    }

    /**
     * TV Home's keys and power-on app ([HomeKeys]): `arg` is its config (HomeConfig, cleaned again here); empty only asks. The answer
     * {free: the keys TV Home may offer (dead ones, app buttons), can: this app may run its key filter, wanted: it is not switched off here}.
     * The work runs on a thread: switching the filter on writes secure settings and a binder call must not wait on that.
     */
    private fun keys(c: Context, arg: String?): Bundle {
        if (!arg.isNullOrEmpty() && HomeKeys.save(c, arg)) {
            Thread({
                KeyFix.sync(c); KeysBridge.poke()
                if (HomeKeys.active(c) && !RemoteService.isRunning()) try { RemoteService.start(c, true) } catch (e: Exception) { }   // the wake-up hearing lives there
            }, "home-keys").apply { isDaemon = true }.start()
        }
        val free = KeyFix.offered(KeyFix.keyMap(c)) { KeyFix.alive(c, it) }   // the dead keys and the app buttons
        return Bundle().apply { putString("json", JSONObject().put("free", JSONArray(free.sorted())).put("can", KeyFix.can(c)).put("all", true).put("wanted", KeyFix.wanted(c)).toString()) }
    }

    /** One second of /proc/stat through the shell, on its own thread, never two at once (a dial may wait minutes for the TV's question). */
    private fun measure() {
        if (!measuring.compareAndSet(false, true)) return
        Thread({
            try {
                val a = RemoteService.shell("head -1 /proc/stat") ?: return@Thread
                Thread.sleep(1000)
                val b = RemoteService.shell("head -1 /proc/stat") ?: return@Thread
                val busy = busy(a, b) ?: return@Thread
                cpu = JSONObject().put("busy", busy.toDouble()).put("cores", Runtime.getRuntime().availableProcessors()).toString()
            } finally { measuring.set(false) }
        }, "home-cpu").apply { isDaemon = true }.start()
    }

    /** One of our own apps (CENSUS_APPS) by its signing key. */
    private fun censusCaller(c: Context, uid: Int): Boolean {
        val pm = c.packageManager
        val mine = certs(pm, c.packageName)
        return (pm.getPackagesForUid(uid) ?: emptyArray()).any { pkg -> censusOk(pkg, certs(pm, pkg), mine) }
    }

    private fun fromHome(c: Context, uid: Int) = c.packageManager.getPackagesForUid(uid)?.contains(HOME) == true

    private fun trusted(c: Context, uid: Int): Boolean {
        val pm = c.packageManager
        if (!fromHome(c, uid)) return false
        val mine = certs(pm, c.packageName)
        return certs(pm, HOME).any { it == HOME_CERT || it in mine }
    }

    @Suppress("DEPRECATION")
    private fun certs(pm: PackageManager, pkg: String): List<String> = try {
        val sigs = if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
    } catch (e: Exception) { emptyList() }

    override fun onCreate() = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    internal companion object {
        @Volatile var cpu: String? = null                              // {busy, cores}, the latest measure
        val measuring = java.util.concurrent.atomic.AtomicBoolean()

        /** The busy share between two "cpu " lines of /proc/stat (user nice system idle iowait ...): idle and waiting for the disk are not busy. */
        fun busy(a: String, b: String): Float? {
            fun nums(s: String) = s.lines().firstOrNull { it.startsWith("cpu ") }?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() }
            val x = nums(a) ?: return null; val y = nums(b) ?: return null
            if (x.size < 4 || y.size < 4) return null
            val idle = { v: List<Long> -> v[3] + v.getOrElse(4) { 0 } }
            val all = y.sum() - x.sum()
            if (all <= 0) return null
            return ((all - (idle(y) - idle(x))).toFloat() / all).coerceIn(0f, 1f)
        }
        /** The apps that may read the stats switch, each with the certificate (SHA-256) of its own signing.jks. */
        val CENSUS_APPS = mapOf(
            "dev.tvremote.home" to "657aabacfdccbc22ff1f61138df7efd79fae765df742f7d15d80dac84c7d81b3",        // app/tvhome/signing.jks
            "dev.tvremote.channels" to "a3e09c97fb750bf09285d6fa7c065d4c63ee6d8247b1caaa555dd279e2534b6a",    // app/tvchannels/signing.jks
            "dev.tvremote.games" to "b7b61e8be4809effc2b4c16ab75a320b9b655d45a0ef5f0f706feb483bbff567",       // app/tvgames/signing.jks
            "dev.tvremote.apps" to "a628f2d051a71479c5c6b7c4dbf26eb9e947c680a2091bfd6a63c17d704d3160")        // app/tvapps/signing.jks

        /** [pkg] is one of those apps and [theirs] (its signing digests) hold its pinned one, or one of [mine] (debug builds of all apps share a key). */
        fun censusOk(pkg: String, theirs: List<String>, mine: List<String>): Boolean = CENSUS_APPS[pkg]?.let { pin -> theirs.any { it == pin || it in mine } } == true
        const val HOME = "dev.tvremote.home"
        /** Screens of the system, not apps a person opens: the "recent" widget leaves them out. */
        val SYSTEM_SCREENS = setOf("com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller", "android")
        const val HOME_CERT = "657aabacfdccbc22ff1f61138df7efd79fae765df742f7d15d80dac84c7d81b3"   // app/tvhome/signing.jks, alias tvhome
    }
}
