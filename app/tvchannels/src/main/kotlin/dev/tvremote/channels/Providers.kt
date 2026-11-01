package dev.tvremote.channels

import android.content.Context
import dev.tvremote.update.SignedList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Channels from other places than iptv-org, by a list the site keeps: docs/channels/providers.json (dev/sign/sign_providers.py).
 * How to ask a provider is code here, one [Kind] per kind; where to ask it (addresses, headers) and whether to ask at all ("on")
 * is the list, so a provider that moves or turns the app away is fixed or switched off without a new APK. The list is signed
 * with the launcher list's key: whoever takes over the site still cannot point the TVs at streams of their own.
 *
 * A provider's channel is merged into iptv-org's channel of the same name as one more stream (first when "first"), so
 * favourites and links keep working; one iptv-org lacks becomes a channel of its own, id "<slug>.<cc>@<provider>". Its stream
 * URL is a link of ours, "<provider>:<key>", turned into the real one only when played ([resolve]): those carry a token.
 */
object Providers {
    /** [f] on a thread of its own; CompletableFuture is API 24, and TV Channels runs on Android 5-6 boxes (census 8f22ae15). */
    fun <T> async(f: () -> T): java.util.concurrent.FutureTask<T> = java.util.concurrent.FutureTask(f).also { Thread(it, "channels-async").apply { isDaemon = true }.start() }
    /**
     * The thread of a screen's own executor: closing the screen (shutdownNow) interrupts a job waiting on the network or on [join],
     * which ends that job, not the app (census 13b5562e: back pressed while the list loaded). Anything else still crashes as before.
     */
    val screenThread = java.util.concurrent.ThreadFactory { r ->
        Thread(r).apply {
            setUncaughtExceptionHandler { t, e ->
                if (generateSequence(e) { it.cause }.none { it is InterruptedException }) Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(t, e)
            }
        }
    }
    /** Its result, or what it threw (as join did). */
    fun <T> join(t: java.util.concurrent.FutureTask<T>): T = try { t.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }

    private val BASES = listOf("https://tvremote.pages.dev/channels/", "https://raw.githubusercontent.com/HoangTran0410/tv-remote/main/docs/channels/")
    private const val DAY = 24 * 3600_000L

    class Provider(val id: String, val kind: String, val name: String, val country: String, val first: Boolean, val o: JSONObject) {
        val headers: Map<String, String> = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
        val skip: Set<String> = o.optJSONArray("skip")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        /** Its key -> the name iptv-org gives the same channel, where the two differ ("vie-channel-htv2-hd" -> "HTV2"). */
        val aliases: Map<String, String> = o.optJSONObject("aliases")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
        /** The provider's guide ids by channel name, for a provider that only gives the guide (TV360: "VTV3" -> "4"), by [key]. */
        val guides: Map<String, String> = o.optJSONObject("channels")?.let { h -> h.keys().asSequence().associate { key(it) to h.optString(it) } }.orEmpty()
    }

    /**
     * One way of asking: [list] the free channels of a country, [play] the playable URL of one (null: not free / gone),
     * [guide] what one shows on [day] ("2026-10-21") from its guide [id] (null: none, or not asked). A kind may do only some.
     */
    interface Kind {
        fun list(p: Provider, get: (String) -> String?): List<Channel> = emptyList()
        fun play(p: Provider, key: String): Played? = null
        fun guide(p: Provider, id: String, day: String): List<Show>? = null
    }
    class Played(val url: String, val until: Long)

    private val KINDS: Map<String, Kind> = mapOf("vieon" to Vieon, "tv360" to Tv360, "vtvgo" to Vtvgo, "streams" to Streams)

    fun kind(p: Provider): Kind? = KINDS[p.kind]

    /** Whether it gives channels (an API's "list", or "streams" written in the list itself), not only a guide (TV360). */
    private fun lists(p: Provider) = p.o.has("list") || p.o.has("streams")

    /** The providers that are on and whose kind this app knows; a shape that is not ours is no provider, not a crash. */
    fun parse(text: String): List<Provider> = try {
        val o = JSONObject(text)
        if (o.optInt("v") != 1) emptyList() else {
            val a = o.optJSONArray("providers") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optBoolean("on", true) && it.optString("kind") in KINDS }
                .map { Provider(it.getString("id"), it.getString("kind"), it.optString("name"), it.optString("country").lowercase(), it.optBoolean("first"), it) }
        }
    } catch (e: org.json.JSONException) { emptyList() }

    @Volatile private var mem: Pair<Long, List<Provider>>? = null

    /** Whether [merge] for [cc] can run without the network (the list and each provider's channels kept, a day at most): the screen then waits for it. */
    fun warm(c: Context, cc: String): Boolean =
        (mem?.let { System.currentTimeMillis() - it.first < DAY } == true || Catalog.young(c, "providers.json")) &&
            all(c).filter { it.country == cc && lists(it) }.all { Catalog.young(c, "prov-${it.id}-$cc.json") }

    /** The list: the site's (checked, kept a day), else the last one read, else the copy packed in the APK. */
    fun all(c: Context, again: Boolean = false): List<Provider> {      // again: a refresh asked by the person reads the site's list too
        if (!again) mem?.let { if (System.currentTimeMillis() - it.first < DAY) return it.second }
        val f = File(c.filesDir, "providers.json")
        var fresh = true
        val text = if (!again && f.exists() && dev.tvremote.guard.Age.fresh(f.lastModified(), DAY)) f.readText() else
            fetchSigned()?.also { SignedList.keep(f, it.toByteArray(Charsets.UTF_8)) }
                ?: run { fresh = false; null }
                ?: f.takeIf { it.exists() }?.readText()
                ?: try { c.assets.open("providers.json").bufferedReader().readText() } catch (e: IOException) { "" }
        // not reached: the old or packed list for half an hour, then asked again (a provider switched off reaches TVs the same day)
        return parse(text).also { mem = (System.currentTimeMillis() - if (fresh) 0 else DAY - 30 * 60_000L) to it }
    }

    private fun fetchSigned(): String? = try {
        SignedList.fetch(BASES, "providers.json", 1 shl 20, connectMs = 6000, readMs = 8000) { list, _, _ -> String(list, Charsets.UTF_8).takeIf { it.isNotEmpty() } }
    } catch (e: Exception) { null }

    /** [iptv] (a country's playlist) with the providers' channels of that country merged in. */
    fun merge(c: Context, cc: String, iptv: List<Channel>, fresh: Boolean): List<Channel> {
        val ps = all(c, fresh).filter { it.country == cc && lists(it) }
        if (ps.isEmpty()) return iptv
        val byName = HashMap<String, Channel>(); for (ch in iptv) byName.getOrPut(key(ch.name)) { ch }
        val before = HashMap<String, MutableList<Channel>>()             // a provider's stream to put ahead of a channel's own
        val after = ArrayList<Channel>()
        val gots = ps.map { p -> async {          // each provider is its own server: asked together, not one after another
            Catalog.cached(c, "prov-${p.id}-$cc.json", "", fresh, null) { channelsJson(p) }?.let { fromJson(it) }.orEmpty()
        } }
        for ((p, g) in ps.zip(gots)) {
            val got = join(g)
            val own = ArrayList<Channel>()
            for (ch in got) {
                val k = ch.url.substringAfter(':')
                if (k in p.skip) continue
                val same = byName[key(p.aliases[k] ?: ch.name)]
                if (same == null) { after.add(ch); own.add(ch); continue }
                val logo = if (p.first) ch.logo.ifEmpty { same.logo } else same.logo.ifEmpty { ch.logo }   // the card shows the first stream's
                val s = ch.copy(id = same.id, name = same.name, logo = logo, group = same.group)
                if (p.first) before.getOrPut(same.id) { ArrayList() }.add(s) else after.add(s)
            }
            for (ch in own) byName.getOrPut(key(ch.name)) { ch }       // a later provider's same channel joins this one: VTVgo's Nghệ An under VieON's, not a second card
        }
        val out = ArrayList<Channel>(iptv.size + after.size)
        val done = HashSet<String>()
        for (ch in iptv) { if (done.add(ch.id)) before[ch.id]?.let { out.addAll(it) }; out.add(ch) }
        val known = iptv.map { it.id }.toSet()
        for (ch in after.filter { it.id in known } + after.filter { it.id !in known }) {   // the same id groups together (byChannel)
            if (ch.id !in known && done.add(ch.id)) before[ch.id]?.let { out.addAll(it) }   // a "first" provider ahead of another provider's channel too
            out.add(ch)
        }
        return out
    }

    /** A name as both lists would agree on it: no accents, no "HD" / "TV" / spaces ("Vĩnh Long 1 HD" = "Vinh Long TV 1"). */
    fun key(name: String): String {
        val plain = Normalizer.normalize(name.lowercase().replace('đ', 'd'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in NOISE }.joinToString("")
    }
    private val NOISE = setOf("hd", "sd", "fhd", "uhd", "4k", "tv", "kenh", "channel")

    /** Ours ("vieon:vtv1-hd") or a plain URL. */
    fun isLink(url: String) = !url.startsWith("http") && url.substringBefore(':', "").isNotEmpty()

    private val played = HashMap<String, Played>()

    /** The URL a link of ours plays now: asked of its provider, kept until shortly before it runs out. [fresh] asks again. */
    @Throws(IOException::class)
    fun resolve(c: Context, url: String, fresh: Boolean = false): String {
        if (!isLink(url)) return url
        synchronized(played) { if (!fresh) played[url]?.takeIf { it.until > System.currentTimeMillis() }?.let { return it.url } }
        val p = all(c).firstOrNull { it.id == url.substringBefore(':') } ?: throw IOException("provider off")
        val got = KINDS[p.kind]?.play(p, url.substringAfter(':')) ?: throw IOException("not free")
        synchronized(played) { played[url] = got }
        return got.url
    }

    private fun channelsJson(p: Provider): String? {
        val list = KINDS[p.kind]?.list(p) { u -> http(u, p.headers, null) } ?: return null
        if (list.isEmpty()) return null                                // a moved API: keep the last list rather than none
        return JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("logo", it.logo).put("group", it.group).put("url", it.url).put("q", it.quality).put("g", it.guide) }).toString()
    }

    private fun fromJson(s: String): List<Channel> = try {
        val a = JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it) }.map { Channel(it.getString("id"), it.getString("name"), it.optString("logo"), it.optString("group"), it.getString("url"), it.optString("q"), guide = it.optString("g")) }
    } catch (e: org.json.JSONException) { emptyList() }

    /** GET, or POST [form] (of [type]) when given; the body on 2xx, null otherwise. */
    fun http(url: String, headers: Map<String, String>, form: String?, type: String = "application/x-www-form-urlencoded"): String? {
        val k = URL(url).openConnection() as HttpURLConnection
        k.connectTimeout = 8000; k.readTimeout = 12000
        headers.forEach { (n, v) -> k.setRequestProperty(n, v) }
        if (form != null) {
            k.requestMethod = "POST"; k.doOutput = true
            k.setRequestProperty("Content-Type", type)
            k.outputStream.use { it.write(form.toByteArray()) }
        }
        return try { if (k.responseCode in 200..299) k.inputStream.bufferedReader().readText() else null } finally { k.disconnect() }
    }
}

/**
 * VieON (vieon.vn): the same calls its web page makes, without an account. The list (`{items: [...]}`, ~40-60 a page) gives
 * every live channel; "is_premium" ones need a package and are left out. Playing asks the channel's details (a form POST of its
 * slug): "permission" 206 means it can be watched, and the H.264 HLS link (HEVC does not play on many TVs) holds its expiry
 * in the path (/<md5>/<ms>/), about a day; it is kept until an hour before that, at most 6 hours.
 */
object Vieon : Providers.Kind {
    override fun list(p: Providers.Provider, get: (String) -> String?): List<Channel> {
        val out = ArrayList<Channel>()
        for (page in 0 until p.o.optInt("pages", 6)) {
            val items = get(p.o.getString("list").replace("{page}", page.toString()))?.let { parseList(it, p) } ?: break
            if (items.isEmpty()) break
            out += items
        }
        return out.distinctBy { it.url }
    }

    fun parseList(text: String, p: Providers.Provider): List<Channel>? = try {
        val a = JSONObject(text).optJSONArray("items")
        a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } }?.mapNotNull { o ->
            val slug = o.optString("slug"); val title = o.optString("title").trim()
            if (slug.isEmpty() || title.isEmpty() || o.optInt("is_premium") != 0) return@mapNotNull null
            val hd = HD.containsMatchIn(title)
            val name = HD.replace(title, "").trim().ifEmpty { title }    // "HTVC Phim HD": the card says HD by its badge
            val img = o.optJSONObject("images")
            val logo = img?.optString("livetv_logo_light")?.ifEmpty { null } ?: o.optString("image_link")
            Channel("$slug.${p.country}@${p.id}", name, logo, guessGroup(name), "${p.id}:$slug", if (hd) "1080p" else "", guide = o.optString("id"))
        }
    } catch (e: org.json.JSONException) { null }

    private val HD = Regex("(?i)\\s+(F?HD)$")
    private fun guessGroup(name: String) = if (Regex("(?i)\\b(FM|AM|radio|audio|VOH)\\b").containsMatchIn(name)) "Music" else "General"

    override fun play(p: Providers.Provider, key: String): Providers.Played? {
        val tpl = p.o.getString("form")                              // "livetv_slug=/truyen-hinh-truc-tuyen/{slug}/"
        val form = tpl.substringBefore('=') + "=" + URLEncoder.encode(tpl.substringAfter('=').replace("{slug}", key), "UTF-8")
        val text = Providers.http(p.o.getString("play"), p.headers, form) ?: return null
        return parsePlay(text, p.o.optInt("free", 206), System.currentTimeMillis())
    }

    /** Today only, whatever [day] says: its guide has no date to ask by. */
    override fun guide(p: Providers.Provider, id: String, day: String): List<Show>? {
        if (day != Guide.day(p)) return null
        return Providers.http(p.o.optString("guide").ifEmpty { return null }.replace("{id}", id), p.headers, null)?.let { parseGuide(it) }
    }

    fun parseGuide(text: String): List<Show>? = try {
        val a = org.json.JSONArray(text)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val t = o.optString("title").trim(); val s = o.optLong("time_start"); val e = o.optLong("time_end")
            if (t.isEmpty() || s <= 0 || e <= s) null else Show(t, s * 1000, e * 1000)
        }
    } catch (e: org.json.JSONException) { null }

    fun parsePlay(text: String, free: Int, now: Long): Providers.Played? = try {
        val d = JSONObject(text)
        val links = d.optJSONObject("play_links")
        fun hls(v: String?) = v?.takeIf { Regex("^https?://.+\\.m3u8").containsMatchIn(it) }
        val url = hls(links?.optJSONObject("h264")?.optString("hls")) ?: hls(d.optString("hls_link_play")) ?: hls(d.optString("link_play"))
            ?: hls(links?.optJSONObject("h265")?.optString("hls"))
        if (d.optInt("permission") != free || url == null) null else {
            val expiry = Regex("/[0-9a-f]{32}/(\\d{13})/").find(url)?.groupValues?.get(1)?.toLong()
            Providers.Played(url, if (expiry == null) now + 3600_000L else maxOf(now, minOf(expiry - 3600_000L, now + 6 * 3600_000L)))
        }
    } catch (e: org.json.JSONException) { null }
}

/**
 * VTVgo (vtvgo.vn), VTV's own free player: the same calls its web page makes, without an account. The channels are the ids the
 * list names ("ids"), their names and logos asked in one call (`{data: {"<id>": {...}}}`; "mustLogin" ones left out; "strip" a
 * regex taken off the names). Playing
 * asks the channel's sources (a JSON POST): plain HLS (drmTypeID "none") on two to four servers, signed for about 4 hours
 * ("expire", seconds). One server of them has answered 404 while the others played (2026-10-24): the first that answers is played.
 */
object Vtvgo : Providers.Kind {
    override fun list(p: Providers.Provider, get: (String) -> String?): List<Channel> {
        val all = JSONObject()
        for (part in p.o.optString("ids").split(',').map { it.trim() }.filter { it.isNotEmpty() }.chunked(20)) {   // 21 ids or more: 400 TOO_MANY_CHANNELS (2026-10-24)
            val d = get(p.o.getString("list").replace("{ids}", part.joinToString(",")))?.let { try { JSONObject(it).optJSONObject("data") } catch (e: org.json.JSONException) { null } }
                ?: return emptyList()                                  // a part missing: the last whole list is kept rather than half of one
            d.keys().forEach { all.put(it, d.get(it)) }
        }
        return parseList(JSONObject().put("data", all).toString(), p).orEmpty()
    }

    fun parseList(text: String, p: Providers.Provider): List<Channel>? = try {
        val d = JSONObject(text).optJSONObject("data")
        val order = p.o.optString("ids").split(',').map { it.trim() }
        val strip = p.o.optString("strip").takeIf { it.isNotEmpty() }?.let { try { Regex(it) } catch (e: Exception) { null } }   // "Truyền hình Nghệ An" -> "Nghệ An", the name the other lists use
        d?.let { o -> order.mapNotNull { o.optJSONObject(it) } }?.mapNotNull { o ->
            val id = o.optString("id"); val name = o.optString("name").trim().let { n -> strip?.replace(n, "")?.trim()?.ifEmpty { n } ?: n }
            if (id.isEmpty() || name.isEmpty() || o.optBoolean("mustLogin")) null
            else Channel("vtvgo$id.${p.country}@${p.id}", name, o.optString("logo"), "General", "${p.id}:$id", "")
        }
    } catch (e: org.json.JSONException) { null }

    override fun play(p: Providers.Provider, key: String): Providers.Played? {
        val text = Providers.http(p.o.getString("play"), p.headers, p.o.getString("body").replace("{id}", key), "application/json") ?: return null
        val (urls, until) = parsePlay(text, System.currentTimeMillis()) ?: return null
        val url = urls.firstOrNull { u -> try { Providers.http(u, p.headers, null) != null } catch (e: IOException) { false } } ?: return null
        return Providers.Played(url, until)
    }

    /** The plain HLS sources of the free mode, in its order, and when to ask again (half an hour before they run out). */
    fun parsePlay(text: String, now: Long): Pair<List<String>, Long>? = try {
        val modes = JSONObject(text).optJSONObject("data")?.optJSONArray("sourceModes") ?: org.json.JSONArray()
        val free = (0 until modes.length()).mapNotNull { modes.optJSONObject(it) }.firstOrNull { it.optInt("isVip") == 0 }
        val ms = free?.optJSONArray("multiSource") ?: org.json.JSONArray()
        val ok = (0 until ms.length()).mapNotNull { ms.optJSONObject(it) }
            .filter { it.optString("drmTypeID") == "none" && it.optString("manifestType") == "hls" }
        val urls = ok.mapNotNull { it.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.takeIf { u -> u.startsWith("https://") } }
        val expire = ok.mapNotNull { it.optJSONObject("drmInfo")?.optLong("expire")?.takeIf { s -> s > 0 } }.minOrNull() ?: 3600L
        if (urls.isEmpty()) null else urls to now + (expire * 1000 - 30 * 60_000L).coerceIn(10 * 60_000L, 6 * 3600_000L)
    } catch (e: org.json.JSONException) { null }
}

/**
 * TV360 (tv360.vn): only its programme guide, which it shows to anyone (its streams need a short-lived token from a browser).
 * Its channel ids come from the list (`"channels": {"VTV3": "4"}`): its own list is a 1 MB web page, too much for a TV box.
 * A day is asked by date, today or tomorrow.
 */
object Tv360 : Providers.Kind {
    override fun guide(p: Providers.Provider, id: String, day: String): List<Show>? =
        Providers.http(p.o.optString("guide").ifEmpty { return null }.replace("{id}", id).replace("{date}", day), p.headers, null)?.let { parseGuide(it) }

    fun parseGuide(text: String): List<Show>? = try {
        val a = JSONObject(text).optJSONObject("data")?.optJSONArray("schedules") ?: org.json.JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val t = o.optString("name").trim(); val s = o.optLong("epochSt"); val e = o.optLong("epochEt")
            if (t.isEmpty() || s <= 0 || e <= s) null else Show(t, s, e)
        }
    } catch (e: org.json.JSONException) { null }
}

/**
 * Streams written in the signed list itself ("streams": [{name, url, group?, logo?}]): the stations' own public addresses
 * (their site's player, VOV's), no API. Named as TV Channels already names a channel, one is that channel's backup stream (the
 * player falls to it when the first one dies); otherwise a channel of its own. Only http(s): the list never points elsewhere.
 */
object Streams : Providers.Kind {
    override fun list(p: Providers.Provider, get: (String) -> String?): List<Channel> = parse(p)

    fun parse(p: Providers.Provider): List<Channel> {
        val a = p.o.optJSONArray("streams") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val name = o.optString("name").trim(); val url = o.optString("url")
            if (name.isEmpty() || !Regex("^https?://[^\\s]+$").matches(url)) null
            else Channel("${Providers.key(name)}.${p.country}@${p.id}", name, o.optString("logo"), o.optString("group", "General"), url)
        }
    }
}
