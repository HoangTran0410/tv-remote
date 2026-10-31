package dev.tvremote.phone.picture

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.R
import dev.tvremote.phone.media.say
import dev.tvremote.phone.remote.caps
import dev.tvremote.phone.remote.flash
import dev.tvremote.phone.remote.kind
import dev.tvremote.phone.ui.ACCENT
import dev.tvremote.phone.ui.ACCENT_TEXT
import dev.tvremote.phone.ui.BG
import dev.tvremote.phone.ui.CARD
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.say

import dev.tvremote.core.T
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.picture.Tv
import kotlin.math.abs

// Air Mouse: the remote's middle as a mouse pad (one of its three modes, beside the arrows and the touchpad of keys), and the phone
// held like a pointer, its gyroscope moving a real mouse pointer on the TV. Android TVs get a virtual HID mouse through the scrcpy
// control socket (Tv.mouseOpen, opened on first use for each session, no picture needed), LG its Magic Remote pointer (Remote.pointer).

private object Air {
    var mode = "arrows"                                              // the remote's middle: arrows, touch (keys) or mouse (this)
    var sensors: SensorManager? = null
    var listener: SensorEventListener? = null
    var mouseTv: Tv? = null                                          // the session the HID mouse was opened on
    var buttons = 0                                                  // 1 while the pointer's button is held (a drag)
    var padHeld = false                                              // a finger held still on the pad pressed the button: the phone drags too
    var freezeUntil = 0L                                             // a click lands where it was aimed: no motion ~80 ms around press / release
    var touching = false                                             // a finger on the pad: the gyroscope waits, so the two never fight
    var thread: android.os.HandlerThread? = null                     // the sensor's callbacks run here, not on the screen's thread
    var synth: Thread? = null
    var fxLog = false                                                // a marker file (air_fxlog) makes every haptic call a log line, so a test can count them
    var tickLast = 0L                                                // when the last tick went out: a fast swipe must not buzz continuously
    var saidAt = 0L                                                  // the "needs ADB" line, at most every few seconds
}

private const val GAIN = 700f             // HID counts per radian turned: about a screen width for a wrist's sweep
private const val DEAD = 0.025f           // rad/s below which the hand's tremor is ignored
private const val HOLD_MS = 400L          // a finger still this long on the pad presses the mouse button (a tap is shorter)
private const val TICK_GAP_MS = 40L   // at least this between ticks: 25 a second at most
private const val TOUCH_GAIN = 1.3f   // HID counts per dp of finger travel at walking speed
private const val TOUCH_BOOST = 0.9f  // extra per dp/ms, like a laptop touchpad's curve
private const val BOOST = 0.45f           // extra speed per rad/s, so slow moves stay fine and fast ones reach the corner

/** This TV can show a pointer: ADB (the HID mouse) or a remote link with "mouse" (LG). */
internal fun MainActivity.airCan(): Boolean {
    val t = tv; val b = basic
    return (t != null && t.ready()) || (b != null && b.ready() && "mouse" in Remote.Kind.caps(b.kind))
}

/** The remote's middle became the mouse pad (or the app came back to it): the gyroscope on. The pad works without one. */
internal fun MainActivity.airStart(mode: String = "mouse") {
    Air.mode = mode
    if (mode != "mouse" || Air.listener != null) { if (mode != "mouse") airStop(); return }
    Air.touching = false; Air.buttons = 0; Air.padHeld = false
    Air.fxLog = try { java.io.File(getExternalFilesDir(null), "air_fxlog").exists() } catch (e: Exception) { false }
    val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    Air.sensors = sm
    airSynth()
    (sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR))?.let { airListen(sm, it) }
}

/** Another mode, or the app left: the sensor off, a held button let go, the TV's mouse (and its pointer) gone. The mode is kept. */
internal fun MainActivity.airStop() {
    if (Air.buttons != 0) airButton(false)
    Air.padHeld = false
    Air.listener?.let { Air.sensors?.unregisterListener(it) }
    Air.listener = null
    Air.thread?.quitSafely(); Air.thread = null
    Air.synth?.interrupt(); Air.synth = null
    Air.mouseTv?.let { it.mouseStats = null; it.mouseClose() }
    Air.mouseTv = null
}

/** The remote's middle: "touch" or "mouse" turns that mode on, or off again when it is the one shown (More's Air Mouse). */
internal var airPick: (String) -> Unit = {}
internal fun airMode() = Air.mode

internal fun MainActivity.airResume() { if (Air.mode == "mouse") airStart() }

/** The session's HID mouse, opened the first time it is used on that session. */
private fun MainActivity.airTv(): Tv? {
    val t = tv
    if (t == null || !t.ready()) return null
    if (Air.mouseTv !== t) { Air.mouseTv = t; t.mouseOpen(); t.mouseStats = { android.util.Log.i("airstat", it) } }
    return t
}

private fun MainActivity.airMove(dx: Float, dy: Float, carry: FloatArray) {
    carry[0] += dx; carry[1] += dy                                   // the fractions a count cannot carry add up to the next one
    val ix = carry[0].toInt(); val iy = carry[1].toInt()
    if (ix == 0 && iy == 0) return
    carry[0] -= ix; carry[1] -= iy
    val t = airTv()
    if (t != null) t.mouseMove(ix, iy, Air.buttons) else basic?.pointer(ix, iy)   // the held button rides along: that is the drag
}

private fun MainActivity.airListen(sm: SensorManager, sensor: Sensor) {
    val carry = FloatArray(2)
    var last = 0L
    val prev = FloatArray(3)                                         // game rotation vector: the last yaw / pitch
    var have = false
    val l = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            val live = Air.padHeld || !Air.touching
            if (!live || covered() || System.currentTimeMillis() < Air.freezeUntil) { last = 0; have = false; return }   // Manage or Settings over the remote: the pointer stays
            var wz: Float; var wx: Float
            if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
                if (last == 0L) { last = e.timestamp; return }
                val dt = (e.timestamp - last) / 1e9f; last = e.timestamp
                if (dt <= 0f || dt > 0.2f) return
                // z is the way the screen faces, x the phone's width: held flat, turning left (z+) is the pointer going left, tipping the top up (x+) going up
                wz = -e.values[2]; wx = -e.values[0]
                val w = kotlin.math.hypot(wz, wx)
                if (w < DEAD) return
                val k = GAIN * dt * (1f + BOOST * w)
                airMove(wz * k, wx * k, carry)
            } else {
                val m = FloatArray(9); val o = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(m, e.values)
                SensorManager.getOrientation(m, o)
                if (!have) { prev[0] = o[0]; prev[1] = o[1]; have = true; return }
                var dyaw = o[0] - prev[0]
                if (dyaw > Math.PI) dyaw -= (2 * Math.PI).toFloat() else if (dyaw < -Math.PI) dyaw += (2 * Math.PI).toFloat()
                val dpitch = o[1] - prev[1]
                prev[0] = o[0]; prev[1] = o[1]
                wz = dyaw; wx = dpitch
                if (kotlin.math.hypot(wz, wx) < 0.0008f) return
                airMove(wz * GAIN, -wx * GAIN, carry)                // rotation vector's azimuth grows clockwise, its pitch grows when the top goes down
            }
        }
    }
    Air.listener = l
    val th = android.os.HandlerThread("air-sensor").apply { start() }; Air.thread = th
    sm.registerListener(l, sensor, 5_000, android.os.Handler(th.looper))   // 200 Hz asked, off the main thread; the system gives what it can
}

/** The mouse pad, in the remote's middle where the arrows are: drag = move, tap = click, a finger held still = the button held
 *  (then the phone or the finger selects text or drags), two fingers or the strip on the right = the wheel. */
@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.airPad(): FrameLayout {
    val pad = FrameLayout(this)
    pad.background = box(CARD, 18)
    val hint = label(T("air.pad"), 14, MUTED)
    hint.gravity = Gravity.CENTER; hint.setPadding(dp(16), 0, dp(68), 0)   // clear of the scroll strip
    pad.addView(hint, FrameLayout.LayoutParams(-1, -1))
    val noAnim = try { android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }   // "Remove animations": no trail or ripple, haptics stay
    val fx = AirFx(this, noAnim, dp(1).toFloat())                     // the finger, its trail, the click ripple and the held tint, all drawn in this one view
    pad.addView(fx, FrameLayout.LayoutParams(-1, -1))
    var downAt = 0L; var sx = 0f; var sy = 0f; var moved = false; var scrollY = 0f; var scrollCarry = 0f
    var lastTapUp = 0L; var dtap = false; var lx = 0f; var ly = 0f; var lt = 0L; var drag = false; val dragCarry = FloatArray(2)
    val slop = dp(8)
    // A finger held still on the pad is the mouse button held down, where the pointer is: then moving the phone or the finger selects
    // text or drags what was under it, as the TV's app decides; lifting the finger lets go.
    val holdDown = Runnable { if (Air.touching && !moved && Air.buttons == 0) { Air.padHeld = true; airButton(true); pad.airHaptic(HapticFeedbackConstants.LONG_PRESS); fx.setHeld(true) } }
    pad.setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent.requestDisallowInterceptTouchEvent(true)          // the page must not scroll under the finger
                if (!airCan()) { val now = SystemClock.uptimeMillis(); if (now - Air.saidAt > 4000) { Air.saidAt = now; say(T("air.needs_adb"), true) } }
                fx.touch(ev.x, ev.y); downAt = ev.eventTime; sx = ev.x; sy = ev.y; lx = ev.x; ly = ev.y; lt = ev.eventTime; moved = false; drag = false; scrollY = 0f; Air.touching = true; dtap = ev.eventTime - lastTapUp < 300; v.postDelayed(holdDown, HOLD_MS)   // a second touch soon after a tap: a drag if it moves
            }
            MotionEvent.ACTION_POINTER_DOWN -> { if (!Air.padHeld) { moved = true; v.removeCallbacks(holdDown) }; scrollY = (ev.getY(0) + ev.getY(1)) / 2 }   // two fingers: a wheel, never a click
            MotionEvent.ACTION_POINTER_UP -> { val i = if (ev.actionIndex == 0) 1 else 0; lx = ev.getX(i); ly = ev.getY(i); lt = ev.eventTime }   // one finger left: carry on from where it is, no jump
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    val y = (ev.getY(0) + ev.getY(1)) / 2
                    scrollCarry += (y - scrollY) / dp(14)            // one wheel notch per 14 dp dragged; drag down = content up, like a touch screen
                    scrollY = y
                    val n = scrollCarry.toInt()
                    if (n != 0) { scrollCarry -= n; airWheel(n); v.airTick() }
                } else {
                    if (!drag && (abs(ev.x - sx) > slop || abs(ev.y - sy) > slop)) { moved = true; drag = true; v.removeCallbacks(holdDown); if (dtap) { airButton(true); v.airHaptic(HapticFeedbackConstants.LONG_PRESS); fx.setHeld(true) } }
                    if (drag) {                                      // a touchpad: relative moves, faster fingers go further
                        val dt = maxOf(1L, ev.eventTime - lt)
                        val ddx = (ev.x - lx) / dp(1); val ddy = (ev.y - ly) / dp(1)   // dp, so every screen feels the same
                        val speed = kotlin.math.hypot(ddx, ddy) / dt     // dp per ms
                        val k = TOUCH_GAIN * (1f + minOf(speed, 3f) * TOUCH_BOOST)
                        airMove(ddx * k, ddy * k, dragCarry)
                    }
                    lx = ev.x; ly = ev.y; lt = ev.eventTime
                    fx.touch(ev.x, ev.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Air.touching = false; fx.lift(); v.removeCallbacks(holdDown)
                if (Air.buttons != 0) { airButton(false); fx.setHeld(false) }           // the hold or the double-tap drag lets go
                val wasHeld = Air.padHeld; Air.padHeld = false
                if (ev.actionMasked == MotionEvent.ACTION_UP && !moved && !wasHeld && ev.eventTime - downAt < HOLD_MS) { airClick(); v.airConfirm(); fx.ripple(ev.x, ev.y); lastTapUp = ev.eventTime }
                v.performClick()
            }
        }
        true
    }
    // The scroll strip on the pad's right edge: a vertical drag on it is the wheel, so one finger can scroll. It is a child that takes
    // its own touches, so the pad's one-finger moves elsewhere are untouched; two fingers on the pad still scroll.
    val strip = FrameLayout(this)
    strip.background = box(BG, 14)                                // a track darker than the pad: there, not loud
    val sfx = StripFx(this, noAnim, dp(1).toFloat())                  // the two arrows, the thumb under the finger
    strip.addView(sfx, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(44) })   // the swap button has the strip's foot
    var stripY = 0f; var stripT = 0L; var stripCarry = 0f
    strip.setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { v.parent.requestDisallowInterceptTouchEvent(true); sfx.down(ev.y); stripY = ev.y; stripT = ev.eventTime; stripCarry = 0f; Air.touching = true }
            MotionEvent.ACTION_MOVE -> {
                val dt = maxOf(1L, ev.eventTime - stripT)
                val dy = (ev.y - stripY) / dp(1)                       // dp
                val speed = abs(dy) / dt                               // dp per ms: a quicker drag scrolls further
                stripCarry += dy / 10f * (1f + minOf(speed, 2f))       // one wheel notch per 10 dp at a slow drag; drag down = content up
                stripY = ev.y; stripT = ev.eventTime
                val n = stripCarry.toInt()
                sfx.move(ev.y)
                if (n != 0) { stripCarry -= n; airWheel(n); v.airTick(); sfx.flash(if (n > 0) 1 else -1) }   // a detent per wheel step sent
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { Air.touching = false; sfx.up(); v.performClick() }
        }
        true
    }
    pad.addView(strip, FrameLayout.LayoutParams(dp(48), -1))
    val off = android.widget.ImageView(this)                          // a way out when the pad fills the screen and the chooser is off it
    off.setImageResource(R.drawable.ic_close); off.setColorFilter(TEXT); off.alpha = 0.35f
    off.setPadding(dp(10), dp(10), dp(10), dp(10)); off.contentDescription = T("common.close")
    off.setOnClickListener { airPick("mouse") }                       // the mode in use again: back to the arrows
    pad.addView(off, FrameLayout.LayoutParams(dp(40), dp(40)))
    // Left-handed: the strip on the left and the close button on the right (kept in the app's prefs). The button sits at the strip's foot.
    val swap = android.widget.ImageView(this)
    swap.setImageResource(R.drawable.ic_swap); swap.setColorFilter(TEXT); swap.alpha = 0.5f
    swap.setPadding(dp(10), dp(10), dp(10), dp(10)); swap.contentDescription = T("air.swap")
    strip.addView(swap, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(4) })
    fun place(left: Boolean) {
        val side = if (left) Gravity.START else Gravity.END; val other = if (left) Gravity.END else Gravity.START
        (strip.layoutParams as FrameLayout.LayoutParams).apply { gravity = side; setMargins(if (left) dp(8) else 0, dp(8), if (left) 0 else dp(8), dp(8)) }
        (off.layoutParams as FrameLayout.LayoutParams).apply { gravity = other or Gravity.TOP; setMargins(if (left) 0 else dp(4), dp(4), if (left) dp(4) else 0, 0) }
        hint.setPadding(if (left) dp(68) else dp(16), 0, if (left) dp(16) else dp(68), 0)
        pad.requestLayout()
    }
    place(prefs.getBoolean("airLeft", false))
    swap.setOnClickListener {
        val left = !prefs.getBoolean("airLeft", false)
        prefs.edit().putBoolean("airLeft", left).apply(); place(left); swap.airTick()
    }
    return pad
}

/** Holds the pointer's button down or lets it go; the gyroscope and the finger both drag while it is down. */
private fun MainActivity.airButton(down: Boolean) {
    Air.freezeUntil = System.currentTimeMillis() + 80
    Air.buttons = if (down) 1 else 0
    val t = airTv()
    if (t != null) t.mouseMove(0, 0, Air.buttons) else basic?.pointerHold(down)
}

private fun MainActivity.airClick() {
    Air.freezeUntil = System.currentTimeMillis() + 80
    val t = airTv()
    if (t != null) { t.mouseMove(0, 0, 1); t.mouseMove(0, 0, 0) } else basic?.pointerClick()   // down, then up
}

private fun MainActivity.airWheel(n: Int) {
    val t = airTv()
    if (t != null) t.mouseMove(0, 0, 0, n.coerceIn(-127, 127)) else basic?.pointerScroll(n)
}

/** A test feed: when the file exists (adb shell touch /sdcard/Android/data/dev.tvremote.phone/files/air_synth) a thread sends a
 *  200 Hz circle through the same path as the gyroscope, so the send path can be measured without waving a phone. */
private fun MainActivity.airSynth() {
    val f = java.io.File(getExternalFilesDir(null), "air_synth")
    if (!f.exists()) return
    val th = Thread {
        val carry = FloatArray(2); var n = 0
        try {
            var next = System.nanoTime()
            while (true) {
                val a = n++ * 0.05
                airMove((Math.cos(a) * 4).toFloat(), (Math.sin(a) * 4).toFloat(), carry)
                next += 5_000_000L
                val d = next - System.nanoTime()
                if (d > 0) Thread.sleep(d / 1_000_000, (d % 1_000_000).toInt())
            }
        } catch (e: InterruptedException) { }
    }
    th.isDaemon = true; th.start(); Air.synth = th
}

/** Feedback goes through View.performHapticFeedback, so the system's touch-feedback switch decides (the remote's own keys use buzz()). */
private fun View.airHaptic(kind: Int) {
    performHapticFeedback(kind)
    if (Air.fxLog) android.util.Log.d("airfx", "h$kind")
}
private fun View.airTick() {
    val now = SystemClock.uptimeMillis()
    if (now - Air.tickLast < TICK_GAP_MS) return
    Air.tickLast = now
    airHaptic(HapticFeedbackConstants.CLOCK_TICK)
}
private fun View.airConfirm() = airHaptic(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

private fun accent(alpha: Int) = (ACCENT and 0xFFFFFF) or (alpha shl 24)

/** Over the touch area: a soft circle under the finger with a fading trail, a ripple on a click, a tint while the button is held.
 *  Fixed arrays and paints: nothing is allocated per touch event or per frame. With "Remove animations" there is no trail or ripple. */
private class AirFx(ctx: Context, private val still: Boolean, private val d: Float) : View(ctx) {
    private val n = 12
    private val xs = FloatArray(n); private val ys = FloatArray(n); private val ts = LongArray(n)
    private var head = 0; private var count = 0
    private var on = false; private var held = false
    private var rx = 0f; private var ry = 0f; private var rt = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    init { isClickable = false; isFocusable = false }
    fun touch(x: Float, y: Float) {
        on = true
        if (!still) { head = (head + 1) % n; xs[head] = x; ys[head] = y; ts[head] = SystemClock.uptimeMillis(); if (count < n) count++ } else { xs[0] = x; ys[0] = y }
        invalidate()
    }
    fun lift() { on = false; invalidate() }
    fun ripple(x: Float, y: Float) { if (still) return; rx = x; ry = y; rt = SystemClock.uptimeMillis(); invalidate() }
    fun setHeld(b: Boolean) { held = b; invalidate() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { box.set(0f, 0f, w.toFloat(), h.toFloat()) }
    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis(); var again = false
        if (held) { p.style = Paint.Style.FILL; p.color = accent(34); c.drawRoundRect(box, 18 * d, 18 * d, p) }
        p.style = Paint.Style.FILL
        if (!still) for (i in 1 until count) {                        // the trail: older points are smaller and fainter
            val k = (head - i + n) % n; val age = now - ts[k]
            if (age < TRAIL_MS) { val f = 1f - age / TRAIL_MS.toFloat(); p.color = accent((80 * f).toInt()); c.drawCircle(xs[k], ys[k], d * (4 + 12 * f), p); again = true }
        }
        if (on) {
            val x = xs[if (still) 0 else head]; val y = ys[if (still) 0 else head]
            p.style = Paint.Style.FILL; p.color = accent(if (held) 120 else 72); c.drawCircle(x, y, d * 28, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = accent(170); c.drawCircle(x, y, d * 28, p)
        }
        val ra = now - rt
        if (!still && ra < RIPPLE_MS) {
            val f = ra / RIPPLE_MS.toFloat()
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = accent((190 * (1f - f)).toInt()); c.drawCircle(rx, ry, d * (16 + 46 * f), p); again = true
        }
        if (again) postInvalidateOnAnimation()
    }
    private companion object { const val TRAIL_MS = 320L; const val RIPPLE_MS = 340L }
}

/** The scroll strip's face: grip ridges like a mouse wheel's along the whole strip, turning with the finger (offset = travel mod spacing, so it
 *  is direct feedback and moves even with "Remove animations"), brighter while touched; an arrow at each end flashes in the scrolled direction. */
private class StripFx(ctx: Context, private val still: Boolean, private val d: Float) : View(ctx) {
    private var y = 0f; private var phase = 0f; private var on = false; private var upAt = 0L
    private var dir = 0; private var flashAt = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tri = Path()
    init { isClickable = false; isFocusable = false }
    fun down(py: Float) { on = true; y = py; invalidate() }
    fun move(py: Float) { phase = (phase + (py - y)) % (GAP * d); y = py; invalidate() }
    fun up() { on = false; upAt = SystemClock.uptimeMillis(); invalidate() }
    fun flash(dd: Int) { dir = dd; flashAt = SystemClock.uptimeMillis() }
    private fun arrow(c: Canvas, cx: Float, cy: Float, pointUp: Boolean, lit: Boolean) {
        val s = 5 * d; val e = if (pointUp) -s else s
        tri.rewind(); tri.moveTo(cx, cy + e); tri.lineTo(cx - s * 1.2f, cy - e); tri.lineTo(cx + s * 1.2f, cy - e); tri.close()
        p.style = Paint.Style.FILL; p.color = if (lit) ACCENT_TEXT else MUTED; c.drawPath(tri, p)
    }
    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis(); var again = false
        val w = width.toFloat(); val h = height.toFloat(); val gap = GAP * d
        val fade = if (on) 1f else maxOf(0f, 1f - (now - upAt) / 400f)          // bright while touched, back to rest after
        if (fade > 0f && !on && !still) again = true
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.strokeCap = Paint.Cap.ROUND
        p.color = (MUTED and 0xFFFFFF) or ((50 + (110 * fade).toInt()) shl 24)
        var ly = (if (phase < 0) phase + gap else phase) + 26 * d                // between the two arrows
        val x0 = 11 * d; val x1 = w - 11 * d
        while (ly < h - 26 * d) { c.drawLine(x0, ly, x1, ly, p); ly += gap }
        val lit = !still && now - flashAt < 180
        arrow(c, w / 2, 14 * d, true, lit && dir < 0)
        arrow(c, w / 2, h - 14 * d, false, lit && dir > 0)
        if (lit) again = true
        if (again) postInvalidateOnAnimation()
    }
    private companion object { const val GAP = 18f }
}
