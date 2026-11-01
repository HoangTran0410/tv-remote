package dev.tvremote.setup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.view.View

/**
 * The voice screen's sound graph: the last second of the microphone's level as bars sliding to the left, the newest at the right.
 * The recognizer's level (onRmsChanged, ~10 a second, jumpy) is a target the bars ease to, drawn once per frame while listening only
 * ([stop] ends the frames: a weak box draws nothing more). With animations off in the TV's settings: one redraw per level, no sliding.
 */
class MicLevel(c: Context, private val color: Int) : View(c) {
    private val n = 15
    private val bars = FloatArray(n)                                  // a ring: bars[(head + i) % n], oldest first
    private var head = 0
    private var target = 0f
    private var cur = 0f
    private var pushedAt = 0L
    private var running = true
    private val moving = try { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f } catch (e: Exception) { true }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }   // not apply { color = color }: inside it "color" is the Paint's own (black)
    private val r = RectF()
    private val d = c.resources.displayMetrics.density

    companion object { private const val STEP = 70L }                 // ms per bar: 15 bars ≈ the last second

    /** [rms] in dB as the recognizer gives it (about -2 quiet to 10 loud). */
    fun feed(rms: Float) {
        val v = ((rms + 2f) / 12f).coerceIn(0f, 1f)
        target = v * v * (3f - 2f * v)                                // smoothstep: room noise stays low, speech stands up
        if (!moving) { push(target); invalidate() }
    }

    /** Listening again (after the TV asked for the microphone): the frames start over. */
    fun go() { if (!running) { running = true; invalidate() } }

    fun stop() { running = false; target = 0f; if (!moving) { bars.fill(0f); invalidate() } }

    private fun push(v: Float) { bars[head] = v; head = (head + 1) % n }

    override fun onDraw(cv: Canvas) {
        val now = SystemClock.uptimeMillis()
        var frac = 0f
        if (moving) {
            cur += (target - cur) * (if (target > cur) 0.45f else 0.18f)   // up fast, down slow: a voice meter, not a flicker
            if (pushedAt == 0L) pushedAt = now
            while (now - pushedAt >= STEP) { push(cur); pushedAt += STEP }
            frac = (now - pushedAt).toFloat() / STEP
        }
        val step = width.toFloat() / n
        val bw = step * 0.5f
        val mid = height / 2f
        val floor = 3 * d
        val breath = if (running) (0.03f * (1 + Math.sin(now / 260.0)).toFloat()) else 0f   // waiting for speech: alive, not flat
        for (i in 0 until n) {
            val v = maxOf(bars[(head + i) % n], breath)
            val h = maxOf(floor, v * height)
            val x = (i - frac) * step + (step - bw) / 2
            paint.alpha = 90 + 165 * (i + 1) / n                      // older bars fade out to the left
            r.set(x, mid - h / 2, x + bw, mid + h / 2)
            cv.drawRoundRect(r, bw / 2, bw / 2, paint)
        }
        if (moving) {                                                 // the bar coming in at the right, at the level now
            val h = maxOf(floor, maxOf(cur, breath) * height); val x = (n - frac) * step + (step - bw) / 2
            paint.alpha = 255; r.set(x, mid - h / 2, x + bw, mid + h / 2); cv.drawRoundRect(r, bw / 2, bw / 2, paint)
        }
        if (moving && (running || cur > 0.01f || bars.any { it > 0.01f })) postInvalidateOnAnimation()   // settles flat after stop, then no more frames
    }
}
