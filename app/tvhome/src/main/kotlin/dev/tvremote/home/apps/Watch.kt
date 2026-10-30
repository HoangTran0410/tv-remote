package dev.tvremote.home.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

/**
 * Other apps' rows, as Android TV's own launcher shows them: "Play Next" (what each app says was being watched) and the programmes of
 * their channels (VTV Go's channels, TV Channels' favourites). TvProvider gives a launcher every app's rows marked searchable (the
 * default) with READ_TV_LISTINGS, a runtime permission granted over ADB with the others (HomeConfig.GRANT_WIFI); without it, or before
 * Android 8, there are none. Asked by the user (2026-10-26): Clean TV users missed Play Next on the home screen.
 */
object Watch {
    const val PERM = "android.permission.READ_TV_LISTINGS"
    private const val PER_APP = 6                                     // one app's channel must not fill the row

    /** [done]: how far it was watched, 0..1, or -1 when the app does not say (Play Next with a position and a length). */
    class Item(val key: String, val pkg: String, val title: String, val art: String, val intent: String, val app: String, val done: Float = -1f,
               val at: Long = 0L)                                    // [at]: the programme's last engagement time (ms), 0 when the app gives none

    /**
     * [items] (Play Next, as the provider gave them) for the row's order: "latest" puts the most recently watched first by [Item.at]; one
     * with no time keeps its place after those that have one (a stable sort, so the provider's order is the fallback). Anything else: as given.
     */
    fun sorted(items: List<Item>, sort: String): List<Item> =
        if (sort == "latest") items.sortedByDescending { it.at } else items

    fun allowed(c: Context) = Build.VERSION.SDK_INT >= 26 && c.packageManager.checkPermission(PERM, c.packageName) == PackageManager.PERMISSION_GRANTED

    /** Up to [max] items: Play Next, newest first, then the channels' programmes, one app after another; [hidden] apps left out. */
    @android.annotation.TargetApi(26)   // allowed() is false below 26
    fun read(c: Context, max: Int, hidden: Set<String>, sort: String = "default"): List<Item> {
        if (!allowed(c) || max <= 0) return emptyList()
        val labels = HashMap<String, String?>()
        fun label(pkg: String) = labels.getOrPut(pkg) {
            try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { null }   // gone, or not visible: its rows are stale
        }
        fun rows(uri: Uri, order: String, prefix: String, engaged: Boolean = false): List<Item> = try {
            val cols = arrayOf("_id", "package_name", "title", "poster_art_uri", "thumbnail_uri", "intent_uri", "last_playback_position_millis", "duration_millis")
            // only Watch Next has the engagement column (a preview programme asked for it would fail the whole query); asked only for "latest"
            c.contentResolver.query(uri, if (engaged) cols + "last_engagement_time_utc_millis" else cols, null, null, order)?.use { k ->
                val out = ArrayList<Item>()
                while (k.moveToNext() && out.size < 200) {
                    val pkg = k.getString(1) ?: continue
                    val intent = k.getString(5) ?: continue
                    if (pkg in hidden || pkg == c.packageName) continue
                    val app = label(pkg) ?: continue
                    val art = k.getString(3)?.ifEmpty { null } ?: k.getString(4) ?: ""
                    val at = k.getLong(6); val len = k.getLong(7)
                    out.add(Item(prefix + k.getLong(0), pkg, k.getString(2)?.trim().orEmpty().ifEmpty { app }, art, intent, app,
                        if (len > 0 && at > 0) (at.toFloat() / len).coerceIn(0f, 1f) else -1f, if (engaged) k.getLong(8) else 0L))
                }
                out
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }                        // SecurityException (taken back), a TV without TvProvider

        val next = sorted(rows(TvContract.WatchNextPrograms.CONTENT_URI, "last_engagement_time_utc_millis DESC", "n", sort == "latest"), sort)
        // TV Channels' favourites are left out: they repeat the shortcuts people pin from TV Channels (its Play Next, the last watched, stays)
        val shows = rows(TvContract.PreviewPrograms.CONTENT_URI, "channel_id, weight DESC", "p").filter { it.pkg != "dev.tvremote.channels" }.groupBy { it.pkg }.mapValues { it.value.take(PER_APP) }
        val out = ArrayList(next.distinctBy { it.intent }.take(max))
        val queues = shows.values.map { ArrayDeque(it) }
        while (out.size < max && queues.any { it.isNotEmpty() }) for (q in queues) if (out.size < max) q.removeFirstOrNull()?.let { s -> if (out.none { it.intent == s.intent }) out.add(s) }
        return out
    }

    /** The intent an item opens, made safe to start from a launcher: no selector, no URI grants. */
    fun intent(i: Item): Intent? = try {
        Intent.parseUri(i.intent, Intent.URI_INTENT_SCHEME).apply {
            selector = null
            flags = (flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()) or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    } catch (e: Exception) { null }

    private val cache = object : LinkedHashMap<String, Bitmap>(16, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 40 }

    /**
     * [i]'s picture as a [w]-wide 16:9 tile, the way TV apps show what to keep watching: a wide still fills it, a tall poster (Stremio,
     * films) stands at the left over a blurred, dimmed copy of itself with the title beside it (shown whole and small in the middle it
     * left most of the tile empty: the user, 2026-10-26), a logo sits whole on [bg]. The title goes on the picture (the row has no
     * labels), the app's icon in a corner, and a bar of how far it was watched along the bottom. Off the main thread; null: no picture.
     */
    fun art(c: Context, i: Item, w: Int, bg: Int): Bitmap? {
        if (i.art.isEmpty()) return null
        val key = i.art + w + i.title + (i.done * 50).toInt()
        synchronized(cache) { cache[key]?.let { return it } }
        val raw = try { bytes(c, Uri.parse(i.art)) } catch (e: Exception) { null } ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        if (o.outWidth <= 0) return null
        var s = 1; while (o.outWidth / (s * 2) >= w) s *= 2
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val h = w * 9 / 16
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(bg)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val r = src.width.toFloat() / src.height
        val m = h * 11 / 100                                          // past the 5 % the picture grows by inside a focused tile (TvTile), else the edges were cut
        var textLeft = -1f; var textTop = 0f; var lines = 1
        if (r in 1.6f..1.95f) {                                       // a 16:9 still: cropped to fill, the title on a shade at the bottom
            val cw = (src.height * 16f / 9).toInt().coerceAtMost(src.width); val ch = (cw * 9f / 16).toInt().coerceAtMost(src.height)
            cv.drawBitmap(src, Rect((src.width - cw) / 2, (src.height - ch) / 2, (src.width + cw) / 2, (src.height + ch) / 2), Rect(0, 0, w, h), p)
            cv.drawRect(0f, h * .45f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, h * .45f, 0f, h.toFloat(), 0, SHADE, Shader.TileMode.CLAMP) })
            textLeft = m.toFloat(); textTop = h * .66f
        } else if (r < .85f) {                                        // a poster: itself at the left, a blurred copy of it behind
            val tiny = Bitmap.createScaledBitmap(src, 12, (12 / r).toInt().coerceAtLeast(1), true)   // drawn back large with filtering: a soft blur, no RenderScript
            val bh = w / r                                            // the copy covers the tile, centred
            cv.drawBitmap(tiny, null, RectF(0f, (h - bh) / 2, w.toFloat(), (h + bh) / 2), p)
            tiny.recycle()
            cv.drawColor(DIM)
            val ph = h - 2f * m; val pw = ph * r
            val dst = RectF(m.toFloat(), m.toFloat(), m + pw, m + ph)
            val rad = ph * .05f
            cv.drawRoundRect(dst.apply { offset(0f, h * .015f) }, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SHADOW })   // a touch of shadow under it
            dst.offset(0f, -h * .015f)
            cv.save(); cv.clipPath(android.graphics.Path().apply { addRoundRect(dst, rad, rad, android.graphics.Path.Direction.CW) })
            cv.drawBitmap(src, null, dst, p); cv.restore()
            textLeft = dst.right + m * .8f; textTop = m + h * .04f; lines = 3
        } else {                                                      // a channel's logo: whole, with room around it
            val k = minOf(w * .7f / src.width, h * .62f / src.height)
            val dw = src.width * k; val dh = src.height * k
            cv.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
        }
        src.recycle()
        val sz = h * 24 / 100
        if (textLeft >= 0 && i.title != i.app) {                      // a title the app gave; its own name alone says nothing the icon does not
            val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK; textSize = h * (if (lines == 1) .1f else .12f); typeface = dev.tvremote.home.Font.bold; setShadowLayer(h * .02f, 0f, h * .005f, SHADOW)
            }
            val room = (w - m - textLeft - (if (lines == 1) sz + m * .5f else 0f)).toInt()
            if (room > h / 4) {
                val lay = if (Build.VERSION.SDK_INT >= 23) android.text.StaticLayout.Builder.obtain(i.title, 0, i.title.length, tp, room).setMaxLines(lines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0f, .95f).build()
                else @Suppress("DEPRECATION") android.text.StaticLayout(i.title.take(60), tp, room, android.text.Layout.Alignment.ALIGN_NORMAL, .95f, 0f, false)   // no max lines before 23: a short cut-off instead
                cv.save(); cv.translate(textLeft, if (lines == 1) textTop + (h * .2f - lay.height) / 2 else textTop); lay.draw(cv); cv.restore()
            }
        }
        if (i.done >= 0f) {                                           // how far: a thin bar along the bottom, as every player shows it
            val bh = maxOf(3f, h * .025f); val y = h - m * .55f - bh
            val bar = RectF(textLeft.takeIf { r < .85f } ?: m.toFloat(), y, w - m.toFloat(), y + bh)
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
            cv.drawRoundRect(bar, bh / 2, bh / 2, bp)
            bp.color = INK; cv.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * maxOf(i.done, .04f), bar.bottom), bh / 2, bh / 2, bp)
        }
        try { corner(cv, c.packageManager.getApplicationIcon(i.pkg), w, h, if (i.done >= 0f) h * .04f else 0f) } catch (e: Exception) { }   // above the bar
        synchronized(cache) { cache[key] = out }
        return out
    }

    /**
     * Whose it is: the app's icon small in the bottom-right corner, as Android TV's launcher does (a name there hid the logos). One
     * place for its size and margin, for the Play Next row and the pinned shortcuts (Pins.badged): a pin's at 6 % touched the edge
     * of a focused tile; 11 % is past the 5 % the picture grows by inside it (TvTile). [lift] raises it (above a progress bar).
     */
    fun corner(cv: Canvas, d: android.graphics.drawable.Drawable, w: Int, h: Int, lift: Float = 0f, pct: Int = 24, ring: Boolean = false) {
        val sz = h * pct / 100; val m = h * (if (pct > 24) 4 else 11) / 100
        val r = RectF((w - m - sz).toFloat(), h - m - sz - lift, (w - m).toFloat(), h - m - lift)
        cv.drawRoundRect(r, sz * .24f, sz * .24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_BG; setShadowLayer(sz * .12f, 0f, sz * .04f, SHADOW) })   // a white icon still reads on a white card
        if (ring) cv.drawRoundRect(r, sz * .24f, sz * .24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, sz * .05f); color = RING })   // a thin dark edge: reads on any picture
        roundIcon(cv, d, r)
    }

    /**
     * [d] in [r] with round corners (radius [k] of the side), as the launcher draws app tiles: some apps give a square icon
     * (VTVgo), others one already round. Through a shader, its edge smooth (a clipPath on a bitmap's canvas is jagged); drawn
     * once into a card that is kept, nothing per frame.
     */
    fun roundIcon(cv: Canvas, d: android.graphics.drawable.Drawable, r: RectF, k: Float = .24f) {
        val s = maxOf(1, r.width().toInt())
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s); d.draw(Canvas(b))
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(android.graphics.Matrix().apply { setTranslate(r.left, r.top) }) }
        }
        cv.drawRoundRect(r, s * k, s * k, p)
        b.recycle()
    }

    private const val INK = 0xFFFFFFFF.toInt()                      // the title and the bar
    private const val TRACK = 0x4DFFFFFF                               // the bar's track: white at 30 %
    private const val SHADE = 0xD9000000.toInt()                       // under a still's title
    private const val DIM = 0x8C0B0D12.toInt()                        // over a poster's blurred copy
    private const val SHADOW = 0x99000000.toInt()
    private const val RING = 0xCC000000.toInt()
    private const val BADGE_BG = 0x66000000                            // under the corner icon: see-through, still reads on a white card

    private fun bytes(c: Context, u: Uri): ByteArray? = when (u.scheme) {
        "http", "https" -> (URL(u.toString()).openConnection() as HttpURLConnection).run {
            try {
                connectTimeout = 5000; readTimeout = 8000; instanceFollowRedirects = true
                if (responseCode !in 200..299 || contentLength > 4_000_000) null else inputStream.use { it.readBytes().takeIf { b -> b.size <= 4_000_000 } }
            } finally { disconnect() }
        }
        "content", "android.resource" -> c.contentResolver.openInputStream(u)?.use { it.readBytes() }
        else -> null
    }
}
