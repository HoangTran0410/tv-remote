package dev.tvremote.home.wall

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.PixelCopy
import android.media.MediaMetadataRetriever
import android.os.Process
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.ImageView
import android.media.MediaPlayer
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File

/**
 * A video (or a GIF made into one) as the background: a file looping on a SurfaceView behind the grid ([Loop]), with the same veil a
 * photo gets over it. The platform's decoder on purpose: it is hardware and costs no library (ExoPlayer is in TV Channels only).
 * A SurfaceView and not a TextureView: the decoder's frames go to the display compositor (an overlay where the box has one) and never
 * through Home's own GPU pass, so Home draws no frame for the video at all (dev/decisions/tv-home.md "TV Home: video", measured).
 *
 * A SurfaceView is a hole in the window, black until its first frame: an ImageView with the poster (the first frame at the screen's
 * size, Home's own window background) covers it until the player shows one, and again whenever the player is let go, so Home never flashes black.
 * The widgets' frost uses the same poster, blurred, as for a photo.
 *
 * It measures itself: [Verdict.WINDOW_MS] after the first frame it reports [Measure] (frames shown against the clip's rate, its CPU)
 * and HomeActivity keeps the verdict ([Videos.measured]); too slow twice and Home shows the poster alone.
 *
 * The loop: a file is fed to the decoder round and round with no flush ([Loop]): no hold at the turn. MediaPlayer's `isLooping` held
 * ~100 ms a turn on the FPT box (flush + seek), a second MediaPlayer ~290 ms, a file with sound paced by the audio clock seconds
 * (dev/decisions/tv-home.md "TV Home: video"). A net video's first turns are streamed by MediaPlayer (not looped) while [NetVideo]
 * keeps its file; when one ends, play() starts again and takes the kept file, which loops.
 *
 * A clip whose end is not its start jumps at the turn; with [fade] on (HomeConfig "wfade": the site's themes set it for such clips,
 * the person can turn it on or off) the turn is a dissolve: the end's last frame is copied off the surface ([PixelCopy], API 24), laid
 * over it and faded out over [FADE_MS] while the next turn plays below. One picture of the video's size, made only when fade is on.
 *
 * It plays only while Home is on screen: [stop] (Home stopped: another app in front, screen off, memory short) releases the decoder,
 * which the app in front may need; [start] makes it again. No timer, no polling.
 */
class VideoWall(private val app: Context, private val root: ViewGroup, private val ui: Handler,
                private val fade: () -> Boolean, private val measured: (Measure) -> Unit, private val failed: () -> Unit) {
    /** What 20 s of playing showed: frames shown per second against the clip's own, this process's CPU (% of one core), errors. */
    class Measure(val fps: Float, val want: Float, val cpu: Int, val errors: Int)

    private val view = SurfaceView(app)
    private var poster: ImageView? = null
    private var veil: View? = null
    private var still: ImageView? = null                            // the end's last frame over the next turn, fading out
    private var shot: Bitmap? = null
    private var player: MediaPlayer? = null                         // a stream's first turns
    private var loop: Loop? = null                                  // a file
    private var surface: Surface? = null
    private var file: File? = null
    private var url: String? = null                                 // a video from the net ([showUrl]): streamed, not in the library, so not measured
    private var vw = 0; private var vh = 0
    private var begun = 0L
    private var errors = 0
    private var t0 = 0L; private var cpu0 = 0L; private var f0 = 0; private var d0 = 0; private var want = 30f
    private val measure = Runnable { measureNow() }

    init {
        view.isFocusable = false; view.isClickable = false
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surface = h.surface; play() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { release(); surface = null }
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> cover() }
    }

    /** Puts [f] behind the grid (the same file again changes nothing but the veil [user]). */
    fun show(f: File, user: Float) {
        veil(user)
        if (file != f || url != null) { release(); file = f; url = null; play() }
    }

    private fun veil(user: Float) {
        if (veil == null) {
            root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
            poster = ImageView(app).apply {                                     // a SurfaceView is a hole in the window: the poster fills it until the first frame
                scaleType = ImageView.ScaleType.CENTER_CROP; isFocusable = false
                root.addView(this, 1, FrameLayout.LayoutParams(-1, -1))
            }
            veil = WebWall.dim(app, user).also { root.addView(it, 2, FrameLayout.LayoutParams(-1, -1)) }
        }
        veil?.background = WebWall.veil(user)
    }

    /**
     * A direct video link (a web wall that was only a video, [WebVideo]) behind the grid, played like a library video. No file, no
     * verdict: when the player cannot play it, [failed] runs and the caller falls back to the page in a WebView.
     */
    fun showUrl(u: String, user: Float) {
        veil(user)
        if (url != u) { release(); file = null; url = u; play() }
    }

    /** Home is on screen: play (the player was let go in [stop]). */
    fun start() { if (player == null && loop == null) play() }

    /** Home is away or memory is short: no player, no decoder; the view and the poster stay. */
    fun stop() = release()

    /** Gone for good: the views are taken out. */
    fun detach() {
        release()
        (view.parent as? ViewGroup)?.removeView(view)
        poster?.let { (it.parent as? ViewGroup)?.removeView(it) }; poster = null
        still?.let { (it.parent as? ViewGroup)?.removeView(it) }; still = null
        veil?.let { (it.parent as? ViewGroup)?.removeView(it) }
        veil = null; file = null; url = null
    }

    private fun play() {
        val s = surface ?: return                                       // the view is not ready: its listener calls again
        if (player != null || loop != null) return
        val src = source() ?: return
        begun = SystemClock.elapsedRealtime(); errors = 0; t0 = 0L
        covered(true)
        if (src.startsWith("/")) { loop(File(src), s); return }        // a file on the TV loops with no hold; a stream plays once, then play() again (the kept file by then)
        try {
            player = MediaPlayer().also { p ->
                p.setSurface(s)
                p.setDataSource(src)
                wire(p)
                p.setOnPreparedListener { it.start() }
                p.prepareAsync()
            }
        } catch (e: Throwable) { release(); ui.post { failed() } }
    }

    /**
     * What to play, its picture alone: the library file, a net video's kept file, else its address once it is known to be light for
     * this TV (and the file is fetched for the next turns). Null: nothing yet, the poster shows.
     */
    private fun source(): String? {
        file?.let { return if (it.isFile) it.path else null }
        val u = url ?: return null
        NetVideo.kept(app, u)?.let { return it.path }
        val again = { ui.post { if (url == u) play() } }                // the poster until then
        return when (NetVideo.heavy(u)) {
            null -> { NetVideo.judge(app, u) { again() }; null }
            true -> { NetVideo.fetch(app, u) { again() }; null }        // never streamed: its file is made light first
            false -> { NetVideo.fetch(app, u) { }; u }
        }
    }

    /** A file, round and round with no hold at the turn ([Loop]); its picture alone: a wall has no sound (the user, 2026-10-27). */
    private fun loop(f: File, s: Surface) {
        lateinit var l: Loop
        l = Loop(f, s,
            size = { w, h -> ui.post { if (loop === l) { vw = w; vh = h; cover() } } },
            first = { ui.post { if (loop === l) {
                Log.i(TAG, "first frame ${SystemClock.elapsedRealtime() - begun} ms")
                covered(false)
                if (file == f && t0 == 0L) beginMeasure(f)
            } } },
            error = { e -> Log.w(TAG, "loop error", e); ui.post { if (loop === l) { errors++; release(); failed() } } },   // a file the codec cannot play: the poster stays
            turn = { go -> Build.VERSION.SDK_INT >= 24 && ui.post { if (loop === l && fade()) dissolve(go) else go.run() } })
        loop = l
        l.start()
    }

    /** The turn as a dissolve: the end's frame copied, shown over the surface, and [go] once it is drawn; then it fades out. */
    private fun dissolve(go: Runnable) {
        if (Build.VERSION.SDK_INT < 24 || vw <= 0 || vh <= 0) return go.run()
        val k = minOf(1f, root.width.coerceAtLeast(1).toFloat() / vw)      // never bigger than the screen
        val bw = (vw * k).toInt().coerceAtLeast(1); val bh = (vh * k).toInt().coerceAtLeast(1)
        val b = shot?.takeIf { it.width == bw && it.height == bh } ?: try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { null }
        if (b == null) return go.run()
        shot = b
        try {
            PixelCopy.request(view, b, { r ->
                val v = if (r == PixelCopy.SUCCESS && loop != null) stillView() else null
                if (v == null) { go.run(); return@request }
                v.animate().cancel()
                v.setImageBitmap(b); v.alpha = 1f; v.visibility = View.VISIBLE
                v.postOnAnimation { v.post {                                      // drawn: the next turn may show below it
                    go.run()
                    v.animate().alpha(0f).setDuration(FADE_MS).withEndAction { v.visibility = View.GONE; v.setImageDrawable(null) }.start()
                } }
            }, ui)
        } catch (e: Throwable) { go.run() }                             // the surface is gone (Home stopping)
    }

    private fun stillView(): ImageView? = still ?: veil?.let { vl ->
        ImageView(app).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; isFocusable = false; visibility = View.GONE   // cropped as the surface is ([cover])
            root.addView(this, root.indexOfChild(vl), FrameLayout.LayoutParams(-1, -1))
        }.also { still = it }
    }

    private fun unfade() {
        still?.let { it.animate().cancel(); it.visibility = View.GONE; it.setImageDrawable(null) }
        shot = null                                                     // its memory back while nothing plays
    }

    /** A stream's first turns, its sound off; not looped: at its end play() takes the kept file. */
    private fun wire(p: MediaPlayer) {
        p.setVolume(0f, 0f)
        p.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; cover() }
        p.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                Log.i(TAG, "first frame ${SystemClock.elapsedRealtime() - begun} ms")
                covered(false)
            }
            false
        }
        p.setOnErrorListener { _, w, e -> Log.w(TAG, "error $w/$e"); errors++; ui.post { release(); failed() }; true }
        p.setOnCompletionListener { if (p === player) ui.post { release(); play() } }
    }

    private fun quit(p: MediaPlayer) {
        try { p.setOnErrorListener(null); p.setOnPreparedListener(null); p.setOnCompletionListener(null); p.stop() } catch (e: Throwable) { }
        try { p.reset() } catch (e: Throwable) { }
        try { p.release() } catch (e: Throwable) { }
    }

    private fun release() {
        ui.removeCallbacks(measure)                                     // nothing is measured (or kept running) once it does not play
        covered(true)
        loop?.let { loop = null; it.stop() }
        unfade()
        val p = player ?: return
        player = null
        quit(p)
    }

    /** The poster over the hole: the very picture Home has as its window's background (shared, no second bitmap in memory). */
    private fun covered(on: Boolean) {
        val v = poster ?: return
        if (on) { v.setImageDrawable(root.rootView.background); v.visibility = View.VISIBLE } else { v.visibility = View.GONE; v.setImageDrawable(null) }
    }

    private fun frames(l: Loop) = (l.shown + l.dropped) to l.dropped

    /** The first frame is on screen: note the counters and look again after [Verdict.WINDOW_MS]; the one timer, taken away by [release]. */
    private fun beginMeasure(f: File) {
        val n = frames(loop ?: return)
        t0 = SystemClock.elapsedRealtime().coerceAtLeast(1); cpu0 = Process.getElapsedCpuTime(); f0 = n.first; d0 = n.second
        want = clipFps(f)
        ui.removeCallbacks(measure); ui.postDelayed(measure, Verdict.WINDOW_MS)
    }

    private fun measureNow() {
        val n = frames(loop ?: return)
        val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
        val shown = ((n.first - f0) - (n.second - d0)).coerceAtLeast(0) * 1000f / dt
        val cpu = ((Process.getElapsedCpuTime() - cpu0) * 100f / dt).toInt()
        Log.i(TAG, "measure frames=${n.first - f0} dropped=${n.second - d0} dt=$dt fps=$shown want=$want cpu=$cpu%")
        measured(Measure(shown, want, cpu, errors))
    }

    /** The clip's own frame rate (made by [Transcode] or sent as it was); 30 when the file does not say. */
    private fun clipFps(f: File): Float = try {
        MediaMetadataRetriever().let { r ->
            try {
                r.setDataSource(f.path)
                val n = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull()
                if (n != null && ms != null && ms > 0 && n > 0) n * 1000f / ms else 30f
            } finally { r.release() }
        }
    } catch (e: Throwable) { 30f }

    /** The picture must cover the screen like a photo: the SurfaceView is made bigger than the window on one axis (the window clips it). */
    private fun cover() {
        val w = root.width.toFloat(); val h = root.height.toFloat()
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        val va = vw.toFloat() / vh; val ra = w / h
        val cw = if (va > ra) (h * va).toInt() else w.toInt(); val ch = if (va > ra) h.toInt() else (w / va).toInt()
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != cw || lp.height != ch || lp.gravity != Gravity.CENTER) { lp.width = cw; lp.height = ch; lp.gravity = Gravity.CENTER; view.layoutParams = lp }
    }

    companion object {
        const val TAG = "TvHomeVideo"
        const val FADE_MS = 1000L
    }
}
