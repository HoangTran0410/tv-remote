package dev.tvremote.home.widgets

import dev.tvremote.home.locale0
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.VERTICAL
import android.widget.TextView
import dev.tvremote.home.Font
import dev.tvremote.home.Store
import dev.tvremote.home.ui.Frost
import dev.tvremote.home.widgets.Widgets.Companion.DIM
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * The clock widget, [mode] 0: a pill in a stack (the face, the weekday and the date), 1: standing alone, the face filling the widget,
 * 2: big, the face with the weekday and the date beside it. The face is the time: no digits beside it, and no date in its dial when
 * the date is written beside (the user, 2026-11-05: the time shown twice, the dial's small red date hard to read). The digital face is its own time and date: it fills the widget in every mode. The face (classic, minimal, roman) and its theme (dark, light) are HomeConfig "wopt", independent of each other (see [ClockFace]).
 */
internal fun Widgets.clock(mode: Int): View {
    val face0 = opt("clock", "face"); val light0 = opt("clock", "theme") == "light"
    val locale = resources.locale0()
    val dayF = SimpleDateFormat("EEEE", locale)
    val time = text("", if (mode == 2) 30f else 20f, Color.WHITE).apply { typeface = Font.medium; maxLines = 1 }   // the weekday, as big as the time was
    // big beside the face in a narrow widget (five in a row) "Chủ Nhật" came out cut: it shrinks to the column first
    fun fitTime() {
        val room = ((time.parent as? View)?.width ?: 0) - time.paddingLeft - time.paddingRight
        if (mode != 2 || room <= 0) return
        var size = 30f; time.textSize = size
        while (size > 18f && time.paint.measureText(time.text.toString()) > room) { size -= 1f; time.textSize = size }
    }
    val date = text("", if (mode == 2) 17f else 13f, DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    // "4 tháng 11" / "November 4", else "4/11" / "4 Nov" when it does not fit beside two other big widgets
    val fullF = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale)
    val shortF = SimpleDateFormat(if (locale.language == "vi") "d/M" else "d MMM", locale)
    var shown = Date()
    fun put(now: Date) {
        shown = now
        time.text = dayF.format(now).replaceFirstChar { it.uppercase() }
        val full = fullF.format(now)
        val room = date.width - date.paddingLeft - date.paddingRight
        date.text = if (mode == 2 && (date.width == 0 || date.paint.measureText(full) <= room)) full else shortF.format(now)
    }
    if (mode == 2) date.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) date.post { put(shown); fitTime() } }
    val face = ClockFace(dp, a, face0, light0, dial = mode == 1) { now -> put(now); fitTime() }
    return LinearLayout(a).apply {
        Frost.on(this, dp(if (mode == 0) 18 else 22).toFloat(), dp(1))
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        if (mode == 1 || face0 == "digital") {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(face, LayoutParams(-1, -1))
            return@apply
        }
        setPadding(dp(if (mode == 0) 10 else 18), dp(if (mode == 0) 8 else 14), dp(14), dp(if (mode == 0) 8 else 14))
        addView(face, if (mode == 0) LayoutParams(dp(62), dp(62)) else LayoutParams(0, -1, 0.9f).apply { })
        addView(LinearLayout(a).apply {
            orientation = VERTICAL
            addView(time); addView(date, LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }, if (mode == 0) LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) } else LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
    }
}

/** "T4" / "CN": the short Vietnamese weekday. */
internal fun viDay(d: Date) = Calendar.getInstance().apply { time = d }.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) "CN" else "T$it" }

/**
 * An analog face drawn by hand: ticks, numbers, hour and minute hands. It redraws itself once a minute while it is on screen (a
 * hidden window, an app in front or a screen off draws nothing, so the loop ends by itself). No second hand: its 30 frames a
 * second redrew Home on the thread a panel over it shares, and held Customize's opening on the FPT box.
 * [onMinute] gets the time each time the minute changes (the digits beside it).
 */
/** One clock face, drawn once a minute; [dp] from its host, so the screensaver (no Widgets there) draws the same faces. */
internal class ClockFace(private val dp: (Number) -> Int, c: Context, val style: String, light: Boolean, private val dial: Boolean = true, val onMinute: (Date) -> Unit) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cal = Calendar.getInstance()
    private var minute = -1
    private val serif = style == "roman"
    private val dark = !light
    private val ink = if (dark) Color.WHITE else 0xFF1C1C1E.toInt()
    private val dim = if (dark) 0x66FFFFFF else 0x55000000
    private val orange = 0xFFFF9500.toInt()
    private val gold = if (light) GOLD_LIGHT else GOLD      // the roman face's ink: pale gold on dark, deep gold on light
    private val red = dev.tvremote.home.Look.accent(if (light) RED_LIGHT else Widgets.ACCENT)       // modern / digital: the date, in the calendar's red
    private val locale = c.resources.locale0()
    private val digitsF = SimpleDateFormat(if (Store.get(c).optBoolean("clock24", true)) "HH:mm" else "h:mm", locale)
    private val dateF = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
    private val dayF = SimpleDateFormat(if (locale.language == "vi") "d" else "EEE d", locale)
    private var digits = ""; private var dateLine = ""; private var dayLine = ""
    private val box = android.graphics.RectF()
    private val frame = Runnable { invalidate() }
    override fun onVisibilityAggregated(v: Boolean) { super.onVisibilityAggregated(v); if (v) invalidate() }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = minOf(w, h) / 2f; val cx = w / 2f; val cy = h / 2f
        val now = System.currentTimeMillis()
        cal.timeInMillis = now
        if (cal.get(Calendar.MINUTE) != minute) { minute = cal.get(Calendar.MINUTE); val d = Date(now); post { onMinute(d) } }
        val min = cal.get(Calendar.MINUTE).toFloat()
        val hr = cal.get(Calendar.HOUR) + min / 60f
        if (style == "digital" || style == "modern") {               // their words, once a minute: drawing allocates nothing else
            val d = Date(now)
            digits = digitsF.format(d); dateLine = dateF.format(d).replaceFirstChar { it.uppercase() }
            dayLine = if (locale.language == "vi") viDay(d) + " " + dayF.format(d) else dayF.format(d).uppercase()
        }
        if (style == "digital") { digital(cv, w, h); return next(now) }
        if (style == "modern") { modern(cv, cx, cy, r, hr, min); return next(now) }
        paint.style = Paint.Style.FILL
        paint.color = if (dark) (when (style) { "roman" -> DISC_ROMAN; "minimal" -> DISC_MINIMAL; else -> DISC_DARK }) else if (serif) DISC_ROMAN_LIGHT else DISC_LIGHT
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, r * 0.012f)
        paint.color = if (dark) 0x33FFFFFF else 0x22000000
        cv.drawCircle(cx, cy, r - paint.strokeWidth / 2, paint)
        paint.strokeCap = Paint.Cap.BUTT
        val edge = r * 0.93f
        for (i in 0 until 60) {
            val hour = i % 5 == 0
            if (style == "minimal" && !hour) continue
            if (serif && !hour) continue
            val len = when { style == "minimal" -> r * 0.14f; hour -> r * 0.09f; else -> r * 0.045f }
            paint.strokeWidth = if (style == "minimal") r * 0.035f else if (hour) maxOf(1.5f, r * 0.022f) else maxOf(1f, r * 0.012f)
            paint.color = if (hour) (if (serif) gold else ink) else dim
            if (!dark && !hour) paint.color = 0x44000000
            line(cv, cx, cy, i * 6f, edge - len, edge, paint)
        }
        if (style != "minimal" && r >= dp(34)) {                     // a face this small: ticks only, digits would be specks
            paint.style = Paint.Style.FILL
            paint.typeface = if (serif) Typeface.SERIF else Font.medium
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (serif) gold else ink
            val quarters = arrayOf("XII", "III", "VI", "IX")
            if (serif) paint.textSize = r * 0.2f else paint.textSize = r * 0.17f
            val fm2 = paint.fontMetrics
            val ring = r * (if (serif) 0.66f else 0.69f)
            val count = if (serif) 4 else 12
            for (n in 0 until count) {
                val a = Math.toRadians((if (serif) n * 90.0 else (n + 1) * 30.0) - 90)
                val label = if (serif) quarters[n] else (n + 1).toString()
                cv.drawText(label, cx + (ring * Math.cos(a)).toFloat(), cy + (ring * Math.sin(a)).toFloat() - (fm2.ascent + fm2.descent) / 2, paint)
            }
        }
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        val thin = style == "minimal"
        paint.color = if (serif) gold else ink
        paint.strokeWidth = r * (if (thin) 0.035f else 0.075f)
        line(cv, cx, cy, hr * 30f, 0f, r * (if (serif) 0.45f else 0.5f), paint)
        paint.strokeWidth = r * (if (thin) 0.025f else 0.055f)
        line(cv, cx, cy, min * 6f, 0f, r * (if (serif) 0.68f else 0.74f), paint)
        paint.style = Paint.Style.FILL
        paint.color = if (serif) gold else ink; cv.drawCircle(cx, cy, r * 0.065f, paint)
        paint.color = orange; cv.drawCircle(cx, cy, r * 0.03f, paint)
        next(now)
    }
    private fun next(now: Long) {
        removeCallbacks(frame)                                      // one pending frame, however many draws asked
        if (isShown && windowVisibility == VISIBLE) {
            postDelayed(frame, 60_000 - now % 60_000)
        }
    }
    /** StandBy's Modern: every minute ticked (the hours bright), twelve numbers, thin hands, the weekday and day in the dial in red. */
    private fun modern(cv: Canvas, cx: Float, cy: Float, r: Float, hr: Float, min: Float) {
        paint.style = Paint.Style.FILL
        paint.color = if (dark) DISC_DARK else DISC_LIGHT
        cv.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.BUTT
        val edge = r * 0.95f
        for (i in 0 until 60) {
            val hour = i % 5 == 0
            paint.strokeWidth = if (hour) maxOf(1.5f, r * 0.026f) else maxOf(1f, r * 0.011f)
            paint.color = if (hour) ink else dim
            line(cv, cx, cy, i * 6f, edge - (if (hour) r * 0.11f else r * 0.055f), edge, paint)
        }
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        if (r >= dp(34)) {
            paint.typeface = Font.medium; paint.color = ink; paint.textSize = r * 0.19f
            val fm = paint.fontMetrics; val ring = r * 0.67f
            for (n in 1..12) {
                val a = Math.toRadians(n * 30.0 - 90)
                cv.drawText(n.toString(), cx + (ring * Math.cos(a)).toFloat(), cy + (ring * Math.sin(a)).toFloat() - (fm.ascent + fm.descent) / 2, paint)
            }
            if (dial) { paint.color = red; paint.textSize = r * 0.13f; cv.drawText(dayLine, cx, cy + r * 0.4f, paint) }   // [dial]: no date written beside
        }
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.color = ink
        paint.strokeWidth = r * 0.06f; line(cv, cx, cy, hr * 30f, 0f, r * 0.5f, paint)
        paint.strokeWidth = r * 0.04f; line(cv, cx, cy, min * 6f, 0f, r * 0.8f, paint)
        paint.style = Paint.Style.FILL
        paint.color = orange; cv.drawCircle(cx, cy, r * 0.06f, paint)
        paint.color = if (dark) DISC_DARK else DISC_LIGHT; cv.drawCircle(cx, cy, r * 0.025f, paint)
    }

    /** StandBy's Digital: a screen in the disc's colour, the date line in red over the time, the digits as large as the screen allows. */
    private fun digital(cv: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = if (dark) DISC_DIGITAL else DISC_LIGHT
        box.set(0f, 0f, w, h); cv.drawRoundRect(box, dp(16).toFloat(), dp(16).toFloat(), paint)
        val pad = minOf(w, h) * 0.12f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Font.medium
        val sp = resources.displayMetrics.scaledDensity
        var ds = (h * 0.15f).coerceIn(11f * sp, 22f * sp)                // the pill: still a line to read, not a speck
        paint.textSize = ds
        val line = if (paint.measureText(dateLine) <= w - pad * 2) dateLine else dayLine   // narrow: "T3 27"
        while (ds > 9f * resources.displayMetrics.density && paint.measureText(line) > w - pad * 2) { ds -= 1f; paint.textSize = ds }
        paint.typeface = Font.light
        var ts = h - ds * 2.2f - pad; paint.textSize = ts
        while (ts > 12f && paint.measureText(digits) > w - pad * 2) { ts -= 2f; paint.textSize = ts }
        val cap = ts * 0.72f; val gap = ds * 0.5f
        val top = (h - (ds + gap + cap)) / 2f
        paint.color = ink
        cv.drawText(digits, w / 2f, top + ds + gap + cap, paint)
        paint.typeface = Font.medium; paint.textSize = ds; paint.color = red
        cv.drawText(line, w / 2f, top + ds * 0.8f, paint)
    }

    /** A stroke at [deg] clockwise from 12, from [from] to [to] of the centre. */
    private fun line(cv: Canvas, cx: Float, cy: Float, deg: Float, from: Float, to: Float, p: Paint) {
        val a = Math.toRadians(deg - 90.0); val co = Math.cos(a).toFloat(); val si = Math.sin(a).toFloat()
        cv.drawLine(cx + co * from, cy + si * from, cx + co * to, cy + si * to, p)
    }
}

// the face's disc by face and theme, and the roman face's gold (dark theme / light theme)
private const val DISC_DARK = 0xFF1C1C1E.toInt(); private const val DISC_MINIMAL = 0xFF161618.toInt(); private const val DISC_ROMAN = 0xFF211C17.toInt()
private const val DISC_LIGHT = 0xFFF4F4F6.toInt(); private const val DISC_ROMAN_LIGHT = 0xFFF6F0E4.toInt()
private const val GOLD = 0xFFE9D8A6.toInt(); private const val GOLD_LIGHT = 0xFF8C6D1F.toInt()
private const val DISC_DIGITAL = 0xFF0B0B0C.toInt(); private const val RED_LIGHT = 0xFFD70015.toInt()   // StandBy's black; a red that reads on the light disc
