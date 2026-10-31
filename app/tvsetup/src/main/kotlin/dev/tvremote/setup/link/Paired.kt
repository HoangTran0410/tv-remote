package dev.tvremote.setup.link

import android.content.Context
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.rules.Clock
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * The devices let in (a browser after the pairing code, or the Phone / Computer app through TrustReceiver). Only the SHA-256
 * of each pass is kept, so reading the prefs does not give a pass. A browser's pass lasts 30 days from its last use (the
 * cookie is renewed daily while it is used); an app's does not run out, but is listed and can be forgotten like the others.
 */
object Paired {
    private const val PREFS = "remote"
    const val WEB_DAYS = 30
    private const val DAY = 24 * 3600_000L
    private const val WRITE_EVERY = 60_000L                           // `seen` is written to the prefs once a minute at most, not per request

    /** [scope]: what it may do here (Scope.kt); apps and the code on the TV's screen give "full", a shared QR what its maker picked. */
    class Entry(val id: String, val h: String, var name: String, val ip: String, val created: Long, var seen: Long, val kind: String, val tls: Boolean, var scope: String = Scope.FULL) {
        fun json(me: Boolean = false): JSONObject =
            JSONObject().put("id", id).put("name", name).put("ip", ip).put("created", created).put("seen", seen).put("kind", kind).put("tls", tls).put("me", me).put("scope", scope)
        fun store(): JSONObject = JSONObject().put("id", id).put("h", h).put("name", name).put("ip", ip).put("created", created).put("seen", seen).put("kind", kind).put("tls", tls).put("scope", scope)
    }

    /** What [check] found: the device, and whether its cookie should be sent again with a new lifetime. */
    class Hit(val entry: Entry, val renew: Boolean)

    private val random = SecureRandom()
    private var list: MutableList<Entry>? = null
    private var written = HashMap<String, Long>()                      // id -> `seen` as last written

    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { String.format("%02x", it) }

    private fun hex(n: Int): String = ByteArray(n).also { random.nextBytes(it) }.joinToString("") { String.format("%02x", it) }

    @Synchronized
    private fun all(c: Context): MutableList<Entry> {
        list?.let { return it }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = ArrayList<Entry>()
        val now = System.currentTimeMillis()
        val raw = p.getString("paired", null)
        if (raw != null) {
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    out.add(Entry(it.getString("id"), it.getString("h"), it.optString("name"), it.optString("ip"), it.optLong("created"), it.optLong("seen"), it.optString("kind", "web"), it.optBoolean("tls"), Scope.of(it.optString("scope", Scope.FULL))))
                }
            } catch (e: Exception) { out.clear() }
        } else {                                                      // before 0.x: a plain set of passes; each becomes a device, seen now
            for (tok in p.getStringSet("tokens", null) ?: emptySet()) out.add(Entry(hex(4), sha256(tok), "", "", 0L, now, "web", false))   // created 0: when is not known
            if (out.isNotEmpty()) p.edit().putString("paired", JSONArray(out.map { it.store() }).toString()).remove("tokens").apply()
        }
        out.forEach { written[it.id] = it.seen }
        list = out
        return out
    }

    private fun save(c: Context) {
        val l = list ?: return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("paired", JSONArray(l.map { it.store() }).toString()).remove("tokens").commit()
        l.forEach { written[it.id] = it.seen }
    }

    /** A browser unseen for [WEB_DAYS] days. A clock before its creation is a box in the wrong year: nothing is known then, so it stays. */
    internal fun stale(e: Entry, now: Long): Boolean = e.kind == "web" && now >= e.created && now - e.seen > WEB_DAYS * DAY

    /** The newest created / seen of any entry: the last moment the clock is known to have been at. */
    private fun newest(l: List<Entry>): Long = l.maxOfOrNull { maxOf(it.created, it.seen) } ?: 0L

    /**
     * Whether the clock cannot be trusted now: a stored reading says it is a day or more off, or there is no reading (it exists
     * only with TOTP / Telegram / client setup) and the clock stands more than a day past everything this list ever saw, which a
     * box that booted a year ahead looks like. Then nothing is dropped and `seen` stays (see [seenAfter]) until a reading of the
     * real time ([Clock.maybeCheck], asked for by [check]) or a new pairing says which it is.
     */
    internal fun suspect(l: List<Entry>, now: Long, hasReading: Boolean, drift: Long): Boolean =
        if (hasReading) Math.abs(drift) >= DAY else l.isNotEmpty() && now - newest(l) > DAY

    /** Drops the browsers unseen for [WEB_DAYS] days unless the clock is [suspect]. True when any went. */
    internal fun dropStale(l: MutableList<Entry>, now: Long, hasReading: Boolean, drift: Long): Boolean =
        !suspect(l, now, hasReading, drift) && l.removeAll { stale(it, now) }

    /** The `seen` of [e] after a use at [now]: never lower, and kept under a [suspect] clock, so a wrong year is never written into it (a day a request would reach it after a year of requests). */
    internal fun seenAfter(l: List<Entry>, e: Entry, now: Long, hasReading: Boolean, drift: Long): Long =
        if (suspect(l, now, hasReading, drift)) e.seen else maxOf(e.seen, now)

    /** Devices that are not seen for [WEB_DAYS] days are dropped (apps stay), unless the clock is [suspect]: a box that booted a year ahead must not forget every browser. */
    private fun prune(c: Context, now: Long) {
        val l = all(c)
        if (dropStale(l, now, Clock.hasReading(c), Clock.driftMs(c))) save(c)
    }

    @Synchronized fun count(c: Context): Int { prune(c, System.currentTimeMillis()); return all(c).size }

    @Synchronized fun list(c: Context): List<Entry> { prune(c, System.currentTimeMillis()); return all(c).sortedByDescending { it.seen } }

    /** A new pass, returned in the clear this once. */
    @Synchronized
    fun add(c: Context, kind: String, name: String, ip: String, tls: Boolean, scope: String = Scope.FULL): String {
        val token = hex(24)
        val now = System.currentTimeMillis()
        all(c).add(Entry(hex(4), sha256(token), name.take(60), ip, now, now, kind, tls, Scope.of(scope)))
        save(c)
        RemoteService.alert("pair", "name" to name.take(60), "ip" to ip, "kind" to kind)   // Telegram /alerts: a new device holds a pass
        return token
    }

    /** The device that holds [token], if it may use it here: a pass made over TLS never works over plain HTTP. */
    @Synchronized
    fun check(c: Context, token: String, tls: Boolean): Hit? {
        if (token.isEmpty()) return null
        val now = System.currentTimeMillis()
        prune(c, System.currentTimeMillis())
        val h = sha256(token).toByteArray()
        val e = all(c).firstOrNull { MessageDigest.isEqual(it.h.toByteArray(), h) } ?: return null
        if (e.tls && !tls) return null
        val known = Clock.hasReading(c); val drift = Clock.driftMs(c)
        val doubt = suspect(all(c), now, known, drift)
        if (doubt && !known) Clock.maybeCheckSoon(c)                    // the network says whether this is a wrong year or a long absence (half-hourly at most)
        val renew = e.kind == "web" && !doubt && now - e.seen > DAY
        e.seen = seenAfter(all(c), e, now, known, drift)                 // never lowered by a clock set back (the next right clock would drop the pass), nor raised by one set ahead
        if (renew || now - (written[e.id] ?: 0L) > WRITE_EVERY) save(c)
        return Hit(e, renew)
    }

    @Synchronized fun forget(c: Context, id: String): Boolean { val r = all(c).removeAll { it.id == id }; if (r) save(c); return r }

    /** The owner changes what a device may do; an app (the Phone or Computer app, over ADB) stays the owner's. */
    @Synchronized fun setScope(c: Context, id: String, scope: String): Boolean {
        val e = all(c).firstOrNull { it.id == id && it.kind == "web" } ?: return false
        if (scope !in Scope.ALL) return false
        e.scope = scope; save(c); return true
    }

    @Synchronized fun forgetAll(c: Context) { all(c).clear(); save(c) }

    /** What stays after a check-out: the Phone / Computer apps' passes (the owner's); every browser (kind "web", a shared QR's too) goes. */
    fun withoutBrowsers(l: List<Entry>): List<Entry> = l.filter { it.kind != "web" }

    /** Check-out: every browser forgotten, so a guest who left cannot use the TV's page from the same Wi-Fi. How many went. */
    @Synchronized fun forgetBrowsers(c: Context): Int {
        val l = all(c)
        val keep = withoutBrowsers(l)
        val gone = l.size - keep.size
        if (gone > 0) { l.retainAll(keep.toSet()); save(c) }
        return gone
    }

    /** "iPhone · Safari" from a browser's User-Agent. */
    fun deviceName(ua: String): String {
        val os = when {
            ua.contains("iPhone") -> "iPhone"
            ua.contains("iPad") -> "iPad"
            ua.contains("Android") -> "Android"
            ua.contains("CrOS") -> "Chromebook"
            ua.contains("Mac OS X") || ua.contains("Macintosh") -> "Mac"
            ua.contains("Windows") -> "Windows"
            ua.contains("Linux") -> "Linux"
            else -> ""
        }
        val browser = when {
            ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/") -> "Edge"
            ua.contains("OPR/") || ua.contains("OPiOS/") -> "Opera"
            ua.contains("Firefox/") || ua.contains("FxiOS/") -> "Firefox"
            ua.contains("SamsungBrowser/") -> "Samsung Internet"
            ua.contains("Chrome/") || ua.contains("CriOS/") -> "Chrome"
            ua.contains("Safari/") -> "Safari"
            else -> ""
        }
        return listOf(os, browser).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Web" }
    }
}
