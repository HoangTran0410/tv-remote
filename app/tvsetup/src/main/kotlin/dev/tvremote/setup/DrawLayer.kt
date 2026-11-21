package dev.tvremote.setup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Drawing over the TV's screen for a phone or computer (Draw.kt in the shared code says how the strokes come). A window over
 * every app that takes no keys and no touches, so the person watching keeps using the TV under it. It goes away when cleared,
 * and by itself some minutes after the last stroke: each stroke says how many ("k", 10 when missing, as before there was a
 * choice). 0 keeps it until cleared, through a restart too: then it is saved in a file ([KEEP]) and brought back when the app
 * starts (SetupApp), and TV Setup's screen gets a tile to clear it with no phone at hand.
 */
internal object DrawLayer {
    private const val MINUTES = dev.tvremote.core.ontv.Draw.MINUTES   // a stroke with no "k": an older client
    private const val KEEP = "draw-kept.json"
    private val ui = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private val token: String by lazy { ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } }
    private var app: Context? = null
    private var view: Board? = null
    private var minutes = MINUTES                                     // of the last stroke: undo keeps it
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var gen = 0                                               // one more on each hide: a restore read before it is dropped
    private var unshown = false                                       // the kept drawing could not be shown: no stroke may forget it

    private val idle = Runnable { hide(keep = unshown) }
    private val save = Runnable { store() }

    /** A drawing kept until cleared is on the screen now (TV Setup's tile to clear it). */
    fun kept(c: Context) = java.io.File(c.filesDir, KEEP).exists()

    /** After a restart: the drawing kept until cleared, back over the screen. Only a file check when there is none. */
    fun restore(c: Context) {
        val f = java.io.File(c.filesDir, KEEP)
        if (!f.exists()) return
        app = c.applicationContext
        val g = gen
        io.execute {
            val ops = try { org.json.JSONArray(f.readText()) } catch (e: Exception) { f.delete(); return@execute }
            val items = (0 until ops.length()).mapNotNull { ops.optJSONObject(it) }.map { it to picture(it) }   // decoded here, not on the UI thread
            ui.post {
                if (view != null || g != gen) return@post             // a phone drew meanwhile (its strokes win), or it was cleared
                if (items.none { (o, p) -> o.optString("o") == "s" || p != null }) { forget(); return@post }   // nothing to show: no empty layer
                val b = board() ?: run { unshown = true; return@post }   // no window over apps yet: kept for the next start
                for ((o, p) in items) if (p != null) b.add(p) else if (o.optString("o") == "s") b.add(o)
                minutes = 0
            }
        }
    }

    /** TV Setup's tile: the kept drawing away, as Clear all from a phone. */
    fun clear(c: Context) { app = app ?: c.applicationContext; ui.post { hide() } }

    /** The port on 127.0.0.1 and its secret, starting the listener if it is not running. */
    @Synchronized fun open(c: Context): JSONObject {
        app = c.applicationContext
        val s = server?.takeIf { !it.isClosed } ?: ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).also { s ->
            server = s
            Thread({ accept(s) }, "draw-listen").apply { isDaemon = true }.start()
        }
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)
        return JSONObject().put("port", s.localPort).put("token", token).put("overlay", overlay).put("pic", true).put("keep", true)
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val k = try { s.accept() } catch (e: Exception) { return }
            Thread({ try { serve(k) } catch (e: java.io.IOException) { } }, "draw").apply { isDaemon = true }.start()   // a sender dropping (Connection reset) ends only its own thread (census 6fd12ce4)
        }
    }

    /** One sender: its secret first, then one operation per line. Another app on the TV could reach the port, not the secret. */
    private fun serve(k: Socket) {
        k.use {
            val r = BufferedReader(InputStreamReader(k.getInputStream(), Charsets.UTF_8))
            if (r.readLine() != token) return
            while (true) {
                val line = r.readLine() ?: return
                val o = try { JSONObject(line) } catch (e: Exception) { continue }
                val p = if (o.optString("o") == "i") picture(o) else null   // decoded on this sender's thread: the UI keeps its frames
                ui.post { apply(o, p) }
            }
        }
    }

    private fun apply(o: JSONObject, p: Pic?) {
        when (o.optString("o")) {
            "s" -> board()?.let { it.add(o); arm(o.optInt("k", MINUTES)) }
            "i" -> if (p != null) board()?.let { it.add(p); arm(o.optInt("k", MINUTES)) }   // one that would not decode: no layer for nothing
            "a" -> view?.let { it.add(o); arm(minutes) }
            "k" -> if (view != null) arm(o.optInt("m", MINUTES))      // the choice changed while drawing: what is there now follows it
            "u" -> view?.let { it.undo(); if (it.empty()) hide(keep = unshown) else arm(minutes) }
            "x", "h" -> hide()
        }
    }

    /** The clock from now: [m] minutes, or none and the drawing saved (a second after the last change, not every point). */
    private fun arm(m: Int) {
        minutes = m.coerceIn(0, 24 * 60)
        ui.removeCallbacks(idle)
        ui.removeCallbacks(save)
        if (minutes > 0) { ui.postDelayed(idle, minutes * 60_000L); if (!unshown) forget() }
        else ui.postDelayed(save, 1000)
    }

    private fun store() {
        val b = view ?: return
        val c = app ?: return
        if (b.empty()) { forget(); return }                           // an empty drawing kept would come back as an empty layer
        unshown = false                                               // this one replaces the kept drawing that could not be shown
        val items = b.items()                                         // taken here, on the UI thread; written there
        io.execute {
            val f = java.io.File(c.filesDir, KEEP)
            val tmp = java.io.File(c.filesDir, "$KEEP.tmp")           // whole or not at all: a cut write must not lose the kept one
            try { tmp.writeText(org.json.JSONArray(items.map { it() }).toString()); if (!tmp.renameTo(f)) throw java.io.IOException("rename") }
            catch (e: Throwable) { tmp.delete(); Log.w("DrawLayer", "not kept: $e") }
        }
    }

    private fun forget() { val c = app ?: return; io.execute { java.io.File(c.filesDir, KEEP).delete() } }

    private fun board(): Board? {
        view?.let { return it }
        val c = app ?: return null
        val b = Board(c)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        return try {
            (c.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).addView(b, lp)
            view = b
            b
        } catch (e: Exception) {
            Log.w("DrawLayer", "no window over other apps: $e")         // the sender was told "overlay": false and says why
            null
        }
    }

    /** [keep]: the kept drawing that could not be shown stays for the next start; Clear all and the tile drop it. */
    private fun hide(keep: Boolean = false) {
        gen++
        ui.removeCallbacks(idle)
        ui.removeCallbacks(save)
        if (!keep) { forget(); unshown = false }
        val b = view ?: return
        view = null
        try { (app?.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)?.removeView(b) } catch (e: Exception) { }
    }

    internal class Pic(val bmp: Bitmap, val x: Int, val y: Int, val w: Int, val gif: Gif? = null) { @Volatile var b64: String? = null }   // b64: once, when kept

    /**
     * A moving GIF: each frame drawn into [Pic.bmp] (a software canvas: android.graphics.Movie draws nothing on a hardware
     * one). Movie runs on every Android TV Setup does (minSdk 21; ImageDecoder only from 9). Shown at the GIF's own pace.
     */
    internal class Gif(val movie: android.graphics.Movie, val frame: Canvas) { var start = 0L }
    private const val GIF_PIXELS = 1280L * 1280                       // one frame's memory; bigger ones stay still
    private const val PIC_BYTES = 32 shl 20                           // ~5 photos at 1280 pixels, dozens of messages

    /** An "i" op's picture, decoded no larger than the screen needs, off the UI thread; null when it will not decode. */
    private fun picture(o: JSONObject): Pic? = try {
        val bytes = Base64.decode(o.optString("b"), Base64.DEFAULT)
        gif(o, bytes) ?: still(o, bytes)
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { Log.w("DrawLayer", "picture too big: $e"); null }   // one picture must not end TV Setup

    /** A GIF with more than one frame and no bigger than [GIF_PIXELS]; null for anything else (drawn still, its first frame). */
    @Suppress("DEPRECATION")
    private fun gif(o: JSONObject, bytes: ByteArray): Pic? {
        if (bytes.size < 6 || String(bytes, 0, 4, Charsets.ISO_8859_1) != "GIF8") return null
        val m = android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (m.duration() <= 0 || m.width() <= 0 || m.height() <= 0 || m.width().toLong() * m.height() > GIF_PIXELS) return null
        val bmp = Bitmap.createBitmap(m.width(), m.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        m.setTime(0); m.draw(c, 0f, 0f)
        return Pic(bmp, o.optInt("x", 5000).coerceIn(0, 10000), o.optInt("y", 5000).coerceIn(0, 10000), o.optInt("w", 4000).coerceIn(100, 10000), Gif(m, c))
            .also { it.b64 = o.optString("b") }                       // kept as it came: a PNG of one frame would stop it
    }

    private fun still(o: JSONObject, bytes: ByteArray): Pic? = run {
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, size)
        if (size.outWidth <= 0 || size.outHeight <= 0) null
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = drawSample(size.outWidth, size.outHeight, 1920) })
            ?.let { Pic(it, o.optInt("x", 5000).coerceIn(0, 10000), o.optInt("y", 5000).coerceIn(0, 10000), o.optInt("w", 4000).coerceIn(100, 10000)) }
    }

    /** The strokes and pictures, kept in 0..10000 of the screen so they fit it whatever its size. */
    private class Board(c: Context) : View(c) {
        private class Stroke(val id: Int, val color: Int, val width: Int) { val pts = ArrayList<Int>() }
        private val strokes = ArrayList<Any>()                        // Stroke or Pic, in the order they came: undo takes the last
        private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
        private val box = RectF()
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val path = Path()

        fun empty() = strokes.isEmpty()

        /** Each stroke and picture as the operation that draws it again; called here, run off the UI thread (a picture's PNG). */
        fun items(): List<() -> JSONObject> = strokes.map { item ->
            if (item is Stroke) { val pts = item.pts.toIntArray(); val c = String.format("#%06x", item.color and 0xffffff)
                ({ JSONObject().put("o", "s").put("id", item.id).put("c", c).put("w", item.width).put("p", org.json.JSONArray(pts)).put("k", 0) }) }
            else { val p = item as Pic
                ({ JSONObject().put("o", "i").put("b", p.b64 ?: java.io.ByteArrayOutputStream().also { p.bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    .let { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }.also { p.b64 = it }).put("x", p.x).put("y", p.y).put("w", p.w).put("k", 0) }) }
        }

        /** A picture; past [PIC_BYTES] the oldest go, so pictures sent all evening never take the app's memory. */
        fun add(p: Pic) {
            strokes.add(p)
            val pics = strokes.filterIsInstance<Pic>()
            for (old in pics.take(drawPicsOver(pics.map { it.bmp.byteCount }, PIC_BYTES))) strokes.remove(old)
            invalidate()
        }

        fun add(o: JSONObject) {
            val id = o.optInt("id")
            val s = if (o.optString("o") == "s") {
                val color = try { Color.parseColor(o.optString("c", "#ff3b30")) } catch (e: Exception) { Color.RED }
                Stroke(id, color, o.optInt("w", 60).coerceIn(10, 400)).also { strokes.add(it) }
            } else strokes.lastOrNull { it is Stroke && it.id == id } as Stroke? ?: return
            val p = o.optJSONArray("p") ?: return
            for (i in 0 until p.length() - 1 step 2) { s.pts.add(p.optInt(i).coerceIn(0, 10000)); s.pts.add(p.optInt(i + 1).coerceIn(0, 10000)) }
            invalidate()
        }

        fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1); invalidate() }

        @Suppress("DEPRECATION")
        override fun onDraw(c: Canvas) {
            val w = width / 10000f
            val h = height / 10000f
            var next = Long.MAX_VALUE                                 // ms to the soonest GIF frame
            for (item in strokes) {
                if (item is Pic) {
                    val pw = item.w * w
                    val ph = pw * item.bmp.height / item.bmp.width
                    box.set(item.x * w - pw / 2, item.y * h - ph / 2, item.x * w + pw / 2, item.y * h + ph / 2)
                    item.gif?.let { g ->
                        val now = android.os.SystemClock.uptimeMillis()
                        if (g.start == 0L) g.start = now
                        g.movie.setTime(((now - g.start) % g.movie.duration()).toInt())
                        item.bmp.eraseColor(Color.TRANSPARENT)
                        g.movie.draw(g.frame, 0f, 0f)
                        next = minOf(next, GIF_FRAME_MS)
                    }
                    c.drawBitmap(item.bmp, null, box, smooth)
                    continue
                }
                val s = item as Stroke
                if (s.pts.size < 2) continue
                path.reset()
                path.moveTo(s.pts[0] * w, s.pts[1] * h)
                if (s.pts.size == 2) path.lineTo(s.pts[0] * w + 0.1f, s.pts[1] * h)   // a tap: a dot
                var i = 2
                while (i + 1 < s.pts.size) {                          // through the midpoints: smooth, not a chain of segments
                    val x0 = s.pts[i - 2] * w; val y0 = s.pts[i - 1] * h
                    val x1 = s.pts[i] * w; val y1 = s.pts[i + 1] * h
                    path.quadTo(x0, y0, (x0 + x1) / 2, (y0 + y1) / 2)
                    i += 2
                }
                if (s.pts.size >= 4) path.lineTo(s.pts[s.pts.size - 2] * w, s.pts[s.pts.size - 1] * h)
                val thick = s.width * w
                pen.color = 0x99000000.toInt()                        // a dark edge: seen on a bright picture as on a dark one
                pen.strokeWidth = thick + maxOf(4f, thick * 0.5f)
                c.drawPath(path, pen)
                pen.color = s.color
                pen.strokeWidth = thick
                c.drawPath(path, pen)
            }
            if (next != Long.MAX_VALUE) postInvalidateDelayed(next)   // a GIF moves on; none left (cleared, undone): nothing runs
        }
    }
}

private const val GIF_FRAME_MS = 66L                                 // 15 a second: GIFs rarely ask for more, a weak TV box pays for each

// Pure, outside the object (its Handler needs a device), so the unit tests reach them.
/** How many of the oldest pictures go so the rest fit [budget] bytes; the newest always stays. */
internal fun drawPicsOver(sizes: List<Int>, budget: Int): Int {
    var total = sizes.sumOf { it.toLong() }
    var n = 0
    while (n < sizes.size - 1 && total > budget) total -= sizes[n++]
    return n
}

/** The power of two to decode at so neither side passes [max]: a phone photo would take more memory than a TV app may use. */
internal fun drawSample(w: Int, h: Int, max: Int): Int {
    var s = 1
    while (w / s > max || h / s > max) s *= 2
    return s
}

/** The shell's way to [DrawLayer] (Draw.kt): only the shell may send it (the manifest), so only a device with ADB here. */
class DrawReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val answer = try { DrawLayer.open(c) } catch (e: Exception) { JSONObject().put("error", e.message ?: e.javaClass.simpleName) }
        resultCode = Activity.RESULT_OK
        resultData = Base64.encodeToString(answer.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}
