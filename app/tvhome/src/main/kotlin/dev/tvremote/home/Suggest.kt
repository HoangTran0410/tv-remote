package dev.tvremote.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.tvremote.update.SignedList
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Backgrounds the site suggests, beside Bing and the person's own: docs/home/wallpapers.json (dev/gen/wallpapers.py, signed with the
 * launcher list's key, as the themes). Its "links" (the Việt Nam slideshow, Pexels videos, a live page) and its pictures (each as
 * loop.html?i=, which TV Home draws natively, NetPicture): every one sets the "web" wall to its url. The list: the site, else GitHub,
 * else the copy kept; small pictures from the same place, checked against their SHA-256 and kept in files/suggest/.
 */
object Suggest {
    /** Their keys in Customize's wall tiles: never a [HomeConfig] wall, only a way to set "wall" + "wurl". */
    const val PREFIX = "sug:"
    private val BASES = listOf("https://tvremote.pages.dev/home/", "https://raw.githubusercontent.com/HoangTran0410/tv-remote/main/docs/home/")
    private const val SITE = "https://tvremote.pages.dev/home/"
    private const val NAME = "wallpapers.json"
    private const val DAY = 24 * 3600_000L
    private val FILE = Regex("[a-z0-9][a-z0-9._-]*\\.jpg")

    class Item(val id: String, val kind: String, val names: Map<String, String>, val url: String, val thumb: String, val sha: String,
               val fade: Boolean = false) {   // a video whose end is not its start: picked, it sets HomeConfig "wfade"
        val key get() = PREFIX + id
        fun name(lang: String) = names[lang] ?: names["en"] ?: id
    }

    @Volatile var items: List<Item> = emptyList(); private set
    /** The wallpapers page while it is open: a newer list came, its tiles are drawn again. */
    @Volatile var onChange: (() -> Unit)? = null

    fun byKey(k: String) = if (k.startsWith(PREFIX)) items.firstOrNull { it.key == k } else null

    /** The list kept (cheap once checked) now, the site's when the kept one is older than [maxAge]; off the main thread for the network. */
    fun load(c: Context, maxAge: Long = DAY) {
        val app = c.applicationContext
        val f = File(app.filesDir, NAME)
        if (items.isEmpty()) SignedList.kept(f)?.let { items = parse(it) }
        if (asking || dev.tvremote.guard.Age.fresh(maxOf(f.lastModified(), askedAt), maxAge) && items.isNotEmpty()) return
        asking = true; askedAt = System.currentTimeMillis()
        Thread({
            try {
                SignedList.fetch(BASES, NAME, 1 shl 20, connectMs = 6000, readMs = 8000) { list, sig, _ ->
                    parse(list).takeIf { it.isNotEmpty() }?.also { SignedList.keep(f, list, sig); items = it; onChange?.invoke() }
                }
            } catch (e: Exception) { } finally { asking = false }
        }, "suggest").apply { isDaemon = true }.start()
    }
    @Volatile private var asking = false
    @Volatile private var askedAt = 0L

    /** The slideshow first, then the videos, the pictures, the live pages last (heavy on a weak box). */
    fun parse(b: ByteArray): List<Item> = try {
        val o = JSONObject(String(b, Charsets.UTF_8))
        fun names(e: JSONObject) = e.optJSONObject("name")?.let { n -> n.keys().asSequence().associateWith { n.optString(it) } }.orEmpty()
        val links = o.optJSONArray("links")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().mapNotNull { e ->
            val url = HomeConfig.wurl(e.optString("url"))
            val kind = e.optString("kind")
            if (url.isEmpty() || !url.startsWith("https://") || kind !in KINDS) null
            else Item(e.optString("id"), kind, names(e), url, e.optString("thumb").takeIf { FILE.matches(it) } ?: "", e.optString("thumb_sha256").lowercase(), e.optBoolean("fade"))
        }
        val pics = o.optJSONArray("walls")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().mapNotNull { e ->
            val file = e.optString("file")
            if (!FILE.matches(file)) null
            else Item(e.optString("id"), "photo", names(e), SITE + "loop.html?i=" + URLEncoder.encode(SITE + file, "UTF-8"),
                e.optString("thumb").takeIf { FILE.matches(it) } ?: "", e.optString("thumb_sha256").lowercase())
        }
        (links + pics).filter { it.id.isNotEmpty() }.sortedBy { KINDS.indexOf(it.kind) }.distinctBy { it.id }
    } catch (e: Exception) { emptyList() }
    private val KINDS = listOf("slides", "video", "photo", "page")

    /**
     * [it]'s small picture, read at about [w] px wide (whole when 0): kept, else fetched and checked against its SHA-256; null (none,
     * offline, altered). Blocking: off the main thread.
     */
    fun picture(c: Context, it: Item, w: Int = 0): Bitmap? {
        if (it.thumb.isEmpty() || it.sha.length != 64) return null
        val dir = File(c.applicationContext.filesDir, "suggest").apply { mkdirs() }
        val f = File(dir, it.thumb)
        val b = if (f.isFile) f.readBytes() else try {
            BASES.firstNotNullOfOrNull { base -> try { SignedList.get(base + it.thumb, 256 * 1024, 6000, 8000).takeIf { x -> hex(x) == it.sha } } catch (e: Exception) { null } }
                ?.also { x -> File(dir, it.thumb + ".tmp").apply { writeBytes(x); renameTo(f) } }
        } catch (e: Exception) { null }
        if (b == null) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        // the tile is ~1/3 of it: read whole, each one took ~25 ms of the FPT box to decode, then was scaled down anyway
        val s = if (w > 0 && o.outWidth > 0) dev.tvremote.home.wall.Wall.sample(o.outWidth, o.outHeight, w, w * o.outHeight / o.outWidth) else 1
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
