package dev.tvremote.home

import dev.tvremote.tvui.Motion
import android.content.Context
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.graphics.Bitmap
import android.graphics.Color
import android.service.dreams.DreamService
import android.app.Activity
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Calendar
import java.util.Date
import java.util.Locale
import dev.tvremote.home.widgets.Lunar
import dev.tvremote.home.widgets.ClockFace
import dev.tvremote.home.widgets.Widgets
import dev.tvremote.home.wall.Wall
import dev.tvremote.home.wall.WallArt
import dev.tvremote.home.wall.Walls
import dev.tvremote.home.wall.WebWall

/**
 * TV Home's screensaver: the big clock (or a StandBy face, HomeConfig "dface"), the day and the lunar date over the wallpaper (the same one the home screen has). Made
 * for weak boxes: views built once, the picture drifts with ONE slow property animation (no redraw in between, on the GPU), the
 * clock text changes once a minute, and it is not animated at all when motion is off. Against burn-in the clock moves to
 * another place every minute, fading while it goes, and everything is a little dim.
 */
/**
 * TV Home's screensaver as a system dream (the body is DreamBody).
 */
class HomeDream : DreamService() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))
    private var body: DreamBody? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true; isScreenBright = false
        body = DreamBody(this).also { setContentView(it.build()) }
    }
    override fun onDetachedFromWindow() { body?.stop(); body = null; super.onDetachedFromWindow() }
}

/** The "Screensaver" button: the same picture in a full-screen activity of TV Home (no permission, no TV Setup); any key or touch closes it. */
class ScreensaverActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))
    private var body: DreamBody? = null
    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        body = DreamBody(this).also { setContentView(it.build()) }
    }
    override fun onKeyDown(k: Int, e: android.view.KeyEvent): Boolean { finish(); return true }
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean { finish(); return true }
    override fun onDestroy() { body?.stop(); body = null; super.onDestroy() }
}

/** The drawing of the screensaver, shared by the system dream and the "Screensaver" button of the Control Center (an activity): built once, see HomeDream. */
class DreamBody(private val c: Context) {
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val bg = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())   // a late job after onDestroy's shutdown is dropped, not thrown (census 1d158634)
    private lateinit var block: LinearLayout
    private var date: TextView? = null
    private var lunar: TextView? = null
    private var wday: TextView? = null                                 // StandBy "modern": the weekday, the day and the month beside the face
    private var dayN: TextView? = null
    private var mon: TextView? = null
    private var wall: ImageView? = null
    private var drift: ObjectAnimator? = null
    private var motion = true
    private var shown: Bitmap? = null
    private var web: android.webkit.WebView? = null
    private val minute = object : Runnable { override fun run() { refresh(); if (!dark()) moveClock(); ui.postDelayed(this, untilNextMinute()) } }
    private var darkSpan = ""                                      // "ddark": the night hours the screen is black ("" = off: no view, no check)
    private var blackout: View? = null

    fun build(): View {
        Font.load(c)
        Store.advance(c, false)                                   // a timed rotation that fell due while Home was not on screen
        val conf = Store.get(c)
        Motion.read(c, conf)
        motion = Motion.on
        val dm = c.resources.displayMetrics
        val root = FrameLayout(c)
        root.background = conf.optString("wall").let { wallDrawable(wallColors(it, conf), wallDir(it, conf)) }
        val id = conf.optString("wall")
        if (Walls.isPicture(id) || WallArt.has(id)) {                 // a photo, Bing's picture or a drawn scene: all become one bitmap here
            val v = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
            wall = v
            root.addView(v, FrameLayout.LayoutParams(-1, -1))
            bg.execute {
                val (bw, bh) = Wall.want(dm.widthPixels, dm.heightPixels)
                val b = if (WallArt.has(id)) Walls.small(c, id, bw, bh, false) else Walls.bitmap(c, id, dm.widthPixels, dm.heightPixels)   // the dim below is its veil
                ui.post { if (b != null && shown == null && wall != null) { shown = b; v.setImageBitmap(b); v.animate().alpha(1f).setDuration(if (motion) 900 else 0).start(); startDrift(v) } }
            }
        }
        if (id == HomeConfig.WEB) {                                   // the page, full-screen: no drift (it moves by itself); gone if it cannot load
            web = WebWall.make(c, conf.optString("wurl"), { WebWall.stop(web); web = null })
            web?.let { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
        root.addView(View(c).apply { setBackgroundColor(0x70000000) }, FrameLayout.LayoutParams(-1, -1))   // dim: night, and words stay readable over a photo

        Look.read(conf)                                               // the accent in this process too: the dream can start it alone
        val face = conf.optString("dface")
        block = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(40), dp(20), dp(40), dp(20)) }
        when (face) {
            // StandBy: the clock widget's own faces (ClockFace, drawn once a minute), large; the block still moves every minute against burn-in
            "digital" -> block.addView(ClockFace({ dp(it) }, c, "digital", false) { }, LinearLayout.LayoutParams((dm.widthPixels * 0.62f).toInt(), (dm.heightPixels * 0.5f).toInt()))
            "modern" -> {
                val side = (dm.heightPixels * 0.56f).toInt()
                block.orientation = LinearLayout.HORIZONTAL; block.gravity = Gravity.CENTER_VERTICAL
                block.addView(ClockFace({ dp(it) }, c, "modern", false, dial = false) { }, LinearLayout.LayoutParams(side, side))   // the date is beside it: not twice
                val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                wday = Font.on(TextView(c)).apply { textSize = 30f; setTextColor(Look.accent(Widgets.ACCENT)); typeface = Font.bold }
                dayN = Font.on(TextView(c)).apply { textSize = 150f; setTextColor(INK); typeface = Font.medium; includeFontPadding = false }
                mon = Font.on(TextView(c)).apply { textSize = 26f; setTextColor(SOFT) }
                lunar = Font.on(TextView(c)).apply { textSize = 20f; setTextColor(FAINT); setPadding(0, dp(6), 0, 0) }
                listOf(wday, dayN, mon, lunar).forEach { col.addView(it) }
                block.addView(col, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(56) })
            }
            else -> {
                val clock = TextClock(c).apply {
                    textSize = 120f; setTextColor(INK); typeface = Font.light
                    includeFontPadding = false; gravity = Gravity.CENTER
                    format24Hour = if (conf.optBoolean("clock24", true)) "HH:mm" else "h:mm"
                    format12Hour = format24Hour
                }
                date = line(26f, SOFT); lunar = line(20f, FAINT)
                block.addView(clock); block.addView(date); block.addView(lunar)
            }
        }
        root.addView(block, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        darkSpan = conf.optString("ddark")
        if (darkSpan.isNotEmpty()) {
            blackout = View(c).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
            root.addView(blackout, FrameLayout.LayoutParams(-1, -1))   // on top of everything: a pure black pixel is an unlit pixel on an OLED
            dark()
        }
        refresh()
        ui.postDelayed(minute, untilNextMinute())
        return root
    }

    /** Inside the dark hours: the black view on, the clock hidden, the wall's drift paused; outside, all back. Returns whether it is dark. */
    private fun dark(): Boolean {
        val b = blackout ?: return false
        val on = HomeConfig.darkAt(darkSpan, Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) })
        if ((b.visibility == View.VISIBLE) == on) return on
        b.visibility = if (on) View.VISIBLE else View.GONE
        block.visibility = if (on) View.INVISIBLE else View.VISIBLE
        drift?.let { if (on) it.pause() else it.resume() }
        return on
    }

    fun stop() {
        ui.removeCallbacksAndMessages(null)
        drift?.cancel(); drift = null
        wall?.setImageDrawable(null); wall = null; shown = null
        WebWall.stop(web); web = null
        bg.shutdownNow()
    }

    private fun dp(v: Number) = (v.toFloat() * c.resources.displayMetrics.density).toInt()

    private fun line(size: Float, color: Int) = Font.on(TextView(c)).apply { textSize = size; setTextColor(color); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }

    /** The day and the lunar date: once a minute, which is when the day can change at all. */
    private fun refresh() {
        val now = Date()
        date?.text = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now).toString().replaceFirstChar { it.uppercase() }
        wday?.text = DateFormat.format("EEEE", now).toString().replaceFirstChar { it.uppercase() }
        dayN?.text = DateFormat.format("d", now)
        mon?.text = c.resources.getStringArray(R.array.cal_month)[Calendar.getInstance().get(Calendar.MONTH)]
        val cal = Calendar.getInstance()
        val d = Lunar.of(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
        val r = c.resources
        val month = r.getStringArray(R.array.lunar_month)[d.month - 1] + if (d.leap) c.getString(R.string.lunar_leap) else ""
        val dayWord = if (d.day <= 10) c.getString(R.string.lunar_day_early, d.day) else d.day.toString()
        val (can, chi) = Lunar.canChi(d.year)
        lunar?.text = c.getString(R.string.lunar_title, dayWord, month) + " · " +
            c.getString(R.string.lunar_year, r.getStringArray(R.array.lunar_can)[can], r.getStringArray(R.array.lunar_chi)[chi])
    }

    /** The clock block to another place on the screen (never the same twice in a row, always fully inside), fading out and in on the way. */
    private fun moveClock() {
        val dm = c.resources.displayMetrics
        val rx = maxOf(0, (dm.widthPixels - block.width) / 2); val ry = maxOf(0, (dm.heightPixels - block.height) / 2)
        val x = ((Math.random() * 2 - 1) * rx).toFloat(); val y = ((Math.random() * 2 - 1) * ry * 0.9).toFloat()
        if (!motion) { block.translationX = x; block.translationY = y; return }
        block.animate().alpha(0f).setDuration(500).withEndAction {
            block.translationX = x; block.translationY = y
            block.animate().alpha(1f).setDuration(700).start()
        }.start()
    }

    /** One slow push-in and sideways drift, back and forth for good: a single animator on one layer, nothing allocated per frame. */
    private fun startDrift(v: ImageView) {
        if (!motion) return
        v.pivotX = 0f; v.pivotY = v.height.toFloat()
        v.scaleX = 1.0f; v.scaleY = 1.0f
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val a = ObjectAnimator.ofFloat(v, "scaleX", 1.0f, 1.12f)
        a.addUpdateListener { v.scaleY = it.animatedValue as Float }
        a.duration = 90_000; a.repeatMode = ObjectAnimator.REVERSE; a.repeatCount = ObjectAnimator.INFINITE
        a.interpolator = LinearInterpolator() as TimeInterpolator
        a.start(); drift = a
        if (blackout?.visibility == View.VISIBLE) a.pause()
    }

    private fun untilNextMinute() = 60_000L - System.currentTimeMillis() % 60_000L + 50

    private companion object {                                    // white at three strengths: the time, the day, the lunar line
        const val INK = 0xE6FFFFFF.toInt(); const val SOFT = 0xCCFFFFFF.toInt(); const val FAINT = 0x99FFFFFF.toInt()
    }
}
