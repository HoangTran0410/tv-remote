package dev.tvremote.home.wall

import android.content.Context
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
 * A video (or a GIF made into one) as the background: a looping MediaPlayer on a SurfaceView behind the grid, with the same veil a
 * photo gets over it. The platform's player on purpose: it is hardware-decoded and costs no library (ExoPlayer is in TV Channels only).
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
 * The loop: a file plays with `isLooping` (the decoder is flushed and the file sought back, the codec and the surface stay): on the FPT box
 * a turn is a ~100 ms hold. It must be a file without sound ([Silent]): the platform player paces the picture by the audio clock even
 * at volume 0 and waits for the audio to start again at each turn (seconds on the FPT box). The earlier loop, a second MediaPlayer made
 * ready before the end and given the surface at completion, held the last frame ~290 ms a turn there: the old player's stop/release
 * (~70 ms, on Home's main thread) and the new player's decoder set-up (~210 ms: prepared without a surface it cannot configure the
 * codec before setSurface) cannot be hidden, so no second player (dev/decisions/tv-home.md "TV Home: video"). A net video's first turns
 * are streamed (with its sound, not looped) while [NetVideo] keeps its file; when one ends, play() starts again and takes the kept
 * file's silent copy, which loops.
 *
 * It plays only while Home is on screen: [stop] (Home stopped: another app in front, screen off, memory short) releases the player and
 * with it the hardware decoder, which the app in front may need; [start] makes it again. No timer, no polling.
 */
class VideoWall(private val app: Context, private val root: ViewGroup, private val ui: Handler,
                private val measured: (Measure) -> Unit, private val failed: () -> Unit) {
    /** What 20 s of playing showed: frames shown per second against the clip's own, this process's CPU (% of one core), errors. */
    class Measure(val fps: Float, val want: Float, val cpu: Int, val errors: Int)

    private val view = SurfaceView(app)
    private var poster: ImageView? = null
    private var veil: View? = null
    private var player: MediaPlayer? = null
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
    fun start() { if (player == null) play() }

    /** Home is away or memory is short: no player, no decoder; the view and the poster stay. */
    fun stop() = release()

    /** Gone for good: the views are taken out. */
    fun detach() {
        release()
        (view.parent as? ViewGroup)?.removeView(view)
        poster?.let { (it.parent as? ViewGroup)?.removeView(it) }; poster = null
        veil?.let { (it.parent as? ViewGroup)?.removeView(it) }
        veil = null; file = null; url = null
    }

    private fun play() {
        val s = surface ?: return                                       // the view is not ready: its listener calls again
        if (player != null) return
        val src = source() ?: return
        val loops = src.startsWith("/")                                    // a file on the TV loops; a stream plays once, then play() again (the kept file by then)
        begun = SystemClock.elapsedRealtime(); errors = 0; t0 = 0L
        covered(true)
        try {
            player = MediaPlayer().also { p ->
                p.setSurface(s)
                p.setDataSource(src)
                wire(p, loops)
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
        file?.let { return if (it.isFile) Silent.of(app, it).path else null }   // a wall has no sound (the user, 2026-10-27): the copy without audio, [Silent]
        val u = url ?: return null
        NetVideo.kept(app, u)?.let { return Silent.of(app, it).path }
        val again = { ui.post { if (url == u) play() } }                // the poster until then
        return when (NetVideo.heavy(u)) {
            null -> { NetVideo.judge(app, u) { again() }; null }
            true -> { NetVideo.fetch(app, u) { again() }; null }        // never streamed: its file is made light first
            false -> { NetVideo.fetch(app, u) { }; u }
        }
    }

    private fun wire(p: MediaPlayer, loops: Boolean) {
        val f = file
        p.setVolume(0f, 0f)
        p.isLooping = loops
        p.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; cover() }
        p.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                Log.i(TAG, "first frame ${SystemClock.elapsedRealtime() - begun} ms")
                covered(false)
                if (f != null && t0 == 0L) beginMeasure(p, f)
            }
            false
        }
        p.setOnErrorListener { _, w, e -> Log.w(TAG, "error $w/$e"); errors++; ui.post { release(); failed() }; true }   // a file the codec cannot play: the poster stays
        if (!loops) p.setOnCompletionListener { if (p === player) ui.post { release(); play() } }   // only a stream gets here (a file loops by itself)
    }

    private fun quit(p: MediaPlayer) {
        try { p.setOnErrorListener(null); p.setOnPreparedListener(null); p.setOnCompletionListener(null); p.stop() } catch (e: Throwable) { }
        try { p.reset() } catch (e: Throwable) { }
        try { p.release() } catch (e: Throwable) { }
    }

    private fun release() {
        ui.removeCallbacks(measure)                                     // nothing is measured (or kept running) once it does not play
        covered(true)
        val p = player ?: return
        player = null
        quit(p)
    }

    /** The poster over the hole: the very picture Home has as its window's background (shared, no second bitmap in memory). */
    private fun covered(on: Boolean) {
        val v = poster ?: return
        if (on) { v.setImageDrawable(root.rootView.background); v.visibility = View.VISIBLE } else { v.visibility = View.GONE; v.setImageDrawable(null) }
    }

    private fun frames(p: MediaPlayer): Pair<Int, Int>? = if (android.os.Build.VERSION.SDK_INT < 26) null else try {   // getMetrics is API 26: no measure before
        p.metrics?.let { m -> (m.get(FRAMES) as? Number)?.let { it.toInt() to ((m.get(DROPPED) as? Number)?.toInt() ?: 0) } }   // numbers of any width: it is a Long here
    } catch (e: Throwable) { null }

    /** The first frame is on screen: note the counters and look again after [Verdict.WINDOW_MS]; the one timer, taken away by [release]. */
    private fun beginMeasure(p: MediaPlayer, f: File) {
        val n = frames(p) ?: return
        t0 = SystemClock.elapsedRealtime().coerceAtLeast(1); cpu0 = Process.getElapsedCpuTime(); f0 = n.first; d0 = n.second
        want = clipFps(f)
        ui.removeCallbacks(measure); ui.postDelayed(measure, Verdict.WINDOW_MS)
    }

    private fun measureNow() {
        val p = player ?: return
        val n = frames(p) ?: return
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
        private const val FRAMES = "android.media.mediaplayer.frames"
        private const val DROPPED = "android.media.mediaplayer.dropped"
    }
}
