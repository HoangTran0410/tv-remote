package dev.tvremote.home.wall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import dev.tvremote.home.wallGradient

/**
 * The wallpaper, painted once at the screen's full size (dithered) into one picture that is [target]'s background (the window's).
 * Drawn live there, a gradient cost an FPT Play box 60 → 40 frames a second and a photo with its shade 42 ms a frame; a small
 * stretched picture showed bands ~20 px wide. On a SurfaceView of its own behind the window instead, a BRAVIA (MT5891, Android 9)
 * blended both full-screen layers on its GPU every frame: 45 ms a frame while focus moved, 9.5 ms with this one picture (the FPT
 * box: 15.8 ms either way). Changing it (a new choice, the rotation) switches at once: a cross-fade (two full-size bitmaps
 * blended on a thread) stalled the FPT box's main thread for up to a second, so there is none.
 * The picture is painted on a thread of its own and sent to the GPU there ([Bitmap.prepareToDraw]); only the swap is on the main
 * thread. Painted there, each slide of a slideshow (a new photo every few minutes) froze the focus for a frame or more, at random
 * to the person: the FPT box's gfxinfo counted "slow bitmap uploads". A new bitmap each time: the old one goes with its drawable.
 */
class Backdrop(private val target: View) {
    /** What is painted: a gradient, a drawn scene ([art], WallArt) or a photo over [colors] (any size: as [fit] says). */
    class Look(val colors: IntArray, val photo: Bitmap? = null, val art: String? = null)

    /** The person's veil ([HomeConfig] "shade" / its default: 1 the usual one), for the next [show]. */
    var strength = 1f
    /** Which way the next gradient runs ([wallDir]: only the person's own wall has a choice). */
    var dir = "tl_br"
    /** How the next photo fills the screen ([HomeConfig] "wfit"): "cover" cropped to fill it, "contain" whole, over itself blurred. */
    var fit = "cover"
    private val pic = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val work = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "backdrop").apply { isDaemon = true } }
    @Volatile private var gen = 0
    /** The photo last shown as it fills the screen, small and without the veil (448 px wide, ~0.3 MB): the live preview's background (ThemeShots.current), null for colours or a scene. */
    @Volatile var small: Bitmap? = null
    /** What the window shows now is a sharp photo (set with the swap, main thread): [HomeActivity.trim] leaves it, as a stand-in would only blur it. */
    var photoShown = false
        private set

    /** The last look again with veil [k], when it was kept ([hold]); false: there is none, load it. */
    fun reshade(k: Float): Boolean {
        val l = kept?.takeIf { hold } ?: return false
        strength = k; show(l); return true
    }

    fun show(colors: IntArray, photo: Bitmap?, art: String? = null) = show(Look(colors, photo, art))

    /**
     * [l] painted off the main thread; the last call wins (an older one still painting is dropped). [then] runs on the main thread
     * with the swap (the widgets' frost changes with the picture, not a few frames before it).
     */
    fun show(l: Look, then: (() -> Unit)? = null) {
        val dm = target.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val g = ++gen; val k = strength; val d = dir; val f = fit
        kept = if (hold) l else null
        work.execute {
            if (g != gen) return@execute
            small = l.photo?.let { p -> try { fitted(p, 448, maxOf(1, 448 * h / w), f) } catch (e: Throwable) { null } }
            val b = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { return@execute }   // 565 would band every gradient
            draw(Canvas(b), w, h, l, k, d, f)
            // on the GPU here, not in the first frame that draws it: prepareToDraw left the FPT box 15 frames of "slow issue draw
            // commands" (to 69 ms) per slide; a HARDWARE copy is uploaded by this thread and the 8 MB in the app's heap go
            val out = if (android.os.Build.VERSION.SDK_INT >= 26) (try { b.copy(Bitmap.Config.HARDWARE, false) } catch (e: Throwable) { null })?.also { b.recycle() } ?: b else b
            target.post {
                if (g != gen) return@post
                target.background = BitmapDrawable(target.resources, out).apply { setDither(true) }
                photoShown = l.photo != null
                target.invalidate()
                then?.invoke()
            }
        }
    }

    private fun draw(c: Canvas, w: Int, h: Int, l: Look, strength: Float, dir: String, fit: String) {
        val p = l.photo
        val art = l.art
        if (art != null && WallArt.has(art)) {
            WallArt.paint(c, w, h, art)
            gradient(GradientDrawable.Orientation.TOP_BOTTOM, shade(WallArt.shade(art), strength), w, h).draw(c)
        } else if (p == null) {
            if (l.colors.all { it == l.colors[0] }) c.drawColor(l.colors[0])
            else wallGradient(l.colors, dir).apply { setBounds(0, 0, w, h) }.draw(c)
        } else {
            photo(c, w, h, p, fit, pic)
            gradient(GradientDrawable.Orientation.TOP_BOTTOM, shade(1f, strength), w, h).draw(c)   // words stay readable over any photo
        }
    }

    private fun gradient(o: GradientDrawable.Orientation, cs: IntArray, w: Int, h: Int) =
        GradientDrawable(o, cs).apply { setBounds(0, 0, w, h); setDither(true) }

    companion object {
        /**
         * [p] on a [w] x [h] canvas: cropped to cover it, or ("contain") whole in the middle over itself cropped, blurred ([Wall.soft]) and
         * dimmed, so a portrait photo's sides carry its own colours instead of black bars (the user, 2026-10-27).
         */
        private fun photo(c: Canvas, w: Int, h: Int, p: Bitmap, fit: String, pic: Paint) {
            val k = maxOf(w.toFloat() / p.width, h.toFloat() / p.height)
            if (fit != "contain" || Math.abs(p.width * h - p.height * w) < p.width * h / 50) {   // within 2 % of the screen's shape: nothing to fill
                val sw = (w / k).toInt(); val sh = (h / k).toInt()
                c.drawBitmap(p, Rect((p.width - sw) / 2, (p.height - sh) / 2, (p.width + sw) / 2, (p.height + sh) / 2), Rect(0, 0, w, h), pic)
                return
            }
            val soft = Wall.soft(p)
            val ks = maxOf(w.toFloat() / soft.width, h.toFloat() / soft.height)
            val bw = (w / ks).toInt().coerceAtLeast(1); val bh = (h / ks).toInt().coerceAtLeast(1)
            c.drawBitmap(soft, Rect((soft.width - bw) / 2, (soft.height - bh) / 2, (soft.width + bw) / 2, (soft.height + bh) / 2), Rect(0, 0, w, h), pic)
            if (soft !== p) soft.recycle()
            c.drawColor(BLUR_DIM)
            val kc = minOf(w.toFloat() / p.width, h.toFloat() / p.height)
            val dw = (p.width * kc).toInt(); val dh = (p.height * kc).toInt()
            c.drawBitmap(p, null, Rect((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), pic)
        }

        /** [p] filling a new [w] x [h] picture as the screen shows it ([fit]), without the veil: the live preview's. */
        fun fitted(p: Bitmap, w: Int, h: Int, fit: String): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            photo(Canvas(it), w, h, p, fit, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        }

        /** A stepper for the veil is open: the last picture is kept (8 MB at 1080p) so each step only paints it again ([reshade]). */
        var hold = false
            set(v) { field = v; if (!v) kept = null }                 // closed: the picture goes at once, not with the next wall
        private var kept: Look? = null                                // while [hold]: the last look, to paint again with another veil
        val SHADE = intArrayOf(0xB0000000.toInt(), 0x40000000, 0x90000000.toInt())
        /**
         * [SHADE] at [k] of its strength (a drawn scene is made dark enough already, a photo is not), then the person's [user]
         * ("shade" / its default, 0..2): under 1 lighter, over 1 each stop goes towards [DARKEST], so the middle (light at 1, where a busy page
         * shows most) darkens most and 2 is an even veil.
         */
        fun shade(k: Float, user: Float = 1f) = IntArray(SHADE.size) {
            val a = (SHADE[it] ushr 24) * k
            val u = if (user <= 1f) a * user else a + (DARKEST - a).coerceAtLeast(0f) * minOf(user - 1f, 1f)
            u.toInt().coerceIn(0, 255) shl 24
        }
        private const val DARKEST = 0xE0.toFloat()
        private const val BLUR_DIM = 0x4D000000                       // a contained photo's blurred self a step back, so the photo's own edge reads
    }
}
