package dev.tvremote.home.widgets

import dev.tvremote.home.locale0
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import dev.tvremote.home.Font
import dev.tvremote.home.ui.Frost
import dev.tvremote.home.DIM
import dev.tvremote.home.Store
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.HomeQr
import dev.tvremote.home.R

/**
 * The widgets between the clock and the favourites (HomeConfig "widgets"), only to look at: no focus stops on them. Read off the
 * main thread once a minute while the screen is shown ([HomeActivity]), the network graph every [SAMPLE_MS]; nothing runs
 * while an app is in front.
 *   system  memory, storage and the Wi-Fi signal as rings, the network's speed, the address, ADB, time on
 *   usage   a column per day of the last week, the most used apps with their share, today's app limits: from TV Setup (its
 *           HomeInfo provider), the count the limits themselves hold to
 *   weather  the sky now, today's low and high and the next days ([Weather]: Open-Meteo, asked on a thread of its own, the last answer kept)

 *   air      the air quality index and PM2.5 ([Weather.Air]: Open-Meteo's air-quality API, same place and cache as the weather)
 *   calendar the month, today a filled circle, a lunar day only on its 1st and 15th ([Month], one View; the lunar days from [Lunar], once a day)
 * every widget but the clock also has the "tonal" look (HomeConfig "wopt" look, HomeConfig.TONAL, drawn only by [Tonal] / tonalFill).
 * and, smaller, stacked in a column beside them:
 *   clock   an analog clock: a face (classic, minimal, roman) in a theme (dark, light) (HomeConfig "wopt"); a face in a stack, standing, or big with the time beside it
 *   lunar   the lunar date, the next full moon or new month, the days to Tết ([Lunar], worked out here)
 *   timer   the sleep timer or bedtime and the time left (TV Setup's rules, through the same provider); none while neither is set
 *   prayer  the five prayers and the sunrise of today at the weather's place, the next one with its countdown, the Hijri date ([Prayer], offline)
 *   note    the family's note (HomeConfig "note"), while there is one
 *   qr1..4  QR widgets (HomeConfig "qrs", [QrView]): big by default, the glass widget with only the code on white
 */
class Widgets(internal val a: Activity, internal val dp: (Number) -> Int) : LinearLayout(a) {
    private var cpuMiss = 0

    /** What a widget needs, gathered on a background thread. */
    class Facts(val ram: Pair<Long, Long>, val disk: Pair<Long, Long>, val ip: String, val eth: Boolean, val rssi: Int?, val up: Long,
                val adb: Boolean, val usage: JSONObject?, val icons: Map<String, Drawable>, val timer: JSONObject?, val moon: Moon?, val cpu: Float?, val cores: Int, val cal: Cal? = null, val wx: Weather.Forecast? = null,
                val link: Int? = null, val dns: String? = null, val adbPort: Int = 0, val cpuWhy: Int = 0, val air: Weather.Air? = null,
                val market: Market.Snap? = null, val news: List<News.Item> = emptyList(), val recent: JSONObject? = null, val pray: PrayerDay? = null)

    /** Today in the lunar calendar and the days until the next full moon (15th), new month (1st) and Tết. */
    class Moon(val day: Lunar.Day, val full: Int, val new: Int, val tet: Int, val hol: Holidays.Next? = null)

    /** The month shown: its grid, today's cell, and today's lunar date (the title's). */
    class Cal(val grid: MonthGrid.Grid, val today: Int, val moon: Lunar.Day)

    companion object {
        const val SAMPLE_MS = 2000L
        internal const val POINTS = 40                                  // 80 s of the network graph
        internal val CYAN = intArrayOf(0xFF62D2FF.toInt(), 0xFF4F7CFF.toInt())
        internal val VIOLET = intArrayOf(0xFFC59BFF.toInt(), 0xFF7B5CFF.toInt())
        internal val GREEN = intArrayOf(0xFF6BE8A6.toInt(), 0xFF22B07D.toInt())
        internal val AMBER = intArrayOf(0xFFFFD27A.toInt(), 0xFFFF8A4C.toInt())
        internal val RED = intArrayOf(0xFFFF8A80.toInt(), 0xFFFF453A.toInt())
        /** A ring or bar of RAM / storage: green, amber from 70 %, red from 85 % ([HomeConfig.level], also TV Setup's RAM live widget). */
        internal fun levelColors(part: Float) = when (HomeConfig.level(Math.round(part * 100))) { "bad" -> RED; "warn" -> AMBER; else -> GREEN }
        internal val GREY = intArrayOf(0xFF8A94A6.toInt(), 0xFF566074.toInt())      // the ad-blocking shield while off
        internal const val MARK = 0x22FFFFFF                           // the veil under the recent-apps widget's marked icon
        internal val ROSE = intArrayOf(0xFFFF7A7A.toInt(), 0xFFE0457B.toInt())
        internal const val DIM = 0xA6FFFFFF.toInt()
        private const val HOT_FILL = 0x14FFFFFF                         // a focused widget (weather, news): a faint veil under its white ring
        private const val HOT_FILL_EDGE = 0x80FFFFFF.toInt()              // a widget's outline in edit mode
        internal const val ACCENT = 0xFFFF453A.toInt()                  // the calendar's red: the month's name and today
        internal val MEDIUM get() = Font.medium
        internal val BOLD get() = Font.bold
        private val BIG = setOf("system", "usage", "calendar", "weather", "air", "market", "news", "recent", "adblock", "prayer")
        const val BIG_H = 180                                          // dp: the widgets' height, fixed: the favourites stay on screen
        private const val FULL_MIN = 180                              // dp: a big widget in a column narrower than this shows its small form (the row of three big widgets gives 190 to 260)

        private var calKey = 0; private var calMemo: Cal? = null

        /** The month of [now]: worked out once a day, the minute's refresh takes the memo. */
        fun month(now: Calendar, first: Int): Cal {
            val key = (now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)) * 40 + now.get(Calendar.DAY_OF_MONTH) + first * 100000000
            calMemo?.let { if (key == calKey) return it }
            val y = now.get(Calendar.YEAR); val m = now.get(Calendar.MONTH) + 1; val d = now.get(Calendar.DAY_OF_MONTH)
            val g = MonthGrid.of(y, m, first)
            return Cal(g, g.offset + d - 1, Lunar.of(d, m, y)).also { calKey = key; calMemo = it }
        }

        /** The lunar facts of [today], counted forward day by day (a year at most: cheap, and asked once a minute). */
        fun moon(today: Calendar): Moon {
            val c = today.clone() as Calendar
            fun at(c: Calendar) = Lunar.of(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
            val day = at(c)
            var full = -1; var new = -1; var tet = -1
            for (i in 0..400) {
                val d = if (i == 0) day else at(c)
                if (full < 0 && d.day == 15) full = i
                if (new < 0 && d.day == 1) new = i
                if (tet < 0 && d.day == 1 && d.month == 1 && !d.leap) tet = i
                if (full >= 0 && new >= 0 && tet >= 0) break
                c.add(Calendar.DAY_OF_MONTH, 1)
            }
            return Moon(day, full, new, tet, Holidays.next(today))
        }
    }

    /** (used, total) bytes of RAM from /proc/meminfo, which any app may read; null when it cannot be read. */
    private fun memory(): Pair<Long, Long>? = memoryUsage()

    /** Set by the screen: the weather arrived (or failed) on its own thread, the widgets are read again. */
    var onWeather: (() -> Unit)? = null
    /** The news widget: the headline chosen (D-pad up / down while it is focused), the one drawn, and whether the widget has the focus. */
    internal var newsAt = 0; internal var newsShown = 0; internal var newsFocused = false; internal var newsCount = 0
    /** The recent-apps widget: the icon the mark is on, whether the widget has the focus, the apps drawn and their cells (Left / Right move the mark inside it). */
    /** Where the glass pane goes inside a widget (the recent-apps widget's marked icon); null: the widget draws its own mark. */
    var onMark: ((View) -> Unit)? = null
    internal var recentAt = 0; internal var recentFocused = false; internal var recentApps: List<String> = emptyList(); internal val recentCells = ArrayList<View>()

    /** The widgets the D-pad can stop on (weather: its map; news: the reader), left to right; the screen says where Up, Down and OK go. */
    val hot = ArrayList<View>()
    var onOpen: ((String) -> Unit)? = null
    /** Edit mode: every widget at rest wears an outline ("you can change this"). Set before [show]. */
    var editing = false
    /** Long OK or Menu on any widget: its small menu (size, look, hide...), live on the widget itself. */
    var onMenu: ((String, View) -> Unit)? = null
    var onUp: ((View) -> Unit)? = null
    /** A widget took or lost the focus: the screen's glass pane follows it. */
    var onFocus: ((View, Boolean) -> Unit)? = null
    var onDown: ((View) -> Unit)? = null

    /**
     * The widget straight above or below [x], else null: Down from the gear at the right no longer crossed the screen to the weather at the left.
     * Two small widgets stacked in one column: the top one from above, the bottom one from the grid ([fromBelow]).
     */
    fun hotAt(x: Int, fromBelow: Boolean = false): View? {
        val at = IntArray(2)
        val col = hot.filter { h -> h.isShown && run { h.getLocationOnScreen(at); x in at[0]..at[0] + h.width } }
        return (if (fromBelow) col.maxByOrNull { h -> h.getLocationOnScreen(at); at[1] } else col.minByOrNull { h -> h.getLocationOnScreen(at); at[1] })
    }

    /** The shown widget next to [v] towards ([dx], [dy]): Up / Down only to one sharing its columns (a stacked pair), Left / Right the nearest beside it. */
    private fun near(v: View, dx: Int, dy: Int): View? {
        val a = IntArray(2); v.getLocationOnScreen(a)
        val ax = a[0] + v.width / 2; val ay = a[1] + v.height / 2
        return hot.filter { it !== v && it.isShown }.mapNotNull { h ->
            val b = IntArray(2); h.getLocationOnScreen(b)
            val bx = b[0] + h.width / 2; val by = b[1] + h.height / 2
            val ahead = if (dy != 0) (by - ay) * dy > v.height / 3 && b[0] < a[0] + v.width && a[0] < b[0] + h.width
                else (bx - ax) * dx > v.width / 3
            if (ahead) h to (if (dy != 0) Math.abs(by - ay) + 2 * Math.abs(bx - ax) else Math.abs(bx - ax) + 2 * Math.abs(by - ay)) else null
        }.minByOrNull { it.second }?.first
    }

    private fun hotify(v: View, k: String) {
        v.isFocusable = true; v.tag = k; hot.add(v)
        if (Build.VERSION.SDK_INT >= 23) v.foreground = android.graphics.drawable.StateListDrawable().apply {
            if (!dev.tvremote.tvui.Motion.glass) addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setStroke(dp(3), Color.WHITE); setColor(HOT_FILL) })   // glass: the screen's pane comes here instead
            if (editing) addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setStroke(dp(2), HOT_FILL_EDGE) })   // at rest: the outline says "you can change this"
        }
        v.setOnFocusChangeListener { _, f -> if (k == "news") newsFocused = f; onFocus?.invoke(v, f); if (k == "recent") { recentFocused = f; recentMark() } }   // after onFocus: the pane goes on to the marked icon
        v.setOnClickListener { if (editing) onOpen?.invoke(k) else when (k) { "recent" -> recentOpen(); "adblock" -> adblockToggle(); else -> onOpen?.invoke(k) } }   // these two act on OK; the others open their screen or their menu
        v.setOnLongClickListener { onMenu?.invoke(k, v); true }
        v.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_MENU) { if (e.action == KeyEvent.ACTION_UP) onMenu?.invoke(k, v); return@setOnKeyListener true }
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val i = hot.indexOf(v)
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (k == "recent" && recentAt > 0) { recentAt--; recentMark() } else (near(v, -1, 0) ?: hot.getOrNull(i - 1))?.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (k == "recent" && recentAt < recentApps.size - 1) { recentAt++; recentMark() } else (near(v, 1, 0) ?: hot.getOrNull(i + 1))?.requestFocus(); true }
                // a small widget stacked over another: Down goes to it before the grid, Up from it back to the top one (the user, 2026-10-27)
                KeyEvent.KEYCODE_DPAD_UP -> { if (k == "news" && newsAt > 0) { newsAt--; onWeather?.invoke() } else near(v, 0, -1)?.requestFocus() ?: onUp?.invoke(v); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (k == "news" && newsAt < newsCount - 1) { newsAt++; onWeather?.invoke() } else near(v, 0, 1)?.requestFocus() ?: onDown?.invoke(v); true }
                else -> false
            }
        }
    }

    internal val sp = a.resources.displayMetrics.scaledDensity
    internal val down = FloatArray(POINTS); internal val up = FloatArray(POINTS)
    internal var lastRx = -1L; internal var lastTx = -1L; internal var lastAt = 0L
    internal var speed: TextView? = null; internal var speedUp: TextView? = null
    internal var ghzMemo: Float? = null

    init {
        orientation = HORIZONTAL; clipChildren = false; clipToPadding = false
        setLayerType(LAYER_TYPE_HARDWARE, null)                        // drawn once into a texture: the dock's focus animation only copies it
    }

    fun read(want: List<String>): Facts {
        // MemAvailable, as TV Setup's RAM live widget reads it: ActivityManager.availMem counts cached apps as free (53 % here, 75 % there)
        val mem = memory() ?: ActivityManager.MemoryInfo().also { (a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.let { (it.totalMem - it.availMem) to it.totalMem }
        val disk = try { StatFs(Environment.getDataDirectory().path).let { (it.totalBytes - it.availableBytes) to it.totalBytes } } catch (e: Exception) { 0L to 0L }
        var ip = ""; var eth = false
        try {                                                          // the system's networks: NetworkInterface would need INTERNET, which TV Home has not
            val cm = a.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION") for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val v4 = cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: continue
                val wired = c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (ip.isEmpty() || wired) { ip = v4.hostAddress ?: ""; eth = wired }   // a cable wins, as the TV routes it
            }
        } catch (e: Exception) { }
        val rssi = if (eth || ip.isEmpty()) null else try {
            @Suppress("DEPRECATION") (a.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.rssi?.takeIf { it in -126..-1 }
        } catch (e: Exception) { null }
        val adb = try { Settings.Global.getInt(a.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 } catch (e: Exception) { false }
        val link = if (rssi == null) null else try {
            @Suppress("DEPRECATION") (a.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.linkSpeed?.takeIf { it > 0 }
        } catch (e: Exception) { null }
        // Private DNS (DNS over TLS), as Settings → Network shows it: only a named server counts, "automatic" is the network's own
        val dns = try {
            if (Settings.Global.getString(a.contentResolver, "private_dns_mode") == "hostname") Settings.Global.getString(a.contentResolver, "private_dns_specifier")?.takeIf { it.isNotBlank() } else null
        } catch (e: Exception) { null }
        val adbPort = if (!adb) 0 else try {                            // ADB over the network (what the clients use), 0 = USB only
            (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "service.adb.tcp.port") as String).toIntOrNull()?.takeIf { it > 0 } ?: 0
        } catch (e: Throwable) { 0 }
        fun ask(m: String) = try { a.contentResolver.call(Uri.parse(HomeConfig.USAGE), m, null, null)?.getString("json")?.let { JSONObject(it) } }
            catch (e: Exception) { null }                                  // no TV Setup, or one older than this widget
        val usage = if ("usage" in want) ask("usage") else null
        val icons = HashMap<String, Drawable>()
        usage?.let { u -> for (k in listOf("week", "limits")) u.optJSONArray(k)?.let { l -> for (i in 0 until l.length()) l.optJSONObject(i)?.optString("pkg")?.let { p ->
            if (p !in icons) try { icons[p] = a.packageManager.getApplicationIcon(p) } catch (e: Exception) { }
        } } }
        val recent = if ("recent" in want) ask("recent") else null          // null: no TV Setup, or one older than this widget
        recent?.optJSONArray("apps")?.let { l -> for (i in 0 until l.length()) l.optString(i).let { p ->
            if (p.isNotEmpty() && p !in icons) try { icons[p] = a.packageManager.getApplicationIcon(p) } catch (e: Exception) { }
        } }
        val timer = if ("timer" in want) ask("timer") else null
        val moon = if ("lunar" in want) moon(Calendar.getInstance()) else null
        val cal = if ("calendar" in want) month(Calendar.getInstance(), Calendar.getInstance(resources.locale0()).firstDayOfWeek) else null
        // the CPU: apps may read neither /proc/stat nor loadavg, so TV Setup's shell measures it (null without TV Setup 0.14.1 or its ADB)
        val cpu = if ("system" in want) ask("cpu") else null
        // no CPU: TV Setup does not answer at all (missing or old), or it does but has no ADB to measure with; the first miss is only
        // the measure not being ready yet (HomeActivity asks again), so "no ADB" is said from the second on
        cpuMiss = if (cpu == null && "system" in want) cpuMiss + 1 else 0
        val cpuWhy = if (cpuMiss == 0) 0 else if (ask("badges") == null) R.string.w_cpu_need_setup else if (cpuMiss >= 2) R.string.w_cpu_need_adb else 0
        val typed = Store.get(a).optString("place")
        val air = "air" in want
        val prayer = "prayer" in want && Weather.placeStale(a, typed)      // the prayer times need the weather's place, nothing else of it
        if (("weather" in want || air || prayer) && Weather.due(a, typed, air = air)) Weather.refresh(a, typed, resources.locale0().language, air) { onWeather?.invoke() }
        val wx = if ("weather" in want) Weather.cached(a) else null
        val btc = opt("market", "btc") == "on"
        if ("market" in want && Market.due(a, btc)) Market.refresh(a, btc) { onWeather?.invoke() }
        val feeds = if ("news" in want) News.urls(opt("news", "src"), resources.locale0().language, Store.get(a).optJSONArray("feeds").let { l -> List(l?.length() ?: 0) { l!!.getString(it) } }) else emptyList()
        if (feeds.isNotEmpty() && News.due(a, feeds)) News.refresh(a, feeds) { onWeather?.invoke() }
        return Facts(mem, disk, ip, eth, rssi, SystemClock.elapsedRealtime(), adb, usage, icons, timer, moon,
            cpu?.optDouble("busy", -1.0)?.takeIf { it >= 0 }?.toFloat(), cpu?.optInt("cores") ?: 0, cal, wx, link, dns, adbPort, cpuWhy, if (air) Weather.cachedAir(a) else null,
            if ("market" in want) Market.cached(a) else null, if ("news" in want) News.cached(a) else emptyList(), recent,
            pray = if ("prayer" in want) prayerFacts() else null)
    }

    /**
     * The widgets in the order of [want], sized by [sizes] (HomeConfig "wsize"). [HomeConfig.pack] makes the columns: a big widget is a
     * column, two small widgets in a row stack in one, a small widget alone stands in a narrow one; what does not fit waits. The height
     * is fixed so the favourites below stay in view. A column's width is its weight's share of the row.
     */
    fun show(want: List<String>, f: Facts, note: String, label: (String) -> String, sizes: JSONObject = JSONObject(), qrs: JSONObject = JSONObject()) {
        val keep = hot.firstOrNull { it.hasFocus() }?.tag
        removeAllViews(); speed = null; speedUp = null; hot.clear()
        val timerView = if ("timer" in want) timer(f.timer) else null
        val noteView = if (note.isNotEmpty()) pill(tile(AMBER, R.drawable.ic_message), note, "", 1).apply {   // the words are the widget: large, no "Note" over them
            ((getChildAt(1) as LinearLayout).getChildAt(0) as TextView).apply { textSize = 16f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        } else null
        val tall = layoutParams
        if (tall != null && tall.height != dp(BIG_H)) { tall.height = dp(BIG_H); layoutParams = tall }
        val keys = want.filter { (it != "timer" || timerView != null) && (it != "lunar" || f.moon != null) && (it !in HomeQr.KEYS || qrs.has(it)) && (it != "adblock" || Adblock.supported()) }   // a widget with nothing to show takes no place; Private DNS is Android 9+
        val big = HomeConfig.bigs(JSONObject().put("wsize", sizes))
        val packed = HomeConfig.pack(keys, big, noteView != null)
        if (packed.cols.isEmpty()) { visibility = View.GONE; return }
        fun plain(k: String, alone: Boolean): View? = when (k) {
            "lunar" -> f.moon?.let { lunar(it) }; "timer" -> timerView
            "clock" -> clock(if (!alone) 0 else if (k in big) 2 else 1)
            in HomeQr.KEYS -> qrWidget(qrs.getJSONObject(k))
            else -> noteView
        }
        fun tonal(k: String) = k in HomeConfig.TONAL && opt(k, "look") == "tonal"                  // the default look, glass, is what a missing one means
        val gap = dp(20)
        val room = (if (width > 0) width else a.resources.displayMetrics.widthPixels - dp(96)) - gap * (packed.cols.size - 1)
        val sum = maxOf(HomeConfig.FLOOR, packed.cols.sumOf { it.weight })
        packed.cols.forEachIndexed { i, col ->
            val w = room * col.weight / sum
            val box = LinearLayout(a).apply { orientation = VERTICAL }
            col.keys.forEachIndexed { j, k ->
                val alone = col.keys.size == 1                         // a lone widget has the column's whole height
                val full = alone && k in big && w >= dp(FULL_MIN)
                val v: View = if (tonal(k)) tonalWidget(!full).also { c -> tonalFill(k, c, f, w, !full, label, qrs) }     // one path for every widget: a figure, a caption, a badge
                else if (k in BIG) widget().also { c ->
                    if (full) when (k) {
                        "system" -> system(c, f, w)
                        "air" -> airGlass(c, f, true)
                        "calendar" -> if (opt("calendar", "layout") == "date") calendarDate(c, f) else calendar(c, f, w, dp(BIG_H) - dp(26) - dp(22))   // minus the widget's padding, the title and its gap
                        "weather" -> weather(c, f, w, fixed = true)
                        "market" -> if (opt("market", "layout") == "ticker") marketTicker(c, f, true) else marketFull(c, f)
                        "news" -> newsFull(c, f, w)
                        "recent" -> recentGlass(c, f, label, w, true)
                        "adblock" -> adblockGlass(c, f, true)
                        "prayer" -> prayerGlass(c, f, true)
                        else -> usage(c, f, label, w < dp(340))
                    } else {                                       // the small form: half the height, or a narrow column
                        c.setPadding(dp(16), dp(8), dp(16), dp(8))
                        when (k) {
                            "system" -> systemHalf(c, f, w - dp(32))
                            "calendar" -> calendarHalf(c, f, w - dp(32))
                            "weather" -> weatherHalf(c, f, w - dp(32))
                            "air" -> airGlass(c, f, false)
                            "market" -> if (opt("market", "layout") == "ticker") marketTicker(c, f, false) else marketHalf(c, f)
                            "news" -> newsHalf(c, f)
                            "recent" -> recentGlass(c, f, label, w, false)
                            "adblock" -> adblockGlass(c, f, false)
                            "prayer" -> prayerGlass(c, f, false)
                            else -> usageHalf(c, f, label)
                        }
                    }
                } else plain(k, alone)!!.also { if (alone && k != "clock" && k !in HomeQr.KEYS) stand(it) }   // a QR is a glass widget already, not a pill to stand up
                bare(v, k)
                hotify(v, k)                                               // every widget a stop (the user, 2026-10-27): OK opens its screen, or its menu where it has none
                box.addView(v, LayoutParams(-1, 0, 1f).apply { if (j > 0) topMargin = dp(12) })
            }
            addView(box, LayoutParams(w, -1).apply { if (i > 0) leftMargin = gap })
        }
        visibility = View.VISIBLE
        if (keep != null) hot.firstOrNull { it.tag == keep }?.let { it.requestFocus(); if (keep == "news") newsFocused = true }
    }

    /**
     * The options the widgets are drawn with: the saved ones (HomeConfig "wopt"), or [options] when something else is looking at a widget with
     * choices not saved yet (the settings dialog's preview). Reads the same way for both, so the preview is the widget.
     */
    var options: JSONObject? = null
    internal fun opt(k: String, id: String): String = HomeConfig.optValue(options ?: Store.get(a), k, id)

    internal fun hm(t: Long) = SimpleDateFormat(if (Store.get(a).optBoolean("clock24", true)) "HH:mm" else "h:mm a", resources.locale0()).format(Date(t))

    /** A small coloured square for a pill: an icon, or [inner] (the lunar day's number). */
    internal fun tile(colors: IntArray, icon: Int?, inner: View? = null) = FrameLayout(a).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(12).toFloat() }
        if (icon != null) addView(ImageView(a).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        if (inner != null) addView(inner, FrameLayout.LayoutParams(-1, -1))
    }

    /** A small widget turned upright: the square larger on top, the words bigger under it, all to the left. */
    internal fun stand(v: View) {
        val p = v as? LinearLayout ?: return
        (p as? Pill)?.let { it.upright = true; it.beside?.let { v -> it.removeView(v) }; it.beside = null }   // upright, its counts say it (Night)
        val square = p.getChildAt(0); val words = p.getChildAt(1) as LinearLayout
        p.orientation = VERTICAL; p.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        p.setPadding(dp(20), dp(14), dp(20), dp(14))
        square.layoutParams = LayoutParams(dp(56), dp(56))
        ((square as FrameLayout).getChildAt(0) as? TextView)?.textSize = 26f   // the lunar day's number
        (square.getChildAt(0) as? ImageView)?.layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
        (p.tag as? Night)?.let { sky ->                                // the lunar widget: its day, the date and the year, the moon on the right
            p.removeView(square)
            val title = words.getChildAt(0) as TextView
            words.removeView(title)
            p.addView(LinearLayout(a).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(square, LayoutParams(dp(56), dp(56)))
                addView(LinearLayout(a).apply {
                    orientation = VERTICAL
                    addView(title.apply { textSize = 18f; maxLines = 1 })
                    addView(text(sky.year, 13f, DIM), LayoutParams(-2, -2).apply { topMargin = dp(2) })
                }, LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
                addView(MoonFace(a, sky.phase), LayoutParams(dp(44), dp(44)))
            }, 0, LayoutParams(-1, -2).apply { topMargin = dp(6) })
            // a title and the top edge like the big widgets beside it (weather, system): centred and untitled it sat off their lines
            p.addView(head(a.getString(R.string.w_lunar), ""), 0)
            p.gravity = Gravity.START or Gravity.TOP
            p.setPadding(dp(20), dp(12), dp(20), dp(14))
        }
        words.layoutParams = LayoutParams(-1, -2).apply { topMargin = dp(14) }
        (p.tag as? Night)?.takeIf { it.counts.isNotEmpty() }?.let { n ->
            (words.getChildAt(0) as? TextView)?.let { words.removeView(it) }
            words.addView(LinearLayout(a).apply {
                orientation = HORIZONTAL
                // "Hôm nay rằm" is a column beside the counts: as a line over them it pushed "tới Tết" off the widget's bottom
                n.line?.let { addView(text(it, 17f, Color.WHITE).apply { typeface = Font.medium; isSingleLine = false; maxLines = 2 }, LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) }) }
                n.counts.forEach { (days, label) ->
                    addView(LinearLayout(a).apply {
                        orientation = VERTICAL
                        addView(text(a.resources.getQuantityString(R.plurals.l_days, days, days), 17f, Color.WHITE).apply { typeface = Font.medium })
                        addView(text(a.getString(label), 12f, DIM))
                    }, LayoutParams(0, -2, 1f))
                }
            }, LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        if (p.tag !is Night) (words.getChildAt(0) as TextView).apply { textSize = 18f; maxLines = 4 }       // a narrow column wraps a long title, never cuts it
        (words.getChildAt(if (p.tag is Night) 0 else 1) as? TextView)?.apply { textSize = 14f; maxLines = 2; setPadding(0, dp(3), 0, 0) }
    }

    /** One of the small widgets: a coloured square, a title, a line under it ([lines] of it). */
    internal fun pill(square: View, title: String, sub: String, lines: Int, side: String? = null) = Pill(a, side).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        Frost.on(this, dp(18).toFloat(), dp(1))
        setPadding(dp(12), dp(8), dp(14), dp(8))
        addView(square, LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(a).apply {
            orientation = VERTICAL
            addView(text(title, 14f, Color.WHITE).apply { typeface = Font.medium; maxLines = 2 })   // a narrow column wraps, never cuts
            if (sub.isNotEmpty() || side != null) addView(text(sub, 12f, DIM).apply { maxLines = maxOf(lines, 2) }.also { below = it })
        }, LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        if (side != null) { under = sub; addView(text(side, 13f, DIM).apply { maxLines = 1; gravity = Gravity.END }.also { beside = it }, LayoutParams(-2, -2).apply { leftMargin = dp(12) }) }
    }

    /**
     * A pill with a second fact ([side], the lunar widget's "Tết sau 105 ngày"): beside the words when the pill is wide, a line under
     * them when it is narrow. As two lines always, a pill alone across a column left most of its width empty and the lines crowded.
     */
    internal class Pill(c: Context, private val side: String?) : LinearLayout(c) {
        var under = ""
        var below: TextView? = null; var beside: TextView? = null       // the line under the title; [side] on the right
        var upright = false                                             // stand() lays it out its own way
        override fun onMeasure(w: Int, h: Int) {
            val sub = below; val right = beside
            if (side != null && sub != null && right != null && !upright) {
                val wide = MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(w) >= (WIDE_DP * resources.displayMetrics.density)
                val text = if (wide) under else if (under.isEmpty()) side else under + "\n" + side
                if (sub.text.toString() != text) sub.text = text
                sub.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                right.visibility = if (wide) View.VISIBLE else View.GONE
            }
            super.onMeasure(w, h)
        }
        companion object { const val WIDE_DP = 320 }
    }

    /** "3d 17h", "5h 12m", "42m". */
    internal fun span(ms: Long): String {
        val m = ms / 60_000
        return when {
            m >= 1440 -> "${m / 1440}d ${m % 1440 / 60}h"
            m >= 60 -> "${m / 60}h ${m % 60}m"
            else -> "${m}m"
        }
    }

    /** A widget's "look" when it is "solid" or "none" (glass is how [widget], [pill] and the clock are made; tonal is its own widget). */
    private fun bare(v: View, k: String) {
        val l = opt(k, "look"); if (l != "solid" && l != "none") return
        (v.background as? Frost)?.look = l
        if (l == "none") shade(v)
    }

    // no widget under them: a soft shadow keeps white words readable on a light part of the photo, as the clock above the dock has
    private val BARE_SHADOW = 0xCC000000.toInt()
    private fun shade(v: View) {
        if (v is TextView) v.setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), BARE_SHADOW)
        else if (v is ViewGroup) for (i in 0 until v.childCount) shade(v.getChildAt(i))
    }

    internal fun widget() = LinearLayout(a).apply {
        orientation = VERTICAL
        Frost.on(this, dp(22).toFloat(), dp(1))                         // the dock band's glass, its blurred photo too
        setPadding(dp(20), dp(12), dp(20), dp(14))
    }

    internal fun head(left: String, right: String) = LinearLayout(a).apply {
        orientation = HORIZONTAL
        addView(text(left.uppercase(), 12f, 0xB3FFFFFF.toInt()).apply { letterSpacing = 0.12f }, LayoutParams(0, -2, 1f))
        if (right.isNotEmpty()) addView(text(right, 12f, 0xB3FFFFFF.toInt()))
    }

    internal fun note(t: String) = text(t, 13f, 0xB3FFFFFF.toInt()).apply { setPadding(0, dp(10), 0, 0); maxLines = 2 }

    internal fun text(t: String, size: Float, color: Int) = Font.on(TextView(a)).apply {
        text = t; textSize = size; setTextColor(color); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.START
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x80000000.toInt())
    }

    internal fun grad(colors: IntArray, w: Float) = LinearGradient(0f, 0f, w, 0f, colors[0], colors[1], Shader.TileMode.CLAMP)

}

/** (used, total) bytes of RAM from /proc/meminfo (MemAvailable, as TV Setup's RAM live widget reads it), which any app may read; null when it cannot be read. The system widget and the Control Center share it. */
internal fun memoryUsage(): Pair<Long, Long>? = try {
    val m = java.io.File("/proc/meminfo").readText()
    fun kb(k: String) = Regex("^$k:\\s+(\\d+)", RegexOption.MULTILINE).find(m)?.groupValues?.get(1)?.toLongOrNull()
    val total = kb("MemTotal"); val free = kb("MemAvailable")
    if (total == null || free == null || total <= 0) null else ((total - free).coerceAtLeast(0) * 1024) to total * 1024
} catch (e: Exception) { null }
