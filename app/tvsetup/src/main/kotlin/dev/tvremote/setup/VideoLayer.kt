package dev.tvremote.setup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import org.json.JSONObject

/**
 * A video in a box over every app (Video.kt in the shared code says how it is sent): a file played from this app's own folder,
 * or a phone's or computer's camera as it films (H.264 frames). The box is its own small window, moved and resized while it
 * plays (only the window's place changes: the picture goes on). It takes no keys and no touches, so the TV under it is used
 * as before. Nothing runs until a video is sent; Close, the end of a camera's stream or a decoder error takes it all away.
 *
 * Each connection: the secret, then one JSON line. {"o":"live","w":1280,"h":720,"x":..,"y":..,"s":..} makes the rest of
 * that connection the camera's frames ([int length][byte flags: 1 key][long µs][bytes], Annex B); any other line makes it a
 * control connection, one operation per line, each answered with the state ({"shown","playing","muted","pos","dur","err"}):
 *   {"o":"file","name":"v.mp4","x":5000,"y":5000,"s":3500}  plays files/video/<name>, looped, centred at x,y, s wide (0..10000)
 *   {"o":"move","x":..,"y":..,"s":..,"rot":90} · {"o":"pause"} · {"o":"play"} · {"o":"mute","on":true} · {"o":"close"} · {"o":"q"}
 * "rot" (0, 90, 180, 270, with any of the place's ops) turns the picture clockwise: a phone's upright video fills a TV turned on its side.
 */
internal object VideoLayer {
    private const val TAG = "VideoLayer"
    private val ui = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private val token: String by lazy { ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } }
    private var app: Context? = null

    private var box: FrameLayout? = null
    private var view: TextureView? = null                             // a TextureView, not a SurfaceView: only it can be turned (rot)
    private var lp: WindowManager.LayoutParams? = null
    @Volatile private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var ready = false                                         // prepared: asked for its place or length before, MediaPlayer fails (-38)
    @Volatile private var live: Socket? = null                       // the camera's stream now shown: a newer one closes it
    private var cx = 5000; private var cy = 5000; private var size = 3500
    private var aspect = 16f / 9f                                     // width / height of the picture
    private var rot = 0                                               // the picture turned clockwise by this, in degrees
    private var muted = false
    private var err = ""

    @Synchronized fun open(c: Context): JSONObject {
        app = c.applicationContext
        val s = server?.takeIf { !it.isClosed } ?: ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).also { s ->
            server = s
            Thread({ accept(s) }, "video-listen").apply { isDaemon = true }.start()
        }
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)
        val dir = c.getExternalFilesDir("video")?.absolutePath ?: ""
        return JSONObject().put("port", s.localPort).put("token", token).put("overlay", overlay).put("dir", dir)
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val k = try { s.accept() } catch (e: Exception) { return }
            Thread({ serve(k) }, "video").apply { isDaemon = true }.start()
        }
    }

    private fun serve(k: Socket) {
        k.use {
            val inp = DataInputStream(BufferedInputStream(k.getInputStream(), 1 shl 16))
            if (line(inp) != token) return
            val first = try { JSONObject(line(inp) ?: return) } catch (e: Exception) { return }
            if (first.optString("o") == "live") { stream(k, inp, first); return }
            val out = k.getOutputStream()
            var o: JSONObject? = first
            while (o != null) {
                answer(out, call(o))
                o = try { JSONObject(line(inp) ?: return) } catch (e: Exception) { return }
            }
        }
    }

    /** One line in UTF-8, read byte by byte: the camera's binary frames follow it on the same stream. */
    private fun line(inp: DataInputStream): String? {
        val b = java.io.ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8")
            if (b.size() > 65536) return null
            b.write(c)
        }
    }

    private fun answer(out: OutputStream, s: JSONObject) {
        try { out.write((s.toString() + "\n").toByteArray(Charsets.UTF_8)); out.flush() } catch (e: Exception) { }
    }

    /** Runs [o] on the main thread and gives the state after it. */
    private fun call(o: JSONObject): JSONObject {
        val done = java.util.concurrent.CountDownLatch(1)
        var st = JSONObject()
        ui.post { try { apply(o) } catch (e: Exception) { Log.w(TAG, "op ${o.optString("o")}: $e"); err = "video:error" }; st = state(); done.countDown() }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return st
    }

    private fun apply(o: JSONObject) {
        when (o.optString("o")) {
            "file" -> {
                val name = File(o.optString("name")).name               // only a file of this app's own folder
                val f = File(app?.getExternalFilesDir("video") ?: return, name)
                if (!f.isFile) { err = "video:nofile"; return }
                close()
                place(o)
                muted = false
                err = ""
                if (!show()) return
                play(f)
            }
            "move" -> { place(o); layout() }
            "pause" -> player?.takeIf { ready }?.let { if (it.isPlaying) it.pause() }
            "play" -> player?.takeIf { ready }?.start()
            "mute" -> { muted = o.optBoolean("on"); player?.let { val v = if (muted) 0f else 1f; it.setVolume(v, v) } }
            "close" -> close()
        }
    }

    private fun place(o: JSONObject) {
        if (o.has("x")) cx = o.optInt("x").coerceIn(0, 10000)
        if (o.has("y")) cy = o.optInt("y").coerceIn(0, 10000)
        if (o.has("s")) size = o.optInt("s").coerceIn(800, 10000)
        if (o.has("rot")) rot = Math.floorMod(o.optInt("rot") / 90, 4) * 90
    }

    /** Width / height of the box: the picture's, swapped when it is turned on its side. */
    private fun shape(): Float = if (rot % 180 == 90) 1f / aspect else aspect

    private fun state(): JSONObject {
        val p = player?.takeIf { ready }
        val playing = try { p?.isPlaying ?: (live != null || player != null) } catch (e: Exception) { false }
        val pos = try { p?.currentPosition ?: 0 } catch (e: Exception) { 0 }
        val dur = try { p?.duration ?: 0 } catch (e: Exception) { 0 }
        return JSONObject().put("shown", box != null).put("playing", playing).put("muted", muted).put("live", live != null)
            .put("pos", pos).put("dur", dur).put("err", err).put("aspect", aspect.toDouble()).put("rot", rot)
            .put("x", cx).put("y", cy).put("s", size)                 // a page opened later draws the box where it is
    }

    private fun screen(): Point {
        val p = Point()
        @Suppress("DEPRECATION") (app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(p)
        return p
    }

    /** The window's place from the centre and width, kept whole on the screen. */
    private fun bounds(): IntArray {
        val sc = screen()
        var w = (sc.x * size / 10000f).toInt()
        var h = (w / shape()).toInt()
        if (h > sc.y) { h = sc.y; w = (h * shape()).toInt() }
        val x = (sc.x * cx / 10000f - w / 2f).toInt().coerceIn(0, maxOf(0, sc.x - w))
        val y = (sc.y * cy / 10000f - h / 2f).toInt().coerceIn(0, maxOf(0, sc.y - h))
        return intArrayOf(x, y, w, h)
    }

    private fun layout() {
        val b = box ?: return
        val l = lp ?: return
        val r = bounds()
        l.x = r[0]; l.y = r[1]; l.width = r[2]; l.height = r[3]
        turn(r[2], r[3])
        try { (app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(b, l) } catch (e: Exception) { }
    }

    /** The picture inside a [w] x [h] box: laid out unturned (sides swapped when on its side), then turned about its centre. */
    private fun turn(w: Int, h: Int) {
        val v = view ?: return
        val side = rot % 180 == 90
        v.layoutParams = FrameLayout.LayoutParams(if (side) h else w, if (side) w else h, Gravity.CENTER)
        v.rotation = rot.toFloat()
    }

    /** The window, once; false when this TV gives no window over other apps (the sender was told "overlay": false). */
    private fun show(): Boolean {
        if (box != null) { layout(); return true }
        val c = app ?: return false
        val v = TextureView(c)
        v.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) { val sf = Surface(t); surface = sf; player?.setSurface(sf) }
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean { try { player?.setSurface(null) } catch (e: Exception) { }; surface?.release(); surface = null; return true }
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
        }
        val b = FrameLayout(c).apply { setBackgroundColor(Color.BLACK); addView(v) }
        view = v
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val r = bounds()
        turn(r[2], r[3])
        val l = WindowManager.LayoutParams(r[2], r[3], type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,          // a TextureView draws only in a hardware window
            PixelFormat.OPAQUE).apply { gravity = Gravity.TOP or Gravity.START; x = r[0]; y = r[1] }
        return try {
            (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(b, l)
            box = b; lp = l
            true
        } catch (e: Exception) {
            view = null
            Log.w(TAG, "no window over other apps: $e")
            err = "video:nooverlay"
            false
        }
    }

    private fun play(f: File) {
        val p = MediaPlayer()
        player = p; ready = false
        p.setOnVideoSizeChangedListener { mp, w, h -> if (player === mp && w > 0 && h > 0) { aspect = w.toFloat() / h; layout() } }
        p.setOnErrorListener { mp, what, extra -> if (player === mp) { Log.w(TAG, "player error $what/$extra"); err = "video:decoder"; close() }; true }   // not one being let go of
        p.setOnPreparedListener { if (player === it) { ready = true; it.start() } }
        p.isLooping = true
        try {
            p.setDataSource(f.absolutePath)
            surface?.let { p.setSurface(it) }
            p.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "cannot play ${f.name}: $e"); err = "video:decoder"; close()
        }
    }

    /** Everything away: the player, a camera's stream, the window. */
    private fun close() {
        val p = player; player = null; ready = false
        live?.let { s -> live = null; try { s.close() } catch (e: Exception) { } }
        val b = box
        val sf = if (b != null) surface else null
        if (b != null) { box = null; lp = null; view = null; surface = null }
        if (p != null) Thread({                                       // a TCL holds stop/release 5 s and more on a webm: off the main thread, which the page's answer waits on
            try { p.stop() } catch (e: Exception) { }; try { p.release() } catch (e: Exception) { }
            sf?.release()                                             // once the player has let go of it
        }, "video-release").start()
        else sf?.release()
        if (b != null) try { (app?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(b) } catch (e: Exception) { }
    }

    /** A camera's frames, decoded straight onto the window's surface; the window goes when the stream ends. */
    private fun stream(k: Socket, inp: DataInputStream, head: JSONObject) {
        val ready = java.util.concurrent.CountDownLatch(1)
        ui.post {
            close()
            place(head)
            val w = head.optInt("w", 1280); val h = head.optInt("h", 720)
            if (w > 0 && h > 0) aspect = w.toFloat() / h
            muted = true; err = ""
            if (show()) live = k
            ready.countDown()
        }
        ready.await(5, java.util.concurrent.TimeUnit.SECONDS)
        if (live !== k) return
        try { k.soTimeout = 6000 } catch (e: Exception) { }                // the page went (a tab closed, a phone asleep): the box goes too
        var codec: MediaCodec? = null
        try {
            var waited = 0
            while (surface == null && waited < 3000 && live === k) { Thread.sleep(20); waited += 20 }
            val sf = surface ?: return
            val fmt = MediaFormat.createVideoFormat("video/avc", head.optInt("w", 1280), head.optInt("h", 720))
            if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val c = MediaCodec.createDecoderByType("video/avc")
            codec = c
            c.configure(fmt, sf, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var buf = ByteArray(1 shl 18)
            while (live === k) {
                val len = inp.readInt()
                val flags = inp.readByte().toInt()
                val us = inp.readLong()
                if (len <= 0 || len > 8_000_000) break
                if (buf.size < len) buf = ByteArray(len)
                inp.readFully(buf, 0, len)
                val i = c.dequeueInputBuffer(100_000)
                if (i >= 0) {
                    val ib = (if (Build.VERSION.SDK_INT >= 21) c.getInputBuffer(i) else null) ?: continue
                    ib.clear(); ib.put(buf, 0, len)
                    c.queueInputBuffer(i, 0, len, us, if (flags and 1 != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                }
                while (true) {                                        // whatever is decoded goes on screen at once: the newest frame wins
                    val o = c.dequeueOutputBuffer(info, 0)
                    if (o >= 0) c.releaseOutputBuffer(o, true)
                    else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        val vw = f.getInteger(MediaFormat.KEY_WIDTH); val vh = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (vw > 0 && vh > 0) ui.post { if (live === k) { aspect = vw.toFloat() / vh; layout() } }
                    } else break
                }
            }
        } catch (e: java.io.EOFException) {
        } catch (e: Exception) {
            Log.w(TAG, "camera stream: $e")
            if (e is MediaCodec.CodecException || e is IllegalStateException) err = "video:decoder"
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            ui.post { if (live === k) close() }
        }
    }
}

/** The shell's way to [VideoLayer] (Video.kt): only the shell may send it (the manifest), so only a device with ADB here. */
class VideoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val answer = try { VideoLayer.open(c) } catch (e: Exception) { JSONObject().put("error", e.message ?: e.javaClass.simpleName) }
        resultCode = Activity.RESULT_OK
        resultData = Base64.encodeToString(answer.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}
