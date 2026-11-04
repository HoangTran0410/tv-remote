package dev.tvremote.games

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager

/** Keeps the screen on while phones play. A phone's press is not the TV's own input, so the TV's sleep timer (screen off,
 *  screensaver) ran out mid-game when nobody touched the remote. On for [HOLD_MS] after the last press from a phone, then
 *  off again: a game left alone still lets the TV sleep. Nothing runs between presses but one delayed check. */
class Awake(private val a: Activity) {
    @Volatile private var on = false
    private val h = Handler(Looper.getMainLooper())
    private val tick = Runnable { check() }

    /** A phone pressed something (any thread). */
    fun poke() { last = SystemClock.elapsedRealtime(); if (!on) h.post(tick) }

    fun stop() { h.removeCallbacks(tick); on = false; a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun check() {
        h.removeCallbacks(tick)
        if (a.isFinishing || a.isDestroyed) return
        val left = left(SystemClock.elapsedRealtime(), last)
        if (left > 0) {
            if (!on) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            on = true
            h.postDelayed(tick, left)
        } else stop()
    }

    companion object {
        @JvmStatic var HOLD_MS = 10 * 60_000L
        @Volatile private var last = 0L                    // shared: a press in the lobby counts for the game that starts after it

        /** How long the screen stays on after a press at [lastAt] (elapsed ms), 0 = no longer. */
        fun left(now: Long, lastAt: Long): Long = if (lastAt == 0L) 0 else (HOLD_MS - (now - lastAt)).coerceAtLeast(0)
    }
}
