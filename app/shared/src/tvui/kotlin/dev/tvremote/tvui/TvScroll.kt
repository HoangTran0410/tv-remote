package dev.tvremote.tvui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The scroll views of the TV apps: the D-pad's focus is brought to the middle, not only just inside the edge. Android's own stops
 * the focused row at the edge, so nothing tells a person more rows wait beyond it (the user, 2026-11-03). Every move goes
 * through [computeScrollDeltaToGetChildRectOnScreen]: a focus change (scrollToChild), requestRectangleOnScreen, an arrow key with
 * the next row off screen, a resize. Near either end it stops at the end, so the first and last rows sit where they always did.
 * A row taller than the view keeps Android's way (its top or bottom edge), or centering would hide both.
 * The view then glides there itself ([Glide]) and Android is told 0: its focus scroll is a jump (scrollToChild calls scrollBy),
 * and its smoothScrollBy jumps too when keys come under 250 ms apart, so a held key read as lag (the user, 2026-11-03).
 * `tests/tv_scroll.test.js` fails on a bare ScrollView / HorizontalScrollView in the TV apps.
 */
open class TvScroll(c: Context) : ScrollView(c) {
    private val glide = Glide(this, { scrollY }) { super.scrollTo(scrollX, it) }
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        val d = centered(rect.top, rect.bottom, scrollY, height - paddingTop - paddingBottom, paddingTop,
            getChildAt(0).height + paddingTop + paddingBottom - height) ?: return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        glide.to(scrollY + d)
        return 0
    }
    override fun scrollTo(x: Int, y: Int) { if (y != scrollY) glide.stop(); super.scrollTo(x, y) }   // the app's own scrollTo wins; onLayout's own (same place) does not stop it
    override fun onTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_DOWN) glide.stop(); return super.onTouchEvent(e) }
    override fun onDetachedFromWindow() { glide.stop(); super.onDetachedFromWindow() }
}

open class TvHScroll(c: Context) : HorizontalScrollView(c) {
    private val glide = Glide(this, { scrollX }) { super.scrollTo(it, scrollY) }
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        val d = centered(rect.left, rect.right, scrollX, width - paddingLeft - paddingRight, paddingLeft,
            getChildAt(0).width + paddingLeft + paddingRight - width) ?: return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        glide.to(scrollX + d)
        return 0
    }
    override fun scrollTo(x: Int, y: Int) { if (x != scrollX) glide.stop(); super.scrollTo(x, y) }
    override fun onTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_DOWN) glide.stop(); return super.onTouchEvent(e) }
    override fun onDetachedFromWindow() { glide.stop(); super.onDetachedFromWindow() }
}

/**
 * A critically damped spring that follows a target, one step a frame and only while it moves: it starts soft, ends soft and
 * never overshoots, and a new target (the next key) keeps the speed it has, where a fresh eased animation per key would stop
 * and start again. Off (no motion, or not laid out yet: the screen opening on a row far down) = straight there.
 */
internal class Glide(private val v: View, private val now: () -> Int, private val set: (Int) -> Unit) : Runnable {
    private var to = 0
    private var x = 0f
    private var speed = 0f
    private var last = 0L
    private var running = false

    fun to(target: Int) {
        if (!moving(v)) { stop(); set(target); return }
        to = target
        if (running) return
        if (now() == target) return
        x = now().toFloat(); speed = 0f; last = 0L; running = true
        v.postOnAnimation(this)
    }

    fun stop() { if (running) { running = false; v.removeCallbacks(this) } }

    override fun run() {
        if (!running) return
        val t = AnimationUtils.currentAnimationTimeMillis()
        val dt = if (last == 0L) 1f / 60 else ((t - last) / 1000f).coerceIn(0.001f, 0.05f)
        last = t
        val s = spring(x, speed, to.toFloat(), dt)
        x = s[0]; speed = s[1]
        if (abs(to - x) < 0.5f && abs(speed) < 30f) { running = false; set(to); return }
        set(x.roundToInt())
        v.postOnAnimation(this)
    }

    private fun moving(v: View) = Motion.on && v.isLaidOut && v.isShown &&
        (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
}

/** How stiff [spring] is: 99 % there in ~6.6 / W s (~390 ms), past 90 % in ~230 ms; the same on a weak box (fewer frames read as a jump). */
internal const val W = 17f

/**
 * One step of the spring from [x] at [speed] px/s toward [to], [dt] seconds; exact for a critically damped one (no Euler drift,
 * so a slow frame cannot make it overshoot or wobble). Returns the new position and speed.
 */
internal fun spring(x: Float, speed: Float, to: Float, dt: Float): FloatArray {
    val e0 = x - to
    val c = speed + W * e0
    val k = Math.exp((-W * dt).toDouble()).toFloat()
    val e = (e0 + c * dt) * k
    return floatArrayOf(to + e, (c - W * (e0 + c * dt)) * k)
}

/**
 * The scroll change that puts [a]..[b] (content coordinates) in the middle of a [view]-long window that starts [pad] in, clamped
 * to 0..[max]; null when it does not fit (the caller's own rule) or the view is not laid out yet.
 */
internal fun centered(a: Int, b: Int, now: Int, view: Int, pad: Int, max: Int): Int? {
    if (view <= 0 || b - a > view) return null
    val to = ((a + b) / 2 - pad - view / 2).coerceIn(0, maxOf(0, max))
    return to - now
}
