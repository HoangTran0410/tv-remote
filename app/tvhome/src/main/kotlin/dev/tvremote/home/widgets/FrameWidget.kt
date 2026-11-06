package dev.tvremote.home.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.tvremote.home.Font
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.R
import dev.tvremote.home.apps.Pics
import dev.tvremote.home.ui.Frost
import dev.tvremote.tvui.Motion

/** The photo frame's rules, apart from the View so they are tested on the JVM. */
object FrameShow {
    /** How long a photo stays for the option value [v] ("s30" ...); an unknown one is the default, 30 s. */
    fun everyMs(v: String): Long = HomeConfig.FRAME_EVERY[v] ?: 30_000L

    /** The index after [at] among [n] photos, round again at the end; -1 when there is none. */
    fun next(at: Int, n: Int): Int = if (n <= 0) -1 else if (at < 0) 0 else (at + 1) % n

    /** Whether the swap timer runs: two photos at least, and the frame on a screen that is shown and in front. */
    fun runs(n: Int, attached: Boolean, visible: Boolean, resumed: Boolean): Boolean = n > 1 && attached && visible && resumed

    /** Where to go on after the list became [now]: the same photo if it is still there, else the first. */
    fun keep(was: List<String>, at: Int, now: List<String>): Int = was.getOrNull(at)?.let { now.indexOf(it) }?.takeIf { it >= 0 } ?: if (now.isEmpty()) -1 else 0

    /** The first photo from [start] on (in order, round) that [read] can open: (index, picture); a bad file is passed over. */
    fun <T> firstGood(ids: List<String>, start: Int, read: (String) -> T?): Pair<Int, T>? {
        if (ids.isEmpty()) return null
        for (i in ids.indices) {
            val at = (Math.floorMod(start, ids.size) + i) % ids.size
            read(ids[at])?.let { return at to it }
        }
        return null
    }
}

/**
 * The photo frame widget (HomeConfig "frame"): the person's own photos, one after another with a fade. Kept by [Widgets] across the
 * minute's rebuild (its picture and its timer go on). Pictures are read one at a time on a short thread, no bigger than the widget
 * ([Pics.bitmap]: inSampleSize, RGB_565, at most 480 x 270), and only two are held (the one fading out and the one in). Two stock
 * ImageViews (centre-crop; the frame's outline clips the corners), nothing drawn by hand. The swap timer runs only while the frame
 * is on a shown screen and Home is resumed ([FrameShow.runs]); one photo is still, none shows a hint.
 */
internal class FrameView(c: Context, private val dp: (Number) -> Int) : FrameLayout(c) {
    private var ids: List<String> = emptyList()
    private var every = 30_000L
    private var at = -1
    private var busy = false; private var bad = false                 // a read under way; none of the photos could be read (tried again when the list changes)
    private var gen = 0                                              // a read that comes back after a newer one started is dropped
    private var dueAt = 0L                                            // uptime of the next swap: the minute's rebuild re-attaches the frame, the wait goes on
    private var boxW = 0; private var boxH = 0
    var resumed = true
        set(v) { if (field == v) return; field = v; if (!v) dueAt = 0; arm() }
    private val hint: TextView
    private val back = pic(c); private val front = pic(c)            // the photo shown; the next one fading in over it
    private val swap = Runnable { dueAt = 0; step() }

    private fun pic(c: Context) = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    init {
        Frost.on(this, dp(22).toFloat(), dp(1))                         // the empty frame is a widget like the others
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(22).toFloat()) }
        }
        clipToOutline = true
        hint = Font.on(TextView(c)).apply {
            text = c.getString(R.string.frame_empty); textSize = 14f; setTextColor(Widgets.DIM); gravity = Gravity.CENTER
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        addView(back, LayoutParams(-1, -1)); addView(front, LayoutParams(-1, -1)); addView(hint, LayoutParams(-1, -1))
        front.alpha = 0f
    }

    private val shown get() = back.drawable != null

    /** The photos and how long each stays; [w] x [h] the size the widget will have (px), the size the pictures are read at. */
    fun set(photos: List<String>, everyMs: Long, w: Int, h: Int) {
        val sized = w != boxW || h != boxH
        boxW = w; boxH = h
        if (everyMs != every) { every = everyMs; dueAt = 0 }
        if (photos == ids && !sized && (shown || ids.isEmpty() || busy || bad)) { arm(); return }   // the minute's rebuild: nothing read again
        val was = ids; val wasAt = at
        ids = photos; bad = false
        at = FrameShow.keep(was, wasAt, photos)
        if (photos.isEmpty()) { gen++; busy = false; clear(); hint.visibility = View.VISIBLE; arm(); return }
        if (shown && !sized && photos[at] == was.getOrNull(wasAt)) arm()   // the photo shown is still there: it stays
        else load(at, false)
    }

    /** The widget is gone (turned off): nothing is held, nothing runs. */
    fun release() { gen++; busy = false; removeCallbacks(swap); clear(); ids = emptyList(); at = -1; dueAt = 0 }

    private fun clear() { front.animate().cancel(); front.alpha = 0f; front.setImageDrawable(null); back.setImageDrawable(null) }

    private fun step() { if (ids.size > 1) load(FrameShow.next(at, ids.size), true) }

    private fun load(from: Int, animate: Boolean) {
        removeCallbacks(swap)
        val g = ++gen; val list = ids; val app = context.applicationContext; busy = true
        val w = maxOf(boxW, dp(160)); val h = maxOf(boxH, dp(90))
        Thread({
            val got = try { FrameShow.firstGood(list, from) { Pics.bitmap(app, it, w, h) } } catch (e: Throwable) { null }
            post {
                if (g != gen) return@post
                busy = false
                if (got == null) { bad = true; clear(); hint.visibility = View.VISIBLE; return@post }   // none of them can be read: the hint, no timer
                at = got.first
                hint.visibility = View.GONE
                show(got.second, animate && Motion.on && shown)
                arm()
            }
        }, "frame").apply { priority = Thread.MIN_PRIORITY; isDaemon = true; start() }
    }

    /** A fade cut short (the minute's rebuild detaches the frame): the new photo is the one shown. */
    private fun settle() {
        front.animate().cancel()
        front.drawable?.let { back.setImageDrawable(it); front.setImageDrawable(null) }
        front.alpha = 0f
    }

    private fun show(b: Bitmap, fade: Boolean) {
        settle()
        if (!fade) { front.alpha = 0f; front.setImageDrawable(null); back.setImageBitmap(b); return }
        front.setImageBitmap(b); front.alpha = 0f
        front.animate().alpha(1f).setDuration(Motion.ms(900)).withLayer().withEndAction {   // a layer: each frame only blends it, the photo is not drawn again; then the new one is the photo shown
            back.setImageBitmap(b); front.alpha = 0f; front.setImageDrawable(null)
        }.start()
    }

    private fun arm() {
        removeCallbacks(swap)
        if (!FrameShow.runs(ids.size, isAttachedToWindow, windowVisibility == View.VISIBLE, resumed)) return
        val now = SystemClock.uptimeMillis()
        if (dueAt == 0L) dueAt = now + every
        postDelayed(swap, maxOf(0L, dueAt - now))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); arm() }
    override fun onDetachedFromWindow() { removeCallbacks(swap); settle(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); if (v != View.VISIBLE) dueAt = 0; arm() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); invalidateOutline() }
}
