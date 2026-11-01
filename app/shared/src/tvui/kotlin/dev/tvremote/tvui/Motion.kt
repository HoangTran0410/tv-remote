package dev.tvremote.tvui

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator

/**
 * A damped spring as an interpolator, [damping] < 1: x(t) = 1 - e^(-ζωt)(cos ωd·t + ζω/ωd · sin ωd·t), with ω chosen so it has
 * settled (within 0.2 %) at t = 1. A PathInterpolator can overshoot once; a spring eases into its rest, which is what reads as smooth.
 */
class Spring(private val damping: Float) : android.animation.TimeInterpolator {
    private val w = 6.2f / damping
    private val wd = w * Math.sqrt(1.0 - damping * damping).toFloat()
    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        val e = Math.exp((-damping * w * t).toDouble()).toFloat()
        return 1f - e * (Math.cos((wd * t).toDouble()).toFloat() + damping * w / wd * Math.sin((wd * t).toDouble()).toFloat())
    }
}

/**
 * The motion of the TV apps (app/DESIGN-TV.md §2.6), moved here from TV Home so [TvTile], [Glass] and the panels share it: on from
 * the app's setting, off when the system's animator duration scale is 0 (Settings, "remove animations").
 */
object Motion {
    @JvmField var on = true
    @JvmField var calm = false                                        // less moves: no overshoot, shorter, only the card grows
    @JvmField var glass = true                                        // the focus is a pane of glass gliding between tiles ([Glass]); false: a blue ring on each
    val lift = PathInterpolator(0.34f, 1.35f, 0.64f, 1f)              // overshoots a little then settles: a spring
    private val springy = Spring(0.62f)                               // a real damped spring: rises fast, passes ~6 %, settles (Apple TV's focus)
    private val bouncy = Spring(0.35f)                                // looser: the nudge at an edge wobbles back
    val glide = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)                // fast out, long soft end
    val spring: android.animation.TimeInterpolator get() = if (calm) glide else springy
    val bounce: android.animation.TimeInterpolator get() = if (calm) glide else bouncy

    /** [t] ms, shorter when calm: fewer frames to draw. */
    fun ms(t: Long): Long = if (calm) t * 11 / 20 else t

    /** Little memory (Android's own flag, or 2.25 GB or less: TCL's 1 GB MT9221, the 2 GB FPT Play): calm unless chosen otherwise. */
    fun lowRam(c: Context): Boolean {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.isLowRamDevice || android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem <= 2304L * 1024 * 1024   // a "2 GB" box (the FPT Play: 1.8 GB): full motion took twice the CPU of calm there
    }

    /** [anim] the app's own switch; [motion] "calm" / "full" / anything else = by memory; [focus] "ring" = the ring, else the glass. */
    fun read(c: Context, anim: Boolean, motion: String, focus: String) {
        val scale = try { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (e: Exception) { 1f }
        on = anim && scale > 0f
        calm = when (motion) { "calm" -> true; "full" -> false; else -> lowRam(c) }
        glass = focus != "ring"
    }

    /** A button a little larger while focused. */
    fun pop(v: View, focused: Boolean) {
        v.animate().cancel()
        val s = if (focused) 1.06f else 1f
        if (!on) { v.scaleX = 1f; v.scaleY = 1f }
        else if (calm) { v.scaleX = s; v.scaleY = s }                   // calm (a weak box): grown at once, one frame instead of ~25
        else v.animate().scaleX(s).scaleY(s).setDuration(ms(if (focused) 420L else 220L)).setInterpolator(if (focused) spring else glide).withLayer().start()   // on a layer: without it each frame drew the whole panel again (16 ms + 36 ms GPU on the FPT box)
    }

    /** A card that opens over the screen (a folder) comes up from a touch smaller and fades in; calm: the fade alone, shorter. */
    fun appear(v: View) {
        if (!on) return
        v.alpha = 0f
        if (!calm) { v.scaleX = .94f; v.scaleY = .94f }
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ms(240)).setInterpolator(glide).withLayer().start()
    }
}
