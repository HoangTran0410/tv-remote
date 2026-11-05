package dev.tvremote.home.widgets

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tvremote.home.Font
import dev.tvremote.home.R
import dev.tvremote.tvui.TvGlass
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

private const val DIMC = 0xA6FFFFFF.toInt()
private const val INK = 0xFF0B0E14.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val CHIP_ON = 0x4D4F7CFF                                   // a layer chip in use: a blue tint with a blue edge
private const val CHIP_REST = 0x1FFFFFFF
private const val EDGE = 0xFF62D2FF.toInt()
private const val BODY = 0xE6FFFFFF.toInt()

private const val CHIP_FOCUS_INK = 0xFF0B0E14.toInt()

private fun Activity.pillBg(focused: Boolean, on: Boolean) = GradientDrawable().apply {
    val d = resources.displayMetrics.density
    cornerRadius = 22 * d; setColor(if (focused) WHITE else if (on) CHIP_ON else CHIP_REST)
    if (on && !focused) setStroke((2 * d).toInt(), EDGE)
}

/** A focusable text button with a line icon: a faint pill, a bright one with the focus, a blue-edged one while [on] (the one choice in use). */
internal fun Activity.pillButton(label: String, on: Boolean = false, icon: Int = 0, start: Boolean = false, click: () -> Unit): TextView {
    val d = resources.displayMetrics.density
    val ico = if (icon != 0) resources.getDrawable(icon).mutate().also { it.setBounds(0, 0, (22 * d).toInt(), (22 * d).toInt()) } else null
    fun paint(v: TextView, f: Boolean) {
        v.background = pillBg(f, v.tag == true)
        val ink = if (f) CHIP_FOCUS_INK else Color.WHITE
        v.setTextColor(ink); ico?.setTint(ink)
    }
    return Font.on(TextView(this)).apply {
        tag = on
        text = label; textSize = 16f; typeface = Font.medium; gravity = if (start) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER; setSingleLine(true)
        isFocusable = true; isClickable = true
        if (ico != null) { setCompoundDrawables(ico, null, null, null); compoundDrawablePadding = (12 * d).toInt() }
        setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
        paint(this, false)
        setOnFocusChangeListener { v, f -> paint(v as TextView, f) }
        setOnClickListener { click() }
    }
}

/** An icon-only [pillButton] (a row of them takes the room of one labelled button); [name] is read out and shown by whoever lists it. */
internal fun Activity.iconPill(icon: Int, name: String, on: Boolean = false, click: () -> Unit): ImageView {
    val d = resources.displayMetrics.density
    return ImageView(this).apply {
        tag = on; contentDescription = name
        setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = (12 * d).toInt(); setPadding(p, p, p, p)
        isFocusable = true; isClickable = true
        fun paint(f: Boolean) { background = pillBg(f, tag == true); setColorFilter(if (f) CHIP_FOCUS_INK else Color.WHITE) }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { click() }
    }
}

/** The one on-state of a [pillButton], kept in step with what it chooses. */
internal fun Activity.setPillOn(v: View, on: Boolean) {
    v.tag = on
    v.background = pillBg(v.isFocused, on)
}

private fun Activity.dpx(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

private fun Activity.roundedPic(): ImageView = ImageView(this).apply {
    scaleType = ImageView.ScaleType.CENTER_CROP
    background = GradientDrawable().apply { cornerRadius = dpx(16).toFloat(); setColor(CHIP_REST) }
    clipToOutline = true
    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dpx(16).toFloat()) } }
}

private fun Activity.label(t: String, size: Float, color: Int, bold: Boolean = false) = Font.on(TextView(this)).apply {
    text = t; textSize = size; setTextColor(color); if (bold) typeface = Font.bold
}

/**
 * The reader of one headline, from the News widget: the picture, the source and time, the title and the summary the feed gave in big
 * text, the next headlines to jump to, "previous / next" and "Open in browser" (only where an app answers a web link). The article
 * itself is never fetched here. A glass panel on the dark screen, as Customize.
 */
class NewsActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(dev.tvremote.home.Lang.wrap(base))
    private var i = 0
    private var items: List<News.Item> = emptyList()
    private lateinit var root: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        items = News.cached(this)
        i = intent.getIntExtra("i", 0).coerceIn(0, maxOf(0, items.size - 1))
        if (items.isEmpty()) { finish(); return }
        root = LinearLayout(this).apply { setBackgroundColor(INK) }
        setContentView(root)
        draw()
    }

    private fun draw() {
        val it = items[i]
        root.removeAllViews()
        root.orientation = LinearLayout.HORIZONTAL
        root.setPadding(dpx(48), dpx(32), dpx(48), dpx(32))
        val pic = roundedPic()
        if (it.image.isNotEmpty()) Pics.into(this, pic, it.image, dpx(640))
        root.addView(pic, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = TvGlass.panelBg(this@NewsActivity); setPadding(dpx(28), dpx(22), dpx(28), dpx(22)); clipToPadding = false }
        val ago = it.at.takeIf { t -> t > 0 }?.let { t -> SimpleDateFormat("HH:mm · d/M", Locale.getDefault()).format(t) }
        val who = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        who.addView(badgeView(this, dpx(40), R.drawable.ic_news, "", EDGE), LinearLayout.LayoutParams(dpx(40), dpx(40)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label(it.src.ifEmpty { getString(R.string.w_news) }, 18f, Color.WHITE, true).apply { setSingleLine(true) })
        if (ago != null) names.addView(label(ago, 14f, DIMC))
        who.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpx(12) })
        who.addView(label("${i + 1} / ${items.size}", 14f, DIMC))
        col.addView(who)
        col.addView(label(it.title, 28f, Color.WHITE, true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpx(14), 0, dpx(8)) })
        val sum = android.widget.FrameLayout(this)
        val body = label(it.summary.ifEmpty { it.link }, 19f, BODY).apply { setLineSpacing(0f, 1.12f); ellipsize = TextUtils.TruncateAt.END }
        sum.addView(body)
        sum.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->      // whole lines only: the room is cut to a line count, the last one ends in "…" (a half line at the edge read as a fault)
            val n = ((b - t) / body.lineHeight).coerceAtLeast(1)
            if (body.maxLines != n) body.maxLines = n
        }
        col.addView(sum, LinearLayout.LayoutParams(-1, 0, 1f))
        var first: View? = null
        val rest = (1..2).map { n -> (i + n) % items.size }.filter { n -> n != i }.distinct()
        if (rest.isNotEmpty()) {
            col.addView(label(getString(R.string.rd_more), 13f, DIMC).apply { setPadding(0, dpx(8), 0, dpx(4)) })
            for (n in rest) {
                val h = items[n]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                    background = TvGlass.rowBg(this@NewsActivity, 16); setPadding(dpx(8), dpx(4), dpx(14), dpx(4))
                    setOnClickListener { i = n; draw() }
                }
                val th = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = dpx(8).toFloat(); setColor(CHIP_REST) }
                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dpx(8).toFloat()) } }
                }
                if (h.image.isNotEmpty()) Pics.into(this, th, h.image, dpx(120))
                row.addView(th, LinearLayout.LayoutParams(dpx(60), dpx(40)))
                row.addView(label(h.title, 14f, Color.WHITE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpx(12) })
                col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpx(5) })
            }
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpx(10), 0, 0); clipChildren = false; clipToPadding = false }
        val gap = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpx(12) }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(it.link))
        if (view.resolveActivity(packageManager) != null)
            bar.addView(pillButton(getString(R.string.rd_open), true, R.drawable.ic_external) { try { startActivity(view) } catch (e: Exception) { } }.also { f -> first = f }, gap)
        if (i > 0) bar.addView(pillButton(getString(R.string.rd_prev), false, R.drawable.ic_left) { i--; draw() }.also { f -> first = first ?: f }, gap)
        if (i < items.size - 1) bar.addView(pillButton(getString(R.string.rd_next), false, R.drawable.ic_right) { i++; draw() }.also { f -> first = first ?: f }, gap)
        col.addView(bar)
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 6f).apply { leftMargin = dpx(28) })
        (first ?: col.getChildAt(0))?.requestFocus()
    }
}

/**
 * The Market widget's detail, on OK: every price with buy and sell (fuel: one retail price per litre), the time of the last update and the sources. The widget shows one
 * figure per row; this is where both live.
 */
class MarketActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(dev.tvremote.home.Lang.wrap(base))

    private fun cell(t: String, w: Int, size: Float, color: Int, bold: Boolean = false) = label(t, size, color, bold).apply {
        gravity = Gravity.END; setSingleLine(true); fontFeatureSettings = "tnum"; layoutParams = LinearLayout.LayoutParams(dpx(w), -2)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        setContentView(root)
        val m = Market.cached(this)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = TvGlass.panelBg(this@MarketActivity); setPadding(dpx(32), dpx(22), dpx(32), dpx(20)) }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }          // the rows: they scroll when a long list does not fit
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(badgeView(this, dpx(44), R.drawable.ic_banknote, "", EDGE), LinearLayout.LayoutParams(dpx(44), dpx(44)))
        head.addView(label(getString(R.string.w_market), 26f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpx(14) })
        if (m != null) head.addView(label(getString(R.string.mk_upd, SimpleDateFormat("HH:mm", Locale.getDefault()).format(m.at)), 15f, DIMC))
        outer.addView(head)
        val tr = getString(R.string.mk_tr)
        fun line(icon: Int, sign: String, tint: Int, name: String, buy: String, sell: String, pct: Double?) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpx(12), dpx(4), dpx(12), dpx(4)); background = TvGlass.glass(this@MarketActivity, 16, TvGlass.ROW_FILL, TvGlass.ROW_EDGE) }
            r.addView(badgeView(this, dpx(32), icon, sign, tint), LinearLayout.LayoutParams(dpx(32), dpx(32)))
            r.addView(label(name, 18f, Color.WHITE).apply { setSingleLine(true) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpx(14) })
            r.addView(cell(buy, 130, 19f, DIMC)); r.addView(cell(sell, 130, 21f, Color.WHITE, true))
            r.addView(FrameLayout(this).apply { pct?.takeIf { Math.abs(it) >= 0.05 }?.let { addView(changeChip(this@MarketActivity, resources.displayMetrics.density, it, 13f), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL)) } }, LinearLayout.LayoutParams(dpx(96), -2).apply { leftMargin = dpx(8) })
            panel.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpx(6) })
        }
        var src: View? = null
        if (m == null || m.empty) panel.addView(label(getString(R.string.mk_none), 18f, DIMC).apply { setPadding(0, dpx(24), 0, dpx(24)) })
        else {
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpx(12), dpx(16), dpx(12), 0) }
            cols.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            cols.addView(cell(getString(R.string.mk_buy), 130, 14f, DIMC)); cols.addView(cell(getString(R.string.mk_sell), 130, 14f, DIMC))
            cols.addView(View(this), LinearLayout.LayoutParams(dpx(104), 1))
            outer.addView(cols)
            m.gold?.let { g ->
                line(R.drawable.ic_gold, "", GOLD_BAR, getString(R.string.mk_sjc), fmtMil(g.sjcBuy) + " " + tr, fmtMil(g.sjcSell) + " " + tr, Market.trend(this, "sjc", g.sjcSell.toDouble()))
                line(R.drawable.ic_gold, "", GOLD_BAR, getString(R.string.mk_ring), fmtMil(g.ringBuy) + " " + tr, fmtMil(g.ringSell) + " " + tr, Market.trend(this, "ring", g.ringSell.toDouble()))
            }
            fun rate(r: Market.Rate) { val s = SYMBOL[r.code]
                line(0, s?.first ?: r.code, s?.second ?: Color.WHITE, r.code, if (r.buy > 0) fmtVnd(r.buy) + " ₫" else "", fmtVnd(r.sell) + " ₫", Market.trend(this, r.code, r.sell)) }
            val fuel = m.fuel?.takeIf { dev.tvremote.home.HomeConfig.optValue(dev.tvremote.home.Store.get(this), "market", "fuel") == "on" }   // the widget's "fuel" switch
            fuel?.let { f ->
                panel.addView(label(getString(R.string.mk_fuel), 14f, DIMC).apply { setPadding(dpx(12), dpx(10), 0, 0) })
                for ((k, n, v) in fuelRows(f)) line(R.drawable.ic_fuel, "", C_FUEL, getString(n), "", fmtVnd(v.toDouble()) + " ₫", Market.sinceChange(this, k, v.toDouble()))
                if (m.rates.isNotEmpty() || m.btc != null) panel.addView(label(getString(R.string.home_ov_rates), 14f, DIMC).apply { setPadding(dpx(12), dpx(10), 0, 0) })   // the rates below are not fuel
            }
            m.rates.take(1).forEach { rate(it) }
            m.btc?.let { c -> line(0, "₿", SYMBOL.getValue("BTC").second, "BTC", "", "$" + String.format(Locale.getDefault(), "%,.0f", c.usd), c.pct) }
            m.rates.drop(1).forEach { rate(it) }
            val srcs = listOfNotNull(m.gold?.src?.takeIf { it.isNotEmpty() }, Sources.now.rates?.name?.takeIf { m.rates.isNotEmpty() && it.isNotEmpty() }, if (m.btc != null) Sources.now.crypto?.name else null, fuel?.src?.takeIf { it.isNotEmpty() }).distinct()
            if (srcs.isNotEmpty()) src = label(getString(R.string.mk_src, srcs.joinToString(" · ")), 14f, DIMC).apply { setPadding(dpx(4), dpx(12), 0, 0) }
        }
        panel.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->                    // a row that does not fit whole is left out, never half shown
            val g = v as LinearLayout; var room = g.height
            for (n in 0 until g.childCount) { val c = g.getChildAt(n); c.measure(View.MeasureSpec.makeMeasureSpec(g.width, View.MeasureSpec.EXACTLY), 0)
                room -= c.measuredHeight + ((c.layoutParams as? LinearLayout.LayoutParams)?.topMargin ?: 0)
                val vis = if (room >= 0) View.VISIBLE else View.GONE; if (c.visibility != vis) c.visibility = vis }
        }
        outer.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f).apply { }.also { })
        src?.let { outer.addView(it) }
        val close = pillButton(getString(R.string.pq_close), false, R.drawable.ic_close) { finish() }
        outer.addView(close, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpx(12) })
        root.addView(outer, FrameLayout.LayoutParams(dpx(760), -1, Gravity.CENTER).apply { topMargin = dpx(24); bottomMargin = dpx(24) })
        close.requestFocus()
    }

}

/**
 * The weather map, from the weather widget: Windy's own embedded map at the widget's place, with the layer to look at (rain, wind,
 * temperature, satellite (its clouds too), PM2.5) and the next days beside it, in a glass panel with the widget's own sky icons. Back closes. Needs the
 * internet, and says so before it shows a blank page.
 */
class WeatherMapActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(dev.tvremote.home.Lang.wrap(base))
    private var web: WebView? = null
    private var lat = 0.0; private var lon = 0.0
    private val layers = listOf(Triple("rain", R.string.map_rain, R.drawable.ic_rain), Triple("wind", R.string.map_wind, R.drawable.ic_wind), Triple("temp", R.string.map_temp, R.drawable.ic_thermometer),
        Triple("satellite", R.string.map_satellite, R.drawable.ic_satellite), Triple("pm2p5", R.string.map_pm, R.drawable.ic_gauge))
    private val chips = HashMap<String, ImageView>()
    private var named: TextView? = null                                         // under the icons: the focused one's name, else the layer shown
    private var loading: View? = null
    private var layer = "rain"; private var ready = false; private var started = false
    private var failed = false; private var spin: View? = null; private var said: TextView? = null   // the main frame did not load: the overlay says so (Chromium's own error page is black-on-dark) and the page is asked for again
    private val retry = Runnable { if (!isDestroyed && failed) { failed = false; started = false; show(layer) } }

    private fun online(): Boolean { @Suppress("DEPRECATION") val n = (getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetworkInfo; return n != null && n.isConnected }

    /** The map cannot be had: the overlay stays over the page with one readable line; asked again in 10 s (the network may be back). */
    private fun fail() {
        failed = true; ready = false
        spin?.visibility = View.GONE; said?.text = getString(R.string.map_offline); loading?.visibility = View.VISIBLE
        loading?.removeCallbacks(retry); loading?.postDelayed(retry, 10_000)
    }

    /** Windy keeps its state in `W.store`: wind particles off (a canvas redrawn every frame is what makes a weak box lag). */
    private fun applyLight() { web?.evaluateJavascript("try{W.store.set('particlesAnim','off')}catch(e){}", null) }
    /** A layer is a store value of the open page: no second page load (Windy's ~300 KB of scripts + tiles again). */
    private fun setLayer(l: String) { web?.evaluateJavascript("try{W.store.set('overlay','$l')}catch(e){}", null) }
    /** Presses Windy's own play button under the map (the map never takes the focus, so the remote cannot reach it); [then]: playing now (its class `off` = paused; it stops by itself at the last hour). */
    private fun togglePlay(then: (Boolean) -> Unit) { web?.evaluateJavascript("(function(){var b=document.getElementById('playpause');if(!b)return false;b.click();return !b.classList.contains('off')})()") { then(it == "true") } }
    private var play: ImageView? = null
    private fun nameNow() { named?.text = (currentFocus as? ImageView)?.contentDescription ?: getString(layers.first { it.first == layer }.second) }

    private fun url(layer: String) = "https://embed.windy.com/embed2.html?lat=$lat&lon=$lon&detailLat=$lat&detailLon=$lon&width=900&height=700&zoom=7&level=surface" +
        "&overlay=$layer&product=ecmwf&menu=&message=true&marker=true&calendar=now&pressure=&type=map&location=coordinates&detail=&metricWind=km%2Fh&metricTemp=%C2%B0C&radarRange=-1"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { setBackgroundColor(INK); setPadding(dpx(48), dpx(32), dpx(48), dpx(32)) }
        setContentView(root)
        val place = try { JSONObject(getSharedPreferences("weather", MODE_PRIVATE).getString("place", "") ?: "") } catch (e: Exception) { null }
        if (!dev.tvremote.tvui.NoWebView.ok(this)) { dev.tvremote.tvui.NoWebView.show(this) { recreate() }; return }   // switched off on this TV (80296222): TV Setup turns it back on
        if (place == null || !place.has("lat")) {
            root.gravity = Gravity.CENTER
            root.addView(label(getString(R.string.map_none), 22f, DIMC).apply { gravity = Gravity.CENTER })
            return
        }
        lat = place.optDouble("lat"); lon = place.optDouble("lon")
        val wv = dev.tvremote.tvui.WebEnd.made(WebView(this)).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            isFocusable = false; isFocusableInTouchMode = false                        // the D-pad stays on the chips: a map would swallow it
            setBackgroundColor(INK)
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) { if (r.isForMainFrame) fail() }
                override fun onPageFinished(v: WebView?, u: String?) {
                    if (failed) return                                                          // Chromium's error page also "finishes"
                    ready = true; applyLight(); if (layer != "rain") setLayer(layer)           // a layer chosen before the page was up
                    loading?.postDelayed({ loading?.visibility = View.GONE }, 2500)            // the map data comes ~1-2 s after the page
                }
                override fun onLoadResource(v: WebView?, u: String?) {
                    if (u != null && u.contains("/im/v3.0/forecast/")) loading?.visibility = View.GONE      // first weather picture: the map is real now
                }
                override fun shouldInterceptRequest(v: WebView?, r: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val u = r?.url?.toString() ?: return null                                  // Windy's analytics and favicon: requests that show nothing
                    return if (u.contains("/sedlina/ga/") || u.endsWith("/favicon.ico")) android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) else null
                }
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dpx(16).toFloat()) } }
        }
        web = wv
        val box = FrameLayout(this)
        box.addView(wv, FrameLayout.LayoutParams(-1, -1))
        loading = LinearLayout(this).apply {                                         // Windy takes ~10 s: say so over the grey box
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(INK)
            spin = android.widget.ProgressBar(this@WeatherMapActivity).also { addView(it) }
            said = label(getString(R.string.map_loading), 18f, DIMC).apply { gravity = Gravity.CENTER; setPadding(dpx(24), dpx(12), dpx(24), 0) }.also { addView(it) }
        }
        box.addView(loading, FrameLayout.LayoutParams(-1, -1))
        root.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 7f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = TvGlass.panelBg(this@WeatherMapActivity); setPadding(dpx(16), dpx(16), dpx(16), dpx(12)); clipChildren = false; clipToPadding = false }
        val fc = Weather.cached(this)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (fc != null) head.addView(Sky(this, fc.kind, fc.night), LinearLayout.LayoutParams(dpx(52), dpx(52)))
        val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (fc != null) who.addView(label("${fc.temp}°", 34f, Color.WHITE, true).apply { includeFontPadding = false })
        who.addView(label(place.optString("name"), 16f, if (fc != null) DIMC else Color.WHITE, fc == null).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        head.addView(who, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (fc != null) dpx(10) else 0 })
        col.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpx(12) })
        // icons only, 3 to a row, and one line naming the focused one (or the layer shown): five labelled buttons left the days one row
        val pills = ArrayList<ImageView>()
        for ((k, w, ic) in layers) iconPill(ic, getString(w), k == "rain") { show(k) }.also { chips[k] = it; pills.add(it) }
        play = iconPill(R.drawable.ic_play, getString(R.string.map_play)) {
            if (ready) togglePlay { on ->
                play?.let { it.setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play); it.contentDescription = getString(if (on) R.string.map_pause else R.string.map_play); setPillOn(it, on) }
                nameNow()
            }
        }.also { pills.add(it) }
        for (row in pills.chunked(3)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { n, v -> r.addView(v, LinearLayout.LayoutParams(0, dpx(52), 1f).apply { if (n > 0) leftMargin = dpx(8) }) }
            col.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpx(8) })
        }
        for (v in pills) v.onFocusChangeListener.let { was -> v.setOnFocusChangeListener { x, f -> was?.onFocusChange(x, f); x.post { nameNow() } } }
        named = label("", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
        col.addView(named, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpx(8) })
        val days = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = true; setPadding(dpx(6), dpx(6), dpx(6), 0) }
        col.addView(days, LinearLayout.LayoutParams(-1, 0, 1f))
        days.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->                // a row that does not fit whole is hidden, never half shown
            val g = v as LinearLayout; var room = g.height - g.paddingTop
            for (n in 0 until g.childCount) { val c = g.getChildAt(n); c.measure(View.MeasureSpec.makeMeasureSpec(g.width - g.paddingLeft - g.paddingRight, View.MeasureSpec.EXACTLY), 0); room -= c.measuredHeight
                val vis = if (room >= 0) View.VISIBLE else View.GONE; if (c.visibility != vis) c.visibility = vis }
        }
        if (fc != null) {
            val fmtIn = SimpleDateFormat("yyyy-MM-dd", Locale.US); val fmtOut = SimpleDateFormat("EEE d/M", Locale.getDefault())
            for (day in fc.days) {
                val name = try { fmtOut.format(fmtIn.parse(day.date)!!) } catch (e: Exception) { day.date }
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpx(3), 0, dpx(3)) }
                r.addView(label(name, 15f, BODY).apply { setSingleLine(true) }, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(Sky(this, day.kind, false), LinearLayout.LayoutParams(dpx(30), dpx(30)).apply { leftMargin = dpx(6); rightMargin = dpx(8) })
                r.addView(label("${day.max}° / ${day.min}°", 15f, Color.WHITE, true).apply { setSingleLine(true); fontFeatureSettings = "tnum" })
                days.addView(r)
            }
        }
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f).apply { leftMargin = dpx(24) })
        show("rain")
        chips["rain"]?.requestFocus()
    }

    private fun show(l: String) {
        for ((k, c) in chips) setPillOn(c, k == l)
        layer = l; nameNow()
        if (ready) setLayer(l) else if (!started) {
            started = true
            if (online()) { failed = false; spin?.visibility = View.VISIBLE; said?.text = getString(R.string.map_loading); loading?.visibility = View.VISIBLE; web?.loadUrl(url(l)) } else fail()
        }   // before the page is up onPageFinished applies `layer`
    }

    override fun onDestroy() { loading?.removeCallbacks(retry); dev.tvremote.tvui.WebEnd.end(web); super.onDestroy() }   // the renderer too, unless the wall still uses it
}
