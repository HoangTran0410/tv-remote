package dev.tvremote.home.widgets

import dev.tvremote.home.locale0
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.VERTICAL
import android.widget.TextView
import dev.tvremote.home.Font
import dev.tvremote.home.HomeQr
import dev.tvremote.home.R
import dev.tvremote.home.widgets.Widgets.Facts
import dev.tvremote.home.widgets.Widgets.Moon

/**
 * The "tonal" look of every widget but the clock (HomeConfig.TONAL: weather, system, lunar, air, calendar, usage, timer, market, news, QR) (HomeConfig "wopt" look; app/DESIGN-TV.md "Tonal cards"): an opaque warm
 * widget, a label, a very large figure, a caption, and a scalloped badge with the widget's icon. One fixed warm tone: TV Home does not
 * pick a colour from the wallpaper. Every part is drawn here so the four widgets cannot drift apart.
 */
internal object Tonal {
    val FILL = 0xFFF6E9E0.toInt()                                      // the widget
    val INK = 0xFF3B2A22.toInt()                                       // dark brown words
    val SUB = 0xFF6D564A.toInt()                                       // the caption
    val PALE = 0xFFE7CDBD.toInt()                                      // a pale badge (dark icon)
    val PALE_INK = 0xFF4A3024.toInt()
    val OLIVE = 0xFF5D6233.toInt()                                     // a dark badge (light icon)
    val OLIVE_INK = 0xFFF3EDD2.toInt()
    val BAR = 0xFF8A5F47.toInt()                                       // a bar's fill, on [TRACK]
    val TRACK = 0xFFE9D5C8.toInt()
    val WARN = 0xFFB07418.toInt(); val BAD = 0xFFB3402E.toInt()        // a bar from 70 % / 85 %
    const val RADIUS = 28                                              // dp
    const val BADGE_MAX = 100                                          // dp; smaller in a narrow widget, see [badge]
    const val BUMPS = 9

    /** The badge's side: a share of the widget's inner width, 44..[BADGE_MAX] dp. */
    fun badge(innerDp: Int) = (innerDp * 0.30f).toInt().coerceIn(44, BADGE_MAX)
}

/** A circle with a wavy edge (nine bumps, Material 3's "cookie") in [fill] with the vector [icon] in [ink] on it. */
internal class Scallop(c: Context, fill: Int, icon: Int, ink: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val path = Path()
    private val glyph = c.getDrawable(icon)!!.mutate().apply { setTint(ink) }
    private var sized = 0

    override fun onDraw(cv: Canvas) {
        val s = minOf(width, height)
        if (s != sized) { sized = s; shape(s.toFloat()) }
        cv.drawPath(path, paint)
        val g = (s * 0.46f).toInt(); val x = (width - g) / 2; val y = (height - g) / 2
        glyph.setBounds(x, y, x + g, y + g); glyph.draw(cv)
    }

    /** r(a) = R (1 - k + k cos(9a)): the bumps reach R, the dips 1 - 2k of it; sampled every 3 degrees, so the edge stays smooth. */
    private fun shape(s: Float) {
        val r = s / 2f; val k = 0.055f
        path.rewind()
        for (i in 0..120) {
            val a = i * 2 * Math.PI / 120
            val rr = r * (1 - k + k * Math.cos(Tonal.BUMPS * a)).toFloat()
            val px = r + rr * Math.cos(a).toFloat(); val py = r + rr * Math.sin(a).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }
}

/** A thick rounded bar of [part] on a pale track, a small dot at the track's end. */
internal class TonalBar(c: Context, private val part: Float, private val fill: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    override fun onDraw(cv: Canvas) {
        val h = height.toFloat(); val r = h / 2
        paint.color = Tonal.TRACK; box.set(0f, 0f, width.toFloat(), h); cv.drawRoundRect(box, r, r, paint)
        paint.color = fill; box.set(0f, 0f, maxOf(h, width * part.coerceIn(0f, 1f)), h); cv.drawRoundRect(box, r, r, paint)
        paint.color = if (part >= 0.97f) Tonal.TRACK else fill                              // a full bar swallows the dot: it shows only on the track
        cv.drawCircle(width - r, r, h * 0.13f, paint)
    }
}

/** The tonal widget itself: opaque, 28 dp corners, no edge, no shadow; the same padding as the glass one. */
internal fun Widgets.tonalWidget(compact: Boolean = false) = LinearLayout(a).apply {
    orientation = VERTICAL
    background = GradientDrawable().apply { setColor(Tonal.FILL); cornerRadius = dp(Tonal.RADIUS).toFloat() }
    if (compact) setPadding(dp(16), dp(8), dp(16), dp(8)) else setPadding(dp(20), dp(14), dp(20), dp(14))
}

internal fun Widgets.ink(t: String, size: Float, color: Int = Tonal.INK) = Font.on(TextView(a)).apply {
    text = t; textSize = size; setTextColor(color); maxLines = 1; includeFontPadding = false
    ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.START
}

/**
 * The three lines and the badge: [label] on top, [value] large, [caption] under it, the badge at the top right ([olive]: dark with a
 * light icon, else pale with a dark one). [width] is the widget's width in px; [compact]: half the height, the figure smaller.
 */
internal fun Widgets.tonalBody(widget: LinearLayout, label: String, value: String, caption: String, icon: Int, olive: Boolean, width: Int, compact: Boolean, headline: Boolean = false) {
    val side = dp(if (compact) 48 else Tonal.badge(((width - dp(40)) / dp(1)).coerceAtLeast(1)))
    val root = FrameLayout(a)
    val col = LinearLayout(a).apply { orientation = VERTICAL }
    val room = side + dp(8)                                            // the label and the figure stop short of the badge; the caption has the full width
    col.addView(ink(label, if (compact) 12f else 14f), LayoutParams(-1, -2).apply { rightMargin = room })
    if (headline) col.addView(TextView(a).apply {                        // a sentence (news): a few lines, not one figure
        text = value; setTextColor(Tonal.INK); textSize = if (compact) 14f else 20f; typeface = Font.medium
        maxLines = if (compact) 2 else 4; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }, LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(4); rightMargin = room })
    else col.addView(Shrink(a, if (compact) 26f else 64f, if (compact) 18f else 34f).apply {
        text = value; setTextColor(Tonal.INK); maxLines = 1; typeface = Font.regular; includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }, LayoutParams(-1, 0, 1f).apply { rightMargin = room })
    col.addView(Shrink(a, if (compact) 12f else 14f, 10f).apply {            // one line, smaller before cut
        text = caption; setTextColor(Tonal.SUB); maxLines = 1; typeface = Font.regular; includeFontPadding = false
    }, LayoutParams(-1, -2))
    root.addView(col, FrameLayout.LayoutParams(-1, -1))
    root.addView(Scallop(a, if (olive) Tonal.OLIVE else Tonal.PALE, icon, if (olive) Tonal.OLIVE_INK else Tonal.PALE_INK),
        FrameLayout.LayoutParams(side, side, Gravity.TOP or Gravity.END))
    widget.addView(root, LayoutParams(-1, -1))
}

private fun Widgets.skyIcon(w: Weather.Forecast) = when (w.kind) {
    Weather.Kind.CLEAR -> if (w.night) R.drawable.ic_moon else R.drawable.ic_wake
    Weather.Kind.PARTLY -> R.drawable.ic_weather
    Weather.Kind.STORM -> R.drawable.ic_zap
    else -> R.drawable.ic_cloud
}

internal fun Widgets.weatherTonal(widget: LinearLayout, f: Facts, width: Int, compact: Boolean) {
    val w = f.wx
    if (w == null) { tonalBody(widget, a.getString(R.string.w_weather), "—", a.getString(R.string.wx_none), R.drawable.ic_weather, false, width, compact); return }
    val d = w.days[0]
    tonalBody(widget, w.place.ifEmpty { a.getString(R.string.wx_near) }, "${w.temp}°", a.getString(R.string.ton_sky, a.getString(kindWords(w.kind)), d.max, d.min),
        skyIcon(w), false, width, compact)
}

internal fun Widgets.airTonal(widget: LinearLayout, f: Facts, width: Int, compact: Boolean) {
    val x = f.air
    if (x == null) { tonalBody(widget, a.getString(R.string.w_air), "—", a.getString(R.string.aq_none), R.drawable.ic_wind, true, width, compact); return }
    tonalBody(widget, a.getString(R.string.w_air), x.aqi.toString(), airLine(x), R.drawable.ic_wind, true, width, compact)
}

/** "Trung bình, PM2.5 25". */
internal fun Widgets.airLine(x: Weather.Air) = a.getString(R.string.aq_line, a.getString(airWords(Weather.airLevel(x.aqi))), x.pm25)

internal fun Widgets.airWords(level: Int) = when (level) {
    0 -> R.string.aq_0; 1 -> R.string.aq_1; 2 -> R.string.aq_2; 3 -> R.string.aq_3; 4 -> R.string.aq_4; else -> R.string.aq_5
}

internal fun Widgets.lunarTonal(widget: LinearLayout, m: Moon, width: Int, compact: Boolean) {
    val d = m.day; val r = a.resources
    val (can, chi) = Lunar.canChi(d.year)
    val year = a.getString(R.string.ton_year, a.getString(R.string.lunar_year, r.getStringArray(R.array.lunar_can)[can], r.getStringArray(R.array.lunar_chi)[chi]))
    val h = m.hol
    val caption = if (h == null) "" else holidayWords(h.key).let { name ->
        if (h.days == 0) a.getString(R.string.h_today, name) else r.getQuantityString(R.plurals.h_in, h.days, name, h.days)
    }
    tonalBody(widget, year + if (d.leap) a.getString(R.string.lunar_leap) else "", "${d.day}/${d.month}", caption, R.drawable.ic_calendar, false, width, compact)
}

internal fun Widgets.holidayWords(key: String) = a.getString(when (key) {
    "newyear" -> R.string.h_newyear; "liberation" -> R.string.h_liberation; "labour" -> R.string.h_labour
    "national" -> R.string.h_national
    "xmas" -> R.string.h_xmas; "tet" -> R.string.h_tet; "hung" -> R.string.h_hung; "buddha" -> R.string.h_buddha
    "duanwu" -> R.string.h_duanwu; "vulan" -> R.string.h_vulan; else -> R.string.h_midautumn
})

/** CPU, memory and storage as rows: a small icon, the name, the figure at the right, a thick bar under it. The half-height widget keeps two. */
internal fun Widgets.systemTonal(widget: LinearLayout, f: Facts, compact: Boolean) {
    val gb = 1024.0 * 1024 * 1024
    fun part(p: Pair<Long, Long>) = if (p.second > 0) p.first.toFloat() / p.second else 0f
    fun size(x: Double) = if (x < 10) "%.1f".format(x) else "%.0f".format(x)
    fun pair(p: Pair<Long, Long>) = "${size(p.first / gb)} / ${size(p.second / gb)} GB"
    class Row(val icon: Int, val name: String, val value: String, val part: Float)
    val cpu = Row(R.drawable.ic_cpu, a.getString(R.string.w_cpu), f.cpu?.let { "${Math.round(it * 100)}%" } ?: "—", f.cpu ?: 0f)
    val ram = Row(R.drawable.ic_memory, a.getString(R.string.w_ram), pair(f.ram), part(f.ram))
    val disk = Row(R.drawable.ic_drive, a.getString(R.string.w_disk), pair(f.disk), part(f.disk))
    val rows = if (compact) listOf(cpu, ram) else listOf(cpu, ram, disk)
    val box = LinearLayout(a).apply { orientation = VERTICAL }
    for (r in rows) {
        val tone = if (r.part >= 0.85f) Tonal.BAD else if (r.part >= 0.7f) Tonal.WARN else Tonal.BAR
        val line = LinearLayout(a).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val top = LinearLayout(a).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(a).apply { setImageResource(r.icon); imageTintList = android.content.res.ColorStateList.valueOf(Tonal.INK) }, LayoutParams(dp(if (compact) 16 else 20), dp(if (compact) 16 else 20)))
        top.addView(ink(r.name, if (compact) 12f else 14f).apply { typeface = Font.medium }, LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        top.addView(Shrink(a, if (compact) 12f else 14f, 9f).apply {
            text = r.value; setTextColor(Tonal.INK); maxLines = 1; typeface = Font.medium; includeFontPadding = false; gravity = Gravity.END
        }, LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        line.addView(top, LayoutParams(-1, -2))
        line.addView(TonalBar(a, r.part, tone), LayoutParams(-1, dp(if (compact) 8 else 10)).apply { topMargin = dp(if (compact) 3 else 6) })
        box.addView(line, LayoutParams(-1, 0, 1f))
    }
    widget.addView(box, LayoutParams(-1, -1))
}

/**
 * Fills a tonal [widget] for widget [k]: the ONE place that says what each widget shows in the look, all through [tonalBody] (a label, a
 * figure, a caption, a badge) and [systemTonal]'s rows; a widget never paints its own tonal style. [w] is the column's width in px.
 */
internal fun Widgets.tonalFill(k: String, widget: LinearLayout, f: Facts, w: Int, compact: Boolean, label: (String) -> String, qrs: org.json.JSONObject) {
    when (k) {
        "system" -> systemTonal(widget, f, compact)
        "weather" -> weatherTonal(widget, f, w, compact)
        "air" -> airTonal(widget, f, w, compact)
        "lunar" -> f.moon?.let { lunarTonal(widget, it, w, compact) }
        "calendar" -> calendarTonal(widget, f, w, compact)
        "usage" -> usageTonal(widget, f, label, w, compact)
        "timer" -> timerTonal(widget, f.timer, w, compact)
        "market" -> marketTonal(widget, f, w, compact)
        "news" -> newsTonal(widget, f, w, compact)
        "recent" -> recentTonal(widget, f, label, w, compact)
        "adblock" -> adblockTonal(widget, f, w, compact)
        "prayer" -> prayerTonal(widget, f, w, compact)
        else -> if (k in HomeQr.KEYS) qrTonal(widget, qrs.optJSONObject(k), compact)
    }
}

/** Today: the weekday over the day's number, the date and the lunar date under it. */
internal fun Widgets.calendarTonal(widget: LinearLayout, f: Facts, width: Int, compact: Boolean) {
    val c = f.cal ?: return
    val r = a.resources; val loc = resources.locale0(); val now = java.util.Date()
    val d = c.moon
    val month = r.getStringArray(R.array.lunar_month)[d.month - 1] + if (d.leap) a.getString(R.string.lunar_leap) else ""
    val dayWord = if (d.day <= 10) a.getString(R.string.lunar_day_early, d.day) else d.day.toString()
    val caption = java.text.SimpleDateFormat("d MMMM", loc).format(now) + " · " + a.getString(R.string.lunar_title, dayWord, month)
    tonalBody(widget, java.text.SimpleDateFormat("EEEE", loc).format(now), java.text.SimpleDateFormat("d", loc).format(now), caption, R.drawable.ic_calendar, false, width, compact)
}

/** The week's screen time over the most used app. */
internal fun Widgets.usageTonal(widget: LinearLayout, f: Facts, appLabel: (String) -> String, width: Int, compact: Boolean) {
    val label = a.getString(R.string.w_week)
    val u = f.usage
    if (u == null) { tonalBody(widget, label, "—", a.getString(R.string.w_need_setup), R.drawable.ic_chart, false, width, compact); return }
    val days = u.optJSONArray("days"); var total = 0L
    if (days != null) for (i in 0 until days.length()) total += days.optLong(i)
    val top = u.optJSONArray("week")?.optJSONObject(0)
    val caption = top?.let { appLabel(it.optString("pkg")) + " · " + span(it.optLong("sec") * 1000) } ?: a.getString(R.string.w_none)
    tonalBody(widget, label, if (total > 0) span(total * 1000) else "—", caption, R.drawable.ic_chart, false, width, compact)
}

/** The sleep timer or bedtime: its time left over when it ends. */
internal fun Widgets.timerTonal(widget: LinearLayout, o: org.json.JSONObject?, width: Int, compact: Boolean) {
    val (title, sub) = timerLine(o) ?: return
    tonalBody(widget, a.getString(R.string.w_timer), sub.ifEmpty { "—" }, title, R.drawable.ic_timer, true, width, compact)
}

/** The first price big, the others in one line under it. */
internal fun Widgets.marketTonal(widget: LinearLayout, f: Facts, width: Int, compact: Boolean) {
    val m = f.market
    val l = if (m == null || m.empty) emptyList() else lines(m, opt("market", "show"), opt("market", "btc") == "on")
    if (l.isEmpty()) { tonalBody(widget, a.getString(R.string.w_market), "—", a.getString(R.string.mk_none), R.drawable.ic_gold, false, width, compact); return }
    val first = l[0]
    val chg = first.pct?.takeIf { Math.abs(it) >= 0.05 }?.let { (if (it > 0) " ▲" else " ▼") + String.format(java.util.Locale.getDefault(), "%.1f%%", Math.abs(it)) }.orEmpty()
    tonalBody(widget, first.name + chg, first.value, l.drop(1).take(if (compact) 1 else 2).joinToString("  ·  ") { it.name + " " + it.value }, R.drawable.ic_gold, false, width, compact)
}

/** One headline (the chosen one while the widget is focused, else turning every two minutes), its source and time under it. */
internal fun Widgets.newsTonal(widget: LinearLayout, f: Facts, width: Int, compact: Boolean) {
    val items = f.news
    if (items.isEmpty()) { tonalBody(widget, a.getString(R.string.w_news), "—", a.getString(R.string.nw_none), R.drawable.ic_news, true, width, compact); return }
    newsCount = minOf(items.size, 3); newsAt = newsAt.coerceIn(0, newsCount - 1)
    val i = if (newsFocused) newsAt else ((System.currentTimeMillis() / 120_000) % items.size).toInt()
    newsShown = i
    tonalBody(widget, a.getString(R.string.w_news), items[i].title, listOf(items[i].src, ago(items[i].at)).filter { it.isNotEmpty() }.joinToString(" · "), R.drawable.ic_news, true, width, compact, headline = true)
}

/** A QR widget: the code on its white square in the tonal widget, the caption and password in the widget's ink (the widget is the one tonal path, so no bespoke fill). */
internal fun Widgets.qrTonal(widget: LinearLayout, c: org.json.JSONObject?, compact: Boolean) {
    c ?: return
    widget.setPadding(dp(14), dp(14), dp(16), dp(14))
    val v = qrView(c, tonal = true) ?: return
    widget.addView(v, LinearLayout.LayoutParams(-1, -1))
}
