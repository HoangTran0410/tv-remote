package dev.tvremote.setup.rules

import android.content.Context
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * How far the TV's clock is from the real time. Google Authenticator's codes come from the phone's clock, and many TVs (no
 * battery, "automatic date & time" off, a box that never reached the network) are minutes out, so a TOTP code is checked against
 * [now] and not [System.currentTimeMillis]. The real time comes from, best first: the `Date` header of a plain HTTPS answer
 * (once a day while TOTP is on), the `date` of a Telegram message (only ever late, so the smallest reading of a day is the best),
 * and the clock of the client that set the code up. The reading is kept with the TV's wall and boot clocks of that moment: a
 * person changing the TV's clock afterwards is seen as a jump between the two and cancelled out, instead of trusting a stale offset.
 */
internal object Clock {
    private const val PREFS = "clock"
    const val HTTP = "http"
    const val TELEGRAM = "telegram"
    const val CLIENT = "client"
    private const val FRESH_MS = 24 * 3_600_000L
    private const val STALE_MS = 36 * 3_600_000L

    /** [driftMs]: the TV's clock minus the real time when it was read, at [wallAt] (the TV's clock) / [elapsedAt] (its boot clock). */
    class Obs(val driftMs: Long, val from: String, val wallAt: Long, val elapsedAt: Long)

    private fun rank(from: String) = when (from) { HTTP -> 3; TELEGRAM -> 2; else -> 1 }

    /** The offset now: the stored one, corrected for any jump of the TV's clock since (a reboot resets the boot clock: then it is kept as it was). */
    fun drift(o: Obs?, wall: Long, elapsed: Long): Long {
        if (o == null) return 0L
        if (elapsed < o.elapsedAt) return o.driftMs
        return o.driftMs + (wall - (o.wallAt + (elapsed - o.elapsedAt)))
    }

    /** Whether [new] replaces [old]: a better source does; the same source does (a Telegram reading only when it is lower, or the old one is a day old); a worse one only once the old one is stale. [force]: the client that just set TOTP up, whose phone is the Authenticator. */
    fun better(old: Obs?, new: Obs, wall: Long, elapsed: Long, force: Boolean = false): Boolean {
        if (old == null || force) return true
        val age = if (elapsed < old.elapsedAt) Long.MAX_VALUE else elapsed - old.elapsedAt
        val a = rank(new.from); val b = rank(old.from)
        if (a > b) return true
        if (a < b) return age > STALE_MS
        if (new.from == TELEGRAM && age < FRESH_MS) return drift(new, wall, elapsed) < drift(old, wall, elapsed)   // latency only adds: the lowest is the truest
        return true
    }

    /** "Sat, 24 Oct 2026 10:15:30 GMT" to ms; null when it is not one. */
    fun parseDate(h: String?): Long? = try {
        if (h.isNullOrBlank()) null else java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(h.trim())?.time   // not java.time: API 26, an Error on Android 7
    } catch (e: Exception) { null }

    private fun load(c: Context): Obs? {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("from")) return null
        return Obs(p.getLong("drift", 0L), p.getString("from", "") ?: "", p.getLong("wall", 0L), p.getLong("elapsed", 0L))
    }

    /** The TV's clock minus the real time, in ms; 0 with no reading yet. */
    fun driftMs(c: Context): Long = drift(load(c), System.currentTimeMillis(), SystemClock.elapsedRealtime())

    /** Whether any reading is stored: with none, [driftMs] is 0 because nothing is known, not because the clock is right. */
    fun hasReading(c: Context): Boolean = load(c) != null

    /** The real time as best known. */
    fun now(c: Context): Long = System.currentTimeMillis() - driftMs(c)

    /** [realMs] is the real time as [from] told it, read at this moment. */
    @Synchronized
    fun note(c: Context, realMs: Long, from: String, force: Boolean = false) {
        val wall = System.currentTimeMillis(); val el = SystemClock.elapsedRealtime()
        val new = Obs(wall - realMs, from, wall, el)
        if (!better(load(c), new, wall, el, force)) return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("drift", new.driftMs).putString("from", from).putLong("wall", wall).putLong("elapsed", el).commit()
    }

    /** True when a reading from the network (the best kind) is missing or a day old. */
    fun due(c: Context): Boolean {
        val o = load(c) ?: return true
        val age = SystemClock.elapsedRealtime() - o.elapsedAt
        return o.from != HTTP || age < 0 || age > FRESH_MS
    }

    @Volatile private var tried = 0L

    /** [due] and not tried in the last half hour: asks the network (off the main thread: the Guard's own). */
    fun maybeCheck(c: Context) {
        val el = SystemClock.elapsedRealtime()
        if (!due(c) || (tried != 0L && el - tried < 30 * 60_000L && el >= tried)) return
        tried = el
        check(c)
    }

    /** [maybeCheck] on its own thread, started only when it would ask (a caller on a request thread asks on every request). */
    @Synchronized fun maybeCheckSoon(c: Context) {
        val el = SystemClock.elapsedRealtime()
        if (!due(c) || (tried != 0L && el - tried < 30 * 60_000L && el >= tried)) return
        tried = el
        Thread({ check(c) }, "clock-check").apply { isDaemon = true }.start()
    }

    /** HEAD to a page every network answers briefly, and the `Date` of the answer. False when neither address answered. Blocks: never on the main thread. */
    fun check(c: Context): Boolean {
        for (u in listOf("https://www.google.com/generate_204", "https://cloudflare.com/")) {
            val t0 = System.currentTimeMillis()
            val date = try {
                val h = URL(u).openConnection() as HttpURLConnection
                h.requestMethod = "HEAD"; h.connectTimeout = 6000; h.readTimeout = 6000; h.instanceFollowRedirects = false
                try { h.responseCode; parseDate(h.getHeaderField("Date")) } finally { h.disconnect() }
            } catch (e: Exception) { null } ?: continue
            val t1 = System.currentTimeMillis()
            note(c, date + 500 + (t1 - t0) / 2, HTTP)   // the Date has whole seconds (+0.5 s on average) and was made about halfway through the call; note() reads the TV's clock now, at its end
            return true
        }
        return false
    }

    /** What the status shows: {auto (the TV's "automatic date & time"), zone, drift_s (TV minus real), from, at (ms of the reading, 0 for none)}. */
    fun info(c: Context): JSONObject {
        val o = load(c)
        val auto = try { android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.AUTO_TIME, 0) == 1 } catch (e: Exception) { false }
        return JSONObject().put("auto", auto).put("zone", java.util.TimeZone.getDefault().id)
            .put("drift_s", Math.round(driftMs(c) / 1000.0).toInt()).put("from", o?.from ?: "").put("at", o?.wallAt ?: 0L)
    }
}
