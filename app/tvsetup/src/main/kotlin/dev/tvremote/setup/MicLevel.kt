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

    private val gate = Gate()

    companion object {
        private const val STEP = 70L                                  // ms per bar: 15 bars ≈ the last second
    }

    /** [rms] in dB as the recognizer gives it (about -2 quiet to 10 loud). */
    fun feed(rms: Float) {
        target = gate.feed(rms, SystemClock.uptimeMillis())
        if (!moving) { push(target); invalidate() }
    }

    private var talking = false
    private var wordAt = 0L

    /**
     * The recognizer heard a voice begin (true) or end. Its level is not always the voice: an FPT box's remote microphone jumps -2..8
     * fifty times a second in silence and speech alike, and the bars stayed flat while words came; so a voice it hears moves them too.
     */
    fun speech(on: Boolean) { talking = on; invalidate() }

    /** A new word came: the bars jump, then settle. */
    fun word() { wordAt = SystemClock.uptimeMillis(); if (!moving) { push(0.9f); invalidate() } }

    private fun heard(now: Long): Float {
        val w = if (wordAt == 0L) 0f else (0.9 * Math.exp(-(now - wordAt) / 350.0)).toFloat()
        val t = if (talking) (0.22 + 0.18 * Math.abs(Math.sin(now / 95.0) * Math.cos(now / 230.0))).toFloat() else 0f
        return maxOf(w, t)
    }

    /** Listening again (after the TV asked for the microphone): the frames start over. */
    fun go() { if (!running) { running = true; invalidate() } }

    fun stop() { running = false; target = 0f; talking = false; wordAt = 0L; if (!moving) { bars.fill(0f); invalidate() } }

    private fun push(v: Float) { bars[head] = v; head = (head + 1) % n }

    override fun onDraw(cv: Canvas) {
        val now = SystemClock.uptimeMillis()
        var frac = 0f
        if (moving) {
            val want = if (running) maxOf(target, heard(now)) else target
            cur += (want - cur) * (if (want > cur) 0.45f else 0.18f)   // up fast, down slow: a voice meter, not a flicker
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

/**
 * The voice above the room's own noise, 0..1. The noise is what came first (the press is before the first word) and follows a steady
 * sound up at [RISE]: on an FPT box the remote's microphone reaches Google's recognizer at 9.8-10 all along (its scale's top), a fan
 * at -2..5, and both drew full bars before. Down at once, so a pause between words finds the noise again.
 */
class Gate {
    private var avg = 0f
    private var floor = 0f
    private var fedAt = 0L

    fun feed(rms: Float, now: Long): Float {
        if (fedAt == 0L) { avg = rms; floor = rms; fedAt = now; return 0f }
        val dt = ((now - fedAt) / 1000f).coerceIn(0f, 0.5f)
        fedAt = now
        avg += (rms - avg) * (1f - Math.exp(-dt / 0.15).toFloat())    // Google's level jumps at 50 a second: its average
        floor = if (avg < floor) avg else floor + minOf(avg - floor, RISE * dt)
        val v = ((avg - floor - 1f) / 6f).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)                                  // smoothstep: noise stays low, speech stands up
    }

    companion object { const val RISE = 6f }                          // dB a second: a steady hum is under it in ~1 s, a word (~0.3 s) is not
}
