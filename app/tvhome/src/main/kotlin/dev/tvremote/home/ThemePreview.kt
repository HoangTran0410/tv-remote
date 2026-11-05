package dev.tvremote.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import dev.tvremote.home.wall.Backdrop
import dev.tvremote.home.wall.WallArt
import dev.tvremote.home.wall.Walls
import org.json.JSONObject
import java.io.File

/**
 * A theme's picture in the Themes page: a miniature of the home screen it makes, built from its config (the page's twin is
 * js/home.js `homeThemePaint`): the real background under the real veil, the clock, the widgets as [HomeConfig.pack] lays
 * them (the same rule HomeActivity uses), the grid with its columns and rows, the live widgets first, the focus on the first one.
 * Rounded shapes only. Each is drawn ONCE into a bitmap (off the main thread) and kept in [cache], so scrolling the page costs nothing.
 */
object ThemePreview {
    private const val INK = 0xFFFFFFFF.toInt()                      // every shape is this at some alpha: it reads on any wall
    private const val SOLID = 0xF0181B22.toInt()                     // Frost's "solid" widget
    private const val FILL_GLASS = 0x2E; private const val FILL_TONAL = 0x4A; private const val FILL_TILE = 0x26
    private const val LIVE_A = 0xFF4FC3A1.toInt(); private const val LIVE_B = 0xFFFFC857.toInt(); private const val LIVE_C = 0xFF8AB4F8.toInt(); private const val LIVE_D = 0xFFF28B82.toInt()
    private val LIVE = intArrayOf(LIVE_A, LIVE_B, LIVE_C, LIVE_D)
    private const val NO_WALL = 0xFF2A2F3A.toInt()                  // a background nothing is known of yet: the Themes page's own plain tile
    private const val PLAY_BG = 0xB3000000.toInt()                  // the "moves" pill: dark at 70% so the white triangle reads on a bright wall

    // 4 MB (~40 previews of ~90 KB), kept between openings and let go on low memory (Customize.forget): drawn again on each
    // opening they took ~230 ms of the FPT box's CPU, a whole page of software round rects
    private val cache = object : LruCache<String, Bitmap>(4 shl 20) { override fun sizeOf(k: String, b: Bitmap) = b.byteCount }
    fun cached(key: String): Bitmap? = cache.get(key)
    fun forget() = cache.evictAll()

    /** Same picture, same key: the theme (its id and the list's rev), its look, the size, and whether the network / captured background is there. */
    fun key(id: String, conf: JSONObject, live: List<String>, w: Int, shot: File?) =
        "$id:${Themes.rev}:$w:${(conf.toString() + live).hashCode()}:${shot?.takeIf { it.isFile }?.lastModified() ?: 0}"

    /** The preview of [conf] at [w] x [h] with corner radius [r]; made on the calling thread (call it off the main one), kept for next time. */
    fun get(a: Context, key: String, conf: JSONObject, live: List<String>, shot: File?, look: String, w: Int, h: Int, r: Float): Bitmap {
        cache.get(key)?.let { return it }
        val b = make(a, conf, live, shot, look, w, h, r)
        cache.put(key, b)
        return b
    }

    private fun make(a: Context, conf: JSONObject, live: List<String>, shot: File?, look: String, w: Int, h: Int, r: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val wall = conf.optString("wall")
        val shade = conf.optInt("shade", HomeConfig.SHADE_DEFAULT).toFloat() / HomeConfig.SHADE_DEFAULT
        val frame = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // the background: a picture (drawn scene, photo, Bing, the captured / downloaded one) cropped to the frame, else the colours
        var pic = picture(a, wall, shot, w, h)
        var artK = if (WallArt.has(wall)) WallArt.shade(wall) else 1f
        if (pic == null && look.isNotEmpty()) { pic = Walls.small(a, look, w, h, false); artK = if (WallArt.has(look)) WallArt.shade(look) else 1f }
        if (pic != null) {
            val k = maxOf(w.toFloat() / pic.width, h.toFloat() / pic.height)
            p.shader = BitmapShader(pic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate((w - pic.width * k) / 2, (h - pic.height * k) / 2) }) }
        } else {
            val cc = if (HomeConfig.isPhoto(wall) || HomeConfig.isVideo(wall) || wall == HomeConfig.BING || wall == HomeConfig.WEB) intArrayOf(NO_WALL, NO_WALL) else wallColors(wall)
            p.shader = if (cc.all { it == cc[0] }) LinearGradient(0f, 0f, 0f, 1f, cc[0], cc[0], Shader.TileMode.CLAMP)
                else LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), cc, null, Shader.TileMode.CLAMP)
        }
        cv.drawRoundRect(frame, r, r, p)
        // the veil at its real strength (what Backdrop puts over the wall)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Backdrop.shade(artK, shade), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawRoundRect(frame, r, r, p)
        pic?.recycle()
        p.shader = null
        cv.save(); cv.clipRect(0f, 0f, w.toFloat(), h.toFloat())
        layout(cv, p, conf, live, w.toFloat(), h.toFloat())
        if (moving(conf)) play(cv, p, w.toFloat(), h.toFloat())
        cv.restore()
        return out
    }

    private fun picture(a: Context, wall: String, shot: File?, w: Int, h: Int): Bitmap? {
        if (WallArt.has(wall)) return Walls.small(a, wall, w, h, false)
        shot?.takeIf { it.isFile }?.let { f -> read(f, w, h)?.let { return it } }
        if (Walls.isPicture(wall)) return Walls.small(a, wall, w, h, false)       // a photo, a video's first frame, the Bing picture kept
        return null
    }

    private fun read(f: File, w: Int, h: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) { null }

    private fun bar(cv: Canvas, p: Paint, x: Float, y: Float, w: Float, h: Float, alpha: Int) {
        p.color = (alpha shl 24) or (INK and 0xFFFFFF)
        cv.drawRoundRect(x, y, x + w, y + h, h / 2f, h / 2f, p)
    }

    private fun layout(cv: Canvas, p: Paint, conf: JSONObject, live: List<String>, w: Float, h: Float) {
        val m = w * 0.06f
        // the head is one row on TV Home (time, greeting, date | buttons): one bar each side, where things are, not each one
        bar(cv, p, m, h * 0.075f, w * 0.30f, h * 0.07f, 0xCC)
        bar(cv, p, w - m - w * 0.16f, h * 0.085f, w * 0.16f, h * 0.05f, 0x40)
        var y = h * 0.21f
        // the widgets, in the columns HomeActivity makes of them: a row under the head, or a column beside the grid ("wplace" left / right)
        val keys = strs(conf, "widgets")
        val place = conf.optString("wplace").takeIf { it in HomeConfig.WPLACES } ?: "top"
        val packed = if (HomeConfig.side(place)) HomeConfig.packColumn(keys, HomeConfig.bigs(conf), false, 378) else HomeConfig.pack(keys, HomeConfig.bigs(conf))   // 378 dp: a 1080p screen under the top bar
        val side = HomeConfig.side(place) && packed.cols.isNotEmpty()
        fun card(k: String, x: Float, cy: Float, cw: Float, ch: Float, n: Int) {
            val look = HomeConfig.optValue(conf, k, "look")
            p.color = if (look == "solid") SOLID else (((if (look == "tonal") FILL_TONAL else FILL_GLASS) shl 24) or (INK and 0xFFFFFF))
            if (look != "none") cv.drawRoundRect(x, cy, x + cw, cy + ch, h * 0.035f, h * 0.035f, p)   // no widget: only what is on it
            val pad = h * 0.03f
            if (k == "clock" && (n == 1 || ch > h * 0.12f)) {                    // the analog face
                val d = minOf(cw, ch) * 0.56f
                p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1f, h * 0.008f); p.color = (0xB3 shl 24) or (INK and 0xFFFFFF)
                cv.drawCircle(x + cw / 2, cy + ch / 2, d / 2, p); p.style = Paint.Style.FILL
            } else {
                bar(cv, p, x + pad, cy + pad, cw * 0.42f, h * 0.018f, 0x66)
                if (ch > h * 0.12f) bar(cv, p, x + pad, cy + ch * 0.45f, cw * 0.62f, h * 0.045f, 0xB3)
                else bar(cv, p, x + pad, cy + ch * 0.55f, cw * 0.5f, h * 0.03f, 0xB3)
            }
        }
        val sideW = (w - 2 * m) / 3; val sideGap = w * 0.033f
        if (side) {                                                   // blocks stacked, each as high as its dp says (a 540 dp screen)
            val x = if (place == "left") m else w - m - sideW; val sc = h / 540f
            var cy = y
            for (col in packed.cols) {
                val n = col.keys.size; val ch = col.weight * sc; val g = HomeConfig.H_GAP * sc * 0.5f
                val each = (ch - g * (n - 1)) / n
                col.keys.forEachIndexed { j, k -> card(k, x, cy + j * (each + g), sideW, each, n) }
                cy += ch + HomeConfig.H_GAP * sc
            }
        } else if (packed.cols.isNotEmpty()) {
            val rowH = h * 0.30f
            val gap = w * 0.02f
            val room = w - 2 * m - gap * (packed.cols.size - 1)
            val sum = maxOf(HomeConfig.FLOOR, packed.cols.sumOf { it.weight }).toFloat()
            val used = room * packed.cols.sumOf { it.weight } / sum + gap * (packed.cols.size - 1)
            var x = if (conf.optBoolean("center", false)) (w - used) / 2 else m   // centered over a centered grid, as TV Home lays it
            for (col in packed.cols) {
                val cw = room * col.weight / sum
                val n = col.keys.size
                val ch = (rowH - (n - 1) * gap * 0.6f) / n
                col.keys.forEachIndexed { j, k -> card(k, x, y + j * (ch + gap * 0.6f), cw, ch, n) }
                x += cw + gap
            }
            y += rowH + h * 0.04f
        }
        // the grid: [cols] to a row, the live widgets first, the first one focused; "rows" as set (a part of a row shows part), else what fits
        val cols0 = conf.optInt("cols", HomeConfig.COLS_DEFAULT).coerceIn(HomeConfig.COLS_MIN, HomeConfig.COLS_MAX)
        val gap = w * 0.014f
        val base = (w - 2 * m - (cols0 - 1) * gap) / cols0
        val gx = if (side && place == "left") m + sideW + sideGap else m; val gw = if (side) w - 2 * m - sideW - sideGap else w - 2 * m
        // beside a column the tiles keep about their size and the row holds fewer (HomeActivity.gridCols)
        val cols = if (side) minOf(cols0, maxOf(minOf(3, cols0), ((gw + gap) / (base * 0.88f + gap)).toInt())) else cols0
        val tw = (gw - (cols - 1) * gap) / cols
        val th = tw * 0.56f
        val rows = conf.optDouble("rows", 0.0)
        val room = (h - y).toDouble()
        val fit = (room + gap) / (th + gap)
        val shown = if (rows >= 1 && !side) minOf(rows, fit.coerceAtLeast(1.0)) else minOf(fit, 3.0)
        val whole = Math.ceil(shown - 0.0001).toInt().coerceAtLeast(1)
        // TV Home keeps the grid at the bottom of the screen: whole rows above a margin, a part of a row cut by the screen's edge
        val exact = Math.abs(shown - Math.rint(shown)) < 0.0001
        if (!side) y = maxOf(y, (if (exact) h * 0.94f else h) - (shown * (th + gap) - (if (exact) gap else 0f)).toFloat())
        val ring = conf.optString("focus", "glass") == "ring"
        val tstyle = conf.optString("tstyle").takeIf { it in HomeConfig.TSTYLES } ?: "banner"
        val acc = HomeConfig.ACCENTS[conf.optString("accent")]?.let { Look.argb(it) }
        val edge = acc ?: INK                                                            // the ring and the glass's rim: the accent, else white
        for (i in 0 until whole * cols) {
            val rx = gx + (i % cols) * (tw + gap); val ry = y + (i / cols) * (th + gap)
            val vis = if (i / cols + 1 <= shown + 0.0001) 1f else (shown - (i / cols)).toFloat().coerceIn(0.05f, 1f)
            val bottom = ry + th * vis
            val isLive = i < live.size
            val r = h * 0.03f
            if (isLive || tstyle == "banner") {
                p.color = if (isLive) (LIVE[i % LIVE.size] and 0xFFFFFF) or (0xCC shl 24) else (FILL_TILE shl 24) or (INK and 0xFFFFFF)
                cv.drawRoundRect(rx, ry, rx + tw, bottom, r, r, p)
            } else {                                                                     // icon / glass: a rounded square for the icon (a frosted card behind it for glass), a bar for the name
                if (tstyle == "glass") { p.color = (0x30 shl 24) or (INK and 0xFFFFFF); cv.drawRoundRect(rx, ry, rx + tw, bottom, r, r, p) }
                val s = th * (if (tstyle == "icon") 0.56f else 0.46f)
                val ix = rx + (tw - s) / 2; val iy = ry + th * (if (tstyle == "icon") 0.04f else 0.1f)
                if (iy + s <= bottom) { p.color = (0xCC shl 24) or (LIVE[i % LIVE.size] and 0xFFFFFF); cv.drawRoundRect(ix, iy, ix + s, iy + s, s * 0.26f, s * 0.26f, p) }
                val ny = iy + s + th * 0.08f
                if (ny + th * 0.05f <= bottom) { p.color = (0x99 shl 24) or (INK and 0xFFFFFF); cv.drawRoundRect(rx + tw * 0.28f, ny, rx + tw * 0.72f, ny + th * 0.05f, th * 0.025f, th * 0.025f, p) }
            }
            if (i == 0) {                                                                // the focus: a ring, or the glass lifting the tile
                if (ring) { p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1.5f, h * 0.012f); p.color = edge; cv.drawRoundRect(rx - 1, ry - 1, rx + tw + 1, bottom + 1, r, r, p); p.style = Paint.Style.FILL }
                else {
                    p.color = (0x40 shl 24) or (INK and 0xFFFFFF); cv.drawRoundRect(rx - tw * 0.04f, ry - th * 0.06f, rx + tw * 1.04f, bottom + th * 0.06f, h * 0.036f, h * 0.036f, p)
                    if (acc != null) { p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1.5f, h * 0.01f); p.color = acc; cv.drawRoundRect(rx - tw * 0.04f, ry - th * 0.06f, rx + tw * 1.04f, bottom + th * 0.06f, h * 0.036f, h * 0.036f, p); p.style = Paint.Style.FILL }
                }
            }
        }
    }

    /** The wall moves (a video, or a web page that is one video): the picture alone cannot say so. js/home_walls.js `homeThemeMoving` is its twin. */
    fun moving(conf: JSONObject): Boolean {
        val wall = conf.optString("wall")
        if (HomeConfig.isVideo(wall)) return true
        if (wall != HomeConfig.WEB) return false
        val u = conf.optString("wurl").lowercase()
        return u.contains("/home/loop.html?v=") || Regex("\\.(mp4|webm|m3u8|mkv|mov)(\\?|#|$)").containsMatchIn(u)
    }

    /** The tags on a theme's tile (the page's `homeMediaBadges` is the twin): "video", "slides" (loop.html?s=), "photos:N" for a rotation of N. */
    fun badges(conf: JSONObject): List<String> {
        val rot = conf.optJSONArray("walls")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val walls = (listOf(conf.optString("wall")) + rot).filter { it.isNotEmpty() }.distinct()
        val u = conf.optString("wurl").lowercase(); val web = conf.optString("wall") == HomeConfig.WEB
        val out = ArrayList<String>()
        if (walls.any { HomeConfig.isVideo(it) } || (web && u.contains("loop.html?v="))) out.add("video")
        if (web && u.contains("loop.html?s=")) out.add("slides") else if (walls.size > 1) out.add("photos:" + walls.size)
        return out
    }

    /** A dark pill with a play triangle in the top right corner, over the head's buttons. */
    private fun play(cv: Canvas, p: Paint, w: Float, h: Float) {
        val bh = h * 0.17f; val bw = bh * 1.6f; val x = w - w * 0.04f - bw; val y = h * 0.05f
        p.color = PLAY_BG; cv.drawRoundRect(x, y, x + bw, y + bh, bh / 2, bh / 2, p)
        val t = bh * 0.42f; val cx = x + bw / 2 + t * 0.12f; val cy = y + bh / 2
        p.color = INK
        cv.drawPath(android.graphics.Path().apply { moveTo(cx - t * 0.45f, cy - t * 0.55f); lineTo(cx + t * 0.6f, cy); lineTo(cx - t * 0.45f, cy + t * 0.55f); close() }, p)
    }

    private fun strs(o: JSONObject, k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
}
