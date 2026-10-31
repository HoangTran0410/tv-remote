package dev.tvremote.core.ontv
import dev.tvremote.core.adb.Adb
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/**
 * TV Home's wallpaper gallery: docs/home/wallpapers.json on the site (made by dev/gen/wallpapers.py), signed like the launcher list
 * (the same key, so the checks are [Launchers.signed] and [Launchers.KEYS]). Each entry: a 1920x1080 JPEG and a 320x180 thumbnail,
 * both pinned by SHA-256, with credit, source and licence. The client downloads the one chosen and sends it as TV Home's photo
 * ([Home.photo]): the TV never reaches the Internet for it, and nothing comes from a place the signed list does not name.
 */
object Wallpapers {
    var bases = listOf("https://tvremote.pages.dev/home/", "https://raw.githubusercontent.com/HoangTran0410/tv-remote/main/docs/home/")
    /** A list to read instead of the site's (tests, or before it is published): TVR_WALLPAPERS. Still signature-checked. */
    @Volatile var testBase: String? = System.getenv("TVR_WALLPAPERS")?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.endsWith("/")) it else "$it/" }
    private val NAME = Regex("[a-z0-9][a-z0-9-]*\\.jpg")

    class Wall(val id: String, val name: Map<String, String>, val file: String, val sha: String, val thumb: String, val thumbSha: String,
               val credit: String, val source: String, val license: String, val bytes: Int)
    /** A suggested background that is a page, not a picture to send ("links": the slideshow, a video, a live page): the client sets "web" to [url]. */
    class Link(val id: String, val kind: String, val name: Map<String, String>, val url: String, val thumb: String, val thumbSha: String,
               val credit: String, val source: String, val license: String, val fade: Boolean = false)   // fade: HomeConfig "wfade" for this video
    class Gallery(val walls: List<Wall>, val base: String, val links: List<Link> = emptyList()) {
        operator fun get(id: String) = walls.firstOrNull { it.id == id }
        fun link(id: String) = links.firstOrNull { it.id == id }
    }

    @Volatile private var cache: Pair<Long, Gallery>? = null

    @Throws(IOException::class)
    fun parse(list: ByteArray, base: String): Gallery = try {
        val o = JSONObject(String(list, Charsets.UTF_8))
        if (o.optInt("v") != 1) throw IOException("wallpaper list: unknown version")
        val a = o.getJSONArray("walls")
        Gallery((0 until a.length()).map { i ->
            val e = a.getJSONObject(i)
            val n = e.getJSONObject("name")
            val w = Wall(e.getString("id"), n.keys().asSequence().associateWith { n.getString(it) }, e.getString("file"), e.getString("sha256").lowercase(),
                e.getString("thumb"), e.getString("thumb_sha256").lowercase(), e.optString("credit"), e.optString("source"), e.optString("license"), e.optInt("bytes"))
            if (!NAME.matches(w.file) || !NAME.matches(w.thumb) || w.sha.length != 64 || w.thumbSha.length != 64) throw IOException("wallpaper list: bad entry")
            w
        }, base, o.optJSONArray("links")?.let { l -> (0 until l.length()).mapNotNull { i ->
            val e = l.optJSONObject(i) ?: return@mapNotNull null
            val n = e.optJSONObject("name") ?: JSONObject()
            Link(e.optString("id"), e.optString("kind"), n.keys().asSequence().associateWith { n.getString(it) }, e.optString("url"), e.optString("thumb"),
                e.optString("thumb_sha256").lowercase(), e.optString("credit"), e.optString("source"), e.optString("license"), e.optBoolean("fade"))
                .takeIf { it.id.isNotEmpty() && it.kind in KINDS && it.url.startsWith("https://") && (it.thumb.isEmpty() || NAME.matches(it.thumb)) }
        } }.orEmpty())
    } catch (e: org.json.JSONException) { throw IOException("wallpaper list: " + e.message) }
    private val KINDS = setOf("slides", "video", "page")

    /** The signed list from the first place that has one, kept a minute. */
    @Throws(IOException::class)
    fun fetch(): Gallery {
        cache?.let { if (System.currentTimeMillis() - it.first < 60_000) return it.second }
        return Launchers.first(testBase?.let { listOf(it) } ?: bases, "wallpapers.json") { list, _, b -> parse(list, b).also { cache = System.currentTimeMillis() to it } }
    }

    /** What the page lists: no file names, only what it shows (the thumbnail is its own route). */
    fun json(g: Gallery): JSONObject = JSONObject().put("walls", JSONArray(g.walls.map { w ->
        JSONObject().put("id", w.id).put("name", JSONObject(w.name)).put("credit", w.credit).put("source", w.source).put("license", w.license)
            .put("kb", (w.bytes + 512) / 1024).put("thumb", "/api/home/walls/thumb?id=" + w.id)
    })).put("links", JSONArray(g.links.map { l ->
        JSONObject().put("id", l.id).put("kind", l.kind).put("name", JSONObject(l.name)).put("url", l.url).put("credit", l.credit).put("source", l.source)
            .put("license", l.license).put("fade", l.fade).put("thumb", if (l.thumb.isEmpty()) "" else "/api/home/walls/thumb?id=" + l.id)
    }))

    private fun checked(b: ByteArray, sha: String): ByteArray {
        if (Launchers.hex(MessageDigest.getInstance("SHA-256").digest(b)) != sha) throw IOException(Launchers.MISMATCH)
        return b
    }

    @Throws(IOException::class)
    fun thumb(id: String): ByteArray? = fetch().let { g ->
        g[id]?.let { checked(Launchers.get(g.base + it.thumb), it.thumbSha) }
            ?: g.link(id)?.takeIf { it.thumb.isNotEmpty() && it.thumbSha.length == 64 }?.let { checked(Launchers.get(g.base + it.thumb), it.thumbSha) }
    }

    /** The wallpaper [id], downloaded, checked against the signed list and put on the TV as its background. */
    @Throws(IOException::class)
    fun apply(adb: Adb, id: String): JSONObject {
        val g = fetch()
        val w = g[id] ?: throw IllegalArgumentException("no such wallpaper")
        return Home.photo(adb, checked(Launchers.get(g.base + w.file), w.sha))
    }
}
