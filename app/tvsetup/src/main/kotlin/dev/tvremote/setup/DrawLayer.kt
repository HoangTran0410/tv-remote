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

    private val idle = Runnable { hide() }
    private val save = Runnable { store() }

    /** A drawing kept until cleared is on the screen now (TV Setup's tile to clear it). */
    fun kept(c: Context) = java.io.File(c.filesDir, KEEP).exists()

    /** After a restart: the drawing kept until cleared, back over the screen. Only a file check when there is none. */
    fun restore(c: Context) {
        val f = java.io.File(c.filesDir, KEEP)
        if (!f.exists()) return
        app = c.applicationContext
        io.execute {
            val ops = try { org.json.JSONArray(f.readText()) } catch (e: Exception) { f.delete(); return@execute }
            ui.post {
                if (view != null) return@post                         // a phone drew meanwhile: its strokes win
                val b = board() ?: return@post
                for (i in 0 until ops.length()) ops.optJSONObject(i)?.let { b.add(it) }
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
                ui.post { apply(o) }
            }
        }
    }

    private fun apply(o: JSONObject) {
        when (o.optString("o")) {
            "s", "i" -> { board()?.let { it.add(o); arm(o.optInt("k", MINUTES)) } }
            "a" -> view?.let { it.add(o); arm(minutes) }
            "k" -> if (view != null) arm(o.optInt("m", MINUTES))      // the choice changed while drawing: what is there now follows it
            "u" -> view?.let { it.undo(); if (it.empty()) hide() else arm(minutes) }
            "x", "h" -> hide()
        }
    }

    /** The clock from now: [m] minutes, or none and the drawing saved (a second after the last change, not every point). */
    private fun arm(m: Int) {
        minutes = m.coerceIn(0, 24 * 60)
        ui.removeCallbacks(idle)
        ui.removeCallbacks(save)
        if (minutes > 0) { ui.postDelayed(idle, minutes * 60_000L); forget() }
        else ui.postDelayed(save, 1000)
    }

    private fun store() {
        val b = view ?: return
        val c = app ?: return
        val items = b.items()                                         // taken here, on the UI thread; written there
        io.execute {
            val f = java.io.File(c.filesDir, KEEP)
            try { f.writeText(org.json.JSONArray(items.map { it() }).toString()) } catch (e: Exception) { Log.w("DrawLayer", "not kept: $e") }
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

    private fun hide() {
        ui.removeCallbacks(idle)
        ui.removeCallbacks(save)
        forget()
        val b = view ?: return
        view = null
        try { (app?.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)?.removeView(b) } catch (e: Exception) { }
    }

    /** The strokes and pictures, kept in 0..10000 of the screen so they fit it whatever its size. */
    private class Board(c: Context) : View(c) {
        private class Stroke(val id: Int, val color: Int, val width: Int) { val pts = ArrayList<Int>() }
        private class Pic(val bmp: Bitmap, val x: Int, val y: Int, val w: Int) { @Volatile var b64: String? = null }   // b64: once, when kept
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

        fun add(o: JSONObject) {
            if (o.optString("o") == "i") { picture(o)?.let { strokes.add(it); invalidate() }; return }
            val id = o.optInt("id")
            val s = if (o.optString("o") == "s") {
                val color = try { Color.parseColor(o.optString("c", "#ff3b30")) } catch (e: Exception) { Color.RED }
                Stroke(id, color, o.optInt("w", 60).coerceIn(10, 400)).also { strokes.add(it) }
            } else strokes.lastOrNull { it is Stroke && it.id == id } as Stroke? ?: return
            val p = o.optJSONArray("p") ?: return
            for (i in 0 until p.length() - 1 step 2) { s.pts.add(p.optInt(i).coerceIn(0, 10000)); s.pts.add(p.optInt(i + 1).coerceIn(0, 10000)) }
            invalidate()
        }

        /** Decoded no larger than the screen needs: a phone photo would take more memory than a TV app may use. */
        private fun picture(o: JSONObject): Pic? {
            val bytes = try { Base64.decode(o.optString("b"), Base64.DEFAULT) } catch (e: Exception) { return null }
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, size)
            if (size.outWidth <= 0) return null
            var sample = 1
            while (size.outWidth / (sample * 2) >= 1920) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            return Pic(bmp, o.optInt("x", 5000).coerceIn(0, 10000), o.optInt("y", 5000).coerceIn(0, 10000), o.optInt("w", 4000).coerceIn(100, 10000))
        }

        fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1); invalidate() }

        override fun onDraw(c: Canvas) {
            val w = width / 10000f
            val h = height / 10000f
            for (item in strokes) {
                if (item is Pic) {
                    val pw = item.w * w
                    val ph = pw * item.bmp.height / item.bmp.width
                    box.set(item.x * w - pw / 2, item.y * h - ph / 2, item.x * w + pw / 2, item.y * h + ph / 2)
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
        }
    }
}

/** The shell's way to [DrawLayer] (Draw.kt): only the shell may send it (the manifest), so only a device with ADB here. */
class DrawReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val answer = try { DrawLayer.open(c) } catch (e: Exception) { JSONObject().put("error", e.message ?: e.javaClass.simpleName) }
        resultCode = Activity.RESULT_OK
        resultData = Base64.encodeToString(answer.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}
