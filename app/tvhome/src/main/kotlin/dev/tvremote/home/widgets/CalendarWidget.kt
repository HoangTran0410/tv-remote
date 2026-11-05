package dev.tvremote.home.widgets

import dev.tvremote.home.locale0
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.VERTICAL
import dev.tvremote.home.Font
import dev.tvremote.home.R
import dev.tvremote.home.widgets.Widgets.Cal
import dev.tvremote.home.widgets.Widgets.Companion.ACCENT
import dev.tvremote.home.widgets.Widgets.Companion.BOLD
import dev.tvremote.home.widgets.Widgets.Companion.CYAN
import dev.tvremote.home.widgets.Widgets.Companion.DIM
import dev.tvremote.home.widgets.Widgets.Companion.MEDIUM
import dev.tvremote.home.widgets.Widgets.Facts
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/** Today as a date: the day in a square, the weekday and date, the lunar date under it (the month grid needs the large slot). */
internal fun Widgets.calendarHalf(widget: LinearLayout, f: Facts, room: Int) {
    val c = f.cal ?: return
    val r = a.resources
    val loc = resources.locale0()
    val now = Date()
    val d = c.moon
    val month = r.getStringArray(R.array.lunar_month)[d.month - 1] + if (d.leap) a.getString(R.string.lunar_leap) else ""
    val dayWord = if (d.day <= 10) a.getString(R.string.lunar_day_early, d.day) else d.day.toString()
    val (can, chi) = Lunar.canChi(d.year)
    val lunar = a.getString(R.string.lunar_title, dayWord, month) +       // the year's name only where it fits whole
        if (room < dp(240)) "" else " · " + a.getString(R.string.lunar_year, r.getStringArray(R.array.lunar_can)[can], r.getStringArray(R.array.lunar_chi)[chi])
    val num =text(SimpleDateFormat("d", loc).format(now), 24f, Color.WHITE).apply { typeface = Font.medium; gravity = Gravity.CENTER }
    val row = LinearLayout(a).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    row.addView(tile(CYAN, null, num), LayoutParams(dp(52), dp(52)))
    val words = LinearLayout(a).apply { orientation = VERTICAL }
    words.addView(text(SimpleDateFormat("EEEE", loc).format(now), 16f, Color.WHITE).apply { typeface = Font.medium })
    words.addView(text(SimpleDateFormat("d MMMM yyyy", loc).format(now), 12f, DIM))
    words.addView(text(lunar, 12f, DIM))
    row.addView(words, LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
    widget.gravity = Gravity.CENTER_VERTICAL
    widget.addView(row, LayoutParams(-1, -2))
}

/** The month: the title and the lunar month and year of today, then one View drawing the weekdays and every day (solar only: a lunar line under each was too busy). */
/** StandBy's Date: the weekday in red over the month's name, the day as the widget's figure, the lunar date under it. */
internal fun Widgets.calendarDate(widget: LinearLayout, f: Facts) {
    val c = f.cal ?: return
    val loc = resources.locale0()
    val now = Date()
    val d = c.moon
    val month = a.resources.getStringArray(R.array.lunar_month)[d.month - 1] + if (d.leap) a.getString(R.string.lunar_leap) else ""
    val head = LinearLayout(a).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
    head.addView(text(SimpleDateFormat("EEEE", loc).format(now).replaceFirstChar { it.uppercase() }, 17f, dev.tvremote.home.Look.accent(ACCENT)).apply { typeface = BOLD; maxLines = 1 })
    head.addView(text("  " + a.resources.getStringArray(R.array.cal_month)[c.grid.month - 1], 17f, DIM).apply { typeface = MEDIUM; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    widget.addView(head)
    widget.addView(text(SimpleDateFormat("d", loc).format(now), 84f, Color.WHITE).apply {
        typeface = Font.medium; includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL
    }, LayoutParams(-1, 0, 1f))
    widget.addView(text(a.getString(R.string.lunar_title, if (d.day <= 10) a.getString(R.string.lunar_day_early, d.day) else d.day.toString(), month), 13f, DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
}

internal fun Widgets.calendar(widget: LinearLayout, f: Facts, width: Int, maxH: Int = Int.MAX_VALUE) {
    val c = f.cal ?: return
    val r = a.resources
    val (can, chi) = Lunar.canChi(c.moon.year)
    val month = r.getStringArray(R.array.lunar_month)[c.moon.month - 1] + if (c.moon.leap) a.getString(R.string.lunar_leap) else ""
    val year = a.getString(R.string.lunar_year, r.getStringArray(R.array.lunar_can)[can], r.getStringArray(R.array.lunar_chi)[chi])
    val lunar = a.getString(R.string.cal_lunar, month, year)
    val inner = width - dp(40)
    val title = LinearLayout(a).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
    title.addView(text(r.getStringArray(R.array.cal_month)[c.grid.month - 1], 16f, dev.tvremote.home.Look.accent(ACCENT)).apply { typeface = BOLD })
    title.addView(text(" " + c.grid.year, 16f, 0x80FFFFFF.toInt()).apply { typeface = MEDIUM })
    if (inner >= dp(320)) title.addView(text(lunar, 12f, 0x99FFFFFF.toInt()).apply { gravity = Gravity.END }, LayoutParams(0, -2, 1f))
    val view = Month(this@calendar, a, c, Calendar.getInstance(resources.locale0()).firstDayOfWeek, inner / 7f, maxH - dp(8))
    widget.addView(title)
    // in a fixed-height widget the month takes all that is left under the title, and the widget gives it some of its padding:
    // six weeks of a long month need every dp there is
    val fixed = maxH < Int.MAX_VALUE
    if (fixed) widget.setPadding(widget.paddingLeft, dp(10), widget.paddingRight, dp(8))
    widget.addView(view, (if (fixed) LayoutParams(-1, 0, 1f) else LayoutParams(-1, view.wanted)).apply { topMargin = dp(if (fixed) 3 else 6) })
}

/**
 * The weekdays and the days in ONE view, as the system calendars draw them: dim weekday initials, plain numbers, today a filled
 * circle, the days of the neighbouring months only a ghost. A lunar day shows only where it means something: the 1st (with its
 * month, "1/10"; the Tết rose) and the 15th, small under the number; a slot too thin for it keeps the numbers alone.
 * Paints made once: drawing allocates nothing.
 */
internal class Month(private val host: Widgets, c: Context, val cal: Cal, first: Int, slot: Float, maxH: Int) : View(c) {
    private fun dp(n: Number) = host.dp(n)
    private val sp get() = host.sp
    private val a get() = host.a
    private val tiny = slot < dp(30)
    private val headH = dp(22)
    private val rowMax = dp(30)
    private var rowH = minOf(rowMax, ((maxH - headH) / cal.grid.rows).coerceAtLeast(dp(16)))       // a six-week month in the fixed-height slot: thinner rows
    val wanted = headH + rowH * cal.grid.rows
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Font.regular; textSize = (if (tiny) 13f else 14f) * sp }       // left-aligned: each number is placed by its own ink
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = (if (tiny) 11f else 12f) * sp; color = 0x8CFFFFFF.toInt(); typeface = MEDIUM }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dev.tvremote.home.Look.accent(ACCENT) }
    private val dows = IntArray(7) { (first - 1 + it) % 7 }                // 0 = Sunday
    private val names = a.resources.getStringArray(R.array.cal_dow_short)
    private val n = cal.grid.rows * 7
    private val inMonth = BooleanArray(n) { it >= cal.grid.offset && it < cal.grid.offset + cal.grid.days }
    private val solar = Array(n) { "" }
    private val ink = android.graphics.Rect()
    init {
        val k = Calendar.getInstance().apply { clear(); set(cal.grid.sy, cal.grid.sm - 1, cal.grid.sd) }
        for (i in 0 until n) { solar[i] = k.get(Calendar.DAY_OF_MONTH).toString(); k.add(Calendar.DAY_OF_MONTH, 1) }
    }
    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else wanted)
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rowH = minOf(rowMax, ((h - headH) / cal.grid.rows).coerceAtLeast(dp(16)))
    }
    override fun onDraw(cv: Canvas) {
        val slot = width / 7f
        for (col in 0 until 7) {
            head.color = if (dows[col] == 0) 0xB3FFB4B4.toInt() else 0x8CFFFFFF.toInt()       // Sunday: a faint rose, not a shout
            cv.drawText(names[dows[col]], slot * col + slot / 2, headH - dp(8).toFloat(), head)
        }
        val r = minOf(rowH / 2f - dp(1), slot / 2f - dp(2))
        for (i in 0 until n) {
            val cx = slot * (i % 7) + slot / 2; val cy = headH + rowH * (i / 7) + rowH / 2f
            val today = i == cal.today
            big.typeface = if (today) BOLD else MEDIUM
            big.color = if (today) Color.WHITE else if (inMonth[i]) 0xF2FFFFFF.toInt() else 0x30FFFFFF
            // the digits' own ink in the middle of the cell, not the run's advance: "21" drawn centred leans right ("1" has room on its left)
            big.getTextBounds(solar[i], 0, solar[i].length, ink)
            if (today) cv.drawCircle(cx, cy, r, back)
            cv.drawText(solar[i], cx - ink.exactCenterX(), cy - ink.exactCenterY(), big)
        }
    }
}
