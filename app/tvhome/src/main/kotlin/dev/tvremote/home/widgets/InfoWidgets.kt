package dev.tvremote.home.widgets

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.VERTICAL
import dev.tvremote.home.Font
import dev.tvremote.home.R
import dev.tvremote.home.widgets.Widgets.Companion.DIM
import dev.tvremote.home.widgets.Widgets.Facts
import dev.tvremote.tvui.Motion
import java.util.Locale

/** The Market widget (gold, the bank's exchange rates, Bitcoin) and the News widget (RSS headlines), drawn like the other glass widgets. */

private val UP = 0xFF6BE8A6.toInt()
private val DOWN = 0xFFFF8A80.toInt()
private const val BADGE_A = 0x33000000
private const val CHIP_A = 0x26000000
private const val SHADOW = 0x80000000.toInt()
private const val THUMB = 0x26FFFFFF                                     // a picture's place while it loads

internal fun fmtVnd(v: Double): String = String.format(Locale.getDefault(), if (v >= 1000) "%,.0f" else "%.2f", v)
internal fun fmtMil(v: Long): String = String.format(Locale.getDefault(), "%.1f", v / 1_000_000.0)

internal const val GOLD_BAR = 0xFFF2C14E.toInt()
internal const val C_USD = 0xFF4CAF7A.toInt(); internal const val C_EUR = 0xFF5B8DEF.toInt(); internal const val C_JPY = 0xFFEF6B6B.toInt()
internal const val C_KRW = 0xFF7C8CF8.toInt(); internal const val C_CNY = 0xFFE5534B.toInt(); internal const val C_GBP = 0xFFB07CF2.toInt()
internal const val C_BTC = 0xFFF7931A.toInt()
internal const val C_FUEL = 0xFF5BB8E8.toInt()
internal val SYMBOL = mapOf("USD" to ("$" to C_USD), "EUR" to ("€" to C_EUR), "JPY" to ("¥" to C_JPY), "KRW" to ("₩" to C_KRW),
    "CNY" to ("¥" to C_CNY), "GBP" to ("£" to C_GBP), "BTC" to ("₿" to C_BTC))

/** A round badge: the line icon (gold) or a currency sign, tinted on a faint disc of the same colour. */
internal fun badgeView(c: android.content.Context, size: Int, icon: Int, sign: String, tint: Int): View {
    val box = android.widget.FrameLayout(c)
    box.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((tint and 0xFFFFFF) or BADGE_A) }
    if (icon != 0) box.addView(ImageView(c).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(tint) },
        android.widget.FrameLayout.LayoutParams(size * 5 / 9, size * 5 / 9, Gravity.CENTER))
    else box.addView(android.widget.TextView(c).apply { text = sign; setTextColor(tint); textSize = size / c.resources.displayMetrics.scaledDensity * 0.52f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false },
        android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    return box
}

/** The ▲ / ▼ chip of a change since yesterday. */
internal fun changeChip(c: android.content.Context, d: Float, pct: Double, size: Float): View = Font.on(android.widget.TextView(c)).apply {
    val up = pct >= 0; val col = if (up) UP else DOWN
    text = (if (up) "▲ " else "▼ ") + String.format(Locale.getDefault(), "%.1f%%", Math.abs(pct)); textSize = size; typeface = Font.medium; setTextColor(col); maxLines = 1; gravity = Gravity.CENTER
    setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
    background = GradientDrawable().apply { cornerRadius = 12 * d; setColor((col and 0xFFFFFF) or CHIP_A) }
}

/** One row of the widget: a badge, a muted name, the one figure in big tabular type on the right, and the change since yesterday. */
internal class Line(val kind: String, val key: String, val icon: Int, val sign: String, val tint: Int, val name: String, val value: String, val pct: Double?)

internal fun Widgets.marketLines(m: Market.Snap) = lines(m, opt("market", "show"), opt("market", "btc") == "on", opt("market", "fuel") == "on")

/** The fuel rows (RON 95, E5, diesel), VND per litre, each with its change since the price before (fuel moves about once a week). */
internal fun fuelRows(f: Market.Fuel): List<Triple<String, Int, Long>> =
    listOf(Triple("f_ron95", R.string.mk_ron95, f.ron95), Triple("f_e5", R.string.mk_e5, f.e5), Triple("f_do", R.string.mk_diesel, f.diesel)).filter { it.third > 0 }

internal fun Widgets.lines(m: Market.Snap, show: String, btc: Boolean, fuel: Boolean = false): List<Line> {
    val tr = a.getString(R.string.mk_tr)
    fun g(key: String, name: Int, sell: Long) = Line("gold", key, R.drawable.ic_gold, "", GOLD_BAR, a.getString(name), fmtMil(sell) + " " + tr, Market.trend(a, key, sell.toDouble()))
    val gold = if (show == "rates") emptyList() else m.gold?.let { listOf(g("sjc", R.string.mk_sjc, it.sjcSell), g("ring", R.string.mk_ring, it.ringSell)) }.orEmpty()
    val rates = if (show == "gold") emptyList() else m.rates.map { r ->
        val (sign, col) = SYMBOL[r.code] ?: (r.code to Color.WHITE)
        Line("rate", r.code, 0, sign, col, r.code, fmtVnd(r.sell) + " ₫", Market.trend(a, r.code, r.sell))
    }
    val coin = if (btc) m.btc?.let { b -> Line("btc", "BTC", 0, "₿", SYMBOL.getValue("BTC").second, "BTC", "$" + String.format(Locale.getDefault(), "%,.0f", b.usd), b.pct) }?.let { listOf(it) }.orEmpty() else emptyList()
    val oil = if (fuel) m.fuel?.let { f -> fuelRows(f).map { (k, n, v) -> Line("fuel", k, R.drawable.ic_fuel, "", C_FUEL, a.getString(n), fmtVnd(v.toDouble()) + " ₫", Market.sinceChange(a, k, v.toDouble())) } }.orEmpty() else emptyList()
    return gold + oil + rates.take(1) + coin + rates.drop(1)                    // gold, then fuel: the big size shows SJC, ring, RON 95, E5
}

private fun Widgets.updated(m: Market.Snap) = a.getString(R.string.mk_upd, hm(m.at))

private fun Widgets.priceRow(x: Line, big: Boolean, dense: Boolean = false) = LinearLayout(a).apply {
    orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    val bs = dp(if (dense) 24 else if (big) 30 else 24)                 // four rows share ~116 dp: a 34 dp disc each touched the next
    addView(badgeView(a, bs, x.icon, x.sign, x.tint), LayoutParams(bs, bs))
    val who = LinearLayout(a).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    who.addView(text(x.name, if (dense) 13f else if (big) 14f else 12f, DIM).apply { maxLines = 1; includeFontPadding = false })
    x.pct?.takeIf { Math.abs(it) >= 0.05 && big }?.let { who.addView(changeChip(a, a.resources.displayMetrics.density, it, if (dense) 9f else 10f).apply { if (dense) setPadding(dp(6), 0, dp(6), 0) }, LayoutParams(-2, -2).apply { topMargin = dp(if (dense) 1 else 2) }) }
    addView(who, LayoutParams(0, -2, 1f).apply { leftMargin = dp(if (big) 12 else 8) })
    addView(Font.on(android.widget.TextView(a)).apply {
        text = x.value; textSize = if (dense) 16f else if (big) 18f else 15f;   // a price is read, not the widget's headline (weather's 34°)
        typeface = Font.medium; setTextColor(Color.WHITE); maxLines = 1; gravity = Gravity.END
        fontFeatureSettings = "tnum"; includeFontPadding = false; setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), SHADOW)
    }, LayoutParams(-2, -2))
}

/** The widget at its full height: the title with the time of the last update, up to four prices, one figure each. */
internal fun Widgets.marketFull(widget: LinearLayout, f: Facts) {
    val m = f.market
    if (m == null || m.empty) { widget.addView(head(a.getString(R.string.w_market), "")); widget.addView(note(a.getString(R.string.mk_none))); return }
    widget.gravity = Gravity.CENTER_VERTICAL
    widget.addView(head(a.getString(R.string.w_market), hm(m.at)))
    val l = marketLines(m).take(4)
    val box = LinearLayout(a).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    for (x in l) box.addView(priceRow(x, true, l.size >= 4), LayoutParams(-1, 0, 1f))
    widget.addView(box, LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
}

/** Half the height, or a narrow column: two prices (gold, then RON 95 when fuel is on, else the dollar). */
internal fun Widgets.marketHalf(widget: LinearLayout, f: Facts) {
    val m = f.market
    widget.gravity = Gravity.CENTER_VERTICAL
    if (m == null || m.empty) { widget.addView(head(a.getString(R.string.w_market), "")); widget.addView(note(a.getString(R.string.mk_none))); return }
    val l = marketLines(m)
    val pick = (l.filter { it.kind == "gold" }.take(1) + l.filter { it.kind == "fuel" }.take(1) + l.filter { it.kind == "rate" }.take(1) + l.filter { it.kind == "btc" }).ifEmpty { l }.take(2)
    for ((i, x) in pick.withIndex()) widget.addView(priceRow(x, false), LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8) })
}

/** One scrolling line: every price after the other. The marquee runs only where motion is on and not calm (a weak box would redraw the widgets each frame). */
internal fun Widgets.marketTicker(widget: LinearLayout, f: Facts, big: Boolean) {
    val m = f.market
    widget.gravity = Gravity.CENTER_VERTICAL
    if (m == null || m.empty) { widget.addView(note(a.getString(R.string.mk_none))); return }
    val l = marketLines(m)
    val moving = Motion.on && !Motion.calm
    val sep = "   ·   "
    val parts = l.map { it.name + " " + it.value }
    val line = parts.joinToString(sep)
    val tv = text(if (moving) line + sep + line else line, if (big) 20f else 15f, Color.WHITE).apply {
        typeface = Font.medium
        if (!moving) addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->           // still: whole prices only, never one cut in the middle
            val t = v as android.widget.TextView; var n = parts.size
            while (n > 1 && t.paint.measureText(parts.take(n).joinToString(sep)) > t.width - t.paddingLeft - t.paddingRight) n--
            val want = parts.take(n).joinToString(sep); if (t.text.toString() != want && t.width > 0) t.text = want
        }
        if (moving) { ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true; isFocusable = false }
    }
    widget.addView(text(a.getString(R.string.w_market) + " · " + hm(m.at), 11f, DIM), LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
    widget.addView(tv, LayoutParams(-1, -2))
}

// ---- news ----

/** "5 min ago": the time of a headline in TV Home's words. */
internal fun Widgets.ago(at: Long): String {
    if (at <= 0) return ""
    val m = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
    return when { m < 60 -> a.getString(R.string.ago_m, maxOf(1, m).toInt()); m < 1440 -> a.getString(R.string.ago_h, (m / 60).toInt()); else -> a.getString(R.string.ago_d, (m / 1440).toInt()) }
}

private fun Widgets.thumb(url: String, w: Int, h: Int): View = ImageView(a).apply {
    scaleType = ImageView.ScaleType.CENTER_CROP
    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(THUMB) }
    clipToOutline = true
    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) } }
    if (url.isNotEmpty()) Pics.into(a, this, url, w)
}

private fun Widgets.byline(i: News.Item) = listOf(i.src, ago(i.at)).filter { it.isNotEmpty() }.joinToString(" · ")

/** The full widget: the picture of the chosen headline on the left, three headlines on the right (the chosen one bright, its source and time under it). */
internal fun Widgets.newsFull(widget: LinearLayout, f: Facts, width: Int) {
    val items = f.news
    widget.addView(head(a.getString(R.string.w_news), ""))
    if (items.isEmpty()) { widget.addView(note(a.getString(R.string.nw_none))); return }
    newsCount = minOf(items.size, 3); newsAt = newsAt.coerceIn(0, newsCount - 1); newsShown = newsAt
    val sel = newsAt
    val body = LinearLayout(a).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val pw = minOf(dp(120), (width - dp(40)) * 2 / 5)
    body.addView(thumb(items[sel].image, pw, pw), LayoutParams(pw, LayoutParams.MATCH_PARENT).apply { topMargin = dp(6); bottomMargin = dp(2) })
    val col = LinearLayout(a).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    for ((i, it) in items.take(3).withIndex()) {
        val on = i == sel
        col.addView(text(it.title, 13f, if (on) Color.WHITE else DIM).apply {
            maxLines = if (on) 3 else 1; if (on) typeface = Font.medium; ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(5) })
        if (on) col.addView(text(byline(it), 11f, DIM), LayoutParams(-1, -2).apply { topMargin = dp(1) })
    }
    body.addView(col, LayoutParams(0, -1, 1f).apply { leftMargin = dp(12) })
    widget.addView(body, LayoutParams(-1, 0, 1f))
}

/** Half the height: the picture, then the headline and its source (the headline turns every two minutes, the chosen one when the widget is focused). */
internal fun Widgets.newsHalf(widget: LinearLayout, f: Facts) {
    val items = f.news
    widget.gravity = Gravity.CENTER_VERTICAL
    if (items.isEmpty()) { widget.addView(head(a.getString(R.string.w_news), "")); widget.addView(note(a.getString(R.string.nw_none))); return }
    newsCount = minOf(items.size, 3); newsAt = newsAt.coerceIn(0, newsCount - 1)
    val i = if (newsFocused) newsAt else ((System.currentTimeMillis() / 120_000) % items.size).toInt()
    newsShown = i
    val row = LinearLayout(a).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    row.addView(thumb(items[i].image, dp(64), dp(64)), LayoutParams(dp(64), dp(56)))
    val col = LinearLayout(a).apply { orientation = VERTICAL }
    col.addView(text(items[i].title, 12f, Color.WHITE).apply { maxLines = 3; typeface = Font.medium })
    col.addView(text(byline(items[i]), 11f, DIM))
    row.addView(col, LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
    widget.addView(row, LayoutParams(-1, -2))
}
