package dev.tvremote.home.widgets

import android.content.Context
import dev.tvremote.update.SignedList
import org.json.JSONObject
import java.io.File

/**
 * Where the Market and News widgets read from: the site's signed list (docs/home/feeds.json, made by dev/sign/sign_feeds.py), else GitHub,
 * else the copy kept in files/, else the one packed in the APK. Same pattern as the ready-made themes ([dev.tvremote.home.Themes]).
 * A source that moves or blocks the app is changed in the list, with no new APK; an unsigned list is ignored.
 */
object Sources {
    class Src(val kind: String, val url: String, val name: String)
    class Feed(val id: String, val name: String, val lang: String, val url: String)
    class All(val rev: Long, val rates: Src?, val gold: List<Src>, val crypto: Src?, val news: List<Feed>, val fuel: List<Src> = emptyList())

    private val BASES = listOf("https://tvremote.pages.dev/home/", "https://raw.githubusercontent.com/HoangTran0410/tv-remote/main/docs/home/")
    private const val NAME = "feeds.json"
    private const val DAY = 24 * 3600_000L
    private val KINDS = mapOf("rates" to setOf("vcb"), "gold" to setOf("btmc", "pnj"), "crypto" to setOf("binance"), "fuel" to setOf("saigonpetro", "webgia"))   // what Market.kt can read; an unknown kind is skipped

    @Volatile var now: All = All(0, null, emptyList(), null, emptyList())

    /** The list as the app uses it: only https addresses, only kinds this build can read. Null when it is not a list. */
    fun parse(json: String): All? = try {
        val o = JSONObject(json)
        if (o.optInt("v") != 1) null
        else {
            fun src(x: JSONObject?, group: String) = x?.takeIf { it.optString("kind") in KINDS.getValue(group) && it.optString("url").startsWith("https://") }
                ?.let { Src(it.getString("kind"), it.getString("url"), it.optString("src")) }
            val g = o.optJSONArray("gold")
            val news = o.optJSONArray("news")
            val fuel = o.optJSONArray("fuel")
            All(o.optLong("rev"), src(o.optJSONObject("rates"), "rates"),
                (0 until (g?.length() ?: 0)).mapNotNull { src(g!!.optJSONObject(it), "gold") },
                src(o.optJSONObject("crypto"), "crypto"),
                (0 until (news?.length() ?: 0)).mapNotNull { i ->
                    news!!.optJSONObject(i)?.takeIf { it.optString("url").startsWith("https://") && it.optString("id").isNotEmpty() }
                        ?.let { Feed(it.getString("id"), it.optString("name", it.getString("id")), it.optString("lang"), it.getString("url")) }
                },
                (0 until (fuel?.length() ?: 0)).mapNotNull { src(fuel!!.optJSONObject(it), "fuel") })
        }
    } catch (e: Exception) { null }

    /** The default feeds for TV Home's language: its own language's, or English for any other. */
    fun feedsFor(lang: String, all: All = now): List<Feed> = all.news.filter { it.lang == lang }.ifEmpty { all.news.filter { it.lang == "en" } }

    private fun rev(b: ByteArray) = try { JSONObject(String(b, Charsets.UTF_8)).optLong("rev", 0) } catch (e: Exception) { 0L }

    fun start(c: Context) {
        val app = c.applicationContext
        val f = File(app.filesDir, NAME)
        var bytes = SignedList.kept(f)
        val packed = SignedList.packed({ app.assets.open(it) }, NAME)
        if (packed != null && rev(packed) > (bytes?.let { rev(it) } ?: -1)) {   // an APK newer than the site carries a newer list
            bytes = packed
            try { SignedList.keep(f, packed, app.assets.open("$NAME.sig").use { String(it.readBytes(), Charsets.UTF_8) }); f.setLastModified(1) } catch (e: Exception) { }
        }
        val mine = bytes?.let { rev(it) } ?: -1
        bytes?.let { parse(String(it, Charsets.UTF_8)) }?.let { now = it }
        if (dev.tvremote.guard.Age.fresh(f.lastModified(), DAY)) return
        Thread({
            try {
                SignedList.fetch(BASES, NAME, 1 shl 18, connectMs = 6000, readMs = 8000) { list, sig, _ ->
                    val p = parse(String(list, Charsets.UTF_8))
                    if (p == null || p.rev < mine) null
                    else { SignedList.keep(f, list, sig); now = p; true }
                }
            } catch (e: Exception) { }
        }, "feeds").apply { isDaemon = true }.start()
    }
}
