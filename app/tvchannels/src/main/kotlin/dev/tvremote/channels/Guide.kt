package dev.tvremote.channels

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One programme: its title and when it runs (ms). */
class Show(val title: String, val start: Long, val end: Long) {
    var ch: Channel? = null                                           // whose guide it is (set by Guide.of: OK on a future row sets a reminder)
}

/**
 * The programme guide: what a channel shows today (and tomorrow where the provider tells), from the providers with a "guide"
 * in providers.json: TV360 knows channels by name, VieON by its own id ([Channel.guide]). The first that knows the channel
 * answers. A day's guide is kept 6 hours in files/guide/ (the player's banner has it at once), "none" 20 minutes in memory.
 */
object Guide {
    private const val KEEP = 6 * 3600_000L
    private const val MISS = 20 * 60_000L
    @Volatile private var pruned = false
    private val mem = HashMap<String, Pair<Long, List<Show>>>()       // channel id -> (asked at, shows); empty: none

    /** Where [ch]'s guide is asked: a provider and its id there, or null. Reads the providers' list: not on the main thread. */
    fun source(c: Context, ch: List<Channel>): Pair<Providers.Provider, String>? = source(Providers.all(c), ch)

    /** A name means the provider's own channel only for a channel of its country (or of none: a person's own list): iptv-org's
     *  "Cinemax.us" in Vietnam's list is the US feed, and TV360's "Cinemax" guide is Vietnam's, other films at other times. */
    fun source(ps: List<Providers.Provider>, ch: List<Channel>): Pair<Providers.Provider, String>? {
        for (p in ps) {
            if (p.o.optString("guide").isEmpty()) continue
            ch.filter { it.country.isEmpty() || it.country == p.country }.firstNotNullOfOrNull { p.guides[Providers.key(it.name)] }?.let { return p to it }
            ch.firstOrNull { it.url.startsWith(p.id + ":") && it.guide.isNotEmpty() }?.let { return p to it.guide }
        }
        return null
    }

    /** What is kept for the channel [id], without asking: null when not asked yet (or too old). */
    fun known(id: String): List<Show>? = synchronized(mem) {
        mem[id]?.takeIf { System.currentTimeMillis() - it.first < if (it.second.isEmpty()) MISS else KEEP }?.second
    }

    /** [ch]'s shows, asked when not kept; empty when no provider has it. Network: not on the main thread. */
    fun of(c: Context, ch: List<Channel>): List<Show> {
        val id = ch.first().id
        known(id)?.let { return it }
        val shows = source(c, ch)?.let { (p, ref) -> read(c, p, ref) }.orEmpty()
        shows.forEach { it.ch = ch.first() }
        synchronized(mem) { mem[id] = System.currentTimeMillis() to shows }
        return shows
    }

    private fun read(c: Context, p: Providers.Provider, ref: String): List<Show> {
        val kind = Providers.kind(p) ?: return emptyList()
        val dir = File(c.cacheDir, "guide").apply { mkdirs() }
        if (!pruned) { pruned = true; dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 2 * 24 * 3600_000L }?.forEach { it.delete() } }   // past days' files, once a run
        val out = ArrayList<Show>()
        for (d in 0 until p.o.optInt("days", 1).coerceIn(1, 3)) {
            val day = day(p, d)
            val f = File(dir, "${p.id}-${ref.filter { it.isLetterOrDigit() || it == '-' }}-$day.json")
            val kept = f.takeIf { it.exists() && dev.tvremote.guard.Age.fresh(it.lastModified(), KEEP) }?.let { fromJson(it.readText()) }
            val got = kept ?: try { kind.guide(p, ref, day) } catch (e: Exception) { null }?.also { if (it.isNotEmpty()) keep(f, toJson(it)) }   // none yet (tomorrow comes out later in the day): asked again
            out += got ?: if (d == 0) f.takeIf { it.exists() }?.let { fromJson(it.readText()) }.orEmpty() else emptyList()
        }
        return out.sortedBy { it.start }.distinctBy { it.start }
    }

    /**
     * The day's guide kept for the next look, or not: Android (low storage) or a cleaner (TV Setup's nightly caches) may empty the
     * cache folder between [read]'s mkdirs and this write, and the guide shown now must not die of it (census 7128da05, an H96 Max).
     */
    internal fun keep(f: File, text: String) {
        try { f.parentFile?.mkdirs(); f.writeText(text) } catch (e: java.io.IOException) { }
    }

    /** The provider's date [plus] days from now, in its own time zone ("tz"): a TV abroad still asks for Vietnam's today. */
    fun day(p: Providers.Provider, plus: Int = 0): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { p.o.optString("tz").takeIf { it.isNotEmpty() }?.let { timeZone = TimeZone.getTimeZone(it) } }
            .format(Date(System.currentTimeMillis() + plus * 24 * 3600_000L))

    fun now(shows: List<Show>, t: Long = System.currentTimeMillis()) = shows.firstOrNull { t >= it.start && t < it.end }
    fun after(shows: List<Show>, t: Long = System.currentTimeMillis()) = shows.firstOrNull { it.start > t }

    /** "20:45", in the TV's own time. */
    fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))

    private fun toJson(s: List<Show>) = JSONArray(s.map { JSONArray().put(it.title).put(it.start).put(it.end) }).toString()
    private fun fromJson(s: String): List<Show>? = try {
        val a = JSONArray(s); (0 until a.length()).map { a.getJSONArray(it) }.map { Show(it.getString(0), it.getLong(1), it.getLong(2)) }
    } catch (e: org.json.JSONException) { null }
}
