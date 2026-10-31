package dev.tvremote.phone.picture

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.media.toggleListen
import dev.tvremote.phone.remote.layoutRemote
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.icon

import dev.tvremote.core.T
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

// The TV's picture can float: the PiP button beside full screen (or Manage / Settings / About / Air Mouse opening over the remote) puts it
// in a small player over every screen of the app, so the rest of the app stays usable. Only while it floats does leaving the
// app (Home, recents) go on in Android's Picture-in-Picture; coming back finds the small player again. One stream throughout: the picture's VideoFrame (with its SurfaceView) is moved between the remote's stage and [MiniPlayer]; the surface goes
// and comes back, which the decoder already survives (Tv.pauseScreen / resumeScreen).

internal const val FLOAT_NONE = 0
internal const val FLOAT_MINI = 1
internal const val FLOAT_PIP = 2
private const val PIP_ACTION = "dev.tvremote.phone.PIP"

/** A picture is showing right now: the only time there is something to float. */
internal fun MainActivity.picLive() = video && basic == null && host != null && pic != null && picShown

internal fun MainActivity.canPip() = Build.VERSION.SDK_INT >= 26 && floating && picLive() && !isFinishing &&
    packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** The picture moves to where it floats ([FLOAT_MINI], [FLOAT_PIP]) or back to the remote's stage ([FLOAT_NONE]). */
internal fun MainActivity.setFloat(mode: Int) {
    if (mode == floatMode) return
    floatMode = mode
    if (mode == FLOAT_NONE) {
        floating = false
        mini.visibility = View.GONE
        (frame.parent as? ViewGroup)?.removeView(frame)
        stage.addView(frame, 0, FrameLayout.LayoutParams(-1, -2))
        layoutRemote()                                                // sizes the frame for the stage again
    } else {
        if (frame.parent !== mini) {
            (frame.parent as? ViewGroup)?.removeView(frame)
            mini.addView(frame, 0, FrameLayout.LayoutParams(-1, -2))
        }
        mini.mode(mode == FLOAT_PIP)
        mini.visibility = View.VISIBLE
    }
    mini.report()
    pipUpdate()
}

/** The PiP button: the picture goes to the small player at once (the stage folds away, the keys get the room), or comes back. */
internal fun MainActivity.toggleFloat() {
    if (floating) { unfloat(); return }
    if (!picLive()) return
    floating = true
    setFloat(FLOAT_MINI)
    layoutRemote()
}

/** The picture back in the remote's stage; whatever was laid over the remote closes, or the stage would be out of sight. */
internal fun MainActivity.unfloat() {
    floating = false
    managing.back()
    settings.visibility = View.GONE
    about.visibility = View.GONE
    if (floatMode == FLOAT_MINI) setFloat(FLOAT_NONE)
    layoutRemote()
}

/** ✕ on the small player: the TV encodes nothing more. On the remote screen that is the eye button's "off"; over Manage the
 *  remote asks for the picture again when it is back on screen. */
internal fun MainActivity.closeMini() {
    val over = covered()
    stopPicture()                                                     // brings the frame home too
    if (!over && video) toggleVideo()
}

internal fun MainActivity.pipParams(): PictureInPictureParams? {
    if (Build.VERSION.SDK_INT < 26) return null
    val b = PictureInPictureParams.Builder()
    val w = vidW.coerceAtLeast(1); val h = vidH.coerceAtLeast(1)
    b.setAspectRatio(when {                                           // Android accepts 1:2.39 … 2.39:1 only
        w * 100L > 239L * h -> Rational(239, 100)
        h * 100L > 239L * w -> Rational(100, 239)
        else -> Rational(w, h)
    })
    val r = Rect()
    if (surface.isShown && surface.getGlobalVisibleRect(r) && !r.isEmpty) b.setSourceRectHint(r)   // the animation starts from the picture, not from the screen
    val acts = ArrayList<RemoteAction>()
    if (basic == null) {                                              // the sound needs ADB too
        val ic = if (listen) "mute" else "headphones"
        val t = T(if (listen) "float.mute" else "float.hear")
        acts.add(RemoteAction(Icon.createWithResource(this, resources.getIdentifier("ic_$ic", "drawable", packageName)), t, t, pipIntent(1)))
    }
    val c = T("float.close")
    acts.add(RemoteAction(Icon.createWithResource(this, resources.getIdentifier("ic_close", "drawable", packageName)), c, c, pipIntent(2)))
    b.setActions(acts)
    if (Build.VERSION.SDK_INT >= 31) {
        b.setAutoEnterEnabled(canPip())                               // Home enters it, but only while the picture floats
        b.setSeamlessResizeEnabled(false)                             // video resizes by cross-fade, not by stretching
    }
    return b.build()
}

private fun MainActivity.pipIntent(n: Int): PendingIntent =
    PendingIntent.getBroadcast(this, n, Intent(PIP_ACTION).setPackage(packageName).putExtra("a", n), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** Tells Android the size and the actions the PiP window will have. */
/** Home / recents with the picture floating: PiP (before Android 12 by hand, from onUserLeaveHint). */
internal fun MainActivity.pipLeaveHint() {
    if (Build.VERSION.SDK_INT < 26 || !canPip() || isInPictureInPictureMode) return
    try { pipParams()?.let { enterPictureInPictureMode(it) } } catch (e: Exception) { }
}

internal fun MainActivity.pipUpdate() {
    if (Build.VERSION.SDK_INT < 26 || !miniReady()) return
    pipBtn.alpha = if (picLive() || floating) 1f else 0.4f
    try { pipParams()?.let { setPictureInPictureParams(it) } } catch (e: Exception) { }
}

private val hidden = ArrayList<Pair<View, Int>>()

internal fun MainActivity.pipChanged(on: Boolean) {
    val root = mini.parent as ViewGroup
    if (on) {
        wasPip = true
        hidden.clear()
        for (i in 0 until root.childCount) { val c = root.getChildAt(i); if (c !== mini) { hidden.add(c to c.visibility); c.visibility = View.GONE } }
        setFloat(FLOAT_PIP)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra("a", 0)) {
                    1 -> { toggleListen(); }
                    2 -> { stopPicture(); moveTaskToBack(true) }      // closes the window; the app itself stays, as after Home
                }
            }
        }
        pipRx = rx
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, IntentFilter(PIP_ACTION), Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, IntentFilter(PIP_ACTION))
    } else {
        pipRx?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        pipRx = null
        for ((v, vis) in hidden) v.visibility = vis
        hidden.clear()
        if (pic == null || !floating) { floating = false; setFloat(FLOAT_NONE); layoutRemote(); return }
        setFloat(FLOAT_MINI)                                          // back from PiP: the small player again, not the stage
        wasCovered = covered()
    }
}

/**
 * The small player over Manage, Settings and About: 60% of the width at first, bottom right, left wherever it is dragged (kept on
 * the screen; corners only got in the way); two fingers make it bigger or smaller (35% to the whole width), the size kept for next time. A tap anywhere but ✕ brings the remote back. In PiP the same view fills the window, bare.
 */
internal class MiniPlayer(private val a: MainActivity) : FrameLayout(a) {
    private val m = a.dp(12)
    private val slop = ViewConfiguration.get(a).scaledTouchSlop
    private var dx = 0f; private var dy = 0f; private var drag = false
    private var pip = false
    private var share = a.prefs.getFloat("mini_w", 0.6f).coerceIn(MIN, 1f)   // its width as a share of the screen's
    private var scaling = false                                       // a pinch: no drag, no tap until every finger is up
    private val pinch = ScaleGestureDetector(a, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; drag = false; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { share = (share * d.scaleFactor).coerceIn(MIN, 1f); size(); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { a.prefs.edit().putFloat("mini_w", share).apply() }
    })
    private val closeBtn = a.icon("@close", T("float.close_mini"), 0xAA000000.toInt())

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, if (pip) 0f else a.dp(14).toFloat()) }
        }
        clipToOutline = true
        elevation = a.dp(10).toFloat()
        setBackgroundColor(0xFF000000.toInt())
        closeBtn.setIcon("@close", 0xFFFFFFFF.toInt())
        addView(closeBtn, LayoutParams(a.dp(34), a.dp(34), Gravity.TOP or Gravity.END).apply { setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4)) })
        visibility = GONE
    }

    fun mode(pip: Boolean) {
        this.pip = pip
        closeBtn.visibility = if (pip) GONE else VISIBLE
        val p = parent as? View
        if (pip) {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            translationX = 0f; translationY = 0f; elevation = 0f
            // the frame fills it, exact height: the picture centred at the TV's aspect
            for (i in 0 until childCount) if (getChildAt(i) is VideoFrame) getChildAt(i).layoutParams = LayoutParams(-1, -1)
        } else {
            layoutParams = FrameLayout.LayoutParams(width(p), -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(m, m, m, m) }
            for (i in 0 until childCount) if (getChildAt(i) is VideoFrame) getChildAt(i).layoutParams = LayoutParams(-1, -2)
            elevation = a.dp(10).toFloat()
            post { keepIn(false) }
        }
        invalidateOutline()
    }

    private fun width(p: View?) = ((p?.width?.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels) - 2 * m).let { (it * share).toInt() }

    /** Live while pinching: it grows about its centre (laid out from the bottom right, so the translation follows), kept on the screen. */
    private fun size() {
        val p = parent as? View ?: return
        val w0 = width; val h0 = height
        (layoutParams as FrameLayout.LayoutParams).width = width(p); requestLayout()
        post { translationX += (width - w0) / 2f; translationY += (height - h0) / 2f; keepIn(false) }
    }

    private fun inClose(e: MotionEvent): Boolean {
        val r = Rect(); closeBtn.getHitRect(r)
        return closeBtn.visibility == VISIBLE && r.contains(e.x.toInt(), e.y.toInt())
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = !pip && !inClose(e)   // the picture below must not receive touches meant for the TV

    private var downAt = 0L

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (pip) return false
        pinch.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; drag = false; scaling = false; downAt = System.currentTimeMillis(); animate().cancel(); return true }
            MotionEvent.ACTION_POINTER_DOWN -> { scaling = true; drag = false }
            MotionEvent.ACTION_MOVE -> {
                if (scaling) return true
                val mx = e.rawX - dx; val my = e.rawY - dy
                if (!drag && Math.hypot(mx.toDouble(), my.toDouble()) > slop) drag = true
                if (drag) { translationX += mx; translationY += my; dx = e.rawX; dy = e.rawY }
            }
            MotionEvent.ACTION_UP -> {
                if (scaling) keepIn(true) else if (!drag) a.unfloat() else keepIn(true)
            }
            MotionEvent.ACTION_CANCEL -> if (drag || scaling) keepIn(true)
        }
        return true
    }

    /** Back inside the screen if a drag or a pinch left part of it outside (translation 0 = its laid-out place, bottom right). */
    private fun keepIn(animate: Boolean) {
        val p = parent as? View ?: return
        val tx = translationX.coerceIn(-(p.width - width - 2 * m).toFloat().coerceAtLeast(0f), 0f)
        val ty = translationY.coerceIn(-(p.height - height - 2 * m).toFloat().coerceAtLeast(0f), 0f)
        if (animate) animate().translationX(tx).translationY(ty).setDuration(160).withEndAction { report() }.start() else { translationX = tx; translationY = ty; report() }
    }

    /** How much of Manage's page bottom it covers, when it sits in the lower half (dragged up, nothing at the bottom is hidden). */
    fun report() {
        val p = parent as? View ?: return
        val top = top + translationY
        val px = if (visibility != VISIBLE || pip || p.height == 0 || top < p.height / 2f) 0 else ((p.height - top) / a.resources.displayMetrics.density).toInt()
        a.managing.cover(px)
    }

    init {
        closeBtn.setOnClickListener { a.closeMini() }
        // setFloat reports while the player is not laid out yet (top 0 = "upper half", nothing covered): the jobs card stayed under it
        addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (t != ot || b != ob) report() }
    }

    private companion object { const val MIN = 0.35f }
}
