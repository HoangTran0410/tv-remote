package dev.tvremote.home.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.tvremote.tvui.PinArt
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A YouTube link turned into a pin: [parse] (pure) gives its canonical https://www.youtube.com/ form, [pin] fetches the name and
 * the picture (oEmbed for a video or a playlist, the page's og: tags for a channel; no key, bounded, off the main thread) and
 * pins it as TV Home's own shortcut ([Pins.pinIntent]). The pin holds the link only: whoever handles it is found when it is
 * pressed ([LinkApps]), so swapping one YouTube app for another keeps every pin working. The contract the clients call:
 * HomeReceiver extra "pin" and POST /h/pin, both [HomeApi.pin].
 */
object YouTube {
    enum class Kind { VIDEO, PLAYLIST, CHANNEL }
    class Link(val kind: Kind, val url: String, val id: String)

    const val NO_LINK = "pin_bad_link"; const val NO_APP = "pin_no_app"; const val FAILED = "pin_failed"

    private val ID = Regex("[A-Za-z0-9_-]{11}")
    private val LIST = Regex("[A-Za-z0-9_-]{10,64}")
    private val HANDLE = Regex("[\\p{L}\\p{N}_.\\-]{3,40}")
    private val CHANNEL = Regex("UC[A-Za-z0-9_-]{22}")
    private val NAME = Regex("[\\p{L}\\p{N}_.\\- ]{1,60}")
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")

    private fun query(q: String?): Map<String, String> {
        val m = HashMap<String, String>()
        for (kv in q.orEmpty().split('&')) { val e = kv.indexOf('='); if (e > 0) m.getOrPut(kv.substring(0, e)) { try { java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8") } catch (x: Exception) { "" } } }
        return m
    }

    /** [raw] as a canonical link, or null when it is not a video, playlist or channel of YouTube (youtube.com, m., music., youtu.be). A missing "https://" is added. */
    fun parse(raw: String): Link? {
        var s = raw.trim()
        if (s.isEmpty() || s.length > 500 || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "https://$s"
        val u = try { java.net.URI(s) } catch (e: Exception) { return null }
        if (u.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = u.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        val parts = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { try { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { it } }
        val q = query(u.rawQuery)
        fun video(id: String?) = id?.takeIf { ID.matches(it) }?.let { Link(Kind.VIDEO, "https://www.youtube.com/watch?v=$it", it) }
        fun list(id: String?) = id?.takeIf { LIST.matches(it) }?.let { Link(Kind.PLAYLIST, "https://www.youtube.com/playlist?list=$it", it) }
        if (host == "youtu.be") return video(parts.firstOrNull()) ?: list(q["list"])
        val first = parts.firstOrNull() ?: return null
        return when {
            first == "watch" -> video(q["v"]) ?: list(q["list"])
            first == "playlist" -> list(q["list"])
            first == "shorts" || first == "live" || first == "embed" -> video(parts.getOrNull(1))
            first.startsWith("@") -> first.substring(1).takeIf { HANDLE.matches(it) }?.let { Link(Kind.CHANNEL, "https://www.youtube.com/@" + enc(it), it) }
            first == "channel" -> parts.getOrNull(1)?.takeIf { CHANNEL.matches(it) }?.let { Link(Kind.CHANNEL, "https://www.youtube.com/channel/$it", it) }
            first == "c" || first == "user" -> parts.getOrNull(1)?.takeIf { HANDLE.matches(it) }?.let { Link(Kind.CHANNEL, "https://www.youtube.com/$first/" + enc(it), it) }
            else -> null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The og: tags of a channel page, from its first bytes: title and image (the avatar, asked at 400 px instead of 900). */
    fun og(html: String): Pair<String?, String?> {
        fun meta(p: String) = Regex("<meta\\s+property=\"og:$p\"\\s+content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)?.let { unescape(it) }
        return meta("title") to meta("image")?.replace(Regex("=s\\d+-"), "=s400-")
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    // ---- fetching: small, bounded, never on the main thread ----

    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    internal fun getBytes(url: String, max: Int, stopAt: String? = null, ms: Int = 3500) = get(url, max, stopAt, ms)

    private fun get(url: String, max: Int, stopAt: String? = null, ms: Int = 3500): ByteArray? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = ms; readTimeout = ms; instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA); setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+")   // the EU consent page would hide the tags
            try {
                if (responseCode != 200) { android.util.Log.i("TvHome", "fetch $responseCode ${url.take(80)}"); null }
                else inputStream.use { i ->
                    val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
                    var tail = ""
                    while (out.size() < max) {
                        val n = i.read(b); if (n < 0) break; out.write(b, 0, n)
                        if (stopAt != null) { val t = tail + String(b, 0, n, Charsets.ISO_8859_1); if (t.contains(stopAt)) break; tail = t.takeLast(stopAt.length) }
                    }
                    out.toByteArray()
                }
            } finally { disconnect() }
        }
    } catch (e: Exception) { android.util.Log.i("TvHome", "fetch ${url.take(80)}: $e"); null }

    private fun bitmap(url: String): Bitmap? = get(url, 1 shl 20)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    class Meta(val name: String?, val image: Bitmap?)

    /**
     * Name and picture of [l] unless the caller gave them; both may stay null (offline, a page that changed): the caller falls back.
     * Nothing at all once: asked again after 1.5 s (on FPT a pin got neither, from a TV Home that had sat behind an app; a minute later
     * the same link fetched fine, the user 2026-11-01): the tile keeps what it got, an app icon is all a miss leaves.
     */
    fun fetch(l: Link, name: String?, img: String?): Meta {
        val m = fetchOnce(l, name, img)
        if (m.image != null && (m.name != null || name != null)) return m
        Thread.sleep(1500)
        val again = fetchOnce(l, name, img)
        return Meta(again.name ?: m.name, again.image ?: m.image)
    }

    private fun fetchOnce(l: Link, name: String?, img: String?): Meta {
        var title = name?.takeIf { it.isNotBlank() }
        var pic: Bitmap? = null
        var imgUrl = img?.takeIf { it.startsWith("https://") }
        if (l.kind == Kind.CHANNEL) {
            // the og: tags sit ~830 KB into the page (measured on @VTV24), after its script: read up to them, no further
            if (title == null || imgUrl == null) get(l.url, 1_600_000, "og:type", 6000)?.let { String(it, Charsets.UTF_8) }?.let { og(it) }?.let { (t, i) -> if (title == null) title = t; if (imgUrl == null) imgUrl = i }
        } else if (title == null || imgUrl == null) {
            get("https://www.youtube.com/oembed?url=" + URLEncoder.encode(l.url, "UTF-8") + "&format=json", 64 * 1024)?.let { try { JSONObject(String(it, Charsets.UTF_8)) } catch (e: Exception) { null } }?.let {
                if (title == null) title = it.optString("title").ifBlank { null }
                if (imgUrl == null) imgUrl = it.optString("thumbnail_url").takeIf { u -> u.startsWith("https://") }
            }
        }
        imgUrl?.let { pic = bitmap(it) }
        if (pic == null && l.kind == Kind.VIDEO) pic = bitmap("https://i.ytimg.com/vi/${l.id}/hqdefault.jpg")
        return Meta(title?.take(80), pic)
    }

    // ---- the card ----

    private const val CARD_BG = 0xFF1C2233.toInt()

    /** A video's / playlist's thumbnail filling a 16:9 card (YouTube's 4:3 thumbnails carry black bars: the centre is cut); a channel's avatar round on a card. */
    fun card(l: Link, pic: Bitmap, w: Int = PinArt.W): Bitmap {
        val h = w * 9 / 16
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (l.kind == Kind.CHANNEL) {
            cv.drawColor(CARD_BG)
            val d = h * .62f; val cx = w / 2f; val cy = h / 2f - h * .03f
            val s = minOf(pic.width, pic.height)
            val clip = Path().apply { addCircle(cx, cy, d / 2, Path.Direction.CW) }
            cv.save(); cv.clipPath(clip)
            cv.drawBitmap(pic, android.graphics.Rect((pic.width - s) / 2, (pic.height - s) / 2, (pic.width + s) / 2, (pic.height + s) / 2), RectF(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2), p)
            cv.restore()
        } else {
            val want = pic.width * 9f / 16                           // the visible 16:9 band, centred
            val top = ((pic.height - want) / 2).coerceAtLeast(0f)
            val srcH = minOf(want, pic.height.toFloat())
            cv.drawBitmap(pic, android.graphics.Rect(0, top.toInt(), pic.width, (top + srcH).toInt()), RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
        }
        return b
    }

    /**
     * Pins [raw]. Returns "ok" (also when it was pinned already) or an error code. Blocking: the network and the system's pin call.
     * [name] and [img] (https) win over what the page says.
     */
    fun pin(c: Context, raw: String, name: String?, img: String?): String {
        val l = parse(raw) ?: return NO_LINK
        val app = LinkApps.best(c, l.url) ?: return NO_APP
        if (!Pins.can(c)) return FAILED
        val m = fetch(l, name?.takeIf { NAME.matches(it.trim()) || it.length <= 80 }, img)
        val label = m.name ?: (if (l.kind == Kind.CHANNEL) "@" else "") + l.id
        val art = m.image?.let { card(l, it) }
        LinkApps.noteGroup(c, Pins.ownId(LinkApps.intent(c, l.url)), "youtube", l.url)
        return when (Pins.pinIntent(c, app.pkg, label, LinkApps.intent(c, l.url), art)) {
            Pins.Made.FAIL -> FAILED
            else -> "ok"
        }
    }
}
