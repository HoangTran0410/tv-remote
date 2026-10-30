package dev.tvremote.home.widgets

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.VERTICAL
import android.widget.TextView
import dev.tvremote.home.Font
import dev.tvremote.home.R
import dev.tvremote.home.Store
import dev.tvremote.home.widgets.Widgets.Companion.DIM
import dev.tvremote.home.widgets.Widgets.Companion.GREEN
import dev.tvremote.home.widgets.Widgets.Facts
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val NEXT_VEIL = 0x33FFFFFF                  // the pill under the next prayer on the glass

/** Today's prayers at the weather's place, the next one, and the Hijri date: worked out here ([Prayer]), once a minute with the other widgets, no network. */
class PrayerDay(val t: Prayer.Times, val next: Prayer.Next?, val hijri: Prayer.Hijri)

/** The facts of the prayer widget, or null while the weather has not found the place yet (the widget says so). */
internal fun Widgets.prayerFacts(): PrayerDay? {
    val place = Weather.place(a) ?: return null
    val method = Prayer.METHODS[opt("prayer", "method").let { if (it == "auto") Prayer.defaultMethod(TimeZone.getDefault().id, Locale.getDefault().country) else it }] ?: Prayer.METHODS.getValue("mwl")
    val hanafi = opt("prayer", "asr") == "hanafi"
    val now = Calendar.getInstance()
    fun day(c: Calendar): Prayer.Times {
        val noon = c.clone() as Calendar; noon.set(Calendar.HOUR_OF_DAY, 12)             // the zone's offset at midday: a day with a clock change is still one offset
        val tz = TimeZone.getDefault().getOffset(noon.timeInMillis) / 3_600_000.0
        return Prayer.times(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), place.lat, place.lon, tz, method, hanafi)
    }
    val today = day(now)
    val tomorrow = (now.clone() as Calendar).also { it.add(Calendar.DAY_OF_MONTH, 1) }
    val hours = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0
    val adjust = when (opt("prayer", "hijri")) { "hijri_m1" -> -1; "hijri_p1" -> 1; else -> 0 }
    return PrayerDay(today, Prayer.next(today, day(tomorrow), hours), Prayer.hijri(now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.MONTH) + 1, now.get(Calendar.YEAR), adjust))
}

private fun Widgets.prayerName(i: Int) = a.resources.getStringArray(R.array.p_names)[i]
private fun Widgets.hijriText(h: Prayer.Hijri) = a.getString(R.string.p_date, h.day, a.resources.getStringArray(R.array.p_months)[h.month - 1], h.year)
private fun Widgets.clock24() = Store.get(a).optBoolean("clock24", true)
private fun Widgets.left(n: Prayer.Next) = if (n.minutes >= 60) a.getString(R.string.p_left_hm, n.minutes / 60, n.minutes % 60) else a.getString(R.string.p_left_m, n.minutes)

/** The tonal look: the next prayer's time over its countdown and the Hijri date (the full size lists all six, see [prayerList]). */
internal fun Widgets.prayerTonal(widget: LinearLayout, f: Facts, width: Int, compact: Boolean) {
    val p = f.pray
    if (p == null) { tonalBody(widget, a.getString(R.string.w_prayer), "—", a.getString(R.string.p_none), R.drawable.ic_compass, false, width, compact); return }
    if (!compact) { prayerList(widget, p, true); return }
    val n = p.next
    if (n == null) { tonalBody(widget, a.getString(R.string.w_prayer), "—", hijriText(p.hijri), R.drawable.ic_compass, false, width, compact); return }
    tonalBody(widget, a.getString(R.string.p_next, prayerName(n.index)), Prayer.clock(p.t.all()[n.index], clock24()), left(n) + " · " + hijriText(p.hijri), R.drawable.ic_compass, false, width, compact)
}

/** The glass look: the full widget lists the six times; the half-height one is a green square, the next prayer's time and its countdown. */
internal fun Widgets.prayerGlass(widget: LinearLayout, f: Facts, full: Boolean) {
    val p = f.pray
    widget.gravity = Gravity.CENTER_VERTICAL
    if (p == null) { if (full) widget.addView(head(a.getString(R.string.w_prayer), "")); widget.addView(note(a.getString(R.string.p_none))); return }
    if (full) { prayerList(widget, p, false); return }
    val n = p.next
    val row = LinearLayout(a).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    row.addView(tile(GREEN, R.drawable.ic_compass), LayoutParams(dp(44), dp(44)))
    val words = LinearLayout(a).apply { orientation = VERTICAL }
    words.addView(text(if (n == null) "—" else Prayer.clock(p.t.all()[n.index], clock24()), 24f, Color.WHITE).apply { typeface = Font.medium })
    words.addView(text(if (n == null) hijriText(p.hijri) else a.getString(R.string.p_next, prayerName(n.index)), 13f, Color.WHITE))
    if (n != null) words.addView(text(left(n), 12f, DIM))
    row.addView(words, LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
    widget.addView(row, LayoutParams(-1, -2))
}

/**
 * The full size, both looks: the next prayer and the time left on top, the six times in two columns (the next one on a pale pill),
 * the Hijri date under them. [tonal]: the dark ink on the warm card, else white on the glass.
 */
private fun Widgets.prayerList(widget: LinearLayout, p: PrayerDay, tonal: Boolean) {
    val main = if (tonal) Tonal.INK else Color.WHITE; val sub = if (tonal) Tonal.SUB else DIM
    fun label(t: String, size: Float, color: Int): TextView = if (tonal) ink(t, size, color) else text(t, size, color)
    val n = p.next
    widget.addView(label(if (n == null) a.getString(R.string.w_prayer) else a.getString(R.string.p_next, prayerName(n.index)) + " · " + left(n), 14f, sub), LayoutParams(-1, -2))
    val grid = LinearLayout(a).apply { orientation = VERTICAL }
    val times = p.t.all(); val h24 = clock24()
    for (r in 0 until 3) {
        val line = LinearLayout(a).apply { orientation = HORIZONTAL }
        for (c in 0 until 2) {
            val i = r * 2 + c
            val hot = n != null && n.index == i
            val cell = LinearLayout(a).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0)
                if (hot) background = GradientDrawable().apply { setColor(if (tonal) Tonal.PALE else NEXT_VEIL); cornerRadius = dp(10).toFloat() }
            }
            cell.addView(label(prayerName(i), 14f, if (hot) main else sub).apply { if (hot) typeface = Font.medium }, LayoutParams(0, -2, 1f))
            cell.addView(label(Prayer.clock(times[i], h24), 15f, main).apply { typeface = Font.medium }, LayoutParams(-2, -2))
            line.addView(cell, LayoutParams(0, -1, 1f).apply { if (c > 0) leftMargin = dp(8) })
        }
        grid.addView(line, LayoutParams(-1, 0, 1f))
    }
    widget.addView(grid, LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(2) })
    widget.addView(label(hijriText(p.hijri), 13f, sub), LayoutParams(-1, -2))
}
