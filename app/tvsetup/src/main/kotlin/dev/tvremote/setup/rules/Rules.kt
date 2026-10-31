package dev.tvremote.setup.rules

import android.content.Context
import android.content.pm.PackageManager
import dev.tvremote.setup.KeysBridge
import dev.tvremote.setup.MicVoice
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.WelcomeState
import dev.tvremote.setup.health.Stats
import dev.tvremote.setup.home.HomeKey
import dev.tvremote.setup.home.HomeKeys
import dev.tvremote.setup.keys.KeyFix
import dev.tvremote.setup.link.KeepAwake
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The timers and limits the remotes set, kept and enforced here on the TV (see [Guard]), so nothing else has to stay on:
 *
 *   sleep timer   the TV goes to standby at a set time (a minute's warning on screen, with "+15 min")
 *   bedtime       in any of a few times of day (22:00–06:30, 13:00–14:00) the TV goes to standby a minute after it is turned on
 *   app limits    minutes per day for an app; when they are used up the app is closed (more can be granted for today)
 *   volume        times of day with a highest volume each (22:00–06:00 at most 30%): louder is turned down, with a word on screen
 *   memory        a notice over whatever is on when the TV's memory stays fuller than a set share, to free it or not
 *   app locks     a PIN asked over an app before it can be used, again each time another app was opened ([PinActivity])
 *   schedules     an app that may be opened only in some windows (days + hours: Mon–Fri 16:00–18:00, Sat 08:00–22:00; max 8), with optional minutes inside it; or never
 *   kids mode     the lock turned round: EVERY app asks for the PIN (or an Authenticator code, or a parent over Telegram) except the ones allowed and the
 *                 always-free ones ([Kids.free]: the home screen, the keyboard, TV Setup...); a total of minutes a day on the TV, apps and all
 *
 * Stored as one JSON object: { sleep_at, bedtime: {on, ranges: [{from, to}], from, to (the first range, for
 * remotes older than ranges)}, snooze_until, limits: {pkg: minutes}, extra: {day: {pkg: minutes}},
 * volume: [{from, to, max (percent)}], ram: {on, pct (used, percent)}, lock: {apps: [pkg], salt, hash (of the PIN), len (its digits)},
 * sched: {pkg: {days: [1..7, Monday = 1], from, to, max (minutes inside the window, 0 = no cap), block (never: days and window ignored)}},
 * lock also: mode ("list" | "kids"), allow: [pkg], methods: {pin, ask}, totp (the Authenticator secret, base32: never in the state), and
 * unlock_until (ms: everything open until then), unlock_apps: {pkg: ms}, total: {min, days, warn}, total_extra: {day: minutes} }.
 * The remotes reach it through [RulesReceiver] (phone, computer: over ADB) or RemoteService (the TV's own page).
 */
internal object Rules {
    private const val PREFS = "rules"
    private val PKG = dev.tvremote.core.adb.Pkg.RE
    private val HHMM = Regex("([01]?\\d|2[0-3]):([0-5]\\d)")

    @Synchronized
    fun load(c: Context): JSONObject = try {
        JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    @Synchronized
    private fun save(c: Context, o: JSONObject) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", o.toString()).commit() }

    /** Something here needs the TV Setup service to keep running, even with the web remote off. */
    fun active(c: Context): Boolean {
        val o = load(c)
        return o.optLong("sleep_at") > System.currentTimeMillis() || o.optJSONObject("bedtime")?.optBoolean("on") == true || (o.optJSONObject("limits")?.length() ?: 0) > 0
            || (o.optJSONArray("volume")?.length() ?: 0) > 0 || ram(o).optBoolean("on")
            || locked(o).isNotEmpty() || kidsMode(o) || totalMin(o, System.currentTimeMillis()) != null
            || o.optBoolean("awake")                                   // KeepAwake lives in the service
            || o.optBoolean("mic")                                     // MicVoice: the key filter stays on for the remote's mic button
            || o.optBoolean("holdkeys")                                // HoldKeys: KeyFixWatch keeps the key filter on
            || o.optString("homekey").isNotEmpty()                     // HomeKey: the filter sends Home to the launcher chosen
            || WelcomeState.active(c)                                  // so does hearing the TV turned on (WelcomeWatch)
            || HomeKeys.active(c)                                      // and TV Home's keys / app on power-on (KeyFixWatch, AutoOpenWatch)
    }

    fun day(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    /**
     * One action from a remote, then the state they show. Actions ("do"): sleep {minutes} (0 cancels), sleep_add {minutes},
     * bedtime {on, ranges: [{from, to}] ("22:00"), or from, to for one}, snooze {minutes} (bedtime paused, 0 resumes), limit {pkg, minutes} (0 removes),
     * extra {pkg, minutes} (more for today only), volume {ranges: [{from, to, max}]} (the whole list),
     * sched {pkg, windows: [{days: [1..7], from, to}] (or the older days, from, to for one), max, or block: true; on: false removes} (an app opened only on those days inside the window, at most max minutes in it),
     * ram {on, pct} (the memory notice, at pct% used), pin {pin} (4 to 8 digits), lock {pkg, on} (a PIN must be set first).
     * Kids mode: kids {mode: "list"|"kids", allow: [pkg], methods: {pin, totp (false only: it is turned on by totp), ask}} (each part optional; kids needs a PIN or a code set),
     * unlock {minutes, pkg?, today?} (everything, or one app, open for that long; today: until midnight; minutes 0 locks again, every app's unlock too),
     * total {min, days: [1..7], warn} (minutes a day on the TV; min 0 removes), total_extra {minutes, set?} (more for today only),
     * totp {secret (base32), code, client_now (ms)} (turns the Authenticator on once the code is right; throws [CodeWrong] with the clock's facts) or {off: true}.
     * An empty "do" only reads.
     */
    @Synchronized
    fun change(c: Context, d: JSONObject): JSONObject {
        val o = load(c)
        val now = System.currentTimeMillis()
        try {
            if (!apply(o, d, now, Clock.driftMs(c))) return state(c)
        } catch (e: CodeWrong) {
            e.clock = Clock.info(c).also { i -> e.clientDriftS?.let { i.put("client_drift_s", it) } }   // the page tells which clock is out
            throw e
        }
        save(c, o)
        if (d.optString("do") == "totp" && !d.optBoolean("off")) {
            if (d.optLong("client_now") > 0) Clock.note(c, d.optLong("client_now"), Clock.CLIENT, force = true)   // the phone is the Authenticator: its time is the one the codes follow
            Thread({ Clock.check(c) }, "clock-check").apply { isDaemon = true }.start()                          // then the network's, which is the real one
        }
        Guard.poke()
        KeepAwake.live?.check()
        if (d.optString("do") in setOf("holdkeys", "mic", "homekey")) Thread({
            if (d.optString("do") == "mic" && d.optBoolean("on")) RemoteService.adb()?.let { MicVoice.grant(c, it) }   // the microphone, allowed through ADB
            KeyFix.sync(c); KeysBridge.poke()
        }, "holdkeys").apply { isDaemon = true }.start()   // the key filter on or off
        return state(c).also { st -> d.optJSONArray("recovery_out")?.let { st.put("recovery", it) } }   // the codes: this once
    }

    /** [change] without the phone's storage: [o] becomes the new rules. False for a read ("" / "get"), which changes nothing. */
    internal fun apply(o: JSONObject, d: JSONObject, now: Long, driftMs: Long = 0L): Boolean {
        val minutes = d.optInt("minutes").coerceIn(0, 24 * 60)
        when (d.optString("do")) {
            "", "get" -> return false
            "sleep" -> o.put("sleep_at", if (minutes > 0) now + minutes * 60_000L else 0L)
            "sleep_add" -> o.put("sleep_at", maxOf(o.optLong("sleep_at"), now) + minutes * 60_000L)
            "bedtime" -> {
                val given = d.optJSONArray("ranges") ?: JSONArray().put(JSONObject().put("from", d.optString("from", "22:00")).put("to", d.optString("to", "06:30")))
                val list = ranges(given, false)
                o.put("bedtime", bedtime(d.optBoolean("on") && list.length() > 0, list))
            }
            "volume" -> o.put("volume", ranges(d.optJSONArray("ranges") ?: JSONArray(), true))
            "ram" -> o.put("ram", JSONObject().put("on", d.optBoolean("on")).put("pct", d.optInt("pct", ram(o).optInt("pct")).coerceIn(RAM_MIN, RAM_MAX)))
            "holdkeys" -> o.put("holdkeys", d.optBoolean("on"))             // HoldKeys: a short press of Input / Settings does nothing, 2 s does
            "homekey" -> o.put("homekey", d.optString("pkg").takeIf { HomeKey.PKG.matches(it) } ?: "")   // HomeKey: Home opens this launcher ("" stops)
            "mic" -> o.put("mic", d.optBoolean("on"))                       // MicVoice: the remote's mic button opens TV Remote's voice, not the Assistant
            "awake" -> o.put("awake", d.optBoolean("on"))                   // KeepAwake: the chip kept up while the TV is "off"
            "pin" -> {
                val pin = d.optString("pin")
                if (!PIN.matches(pin)) throw IllegalArgumentException("bad PIN")
                val salt = java.util.UUID.randomUUID().toString()
                o.put("lock", (o.optJSONObject("lock") ?: JSONObject().put("apps", JSONArray())).put("salt", salt).put("hash", hash(salt, pin)).put("len", pin.length))
            }
            "lock" -> {
                val pkg = d.optString("pkg")
                if (!PKG.matches(pkg) || pkg == SELF) throw IllegalArgumentException("bad package name")
                val lock = o.optJSONObject("lock")
                if (lock?.has("hash") != true) throw IllegalArgumentException("set a PIN first")
                val apps = locked(o).toMutableList().apply { remove(pkg); if (d.optBoolean("on")) add(pkg) }
                lock.put("apps", JSONArray(apps))
            }
            "sched" -> {                                               // on: false takes the schedule back
                val pkg = d.optString("pkg")
                if (!PKG.matches(pkg) || pkg == SELF) throw IllegalArgumentException("bad package name")
                val all = o.optJSONObject("sched") ?: JSONObject()
                if (d.optBoolean("on", true)) {
                    if (!all.has(pkg) && all.length() >= SCHED_MAX) throw IllegalArgumentException("too many schedules")
                    all.put(pkg, clean(d))
                } else all.remove(pkg)
                o.put("sched", all)
            }
            "kids" -> {
                val lock = o.optJSONObject("lock") ?: JSONObject().put("apps", JSONArray()).also { o.put("lock", it) }
                d.optString("mode").takeIf { it.isNotEmpty() }?.let { m ->
                    if (m != "list" && m != "kids") throw IllegalArgumentException("bad mode")
                    if (m == "kids" && !lock.has("hash") && !lock.has("totp")) throw IllegalArgumentException("set a PIN first")
                    lock.put("mode", m)
                }
                d.optJSONArray("allow")?.let { a ->
                    val list = (0 until minOf(a.length(), ALLOW_MAX)).map { a.optString(it) }.onEach { if (!PKG.matches(it)) throw IllegalArgumentException("bad package name") }.distinct()
                    lock.put("allow", JSONArray(list))
                }
                d.optJSONObject("methods")?.let { m ->
                    val have = lock.optJSONObject("methods") ?: JSONObject()
                    if (m.has("ask")) have.put("ask", m.optBoolean("ask"))
                    if (m.has("totp") && !m.optBoolean("totp")) { lock.remove("totp"); lock.remove("recovery") }   // off here, on only by a right code
                    if (m.has("pin")) have.put("pin", m.optBoolean("pin"))
                    // The PIN may be off (the parent's choice: a child who learns it must not beat the Authenticator), but only with a way
                    // back that does not need the phone: the Authenticator on and recovery codes made (else "need_recovery"). Turning the
                    // Authenticator off while the PIN is off puts the PIN back: there is never no way in.
                    if (!have.optBoolean("pin", true) && !(m.has("totp") && !m.optBoolean("totp"))) {
                        if (!totpOn(o) || recoveryLeft(o) == 0) throw IllegalArgumentException("need_recovery")
                    } else if (m.has("totp") && !m.optBoolean("totp")) have.put("pin", true)
                    lock.put("methods", have)
                }
            }
            "unlock" -> {
                val pkg = d.optString("pkg")
                val until = if (d.optBoolean("today")) endOfDay(now) else if (minutes > 0) now + minutes * 60_000L else 0L
                if (pkg.isNotEmpty()) {
                    if (!PKG.matches(pkg)) throw IllegalArgumentException("bad package name")
                    val apps = JSONObject()
                    o.optJSONObject("unlock_apps")?.let { old -> for (k in old.keys()) if (old.optLong(k) > now && k != pkg) apps.put(k, old.optLong(k)) }
                    if (until > 0) apps.put(pkg, until)
                    o.put("unlock_apps", apps)
                } else {
                    o.put("unlock_until", until)
                    if (until == 0L) o.put("unlock_apps", JSONObject())                       // "Lock now" takes every grant back
                }
            }
            "total" -> {
                val min = d.optInt("min").coerceIn(0, 24 * 60)
                if (min == 0) o.remove("total") else {
                    val t = JSONObject().put("min", min).put("warn", d.optInt("warn", 5).coerceIn(0, 60))
                    d.optJSONArray("days")?.takeIf { it.length() in 1..6 }?.let { a ->
                        val days = java.util.TreeSet<Int>()
                        for (j in 0 until a.length()) days.add(a.optInt(j).also { if (it !in 1..7) throw IllegalArgumentException("bad day") })
                        t.put("days", JSONArray(days.toList()))
                    }
                    o.put("total", t)
                }
            }
            "total_extra" -> {
                val today = o.optJSONObject("total_extra")?.optInt(day(now)) ?: 0
                o.put("total_extra", JSONObject().put(day(now), maxOf(0, if (d.optBoolean("set")) minutes else today + minutes)))   // only today's grant is worth keeping
            }
            "totp" -> {
                val lock = o.optJSONObject("lock") ?: JSONObject().put("apps", JSONArray()).also { o.put("lock", it) }
                if (d.optBoolean("off")) { lock.remove("totp"); lock.remove("recovery") } else {
                    val secret = d.optString("secret").uppercase().filter { it != ' ' && it != '-' }
                    if (!Totp.validSecret(secret)) throw IllegalArgumentException("bad secret")
                    val client = d.optLong("client_now")
                    val drift = if (client > 0) now - client else driftMs                     // the phone makes the codes, so its clock is the one to match
                    if (!Totp.verify(secret, d.optString("code").trim(), now - drift)) throw CodeWrong(if (client > 0) (now - client) / 1000 else null)
                    lock.put("totp", secret)
                    d.put("recovery_out", newRecovery(lock))                                 // shown once, in the answer
                }
            }
            "recovery_new" -> {
                val lock = o.optJSONObject("lock") ?: throw IllegalArgumentException("set a PIN first")
                d.put("recovery_out", newRecovery(lock))
            }
            "snooze" -> o.put("snooze_until", if (minutes > 0) now + minutes * 60_000L else 0L)
            "limit", "extra" -> {
                val pkg = d.optString("pkg")
                if (!PKG.matches(pkg)) throw IllegalArgumentException("bad package name")
                if (d.optString("do") == "limit") {
                    val limits = o.optJSONObject("limits") ?: JSONObject()
                    if (minutes > 0) limits.put(pkg, minutes) else limits.remove(pkg)
                    o.put("limits", limits)
                } else {
                    val extra = o.optJSONObject("extra") ?: JSONObject()
                    val today = extra.optJSONObject(day(now)) ?: JSONObject()
                    today.put(pkg, if (d.optBoolean("set")) minutes else today.optInt(pkg) + minutes)   // set: 0 takes today's grant back
                    o.put("extra", JSONObject().put(day(now), today))      // only today's grants are worth keeping
                }
            }
            else -> throw IllegalArgumentException("unknown action")
        }
        return true
    }

    const val SCHED_MAX = 50
    const val ALLOW_MAX = 100
    const val RECOVERY_N = 8

    /** A fresh set of [RECOVERY_N] one-time codes of 8 digits for when the phone is lost: only salted hashes are kept, the codes are returned (once). The old ones are void. */
    internal fun newRecovery(lock: JSONObject, random: java.util.Random = java.security.SecureRandom()): JSONArray {
        val codes = JSONArray(); val kept = JSONArray()
        repeat(RECOVERY_N) {
            val code = "%08d".format(random.nextInt(100_000_000))
            val salt = java.util.UUID.randomUUID().toString().take(8)
            codes.put(code); kept.put(salt + ":" + hash(salt, code))
        }
        lock.put("recovery", kept)
        return codes
    }

    fun recoveryLeft(o: JSONObject): Int = o.optJSONObject("lock")?.optJSONArray("recovery")?.length() ?: 0

    /** [code] is an unspent recovery code: it is spent (removed) in [o]; the caller saves. */
    internal fun spendRecovery(o: JSONObject, code: String): Boolean {
        val lock = o.optJSONObject("lock") ?: return false
        val a = lock.optJSONArray("recovery") ?: return false
        if (code.length != 8 || !code.all { it in '0'..'9' }) return false
        val rest = JSONArray(); var hit = false
        for (i in 0 until a.length()) {
            val e = a.optString(i); val salt = e.substringBefore(':')
            if (!hit && java.security.MessageDigest.isEqual(e.substringAfter(':').toByteArray(), hash(salt, code).toByteArray())) hit = true else rest.put(e)
        }
        if (hit) lock.put("recovery", rest)
        return hit
    }

    /** [spendRecovery] on the stored rules. */
    @Synchronized fun useRecovery(c: Context, code: String): Boolean {
        val o = load(c)
        if (!spendRecovery(o, code)) return false
        save(c, o); return true
    }

    /** The first moment of tomorrow, local time: "until tomorrow". */
    fun endOfDay(now: Long): Long = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    const val WINDOWS_MAX = 8

    /**
     * One schedule as stored: {windows: [{days, from, to}], max}, or {block}. Days sorted and unique, padded times, the cap in range; a bad one
     * throws, so nothing odd is kept. A client older than windows sends days / from / to: they read as the one window. The first window is
     * also kept as days / from / to, which a TV Setup older than windows (and a page that reads only those) understands.
     */
    internal fun clean(d: JSONObject): JSONObject {
        if (d.optBoolean("block")) return JSONObject().put("block", true)
        val list = windows(d)
        if (list.length() == 0) throw IllegalArgumentException("no days")
        if (list.length() > WINDOWS_MAX) throw IllegalArgumentException("too many windows")
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val w = list.optJSONObject(i) ?: throw IllegalArgumentException("bad window")
            val given = w.optJSONArray("days") ?: JSONArray()
            val days = java.util.TreeSet<Int>()
            for (j in 0 until given.length()) days.add(given.optInt(j).also { if (it !in 1..7) throw IllegalArgumentException("bad day") })
            if (days.isEmpty()) throw IllegalArgumentException("no days")
            val from = w.optString("from"); val to = w.optString("to")
            if (!HHMM.matches(from) || !HHMM.matches(to) || pad(from) == pad(to)) throw IllegalArgumentException("bad time")   // the same time twice is never a window
            out.put(JSONObject().put("days", JSONArray(days.toList())).put("from", pad(from)).put("to", pad(to)))
        }
        val first = out.getJSONObject(0)
        return JSONObject().put("windows", out).put("days", first.get("days")).put("from", first.get("from")).put("to", first.get("to"))
            .put("max", d.optInt("max").coerceIn(0, 24 * 60))
    }

    /** The windows of a schedule as saved or asked for: "windows", or the older single days / from / to as one window (none for a block). */
    fun windows(s: JSONObject): JSONArray = s.optJSONArray("windows")
        ?: if (s.has("days")) JSONArray().put(JSONObject().put("days", s.optJSONArray("days")).put("from", s.optString("from")).put("to", s.optString("to"))) else JSONArray()

    /** At most 8 {from, to} ranges ("H:MM" padded to "HH:MM"), each with a "max" percent when [withMax]; a bad time throws. */
    private fun ranges(given: JSONArray, withMax: Boolean): JSONArray {
        val list = JSONArray()
        for (i in 0 until minOf(given.length(), 8)) {
            val r = given.getJSONObject(i)
            val from = r.optString("from"); val to = r.optString("to")
            if (!HHMM.matches(from) || !HHMM.matches(to)) throw IllegalArgumentException("bad time")
            val one = JSONObject().put("from", pad(from)).put("to", pad(to))
            if (withMax) one.put("max", r.optInt("max", 30).coerceIn(0, 100))
            list.put(one)
        }
        return list
    }

    /** The sleep timer has gone off (unless it was set again meanwhile). */
    @Synchronized
    fun clearSleep(c: Context, at: Long) {
        val o = load(c)
        if (o.optLong("sleep_at") == at) save(c, o.put("sleep_at", 0L))
    }

    private val PIN = Regex("\\d{4,8}")
    private const val SELF = "dev.tvremote.setup"

    /** The apps a PIN is asked for. */
    fun locked(o: JSONObject): List<String> = o.optJSONObject("lock")?.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

    /** Whether [pin] is the one set (and the PIN is still a way in: the parent may keep only the Authenticator). */
    fun pinOk(o: JSONObject, pin: String): Boolean {
        val lock = o.optJSONObject("lock") ?: return false
        return lock.has("hash") && lock.optJSONObject("methods")?.optBoolean("pin", true) != false && hash(lock.optString("salt"), pin) == lock.optString("hash")
    }

    /** The Authenticator is on: a code was proved right when it was set up. */
    fun totpOn(o: JSONObject): Boolean = o.optJSONObject("lock")?.optString("totp")?.isNotEmpty() == true

    /** [code] is the Authenticator's now; [driftMs] the TV clock's offset ([Clock.driftMs]). */
    fun totpOk(o: JSONObject, code: String, now: Long, driftMs: Long): Boolean =
        totpOn(o) && Totp.verify(o.getJSONObject("lock").optString("totp"), code, now - driftMs)

    /** Why the way in, by method: {pin, totp, ask}. The PIN is on unless turned off; the Authenticator once set; asking a parent when asked for. */
    fun methods(o: JSONObject): JSONObject {
        val m = o.optJSONObject("lock")?.optJSONObject("methods")
        return JSONObject().put("pin", o.optJSONObject("lock")?.has("hash") == true && m?.optBoolean("pin", true) != false).put("totp", totpOn(o)).put("ask", m?.optBoolean("ask") == true)
    }

    /** A PIN or an Authenticator is set: TV Setup's own rule screens then ask it too ([Guard.RULE_SCREENS]). */
    fun hasCode(o: JSONObject): Boolean = methods(o).let { it.optBoolean("pin") || it.optBoolean("totp") }

    fun kidsMode(o: JSONObject): Boolean = o.optJSONObject("lock")?.optString("mode") == "kids"

    /** In kids mode, the apps a child may open without a code. */
    fun allow(o: JSONObject): List<String> = o.optJSONObject("lock")?.optJSONArray("allow")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

    /** Everything is open until then (0 when not). */
    fun unlockUntil(o: JSONObject, now: Long): Long = o.optLong("unlock_until").let { if (it > now) it else 0L }

    /**
     * Whether [pkg] on screen must be covered by [PinActivity] now. [free]: the apps never asked ([Kids.free]). Open: the whole TV
     * unlocked for a while, or this app granted by a parent. Else the locked apps always ask, and in kids mode every app but the allowed and the free ones.
     */
    fun needsPin(o: JSONObject, pkg: String, now: Long, free: Set<String>): Boolean {
        if (pkg.isEmpty() || pkg == SELF) return false
        if (unlockUntil(o, now) > 0 || (o.optJSONObject("unlock_apps")?.optLong(pkg) ?: 0L) > now) return false
        return pkg in locked(o) || (kidsMode(o) && pkg !in free && pkg !in allow(o))
    }

    // ---- total minutes a day on the TV
    /** The day's minutes of the total limit (without extra), null when it is off or today is not one of its days. */
    fun totalMin(o: JSONObject, now: Long): Int? {
        val t = o.optJSONObject("total")?.takeIf { it.optInt("min") > 0 } ?: return null
        val days = t.optJSONArray("days")
        if (days != null && days.length() > 0 && (0 until days.length()).none { days.optInt(it) == isoDay(now) }) return null
        return t.optInt("min")
    }

    /** Minutes granted today on top of the total. */
    fun totalExtra(o: JSONObject, now: Long): Int = o.optJSONObject("total_extra")?.optInt(day(now)) ?: 0

    /** Seconds on the TV today: every app's but the [skip]ped ones (the home screen, TV Setup, the keyboard: passing through is not watching). */
    fun totalUsedSec(used: JSONObject, skip: Set<String>): Long = used.keys().asSequence().filter { it !in skip }.sumOf { used.optLong(it) }

    /** Seconds left of today's total with its extra; null when there is none. Zero or less: used up. */
    fun totalLeftSec(o: JSONObject, used: JSONObject, skip: Set<String>, now: Long): Long? =
        totalMin(o, now)?.let { (it + totalExtra(o, now)) * 60L - totalUsedSec(used, skip) }

    private fun hash(salt: String, pin: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    const val RAM_MIN = 75
    const val RAM_MAX = 97

    /** The memory notice: {on, pct}, off at 90% until a remote turns it on. */
    fun ram(o: JSONObject): JSONObject = o.optJSONObject("ram") ?: JSONObject().put("on", false).put("pct", 90)

    private fun pad(hhmm: String): String = hhmm.split(":").let { it[0].padStart(2, '0') + ":" + it[1] }

    /** "Mon – Fri", "Mon, Wed, Sun", "Every day": the days of a schedule in a sentence (the page's dayText does the same). */
    fun dayText(days: JSONArray?): String {
        val list = (0 until (days?.length() ?: 0)).map { days!!.optInt(it) }
        if (list.size == 7) return dev.tvremote.core.T("auto.every_day")
        val runs = ArrayList<IntArray>()
        for (d in list) { val last = runs.lastOrNull(); if (last != null && last[1] == d - 1) last[1] = d else runs.add(intArrayOf(d, d)) }
        fun n(d: Int) = dev.tvremote.core.T("auto.day_$d")
        return runs.joinToString(", ") { (a, b) -> if (a == b) n(a) else n(a) + (if (b == a + 1) ", " else " – ") + n(b) }
    }

    /** The packages with a schedule. */
    fun schedApps(o: JSONObject): List<String> = o.optJSONObject("sched")?.keys()?.asSequence()?.toList() ?: emptyList()

    /** Monday = 1 … Sunday = 7 at [now] (Calendar counts from Sunday). */
    private fun isoDay(now: Long): Int = (Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

    /**
     * Whether [pkg] may be on screen at [now]: true with no schedule. A window that runs past midnight (22:00–02:00) belongs to the day
     * it begins on, so Friday's still runs at 01:00 on Saturday.
     */
    fun allowedNow(o: JSONObject, pkg: String, now: Long): Boolean {
        val s = o.optJSONObject("sched")?.optJSONObject(pkg) ?: return true
        if (s.optBoolean("block")) return false
        val list = windows(s)
        return (0 until list.length()).any { inWindow(list.optJSONObject(it) ?: return@any false, now) }   // any one window is enough
    }

    /** [now] inside one window {days, from, to}: the time by [within] (midnight crossing as bedtime's ranges), the day it began on. */
    private fun inWindow(w: JSONObject, now: Long): Boolean {
        val days = w.optJSONArray("days") ?: return false
        val from = w.optString("from"); val to = w.optString("to")
        if (!within(from, to, now)) return false
        val today = isoDay(now)
        val has = { day: Int -> (0 until days.length()).any { days.optInt(it) == day } }
        val past = Calendar.getInstance().apply { timeInMillis = now }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } < from.split(":")[0].toInt() * 60 + from.split(":")[1].toInt()
        return has(if (past) (today + 5) % 7 + 1 else today)       // before "from" inside a window = the night of the day before
    }

    /** "Mon – Fri 16:00–18:00 · Sat, Sun 08:00–22:00": a schedule's windows in a sentence (the page's schedText does the same). */
    fun windowsText(s: JSONObject): String {
        val list = windows(s)
        return (0 until list.length()).joinToString(" · ") { val w = list.getJSONObject(it); dayText(w.optJSONArray("days")) + " " + w.optString("from") + "–" + w.optString("to") }
    }

    /** The minutes [pkg] may use today before it is closed (extra grants not counted): the lowest of its daily limit and its schedule's cap inside the window; null for none. */
    fun budget(o: JSONObject, pkg: String, now: Long): Int? {
        val cap = o.optJSONObject("sched")?.optJSONObject(pkg)?.optInt("max")?.takeIf { it > 0 && allowedNow(o, pkg, now) }
        val limit = o.optJSONObject("limits")?.optInt(pkg)?.takeIf { it > 0 }
        return if (cap != null && limit != null) minOf(cap, limit) else cap ?: limit
    }

    /** Minutes of today granted on top of [pkg]'s limit. */
    fun extra(o: JSONObject, pkg: String, now: Long): Int = o.optJSONObject("extra")?.optJSONObject(day(now))?.optInt(pkg) ?: 0

    /** {on, ranges, from, to}: from and to are the first range, what remotes older than several ranges read. */
    private fun bedtime(on: Boolean, ranges: JSONArray): JSONObject {
        val first = ranges.optJSONObject(0)
        return JSONObject().put("on", on).put("ranges", ranges).put("from", first?.optString("from") ?: "22:00").put("to", first?.optString("to") ?: "06:30")
    }

    /** The bedtime as stored, a TV Setup older than several ranges having kept only from and to. */
    fun bedtime(o: JSONObject): JSONObject {
        val b = o.optJSONObject("bedtime") ?: return bedtime(false, JSONArray().put(JSONObject().put("from", "22:00").put("to", "06:30")))
        return bedtime(b.optBoolean("on"), b.optJSONArray("ranges")
            ?: JSONArray().put(JSONObject().put("from", b.optString("from", "22:00")).put("to", b.optString("to", "06:30"))))
    }

    /** The bedtime range [now] falls in ({from, to}, which may run past midnight), null outside them or with bedtime off. */
    fun bedNow(o: JSONObject, now: Long): JSONObject? {
        val b = bedtime(o)
        if (!b.optBoolean("on")) return null
        val list = b.getJSONArray("ranges")
        return (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { within(it.optString("from"), it.optString("to"), now) }
    }

    fun inBedtime(o: JSONObject, now: Long): Boolean = bedNow(o, now) != null

    /** Minutes from [now] to the end of the bedtime range it is in (1 when the range is unreadable, so a grant is never zero). */
    fun bedLeftMin(o: JSONObject, now: Long): Int {
        val to = bedNow(o, now)?.optString("to") ?: return 1
        if (!HHMM.matches(to)) return 1
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val end = to.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        return maxOf(1, (end - m + 1440) % 1440)
    }

    /** Something protects the rules: a PIN or the Authenticator. Without one, "unlock" on a notice needs no code (nothing to prove). */
    fun hasLock(o: JSONObject): Boolean = o.optJSONObject("lock")?.has("hash") == true || totpOn(o)

    /** The volume range [now] is in ({from, to, max}), the lowest when two overlap; null outside them all. */
    fun volumeCap(o: JSONObject, now: Long): JSONObject? {
        val list = o.optJSONArray("volume") ?: return null
        var cap: JSONObject? = null
        for (i in 0 until list.length()) {
            val r = list.optJSONObject(i) ?: continue
            if (within(r.optString("from"), r.optString("to"), now) && (cap == null || r.optInt("max") < cap.optInt("max"))) cap = r
        }
        return cap
    }

    /** Whether [now] is between two times of day, a window that may run past midnight; the same from and to is never. */
    private fun within(fromHm: String, toHm: String, now: Long): Boolean {
        if (!HHMM.matches(fromHm) || !HHMM.matches(toHm)) return false
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val mins = { s: String -> s.split(":").let { it[0].toInt() * 60 + it[1].toInt() } }
        val from = mins(fromHm)
        val to = mins(toHm)
        return if (from == to) false else if (from < to) m in from until to else m >= from || m < to
    }

    /** What the pages show. `now` is the TV's clock, so a remote with a wrong clock still counts down right. */
    fun state(c: Context): JSONObject {
        val o = load(c)
        val now = System.currentTimeMillis()
        val used = try { Stats(c).today(now, true).first } catch (e: Exception) { JSONObject() }
        val limits = JSONArray()
        o.optJSONObject("limits")?.let { l ->
            for (pkg in l.keys()) limits.put(JSONObject().put("pkg", pkg).put("label", label(c, pkg)).put("minutes", l.optInt(pkg))
                .put("extra", extra(o, pkg, now)).put("used", used.optLong(pkg) / 60))
        }
        val sched = JSONArray()
        o.optJSONObject("sched")?.let { l ->
            for (pkg in l.keys()) sched.put(JSONObject(l.getJSONObject(pkg).toString()).also { if (!it.optBoolean("block")) it.put("windows", windows(it)) }.put("pkg", pkg)   // data saved before windows reads as one.put("label", label(c, pkg))
                .put("now", allowedNow(o, pkg, now)).put("used", used.optLong(pkg) / 60).put("extra", extra(o, pkg, now)))
        }
        val sleepAt = o.optLong("sleep_at").let { if (it > now) it else 0L }
        return JSONObject().put("ok", true).put("now", now).put("sleep_at", sleepAt)
            .put("bedtime", bedtime(o))
            .put("in_bedtime", inBedtime(o, now)).put("snooze_until", o.optLong("snooze_until").let { if (it > now) it else 0L })
            .put("limits", limits).put("volume", o.optJSONArray("volume") ?: JSONArray()).put("volume_now", volumeCap(o, now) ?: JSONObject.NULL)
            .put("ram", ram(o)).put("volume_fixed", Guard.volumeFixed(c)).put("running", RemoteService.isRunning()).put("wanted", active(c))
            .put("adb", RemoteService.adbState()).put("awake", o.optBoolean("awake")).put("holdkeys", o.optBoolean("holdkeys")).put("mic", o.optBoolean("mic")).put("homekey", o.optString("homekey"))
            .put("keys", KeysBridge.bound())                           // the key filter is up: LauncherSwap.byKey keeps Home only then (issue #9)
            .put("sched", sched)
            .put("lock", JSONObject().put("pin", o.optJSONObject("lock")?.has("hash") == true)
                .put("apps", JSONArray(locked(o).map { JSONObject().put("pkg", it).put("label", label(c, it)) }))
                .put("mode", if (kidsMode(o)) "kids" else "list").put("allow", JSONArray(allow(o))).put("methods", methods(o))
                .put("wait_until", Lockout.until(c, now)).put("recovery_left", recoveryLeft(o)))
            .put("unlock_until", unlockUntil(o, now))
            .put("total", JSONObject().put("min", o.optJSONObject("total")?.optInt("min") ?: 0).put("used", Kids.usedToday(c, used) / 60).put("extra", totalExtra(o, now))
                .put("warn", o.optJSONObject("total")?.optInt("warn", 5) ?: 5).put("days", o.optJSONObject("total")?.optJSONArray("days") ?: JSONArray()))
            .put("clock", Clock.info(c))
    }

    private fun label(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg.substringAfterLast('.') }
}

/** A wrong Authenticator code while turning it on: [clientDriftS] is the TV's clock minus the sending client's, [clock] what [Clock.info] says (set by [Rules.change]). */
internal class CodeWrong(val clientDriftS: Long?) : IllegalArgumentException("code_wrong") {
    var clock: JSONObject = JSONObject()
}
