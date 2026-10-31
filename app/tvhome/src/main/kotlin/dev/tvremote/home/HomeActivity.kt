package dev.tvremote.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.view.animation.PathInterpolator
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.Locale
import dev.tvremote.tvui.Glass
import dev.tvremote.tvui.Motion
import dev.tvremote.tvui.TvGlass
import dev.tvremote.tvui.TvTile
import dev.tvremote.tvui.TvUi
import dev.tvremote.update.Release
import dev.tvremote.update.SelfUpdate
import dev.tvremote.home.apps.App
import dev.tvremote.home.apps.FolderArt
import dev.tvremote.home.apps.AppPages
import dev.tvremote.home.apps.HomeLock
import dev.tvremote.home.apps.AppRules
import dev.tvremote.home.apps.Apps
import dev.tvremote.home.wall.Backdrop
import dev.tvremote.home.apps.Badges
import dev.tvremote.home.ui.Drop
import dev.tvremote.home.ui.Frost
import dev.tvremote.home.ui.PixelShift
import dev.tvremote.home.apps.InputOpen
import dev.tvremote.home.apps.Inputs
import dev.tvremote.home.apps.LinkApps
import dev.tvremote.home.apps.Links
import dev.tvremote.home.keys.Keys
import dev.tvremote.home.keys.Picker
import dev.tvremote.home.manage.ManageServer
import dev.tvremote.home.apps.Nav
import dev.tvremote.home.wall.Online
import dev.tvremote.home.manage.PhotoScreen
import dev.tvremote.home.apps.Pins
import dev.tvremote.home.apps.YouTube
import dev.tvremote.home.manage.HomeApi
import dev.tvremote.home.widgets.sample
import dev.tvremote.home.widgets.adblockToggle
import dev.tvremote.home.apps.SetupHand
import dev.tvremote.home.apps.LiveWidgets
import dev.tvremote.home.apps.Watch
import dev.tvremote.home.apps.LiveFace
import dev.tvremote.home.ui.BarBtn
import dev.tvremote.home.ui.TopBar
import dev.tvremote.home.wall.Wall
import dev.tvremote.home.wall.WallArt
import dev.tvremote.home.wall.Walls
import dev.tvremote.home.wall.WebWall
import dev.tvremote.home.wall.VideoWall
import dev.tvremote.home.wall.Videos
import dev.tvremote.home.wall.WebSnap
import dev.tvremote.home.wall.WebSnapper
import dev.tvremote.home.wall.WebVideo
import dev.tvremote.home.wall.NetPicture
import dev.tvremote.home.wall.Slideshow
import dev.tvremote.home.wall.Sky
import dev.tvremote.home.widgets.Widgets
import dev.tvremote.home.ui.Zoom

/**
 * The home screen: the clock, two round buttons in one capsule (network, settings = the Control Center: inputs and the power actions are in it), and ONE grid of apps: the favourites
 * first, then every other app.
 * Plain views, built in code: it has to come up at once on the weakest TV chip. What it shows is set in [Store], from here
 * (hold OK on an app) or from the phone / computer (HomeReceiver).
 */
class HomeActivity : Activity() {
    private val bg = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())   // a late job after onDestroy's shutdown is dropped, not thrown (census 1d158634)
    private lateinit var backdrop: Backdrop
    private lateinit var scroll: ScrollView
    private lateinit var content: FrameLayout
    private lateinit var glass: Glass
    private lateinit var clock: TextClock
    private lateinit var dateLine: TextView
    private lateinit var rule: View
    private lateinit var greetLine: TextView
    private lateinit var netPill: BarBtn
    private lateinit var settingsPill: BarBtn
    private lateinit var bar: TopBar
    private lateinit var grid: LinearLayout                         // the favourites first, then every other app, then the hidden apps' tile: one grid
    private var apps: List<App> = emptyList()
    /** The apps with their pictures as the grid has them: the picker shows these instead of loading every banner again (~230 ms). */
    internal fun loaded(): List<App> = apps
    @Volatile private var pins: List<App> = emptyList()                // shortcuts other apps pinned here ([Pins])
    private var conf = JSONObject()
    private var wallShown = ""
    private var wallShade = -1                                       // the "shade" shown with [wallShown]
    private val CAP_X = 48; private val CAP_Y = 6                     // the slideshow caption's place (dp): top right, above the buttons' pill, where no widget or tile reaches
    private val CAP_INK = 0xE6FFFFFF.toInt(); private val CAP_DIM = 0x59FFFFFF.toInt()   // the credit: there to be found, not read (at 60% it drew the eye: the user, 2026-10-26)
    private var lastWall = ""
    private var web: android.webkit.WebView? = null                 // the "web" wall's page, behind the grid and over the window's gradient; null for every other wall
    private var webDim: View? = null
    private var webOf = ""                                          // the address [web] shows
    private var video: VideoWall? = null                           // a video or GIF wall: the player behind the grid (the poster is the window's background until it moves)
    private var netVideo = ""                                       // the direct video a web wall plays instead of a WebView ([WebVideo]); "" = none
    private val netBad = HashSet<String>()                          // those the player could not play this run: the page is shown instead
    private var snapper: WebSnapper? = null                         // "web" in snap mode: takes the pictures that are the background (no page on screen)
    private lateinit var head: LinearLayout
    private lateinit var headLeft: LinearLayout
    private var scrollAnim: ObjectAnimator? = null
    private val zoom by lazy { Zoom(this) }
    private var away = false                                        // really left (stopped), not just paused: only then does the screen come in again
    private var trimmed = false                                     // the pictures were let go for memory while away ([trim])
    private var shownKey = ""                                       // what the tiles were built from: the same again builds nothing
    private var tileCache = HashMap<String, Tile>()                 // a tile is made once per app and size, then moved
    private var soft: Bitmap? = null                                // the photo wallpaper's small blurred copy (the widgets' frost, the stand-in in [trim]); the photo itself is not kept, the [backdrop] has it painted
    private var badges = Badges.NONE
    private var focusNext: String? = null                          // a tile just added: the next [show] focuses it, so it can be moved at once
    private var wantCol = -1                                        // the grid column Up and Down try to keep ([Nav])
    private var lastOpened: String?                                 // kept in prefs: the home may be made again while the app is open
        get() = getSharedPreferences("nav", MODE_PRIVATE).getString("last", null)
        set(v) { getSharedPreferences("nav", MODE_PRIVATE).edit().putString("last", v).apply() }
    private var topAt: View? = null; private var topVia: View? = null   // the tile left by Up and the widget it went to: Down from that widget lands back on it
    private var stepping = false
    internal companion object {
        var reopen = false                                            // across recreate(): the panel was open when the language changed
        const val SETUP_CODE = "2397346"                              // TV Setup's Downloader code (README, the site)
        const val BT_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        const val REQ_BT = 7
    }
    private var open: android.app.Dialog? = null                    // the panel or menu on top, closed by a Home press
    private lateinit var widgets: Widgets
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val widgetTick = object : Runnable { override fun run() { widgetsRead(); ui.postDelayed(this, 60_000) } }
    private val netTick = object : Runnable { override fun run() { widgets.sample(); if (widgets.speed != null) ui.postDelayed(this, Widgets.SAMPLE_MS) } }   // no network graph: no 2 s wake-up

    private fun dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    /**
     * Started before the TV's first unlock since it booted (direct boot): none of TV Home's own storage can be read yet, so a clock on
     * black stands in and the real screen is built once Android unlocks. Home must open then: a TV with no FallbackHome never finished
     * booting with a Home that could not start before the unlock (cleanyourtv, a Vision on Android 13: black screen, remote dead).
     */
    private var locked = false
    private val unlocked = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (locked) recreate() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (CrashLoop.tripped(this)) {                               // crashing on start again and again: let the person change the home app
            dev.tvremote.home.apps.SystemSettings.open(this)
            finish(); return
        }
        wantInputs = intent?.action == InputOpen.INPUTS
        Font.load(this)
        InputOpen.sync(this)
        if (intent?.action == HomeConfig.PHOTO_QR) window.decorView.post { if (!locked) PhotoScreen.show(this) {} }
        if (android.os.Build.VERSION.SDK_INT >= 24 && getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == false) {
            locked = true
            registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            setContentView(FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(TextClock(this@HomeActivity).apply { textSize = 64f; setTextColor(Color.WHITE) },
                    FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            })
            return
        }
        shortcutWatch = Pins.watch(this) { r -> bg.execute(r) }
        if (!Pins.listed(this)) bg.execute { Pins.refresh(this) }       // the first Home: + Add has the apps' shortcuts to list
        Store.advance(this, true)                                     // a rotation on "each time Home is shown" (or one that fell due while away): before the first paint, so nothing is drawn twice
        conf = Store.get(this); readNames(); Pins.resize(pinW())
        val root = FrameLayout(this)
        backdrop = Backdrop(window.decorView)

        Motion.read(this, conf)
        scroll = Scroller(this) { Frost.moved(); slideCap?.translationY = -scroll.scrollY.toFloat() }.apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipChildren = false }   // the caption leaves with the top bar (it stayed and wrote over the widgets)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(48), dp(34), dp(48), dp(48))
        }
        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        content.addView(col, FrameLayout.LayoutParams(-1, -2))
        glass = Glass(this, ::dp)                                     // over the tiles, in what scrolls: a scroll carries it with its tile
        content.addView(glass, FrameLayout.LayoutParams(0, 0))
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> (currentFocus as? Tile)?.let { if (glass.placed) it.glassOn(false) } }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // the clock, then the greeting (small, above: it is read once) and the day beside it (one row: under the clock they took
        // the room of the widgets), the three buttons on the right
        head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }  // a focused button grows past its row
        val left = LinearLayout(this).apply {                       // focusable: long OK / Menu on the clock, the day or the greeting is their menu
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false
            isFocusable = false; isClickable = true; id = View.generateViewId()   // a stop while something in it is shown (clockAndDay)
            setPadding(dp(14), dp(6), dp(14), dp(6));             setOnLongClickListener { headMenu(); true }
            setOnKeyListener { v, code, e ->
                if (code == KeyEvent.KEYCODE_MENU) { if (e.action == KeyEvent.ACTION_UP) headMenu(); true }
                else code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN && (widgets.hotAt(centre(v)) ?: below(v))?.let { go(it); true } == true
            }
            setOnClickListener { headMenu() }
            setOnFocusChangeListener { v, f -> if (f) { scrollToY(0); glassTo(v) } else glassOff() }
        }
        headLeft = left; paintHead()
        clock = TextClock(this).apply {
            textSize = 60f; setTextColor(Color.WHITE); typeface = Font.light
            includeFontPadding = false; shadow(this)
        }
        greetLine = text(16f, HEAD_DIM).apply { setPadding(0, 0, 0, dp(2)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }   // a person's own words can be long
        dateLine = text(22f, Color.WHITE).apply { typeface = Font.medium }
        // each line as wide as its words: at the column's width (the default), new words were only re-wrapped in the old width, cut short
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(greetLine, LinearLayout.LayoutParams(-2, -2)); addView(dateLine, LinearLayout.LayoutParams(-2, -2)) }
        left.addView(clock)
        rule = View(this).apply { setBackgroundColor(HEAD_RULE) }
        left.addView(rule, LinearLayout.LayoutParams(dp(1), dp(56)).apply { leftMargin = dp(28); rightMargin = dp(28) })
        left.addView(words, LinearLayout.LayoutParams(-2, -2))
        // as wide as its words, in a slot that takes the rest of the row: stretched, its focus (the glass) reached the top bar (the user, 2026-10-27)
        val slot = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        slot.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        head.addView(slot, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = -dp(14); rightMargin = dp(16) })   // its padding is the ring's room: the clock stays where it was
        bar = TopBar(this, ::dp)
        netPill = BarBtn(this, R.drawable.ic_wifi, getString(R.string.wifi), ::dp) { if (editing) barMenu(netPill) else open(Settings.ACTION_WIFI_SETTINGS) }
        // one button for the TV's settings, TV Home's and the everyday switches: the Control Center (ui/ControlCenter.kt); a second button beside it read as the same thing
        settingsPill = BarBtn(this, R.drawable.ic_settings, getString(R.string.cc_title), ::dp) {   // the gear people know; its name says Control Center (the user tried a switches icon, 2026-10-25)
            if (editing) { barMenu(settingsPill); return@BarBtn }
            open = dev.tvremote.home.ui.ControlCenter.show(this, bar.capsule, { open(it) }, { inputs() }, { customize() })
        }
        for (b in listOf(netPill, settingsPill)) bar.add(b)
        for (b in bar.buttons) b.setOnLongClickListener { barMenu(b); true }
        for (b in bar.buttons) b.setOnKeyListener { v, code, e ->           // Down from the bar: the widget below it first, the grid only when there is none
            if (code == KeyEvent.KEYCODE_MENU) { if (e.action == KeyEvent.ACTION_UP) barMenu(v as BarBtn); return@setOnKeyListener true }
            code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN && (widgets.hotAt(centre(v))?: below(v))?.let { go(it); true } == true
        }
        // the row's two ends stay put: Android's own search went from the left pill down-left onto a tile, and OK then opened its app
        netPill.nextFocusLeftId = View.generateViewId().also { netPill.id = it }
        settingsPill.id = View.generateViewId(); settingsPill.nextFocusRightId = settingsPill.id
        bar.focusIn = { scrollToY(0); glassTo(it) }
        for (b in bar.buttons) b.onFocusChangeListener.let { l -> b.setOnFocusChangeListener { v, f -> l?.onFocusChange(v, f); if (!f) glassOff() } }
        head.addView(bar)
        col.addView(head)
        widgets = Widgets(this, ::dp).apply { visibility = View.GONE; onWeather = { runOnUiThread { if (!isDestroyed) widgetsRead() } } }
        widgets.onMenu = ::widgetMenu
        widgets.onFocus = { v, f -> if (f) glassTo(v) else glassOff() }
        widgets.onMark = { glassTo(it, r = dp(12).toFloat()) }
        widgets.onOpen = { k -> if (editing || k !in OPENS) widgets.hot.firstOrNull { it.tag == k }?.let { widgetMenu(k, it) } else startActivity(Intent(this, when (k) { "news" -> dev.tvremote.home.widgets.NewsActivity::class.java; "market" -> dev.tvremote.home.widgets.MarketActivity::class.java; else -> dev.tvremote.home.widgets.WeatherMapActivity::class.java }).putExtra("i", widgets.newsShown)) }
        widgets.onUp = { v -> (bar.buttons.filter { it.isShown } + listOfNotNull(headLeft.takeIf { it.isShown && it.isFocusable })).minByOrNull { p -> val q = IntArray(2); val r = IntArray(2); p.getLocationOnScreen(q); v.getLocationOnScreen(r); Math.abs(q[0] + p.width / 2 - r[0] - v.width / 2) }?.requestFocus() }
        widgets.onDown = { v -> below(v)?.let { go(it) } }
        col.addView(widgets, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })

        // ONE grid: the favourites are its first tiles, then every other app; no panel, the wallpaper stays whole
        grid = Flat(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(40) })
        // no widgets: the grid starts low, so the first row is whole and the second is cut by the screen's edge; the wallpaper above
        grid.viewTreeObserver.addOnGlobalLayoutListener { peek(); if (tipOn) (grid.getChildAt(0) as? ViewGroup)?.let { tipPlace(it) } }  // after every layout: rows came or went, a widget opened
        scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> veil(); tip() }
        scroll.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) peek() }

        setContentView(root)
        ThemeShots.live = { webShot() ?: backdrop.small }              // a web wall: the page as it is drawn now, or the site's picture / slide TV Home draws itself
        Store.onChange = {
            val was = conf; conf = Store.get(this)
            // the veil alone (its stepper, a step every press): the picture painted again, nothing else rebuilt; the whole screen
            // made again took ~60 ms of the FPT box's main thread a step (the widgets, the tiles)
            if (veilOnly(was, conf)) wall() else onConf()
        }
        val pkgs = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(changed, pkgs)
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            @Suppress("DEPRECATION") addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_DREAMING_STARTED); addAction(Intent.ACTION_DREAMING_STOPPED)
        })
        if (Store.suggestOff(this)) { Store.suggestOff(this, false); conf = Store.change(this, JSONObject().put("showSuggest", false)) }   // the old "hide" flag is now the setting everyone sees
        loadApps()
        if (Motion.on) enter()
        if (reopen) { reopen = false; ui.post { customize("lang") } }
    }

    /** A new language: the screen built again in it, the panel reopened if the change came from it (else it is closed). */
    private fun relang() {
        reopen = open?.isShowing == true && open === panel
        open?.dismiss(); open = null
        recreate()
    }

    private var shortcutWatch: Any? = null

    override fun onDestroy() {
        Pins.unwatch(this, shortcutWatch)
        Customize.forget(this)
        if (locked) { try { unregisterReceiver(unlocked) } catch (e: Exception) { }; super.onDestroy(); return }
        Store.onChange = null; ThemeShots.live = null
        open?.dismiss()
        ui.removeCallbacks(widgetTick); ui.removeCallbacks(netTick); ui.removeCallbacks(wallTick); ui.removeCallbacks(slideTick); ui.removeCallbacks(webRetry); ui.removeCallbacks(liveTick); ui.removeCallbacks(skyTick); ui.removeCallbacks(shiftTick)
        detachWeb(); detachVideo()
        try { unregisterReceiver(changed); unregisterReceiver(tick) } catch (e: Exception) { }
        bg.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (locked) return
        if (wantInputs) { wantInputs = false; bar.post { inputs() } }   // started by the Input key: the menu once the home is up
        dev.tvremote.home.ui.ControlCenter.warm(this)
        Themes.refresh(this)                                          // a day old: the launcher's process outlives the check at its start
        Motion.read(this, conf)                                       // the system's "remove animations" may have changed while away
        zoom.back()                                                   // the plate that grew over the opened app shrinks into its tile
        clockAndDay()
        // back from an app or a screensaver: no fade-in ([enter] is the first start only). The zoom above is the way back; a fade
        // from alpha 0 while onResume reloads left the wallpaper and the glass alone for seconds on the FPT Play box (Android 14)
        away = false
        if (trimmed && !locked) untrim()                              // a trim queued while away landed between onStart and here (away still true): blurred wallpaper, no banners, until Home was left again (a Coocaa)
        appsSince()
        watchRead()
        Keys.push(this)                                               // TV Setup keeps the keys and the power-on app: told again, so a TV Setup installed later has them
        badgesRead()
        pinsRead()                                                    // a pin is confirmed in its own screen, then the home comes back here
        if (Store.chooseDue(this)) ui.postDelayed({ if (!isDestroyed && !away) open = Picker.show(this, Picker.Mode.CHOOSE, getString(R.string.pk_choose_title)) }, 600)   // first run only: nothing is hidden without being shown
        bg.execute { forget(null); if (LiveWidgets.refresh(this)) runOnUiThread { if (!isDestroyed) { shownKey = ""; show() } } }   // what TV Setup offers (it may have been installed or updated while away)
        ui.removeCallbacks(liveTick); ui.post(liveTick)
        ui.removeCallbacks(widgetTick); ui.post(widgetTick)
        ui.removeCallbacks(netTick); ui.postDelayed(netTick, Widgets.SAMPLE_MS)
        slideGo()                                                     // a slideshow wall: the slide of this slot, the timer again
        if (!Store.advance(this, false)) wallTimer()                  // on a change the new look sets the timer itself ([Store.onChange])
        bingCheck()
        ui.postDelayed({ if (!isDestroyed) bg.execute { Customize.warm(this) } }, 3000)   // the panel's pictures made while nothing else goes on
        ui.postDelayed({ Customize.prepare(this) }, 3500)                  // and the panel itself, on the main thread once it is idle
        upd.check({ r -> ui.postDelayed({ if (!isDestroyed && !away && open == null) askUpdate(r) }, 2500) }, { updating(it) })   // asked once the home has settled
    }

    private val upd by lazy { SelfUpdate(this, "home", "tv-home.apk") }

    /** A newer TV Home in the latest release (SelfUpdate): Update now / Later / Skip this version; Back is a later too. */
    private fun askUpdate(r: Release) {
        // Back closes the menu: a later, as before (dismiss does nothing to the update)
        val acts = listOf(TvUi.Item(R.drawable.ic_download, getString(R.string.upd_now)) { updating(r) },
            TvUi.Item(R.drawable.ic_clock, getString(R.string.upd_later)) { upd.later() },
            TvUi.Item(R.drawable.ic_close, getString(R.string.upd_skip)) { upd.skip(r) })
        open = TvUi.menu(this, R.style.TvSheet, getString(R.string.upd_title, r.version) + "\n" + getString(R.string.upd_note, upd.version()), acts, R.drawable.ic_check)
    }

    /** Its download, with Cancel (and Back, Home); at 100 % Android's installer takes over with its own question. */
    private fun updating(r: Release, u: SelfUpdate = upd, title: String = getString(R.string.upd_title, r.version)) {
        val (d, box) = TvUi.sheet(this, R.style.TvSheet)
        TvUi.sheetTitle(box, title)
        val note = TvUi.sheetNote(box, getString(R.string.upd_loading, 0))
        box.addView(TvUi.button(this, getString(R.string.upd_cancel)) { d.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        d.setOnDismissListener { u.cancel() }
        d.show()
        open = d
        val started = u.install(r, { pct ->
            note.text = getString(R.string.upd_loading, pct)
            if (pct >= 100) { d.setOnDismissListener(null); d.dismiss() }
        }, { m ->
            d.setOnDismissListener(null); d.dismiss()
            val (f, fb) = TvUi.sheet(this, R.style.TvSheet)
            TvUi.sheetTitle(fb, title)
            TvUi.sheetNote(fb, if (u === upd) getString(R.string.upd_failed, m) else getString(R.string.suggest_failed, m))
            fb.addView(TvUi.button(this, getString(android.R.string.ok)) { f.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            f.show(); open = f
            fb.getChildAt(fb.childCount - 1).requestFocus()
        })
        if (!started) { d.setOnDismissListener(null); d.dismiss() }      // Android's "install unknown apps" first: back from it, onResume goes on
        else box.getChildAt(box.childCount - 1).requestFocus()
    }

    /** TV Setup suggested on the grid: not installed, not turned down. It does what a launcher cannot (ADB: Optimize TV, keys, the
     *  phone's remote), so a TV with TV Home alone is told once where the rest is (the user, 2026-10-25). */
    private var watch: List<Pair<Watch.Item, android.graphics.Bitmap?>> = emptyList()
    private val watchBg = java.util.concurrent.Executors.newSingleThreadExecutor()   // posters come over the network: not on [bg], which the tiles wait on

    /** Other apps' rows read again (on every return to Home: something was just watched); the grid is drawn again only when they changed. */
    private fun watchRead() {
        val cap = watchCap(); val w = tileW()
        watchBg.execute {
            val got = (if (conf.optBoolean("showWatch", true)) Watch.read(this, cap, Store.rowsOff(this)) else emptyList()).map { it to Watch.art(this, it, w, TvUi.CARD) }
            runOnUiThread {
                if (isDestroyed || got.map { it.first.key + it.first.title + it.first.done + (it.second != null) } == watch.map { it.first.key + it.first.title + it.first.done + (it.second != null) }) return@runOnUiThread
                watch = got; show()
            }
        }
    }

    private fun watchOpen(it: Watch.Item) {
        val i = Watch.intent(it)
        try { if (i != null) startActivity(i) else toast(R.string.no_open) } catch (e: Exception) { toast(R.string.no_open) }
    }

    /** Where the Play Next items go ([HomeConfig.WATCH_AT]): the old config without the key reads as the default. */
    private fun watchAt() = conf.optString("watchAt").takeIf { it in HomeConfig.WATCH_AT } ?: HomeConfig.WATCH_AT[0]
    /** How many items are read: a row of their own scrolls sideways, so it holds more; among the tiles they are one grid row's worth, or the grid would grow without end. */
    private fun watchCap() = if (watchAt() == "tiles") conf.optInt("cols", HomeConfig.COLS_DEFAULT) else WATCH_ROW
    private fun watchAtName(k: String) = getString(when (k) { "top" -> R.string.watch_at_top; "under1" -> R.string.watch_at_under1; "tiles" -> R.string.watch_at_tiles; else -> R.string.watch_at_bottom })

    private fun watchMenu(it: Watch.Item) {
        val pic = watch.firstOrNull { p -> p.first === it }?.second
        elementMenu(it.app, listOf(
            gi(R.drawable.ic_updown, getString(R.string.watch_at), live = { watchAtName(watchAt()) }) {      // one press, the next place: the home screen changes behind the menu
                val n = HomeConfig.WATCH_AT[(HomeConfig.WATCH_AT.indexOf(watchAt()) + 1) % HomeConfig.WATCH_AT.size]
                Store.change(this, JSONObject().put("watchAt", n))
            },
            gi(R.drawable.ic_star, getString(R.string.watch_pin)) { watchPin(it, pic) },
            gi(R.drawable.ic_eye_off, getString(R.string.watch_hide, it.app)) { Store.rowsOff(this, it.pkg); watchRead() },
            hideItem(R.string.watch_hide_all, "showWatch"), moreItem(), editItem()), getString(R.string.watch_note))
    }

    /** The item as a pinned shortcut of the grid ([Pins.pinIntent]): a tile like any pin, opening the same safe intent as the row's. */
    private fun watchPin(it: Watch.Item, pic: android.graphics.Bitmap?) {
        val i = Watch.intent(it)
        when (if (i == null) Pins.Made.FAIL else Pins.pinIntent(this, it.pkg, it.title, i, pic, marked = pic != null)) {
            Pins.Made.SAME -> toast(R.string.watch_pin_same)
            Pins.Made.FAIL -> toast(R.string.watch_pin_fail)
            Pins.Made.OK -> ui.postDelayed({ if (!isDestroyed) pinsRead() }, 600)          // the system confirms through PinActivity first
        }
    }

    // ---- the small menu of each element on the home screen (long OK, or Menu): the SAME switches as Customize, applied live behind it ----

    /** A row of an element's menu, in the one glass look of every TV Home menu ([TvGlass]): [on] makes it a switch, [live] a row that shows what it is set to; both stay open when pressed. */
    private fun gi(icon: Int, label: String, on: (() -> Boolean)? = null, live: (() -> String)? = null, go: () -> Unit) =
        TvGlass.Item(icon, label, go = go).also { it.on = on; it.live = live }

    private fun editItem() = if (editing) gi(R.drawable.ic_check, getString(R.string.el_done)) { endEdit() }   // already in it: the way out
        else gi(R.drawable.ic_apps, getString(R.string.el_edit)) { edit() }   // the grid, not the pen: Rename above it has the pen
    private fun moreItem(at: String = "look") = gi(R.drawable.ic_sliders, getString(R.string.el_more)) { customize(at) }
    private fun hideItem(label: Int, key: String) = gi(R.drawable.ic_eye_off, getString(label)) { Store.change(this, JSONObject().put(key, false)) }
    /** A switch that stays in the menu: the home screen behind it changes at once. */
    private fun switchItem(icon: Int, label: Int, key: String) = gi(icon, getString(label), on = { Store.get(this).optBoolean(key, true) })
        { Store.change(this, JSONObject().put(key, !Store.get(this).optBoolean(key, true))) }

    private val glassFont: (TextView) -> Unit = { it.typeface = if (it.typeface?.isBold == true) Font.bold else Font.regular }

    /** [items] in the glass panel of the app menu ([TvGlass]), on the half of the screen away from [anchor] so the element being changed stays in view, the dim light. */
    private fun elementMenu(title: String, items: List<TvGlass.Item>, note: String = "", anchor: View? = currentFocus) {
        val x = anchor?.let { v -> IntArray(2).also { v.getLocationOnScreen(it) }[0] + v.width / 2 } ?: 0
        val dlg = TvGlass.menu(this, R.style.Menu, title, items, note = note, checkIcon = R.drawable.ic_check, sideRight = x < resources.displayMetrics.widthPixels / 2, font = glassFont)
        dlg.setOnDismissListener { ui.postDelayed({                    // 350: the widgets are read again off the main thread
            if (isDestroyed) return@postDelayed
            // a switch rebuilds the screen behind the menu and the focus drifts (to the first tile): back to the element if it is still there
            if (anchor != null && anchor.isAttachedToWindow && anchor.isShown && anchor.isFocusable) anchor.requestFocus() else settle()
        }, 350) }
        open = dlg
    }

    // ---- edit mode: every element takes the focus (an outline each); OK opens its menu, Back leaves. Normal D-pad paths are untouched ----
    private var editing = false
    private val hint by lazy {
        TvUi.text(this, getString(R.string.el_edit_hint), TvUi.SP_ROW).apply {
            setPadding(dp(20), dp(10), dp(20), dp(10)); background = TvUi.round(TvUi.SHEET_FILL, dp(TvUi.R_PILL).toFloat(), 1, TvUi.SHEET_EDGE); visibility = View.GONE
            (window.decorView as ViewGroup).findViewById<FrameLayout>(android.R.id.content).addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        }
    }
    private fun paintHead() {
        headLeft.background = android.graphics.drawable.StateListDrawable().apply {
            if (!Motion.glass) addState(intArrayOf(android.R.attr.state_focused), TvUi.ring(this@HomeActivity, TvUi.FOCUS_FILL, dp(18).toFloat()))   // glass: the pane comes here
            if (editing) addState(intArrayOf(), TvUi.round(Color.TRANSPARENT, dp(18).toFloat(), dp(2), EDIT_EDGE))   // at rest: the outline says "you can change this"
        }
    }
    internal fun edit() {
        if (editing) return
        editing = true; widgets.editing = true; hint.visibility = View.VISIBLE
        paintHead(); clockAndDay(); show(); widgetsRead()            // a rebuild gives the widgets their stops; the focus comes back to the widget it was on
        ui.postDelayed({ if (!isDestroyed && currentFocus == null) settle() }, 350)
    }
    private fun endEdit() {
        editing = false; widgets.editing = false; hint.visibility = View.GONE
        paintHead(); clockAndDay(); show(); widgetsRead()             // clockAndDay: the clock stops being a stop now, not at the next tick (the focus landed on it, then on nothing)
        ui.postDelayed({ if (!isDestroyed) settle() }, 350)           // a widget that took no focus before gives it back to a neighbour
    }

    private var wantTag: String? = null                               // the widget next to one just hidden
    /** After a menu: an element that went leaves the focus nowhere; it lands on its nearest neighbour. */
    private fun settle() {
        if (open?.isShowing == true) return
        val cur = currentFocus
        if (cur != null && cur.isShown && cur.isFocusable) return
        val tag = wantTag; wantTag = null
        (tag?.let { t -> widgets.hot.firstOrNull { it.tag == t && it.isShown } }
            ?: headLeft.takeIf { it.isShown && it.isFocusable } ?: bar.buttons.firstOrNull { it.isShown } ?: firstGridTile())?.requestFocus()
    }

    private fun lookName(v: String) = resources.getIdentifier("home_ov_$v", "string", packageName).let { if (it != 0) getString(it) else v }
    private fun widgetName(k: String) = getString(resources.getIdentifier("w_$k", "string", packageName).takeIf { it != 0 } ?: R.string.w_qr)

    private fun widgetMenu(k: String, v: View) {
        val opts = HomeConfig.options(k).map { it.id }
        val on = Store.list(this, "widgets")
        val items = ArrayList<TvGlass.Item>()
        if (k == "adblock") items.add(gi(R.drawable.ic_shield_ok, getString(R.string.adb_toggle), on = { dev.tvremote.home.widgets.Adblock.on(this) }) { widgets.adblockToggle() })   // the widget's OK, as a switch
        if ("size" in opts) items.add(gi(R.drawable.ic_expand, getString(R.string.el_big), on = { k in HomeConfig.bigs(Store.get(this)) }) {
            val sizes = JSONObject((Store.get(this).optJSONObject("wsize") ?: JSONObject()).toString())   // the key Customize's gear writes
            Store.change(this, JSONObject().put("wsize", sizes.put(k, if (k in HomeConfig.bigs(Store.get(this))) "small" else "big")))
        })
        HomeConfig.options(k).firstOrNull { it.id == "look" }?.let { o ->              // one press, the next look: the widget changes behind the menu
            fun now() = HomeConfig.optValue(Store.get(this), k, "look")
            items.add(gi(R.drawable.ic_scene, getString(R.string.home_o_look), live = { lookName(now()) }) {
                Store.change(this, JSONObject().put("wopt", JSONObject().put(k, JSONObject().put("look", o.values[(o.values.indexOf(now()) + 1) % o.values.size]))))
            })
        }
        val i = on.indexOf(k)
        if (on.size > 1 && i >= 0) {
            val j = if (i > 0) i - 1 else 1
            items.add(gi(if (i > 0) R.drawable.ic_left else R.drawable.ic_right, getString(if (i > 0) R.string.el_earlier else R.string.el_later)) {
                val n = on.toMutableList(); n[i] = n[j]; n[j] = k; Store.putList(this, "widgets", n); wantTag = k
            })
        }
        items.add(gi(R.drawable.ic_eye_off, getString(R.string.el_hide_widget)) {
            val h = widgets.hot; val at = h.indexOfFirst { it.tag == k }
            wantTag = (h.getOrNull(at + 1) ?: h.getOrNull(at - 1))?.tag as? String
            Store.putList(this, "widgets", on - k)
        })
        items.add(moreItem("widgets")); items.add(editItem())
        elementMenu(widgetName(k), items, if (k == "adblock") getString(R.string.adb_note) else "", anchor = v)
    }

    private fun headMenu() = elementMenu(getString(R.string.el_head), listOf(
        switchItem(R.drawable.ic_clock, R.string.show_clock, "showClock"), switchItem(R.drawable.ic_calendar, R.string.show_date, "showDate"),
        switchItem(R.drawable.ic_message, R.string.greet, "greet"), moreItem(), editItem()), anchor = headLeft)

    private fun barMenu(b: BarBtn) {
        val gear = b === settingsPill
        elementMenu(b.label, listOf(hideItem(if (gear) R.string.el_hide_gear else R.string.el_hide_net, if (gear) "showGear" else "showNet"), moreItem(), editItem()),
            if (gear) getString(R.string.el_note_gear) else "", anchor = b)
    }

    private fun addMenu(t: View) = elementMenu(getString(R.string.add), listOf(hideItem(R.string.el_hide_add, "showAdd"), moreItem(), editItem()), getString(R.string.el_note_add), t)
    private fun suggestMenu(t: View) = elementMenu(getString(R.string.suggest_setup), listOf(hideItem(R.string.suggest_hide, "showSuggest"), moreItem(), editItem()), anchor = t)

    private fun suggestSetup() = conf.optBoolean("showSuggest", true) && !Store.suggestOff(this) && apps.none { it.pkg == Store.OWN[0] } && apps.isNotEmpty()

    private fun askSetup() {
        val acts = listOf(TvUi.Item(R.drawable.ic_download, getString(R.string.suggest_install)) { getSetup() },
            TvUi.Item(R.drawable.ic_eye_off, getString(R.string.suggest_hide)) { Store.change(this, JSONObject().put("showSuggest", false)) })   // the same switch as in Customize
        open = TvUi.menu(this, R.style.TvSheet, getString(R.string.suggest_setup), acts, R.drawable.ic_check, note = getString(R.string.suggest_note))
    }

    /** The latest TV Setup from the release, as TV Home's own update: Android's installer asks at the end. */
    private fun getSetup() {
        bg.execute {
            val r = try { Release.newer("0", "tv", "tv-setup.apk") } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (r != null) updating(r, SelfUpdate(this, "tv", "tv-setup.apk"), getString(R.string.suggest_setup))
                else Toast.makeText(this, getString(R.string.suggest_failed, "GitHub"), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (locked) return
        if (away) Store.advance(this, true)                           // Home is shown again: "each time" moves on here, and a timed one that fell due while away
        if (trimmed) untrim()
        else if (away) {                                              // a retry that came due while away did not run (a page attached then costs ~95 MB nobody sees): once now (not [webRetry]: it wants !away, cleared only in onResume)
            val w = conf.optString("wall")
            if (w == HomeConfig.WEB && web == null && conf.optString("wmode") != "snap") attachWeb() else if (HomeConfig.isVideo(w) && video == null) attachVideo(w)
        }
        WebWall.wake(web); snapper?.resume(); video?.start()                          // a page keeps running only while Home is on screen: it is a slideshow, not a service
    }

    override fun onStop() { away = true; ui.removeCallbacks(webRetry); ui.removeCallbacks(videoRetry); zoom.hidden(); ManageServer.release(); WebWall.sleep(web); snapper?.pause(); video?.stop(); super.onStop() }   // the QR stops opening the page; a phone already on it goes on until idle

    /** Memory is short and the screen is hidden: the wallpaper's big bitmap and every banner go, the next [onStart] brings them back. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (locked) return
        val low = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (away && low) trim()                                       // RUNNING_LOW..CRITICAL also count: a game in front can starve the system before UI_HIDDEN's later levels
        else if (low) Customize.forget()                              // shown: the tiles' logos and the wallpaper stay (a Coocaa lost both), only the panel's thumbnails go, made again when it opens
    }

    private fun trim() {
        if (trimmed) return
        trimmed = true
        val id = conf.optString("wall")
        detachWeb()                                                   // a page costs the most memory of any wall: gone while away, made again by [untrim]
        if (WallArt.has(id)) backdrop.show(wallColors(id, conf), null, id)      // a drawn scene costs nothing to keep: only the picture is repainted
        else backdrop.show(wallColors(id, conf), soft)                          // the blurred copy stands in, so the return is not a blank
        val keep = firstRows()                                        // the logos seen first on return stay (~10 x 230 KB): a home without them is worse than the memory
        for (t in tileCache.values) if (t.app !in keep) t.release()
        for (a in apps) if (a !in keep) a.release()
        Customize.forget()
    }

    private fun firstRows() = tiles().take(2 * conf.optInt("cols", HomeConfig.COLS_DEFAULT)).mapNotNull { it.app }.toSet()

    private fun untrim() {
        trimmed = false
        val dm = resources.displayMetrics
        if (Walls.isPicture(conf.optString("wall")) || conf.optString("wall") == HomeConfig.WEB) loadWall(conf.optString("wall"))
        for (t in tiles().take(2 * conf.optInt("cols", HomeConfig.COLS_DEFAULT))) { t.app?.let { if (it.banner == null && it.icon == null) it.load(this) }; t.restore() }   // the favourites at once (kept by [trim] unless the list moved), the rest from the side
        bg.execute {
            for (a in apps) if (a.banner == null && a.icon == null) a.load(this)
            runOnUiThread { if (!isDestroyed && !trimmed) for (t in tileCache.values) t.restore() }
        }
    }

    override fun onPause() { if (moving != null) endMove(false); zoom.paused(); ui.removeCallbacks(widgetTick); ui.removeCallbacks(netTick); ui.removeCallbacks(wallTick); ui.removeCallbacks(slideTick); ui.removeCallbacks(liveTick); ui.removeCallbacks(skyTick); ui.removeCallbacks(shiftTick); super.onPause() }

    private var cpuAgain = false

    private var noWidgets = false

    /**
     * The grid's top margin, so the first screen shows "rows" rows of apps (1.2: row 1 whole and a fifth of row 2), at most up to the
     * clock or the widgets. By itself: without widgets 1.2 (a clean screen that still says there is more), with them right under them.
     * From the real screen and tile heights.
     */
    private fun peek() {
        val lp = grid.layoutParams as LinearLayout.LayoutParams
        val rows = grid.childCount
        val rowH = if (rows > 0) grid.getChildAt(0).height else 0
        val want = conf.optDouble("rows", 0.0).let { if (it >= 1) it else if (noWidgets) 1.2 else 0.0 }
        if (want > 0 && rows > 0 && rowH == 0) return                   // new rows, not laid out yet: the next layout asks again
        val above = if (noWidgets) head.bottom else widgets.bottom
        val least = dp(if (noWidgets) 48 else 36); val gap = dp(GRID_GAP)
        if (rowH > 0 && scroll.height > 0) {
            val room = scroll.height - above - least
            val n = maxOf(0, (room + gap) / (rowH + gap)); val f = (room - n * (rowH + gap)).toDouble() / rowH
            rowsFit = maxOf(1.0, if (f >= 1) n + 1.0 else n + Math.floor(f * 10) / 10)
        }
        // one row sits where row 1 of many would (more columns put few apps in one row: it rose to the clock)
        val m = if (want == 0.0 || rows == 0 || rowH == 0 || scroll.height == 0) least else {
            val n = Math.floor(want + 1e-6).toInt(); val f = want - n
            // whole rows keep half a row clear under them (the rows below, hidden there until the focus goes down: [veil])
            val tall = if (f < 0.05) n * (rowH + gap) + rowH / 2 else n * (rowH + gap) + (f * rowH).toInt()
            maxOf(least, scroll.height - tall - above)
        }
        if (Math.abs(m - lp.topMargin) > 1) {
            // a new number of rows glides there: the grid moved at once and drawn from where it was (no layout per frame)
            val from = lp.topMargin
            lp.topMargin = m; grid.requestLayout()
            if (Motion.on && grid.isLaidOut && grid.alpha == 1f && grid.animation == null) {
                grid.animate().cancel()
                grid.translationY = grid.translationY + from - m
                grid.animate().translationY(0f).setDuration(320).setInterpolator(Motion.glide).withLayer().start()
            }
        }
        veil()
    }

    /** "rows" a whole number: the rows under them stay hidden in the half row left clear until the focus goes down into them. */
    private fun veil(glide: Boolean = true) {
        if (moving != null) {                                         // a step takes the carried tile out and back: focus leaves for a moment, and the
            for (i in 0 until grid.childCount) grid.getChildAt(i).let { it.animate().cancel(); it.alpha = 1f }   // fade it started (to 0) outlived the call that wanted 1: every other step hid the rows
            return
        }
        val r = conf.optDouble("rows", 0.0)
        val n = if (r >= 1 && r - Math.floor(r) < 0.05) Math.floor(r).toInt() else Int.MAX_VALUE
        var v: View? = currentFocus
        while (v != null && v.parent !== grid) v = v.parent as? View
        val down = v != null && grid.indexOfChild(v) >= n
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i); val want = if (i < n || down) 1f else 0f
            if (row.alpha != want) { row.animate().cancel(); if (glide && Motion.on) row.animate().alpha(want).setDuration(220).start() else row.alpha = want }
        }
    }

    /** The widgets' numbers, read off the main thread; none at all while they are off. */
    private fun widgetsRead() {
        val want = Store.list(this, "widgets")
        val conf = Store.get(this); val note = conf.optString("note"); val sizes = conf.optJSONObject("wsize") ?: JSONObject()
        val none = want.isEmpty() && note.isEmpty()
        noWidgets = none
        peek()                                                        // the grid's margin is peek()'s alone: set here too, every minute's read made it jump
        if (none) { widgets.visibility = View.GONE; return }
        bg.execute {
            val f = widgets.read(want)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                widgets.show(want, f, note, { p -> apps.firstOrNull { it.pkg == p }?.label ?: p.substringAfterLast('.') }, sizes, conf.optJSONObject("qrs") ?: JSONObject())
                if (widgets.speed != null) { ui.removeCallbacks(netTick); ui.postDelayed(netTick, Widgets.SAMPLE_MS) }   // the graph was just added: its tick starts
                // TV Setup answers the CPU with its last measure and starts the next: the first time there is none, so ask again soon, once
                if ("system" in want && f.cpu == null && !cpuAgain) { cpuAgain = true; ui.postDelayed({ if (!isDestroyed) widgetsRead() }, 2500) }
            }
        }
    }

    /** The screen comes in on the first start: the clock and the buttons ease down, the grid rises. */
    private fun enter() {
        val d = dp(14).toFloat()
        head.alpha = 0f; head.translationY = -d
        grid.alpha = 0f; grid.translationY = d
        head.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(Motion.glide).withLayer().start()
        grid.animate().alpha(1f).translationY(0f).setStartDelay(70).setDuration(420).setInterpolator(Motion.glide).withLayer().start()
    }

    /** Scrolls to [y] gliding (one animation at a time, the last target wins); at once when motion is off. */
    /** The glass onto this tile, when the focus is glass ([Motion.glass]); [glide] false: there at once. A tile being moved has the ring alone. */
    /** The pane onto a stop that is not a tile (a widget, the header, a top-bar button), in its own shape and corners. */
    private fun glassTo(v: View, glide: Boolean = true, r: Float? = null) {
        glassAt?.removeOnLayoutChangeListener(glassFollow)
        if (!Motion.glass || moving != null) { glass.hide(); glassAt = null; return }
        glassAt = v; v.addOnLayoutChangeListener(glassFollow)        // a widget that grows (the weather came) takes the pane with it
        glass.to(content, v, 1f, r ?: when (v) { is BarBtn -> v.height / 2f; headLeft -> dp(18).toFloat(); else -> dp(22).toFloat() }, glide && Motion.on)
    }
    private var glassAt: View? = null
    private val glassFollow = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (v.hasFocus() && glass.placed) v.post { if (v.isFocused) glassTo(v, false) } }
    /** Focus left a stop: the pane goes unless the next one is a stop too (it is placed there by its own listener). */
    private fun glassOff() { glassAt?.removeOnLayoutChangeListener(glassFollow); glassAt = null; content.post { if (!glassy(currentFocus)) glass.hide() } }
    private fun glassy(v: View?) = v is Tile || v is BarBtn || v === headLeft || (v != null && v in widgets.hot)

    private fun Tile.glassOn(glide: Boolean) { if (Motion.glass && moving == null) glass.to(content, pane, TvTile.GROW, corner, glide && Motion.on) else glass.hide() }

    private fun scrollToY(y: Int) {
        scrollAnim?.cancel()
        if (!Motion.on) { scroll.scrollTo(0, y); return }
        if (scroll.scrollY == y) return
        scrollAnim = ObjectAnimator.ofInt(scroll, "scrollY", scroll.scrollY, y).apply { duration = 420; interpolator = Motion.glide; start() }
    }

    /** Home pressed while already home, or Back: to the top, on the first tile. */
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (locked) return
        if (i.action == InputOpen.INPUTS) { inputs(); return }
        if (i.action == HomeConfig.QUICK_KEYS) { open?.dismiss(); open = null; dev.tvremote.home.keys.QuickKeys.show(this); return }   // TV Setup's key tester: "assign this key"
        if (i.action == HomeConfig.PHOTO_QR) { open?.dismiss(); PhotoScreen.show(this) {}; return }                  // the remote's Input key while another app is in front
        if (open?.isShowing == true) { open?.dismiss(); open = null; return }                          // a panel or menu is open: this press closes it
        val last = if (away) lastOpened?.let { p -> tiles().firstOrNull { it.app?.pkg == p } } else null
        if (last != null) last.requestFocus() else toTop()            // back from an app: on its tile; Home on the home screen: to the top
    }

    @Deprecated("Back on a home screen goes nowhere")
    override fun onBackPressed() { if (editing) { endEdit(); return }; if (!locked && SystemClock.uptimeMillis() - back > 500) toTop() }

    // Android 13+ hands the Back that closed a menu or the panel to the home too: it must not also move the focus to the dock
    private var back = 0L
    private var wantInputs = false
    override fun onWindowFocusChanged(has: Boolean) { super.onWindowFocusChanged(has); if (has) back = SystemClock.uptimeMillis() }

    private fun toTop() {
        val first = firstGridTile()
        if (first != null && first.isFocused && scroll.scrollY == 0) return    // already there: nothing to move, nothing to redraw
        scrollToY(0)
        first?.requestFocus()
    }

    /** The marks on the tiles: asked of TV Setup off the main thread, on coming back and then every minute; the answer is applied only where it differs. */
    private fun badgesRead() {
        bg.execute {
            val info = Badges.withOwn(Badges.read(this) ?: Badges.NONE, HomeLock.locked(this))
            runOnUiThread { if (!isDestroyed && !(info.bed == badges.bed && info.by == badges.by && info.kids == badges.kids && info.open == badges.open)) { badges = info; badgeTiles() } }
        }
    }

    private fun badgeTiles() {
        val m = getString(R.string.b_m); val h = getString(R.string.b_h)
        val conf = Store.get(this)
        val power = if (conf.optBoolean("autoon")) conf.optString("autoapp") else ""
        for (t in tileCache.values) t.app?.let { a -> t.mark(badges.of(a.pkg) ?: Badges.POWER.takeIf { a.pkg == power }) { Badges.label(it, m, h) } }
        // which keys open each tile: a key's target is the tile's own key (a package, "pin:…", "tile:…"), only while keys are on
        val byTile = HashMap<String, MutableList<Int>>()
        if (conf.optBoolean("keyon")) for ((code, t) in HomeConfig.keyApps(conf.optJSONObject("keyapps"))) byTile.getOrPut(t) { mutableListOf() }.add(code)
        for (t in tileCache.values) t.app?.let { a -> t.keys(byTile[a.pkg]?.sorted() ?: emptyList()) { dev.tvremote.home.keys.Keys.name(this, it) } }
    }

    // ---- the one-time tip: "Hold OK on an app for more options", under the first row while focus is in it; gone once ✕ or a menu was used ----

    private val tipClose: ImageView by lazy {
        ImageView(this).apply {
            setImageResource(R.drawable.ic_close); setPadding(dp(6), dp(6), dp(6), dp(6)); isFocusable = true; contentDescription = getString(R.string.tip_close)
            imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(PILL_INK, Color.WHITE))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x00000000) })
            }
            setOnClickListener { Store.tipDone(this@HomeActivity); (grid.getChildAt(0) as? ViewGroup)?.let { r -> r.getChildAt(Nav.nearest(centres(r), centre(this)).coerceAtLeast(0)) }?.let { go(it) }; tip() }   // focus goes back to the row, not to what is nearest by luck
            setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) false
                else when (code) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val toRow = (code == KeyEvent.KEYCODE_DPAD_UP) == tipBelow          // toward the first row; the other way leaves the tip: row 2 (below it) or what is above the grid (above it)
                        val r = grid.getChildAt(if (toRow || !tipBelow) 0 else 1) as? ViewGroup
                        val t = r?.getChildAt(Nav.nearest(centres(r), centre(this)).coerceAtLeast(0))
                        if (t == null) nudge() else if (toRow || tipBelow) go(t) else { tipPast = true; try { step(t as Tile, KeyEvent.KEYCODE_DPAD_UP) } finally { tipPast = false } }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { nudge(); true }
                    else -> false
                }
            }
        }
    }
    private fun View.nudge() { animate().cancel(); animate().translationY(dp(3).toFloat()).setDuration(70).withEndAction { animate().translationY(0f).setDuration(120).start() }.start() }
    private val tipView: LinearLayout by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; alpha = 0f
            setPadding(dp(20), dp(6), dp(8), dp(6)); elevation = dp(6).toFloat()
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(PILL_BG); setStroke(dp(1), PILL_EDGE) }
            addView(Font.on(TextView(this@HomeActivity)).apply { text = getString(R.string.tip_menu); textSize = 16f; setTextColor(Color.WHITE) })
            addView(tipClose, LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(14) })
            content.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(48) })
        }
    }
    private var tipOn = false
    private var tipPast = false                                       // ✕ hands Up on to what is above the grid: the tip must not catch it again
    private var tipBelow = true                                       // under the row's names when the screen has room (first row seen from the top of the page), else just above the row

    private fun tipPlace(row: ViewGroup) {
        var y = 0; var p: View? = row
        while (p != null && p !== content) { y += p.top; p = p.parent as? View }
        val h = dp(48)
        tipBelow = y + row.height + dp(40) + h <= scroll.height - dp(8)
        val at = if (tipBelow) y + row.height + dp(40) else y - h - dp(24)   // clear of the focused tile, which grows when focused
        val lp = tipView.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != at) { lp.topMargin = at; tipView.requestLayout() }
    }

    /** Shown while focus is on the first row's tiles (or on its ✕); an overlay in the scrolled page, so the grid does not move. */
    private fun tip() {
        val row = grid.getChildAt(0) as? ViewGroup
        val f = currentFocus
        val want = !locked && !away && moving == null && !Store.tipIsDone(this) && row != null && row.childCount > 0 && f != null && (f === tipClose || (f.parent === row && f is Tile))
        if (want) tipPlace(row!!)
        if (want == tipOn) return
        tipOn = want
        tipView.animate().cancel()
        val d = if (Motion.on) 150L else 0L
        if (want) { tipView.visibility = View.VISIBLE; tipView.animate().alpha(1f).setDuration(d).start() }
        else tipView.animate().alpha(0f).setDuration(d).withEndAction { if (!tipOn) tipView.visibility = View.GONE }.start()
    }

    private fun firstGridTile(): View? = (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)

    /** Where Down from [v] (a bar button or a widget) lands in the grid: the tile it came up from (a widget only), else the first row's tile nearest in x. */
    private fun below(v: View): View? {
        topAt?.takeIf { it.isAttachedToWindow && topVia != null && topVia === v }?.let { return it }
        val row = grid.getChildAt(0) as? ViewGroup ?: return null
        return row.getChildAt(Nav.nearest(centres(row), centre(v)).takeIf { it >= 0 } ?: return null)
    }

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart
            if (pkg == null) { loadApps(); return }
            if (i.action == Intent.ACTION_PACKAGE_REMOVED && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return   // the new version's ADDED follows
            packageChanged(pkg, i.action == Intent.ACTION_PACKAGE_REMOVED)
        }
    }
    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // a screensaver over Home: Android 11+ stops the activity, older TVs only lay a window over it and the video went on decoding
            when (i.action) { Intent.ACTION_DREAMING_STARTED -> { video?.stop(); WebWall.sleep(web); return }
                Intent.ACTION_DREAMING_STOPPED -> { if (!away) { video?.start(); WebWall.wake(web) }; return } }   // the live page sleeps under it too: it was drawing frames nobody sees
            clockAndDay(); bingCheck()                                // Bing: a new day's picture seen while Home stays on
        }
    }

    private var seq = 0                                               // PackageManager's change counter at the last full list; 0 = everything since boot

    /** Back on screen: the list is read again only if the system says a package changed meanwhile (API 26+); a missed broadcast shows up here. */
    private fun appsSince() {
        if (Build.VERSION.SDK_INT < 26) return                        // no counter: the broadcasts are all there is, as before
        val ch = try { packageManager.getChangedPackages(seq) } catch (e: Exception) { null } ?: return   // null = nothing changed
        val names = ch.packageNames.filter { it != packageName }
        if (names.isEmpty()) { seq = ch.sequenceNumber; return }
        if (names.size > 3) { loadApps(); return }                    // many at once (an update of everything): one pass beats a tile each
        seq = ch.sequenceNumber
        for (n in names) packageChanged(n, false)                     // skips by itself a part that turned on or off with the same name and entry
    }

    private fun loadApps() {
        if (Build.VERSION.SDK_INT >= 26) try { packageManager.getChangedPackages(seq)?.let { seq = it.sequenceNumber } } catch (e: Exception) { }   // before the list, so a change during it is seen on the next return
        bg.execute {
            val list = Apps.list(this)
            list.forEach { it.load(this) }                            // the pictures off the main thread: dozens of apps on a slow chip
            runOnUiThread { if (!isDestroyed) { apps = (list + apps.filter { it.grid }).sortedBy { it.label.lowercase() }; shownKey = ""; show() } }
        }
    }

    /** The shortcuts other apps pinned here ([Pins]): the grid is built again only when they changed. */
    internal fun pinsRead() {
        bg.execute {
            val now = Pins.list(this)
            if (now.map { it.pkg to Pins.stamp(it) } == pins.map { it.pkg to Pins.stamp(it) }) return@execute   // a new icon too (TV Channels mends a pin made with no logo)
            now.forEach { it.load(this) }
            runOnUiThread { if (!isDestroyed) { pins = now; shownKey = ""; show() } }
        }
    }

    /** One app came, went or changed: only its picture is read again, the other tiles are kept and moved. */
    private fun packageChanged(pkg: String, removed: Boolean) {
        if (pkg == packageName) return
        bg.execute {
            if (removed) forget(pkg)
            val one = if (removed) null else Apps.one(this, pkg)?.also { it.load(this) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (removed && apps.any { it.grid && Target.pkg(it.pkg) == pkg }) apps = apps.filter { !(it.grid && Target.pkg(it.pkg) == pkg) }
                val was = apps.firstOrNull { it.pkg == pkg }
                if (one == null && was == null) return@runOnUiThread    // not an app this screen lists (a service, a library)
                // a part of it turned on or off with its name and entry the same: some boxes' apps do it every 15 s, and each rebuilt the grid.
                // An update is not that: its APK moved, and the kept entry would read its pictures again from the old, deleted file after a
                // trim (TV Setup's tile came back as Android's default icon)
                if (one != null && was != null && one.label == was.label && one.info?.name == was.info?.name
                    && one.info?.iconResource == was.info?.iconResource && one.info?.bannerResource == was.info?.bannerResource
                    && one.info?.applicationInfo?.sourceDir == was.info?.applicationInfo?.sourceDir) return@runOnUiThread
                apps = (apps.filter { it.pkg != pkg } + listOfNotNull(one)).sortedBy { it.label.lowercase() }
                shownKey = ""; show()
                pinsRead()                                            // an app's pins leave with it
            }
        }
    }

    /**
     * An app that left takes its shortcuts and live widgets off the grid, the dock and the order, and out of the picker's list: they
     * stayed as dead grid items (TV Channels' VTV1, its "Keep watching") until each was removed by hand. Its keys and power-on choice
     * stay. [pkg] null: every app no longer installed (on resume: one removed while Home was not running missed the broadcast).
     */
    private fun forget(pkg: String?) {
        fun gone(p: String) = if (pkg != null) p == pkg else try { packageManager.getPackageInfo(p, 0); false } catch (e: Exception) { true }
        val change = JSONObject()
        for (k in listOf("tiles", "dock", "order", "hidden")) {
            val l = Store.list(this, k)
            val keep = l.filter { !(Target.kind(it).let { t -> t == Target.Kind.PIN || t == Target.Kind.TILE } && gone(Target.pkg(it))) }
            if (keep.size != l.size) change.put(k, org.json.JSONArray(keep))
        }
        val folders = if (Store.get(this).optJSONObject("folders")?.length() ?: 0 > 0) Folders.forget(Store.get(this)) { gone(Target.pkg(it)) } else null   // an uninstalled app leaves its folder (an emptied one goes)
        if (folders != null) runOnUiThread { if (!isDestroyed) Store.change(this, Folders.forget(Store.get(this)) { gone(Target.pkg(it)) } ?: return@runOnUiThread) }
        if (change.length() > 0) runOnUiThread { if (!isDestroyed) Store.change(this, change) }   // its listeners redraw: the main thread only (a dialog "Only the original thread…" on the FPT box)
        if (pkg != null) { Pins.refresh(this); LiveWidgets.refresh(this) }
    }

    // ---- what is shown ----

    /** A change of the settings ([Store.onChange]): everything that reads them, drawn again. */
    private fun onConf() {
        readNames(); if (Pins.resize(pinW())) pinsRedraw()
        if (conf.optString("lang") != Lang.shown) relang() else { show(); watchRead(); wallTimer(); bingCheck(); peek(); folderFill?.invoke() }
    }

    /** [b] is [a] with another "shade" and nothing else. */
    private fun veilOnly(a: JSONObject, b: JSONObject): Boolean {
        if (a === b || a.optInt("shade", -1) == b.optInt("shade", -1) || a.length() != b.length()) return false
        for (k in b.keys()) if (k != "shade" && a.opt(k)?.toString() != b.opt(k)?.toString()) return false
        return true
    }

    private fun show() {
        moving?.let { it.carry(false); hint(false); for (o in tiles()) { o.animate().cancel(); o.alpha = 1f } }; moving = null                           // anything that rebuilds the tiles ends a move that was not confirmed: the saved order wins
        val keep = focusNext ?: (currentFocus as? Tile)?.app?.pkg
        focusNext = null
        val keepAt = tiles().indexOf(currentFocus)                    // its place: an app hidden or a live widget taken off leaves no tile to go back to
        Motion.read(this, conf)
        if (!Motion.glass) glass.hide()
        wall()
        clockAndDay()
        widgetsRead()
        syncLive()
        if (Store.promote(this, apps.map { it.pkg })) return          // TV Setup, Games or Channels came: the change draws the screen again
        val byPkg = apps.associateBy { it.pkg }
        val byKey = byPkg + pins.filter { it.pkg !in byPkg }.associateBy { it.pkg }   // a pinned shortcut is one of the grid's tiles, with a place of its own like an app
        val dockPkgs = Store.list(this, "dock").filter { it in byKey }
        val hidden = Store.list(this, "hidden").toSet()
        val shownPins = pins.filter { it.pkg !in byPkg && it.pkg !in dockPkgs }   // a shortcut put on the grid by the picker shows as that tile, not twice
        val inFolder = if (conf.optJSONObject("folders")?.length() ?: 0 == 0) emptySet() else Folders.members(conf)   // none: no work at all
        val key = (dockPkgs + shownPins.map { it.pkg }).joinToString(",") + "|" + apps.filter { it.pkg !in dockPkgs && it.pkg !in hidden && it.pkg !in inFolder }.joinToString(",") { it.pkg } + "|" + apps.filter { it.folder }.joinToString(",") { it.pkg + it.members } + "|" + apps.count { it.pkg in hidden } + "|" + conf.optInt("cols") + "|" + conf.optJSONArray("order") + "|" + conf.optJSONObject("names") + "|" + suggestSetup() + "|" + conf.optBoolean("showAdd", true)
        val all = key + "|" + watchAt() + "|" + watch.joinToString(",") { it.first.key + it.first.title + (it.second != null) }
        if (all == shownKey) { badgeTiles(); return }                 // nothing that decides the tiles changed: not one view is rebuilt (the power mark may have)
        shownKey = all
        val made = HashMap<String, Tile>()                            // the tiles kept for the next build: those of apps still here
        val favs = dockPkgs.map { byKey.getValue(it) }

        for (r in 0 until grid.childCount) (grid.getChildAt(r) as ViewGroup).removeAllViews()   // the tiles leave their rows alive, to be put in new ones
        grid.removeAllViews()
        val place = Store.list(this, "order").withIndex().associate { it.value to it.index }   // the grid's own order; an app it does not know goes last, A-Z
        val rest = (shownPins + apps.filter { it.pkg !in dockPkgs && it.pkg !in hidden && it.pkg !in inFolder }).sortedBy { place[it.pkg] ?: if (it.pin != null && !it.grid) -1 else Int.MAX_VALUE }   // a pin nobody placed stays where it always was: right after the favourites
        val cols = conf.optInt("cols", HomeConfig.COLS_DEFAULT)
        val gap = dp(GRID_GAP)
        val w = tileW()                                               // one size for every tile: the favourites are only the first ones
        val cells: MutableList<View> = (favs + rest).map { tile(it, w, made) }.toMutableList()
        if (suggestSetup()) cells.add(favs.indexOfFirst { it.pkg !in Store.OWN }.let { if (it < 0) favs.size else it }, moves(Tile(this, null, w, getString(R.string.suggest_setup), ::dp,
            art = getDrawable(R.drawable.suggest_setup), corner = getString(R.string.suggest_install)).apply { setOnClickListener { if (editing) suggestMenu(this) else askSetup() }; setOnLongClickListener { suggestMenu(this); true } }) { suggestMenu(currentFocus ?: grid) })   // with the project's other apps in the first row: seen, as its own would be
        if (conf.optBoolean("showAdd", true)) cells.add(moves(Tile(this, null, w, getString(R.string.add), ::dp, mark = R.drawable.ic_plus).apply { setOnClickListener { if (editing) addMenu(this) else addPanel() }; setOnLongClickListener { addMenu(this); true } }) { addMenu(currentFocus ?: grid) })   // the way to put anything on the grid: hidden apps, shortcuts, live widgets
        // other apps' Play Next and channels ([Watch]): by "watchAt" a row of their own (above, under the first row, after the last: it scrolls sideways) or tiles after the grid's last
        val shows = watch.take(watchCap()).map { (it, pic) -> moves(Tile(this, null, w, it.title, ::dp, mark = R.drawable.ic_play, art = pic?.let { b -> android.graphics.drawable.BitmapDrawable(resources, b) }, corner = if (pic == null) it.app else null)
            .apply { setOnClickListener { _ -> if (editing) watchMenu(it) else watchOpen(it) }; setOnLongClickListener { _ -> watchMenu(it); true } }) { watchMenu(it) } }
        val at = when (watchAt()) { "top" -> 0; "under1" -> minOf(cols, cells.size); else -> cells.size }
        cells.addAll(at, shows)
        val own = shows.isNotEmpty() && watchAt() != "tiles"
        fill(cells, cols, gap, if (own) setOf(at, at + shows.size) else emptySet(), if (own) at until at + shows.size else IntRange.EMPTY)
        tileCache = made
        badgeTiles()
        ui.removeCallbacks(liveTick); if (!away && liveViews().isNotEmpty()) ui.post(liveTick)   // a new grid asks at once

        val again = (keep ?: lastOpened.takeIf { currentFocus == null })?.let { p -> tiles().firstOrNull { it.app?.pkg == p } }
        val near = if (again == null && keepAt >= 0) tiles().let { t -> t.getOrNull(minOf(keepAt, t.size - 1)) } else null   // the one now in its place (the last, if it was last): not the top of Home, a scroll away
        if (again != null) again.requestFocus() else if (near != null) near.requestFocus() else if (currentFocus == null || currentFocus !is Tile && currentFocus !is BarBtn) toTop()
    }

    /** A focused tile's width: what a pin's picture is drawn at ([Pins.fit]). */
    private fun pinW() = (tileW() * TvTile.GROW).toInt()

    /** The columns changed: the pins' pictures again at the new size (the grid's own pins come again through [Pins.app]). */
    private fun pinsRedraw() {
        val now = pins
        bg.execute { now.forEach { it.load(this) }; runOnUiThread { if (!isDestroyed) { shownKey = ""; show() } } }
    }

    private fun tileW(): Int { val cols = conf.optInt("cols", HomeConfig.COLS_DEFAULT); return (resources.displayMetrics.widthPixels - dp(96) - (cols - 1) * dp(GRID_GAP)) / cols }

    /** [breaks]: where a row starts early (the Play Next row stands alone); [strip]: the cells that are one sideways-scrolling [Strip] whatever their number. */
    private fun fill(cells: List<View>, cols: Int, gap: Int, breaks: Set<Int> = emptySet(), strip: IntRange = IntRange.EMPTY) {
        var row: LinearLayout? = null
        var n = 0
        cells.forEachIndexed { i, v ->
            if (i == 0 || (n == cols && i !in strip) || i in breaks) {
                if (i in strip) {
                    val px = dp(STRIP_PAD); val py = dp(STRIP_PAD_V)   // bounds wider than the grid by the room a focused tile's ring needs, so the clip cuts nothing of it
                    row = Strip(this, px, py)
                    grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(-px, (if (i > 0) gap else 0) - py, -px, -py) })
                } else {
                    row = Flat(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                    grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = gap })   // as far apart as the tiles are side by side
                }
                n = 0
            }
            row!!.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (n > 0) leftMargin = gap })
            n++
        }
        veil(false)                                                   // new rows come shown: the hidden ones at once, not seen fading out
    }

    /**
     * The Play Next row of its own: as many tiles as there are, in a row as wide as the grid, scrolled sideways by the focus (it is not a
     * ScrollView: its tiles stay its direct children, so [step], [tiles] and the move mode treat it as any row). Its bounds are
     * [pad] wider than the grid each side, its first tile still on the grid's first column.
     */
    private inner class Strip(c: Context, private val pad: Int, padV: Int) : LinearLayout(c) {
        // clipChildren cut each tile to its own resting bounds: a focused one grew square-cornered, its ring and glass edge gone
        // (the user, 2026-10-27). Only the sides are cut, to the row's width (clipBounds, not scrolled): the tiles past it stay hidden
        init {
            orientation = HORIZONTAL; clipToPadding = false; clipChildren = false; setPadding(pad, padV, pad, padV)
            addOnLayoutChangeListener { v, _, _, r, b, _, _, or, ob -> if (r != or || b != ob) v.clipBounds = android.graphics.Rect(0, -v.height, v.width, 2 * v.height) }
        }
        override fun hasOverlappingRendering() = false

        /** Measured as wide as its tiles want (a LinearLayout gives the ones past its own width none), then cut to the room it has. */
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), h)
            setMeasuredDimension(MeasureSpec.getSize(w), measuredHeight)
        }

        /** Scrolls so [v] is in view with a bit of the next tile showing (the first / last at the edge); the slide to run after the focus pane is placed on the new spot, or null. */
        fun reveal(v: View): (() -> Unit)? {
            val last = getChildAt(childCount - 1) ?: return null
            if (width == 0) return null
            val peek = dp(36)
            val max = maxOf(0, last.right - (width - pad))
            var to = scrollX
            if (v.left >= scrollX + pad && v.right <= scrollX + width - pad) return null   // wholly inside the grid's width: it stays (arriving from Up / Down must not move the row)
            if (v.left - peek < scrollX + pad) to = v.left - peek - pad
            else if (v.right + peek > scrollX + width - pad) to = v.right + peek - (width - pad)
            to = to.coerceIn(0, max)
            if (to == scrollX) return null
            val d = (to - scrollX).toFloat()
            scrollX = to                                       // the pane goes to the tile's final place; the tiles slide there
            if (!Motion.on) return null
            return { for (i in 0 until childCount) getChildAt(i).let { t -> t.animate().cancel(); t.translationX += d; t.animate().translationX(0f).setDuration(320).setInterpolator(Motion.glide).start() } }
        }
    }

    // ---- move mode: an app is carried through every row with the D-pad, OK saves, Back / Home cancels ----

    private var moving: Tile? = null
    private val hintView: TextView by lazy {
        Font.on(TextView(this)).apply {                                // a pill at the top centre, over no tile (the clock is left, the buttons right)
            text = getString(R.string.move_hint); textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(22), dp(10), dp(22), dp(10)); visibility = View.GONE; alpha = 0f
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(PILL_BG); setStroke(dp(1), PILL_EDGE) }
            (window.decorView as ViewGroup).let { (it.findViewById<ViewGroup>(android.R.id.content)).addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40) }) }
        }
    }

    private fun startMove(pkg: String) {
        val t = tiles().firstOrNull { it.app?.pkg == pkg } ?: return
        moving = t; movingFavs = dockCount()
        t.requestFocus(); t.carry(true); glass.hide()                 // the glass's edge inside the ring read as two borders
        for (o in tiles()) if (o !== t) o.animate().cancel().also { o.animate().alpha(DIM).setDuration(if (Motion.on) 150L else 0L).start() }   // the rest steps back
        hint(true)
    }

    private fun hint(on: Boolean) {
        hintView.animate().cancel()
        if (on) { hintView.visibility = View.VISIBLE; hintView.animate().alpha(1f).setDuration(if (Motion.on) 150L else 0L).start() }
        else hintView.animate().alpha(0f).setDuration(if (Motion.on) 150L else 0L).withEndAction { hintView.visibility = View.GONE }.start()
    }

    /** The tiles an app can be carried through: every app's, across the rows (the favourites are only the first [movingFavs]); a pinned shortcut keeps its place after them. */
    private fun movable(): List<Tile> = tiles().filter { it.app != null }
    private var movingFavs = 0

    /** The favourites are the first tiles, as many as the saved dock holds (pins and the rest follow). */
    private fun dockCount(): Int { val by = (apps.map { it.pkg } + pins.map { it.pkg }).toSet(); return Store.list(this, "dock").count { it in by } }

    private fun moveKey(t: Tile, code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { endMove(true); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { endMove(false); return true }
        }
        val cols = conf.optInt("cols", HomeConfig.COLS_DEFAULT)
        val d = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -cols; KeyEvent.KEYCODE_DPAD_DOWN -> cols
            else -> return false
        }
        val shown = tiles()
        val m = movable().toMutableList()
        val at = m.indexOf(t); val to = at + d
        if (to !in m.indices) { t.nudge(if (d == 1) 1 else if (d == -1) -1 else 0, if (d == cols) 1 else 0); return true }
        val before = shown.associateWith { val xy = IntArray(2); it.getLocationOnScreen(xy); xy }
        m.add(to, m.removeAt(at))
        val rows = (0 until grid.childCount).map { r -> (grid.getChildAt(r) as ViewGroup).let { row -> (0 until row.childCount).map { row.getChildAt(it) } } }
        val next = m.iterator()                                       // apps take the apps' places in order; every other tile (Play Next, + Add, Get TV Setup) keeps its own,
        val laid = rows.map { row -> row.map { v -> if ((v as? Tile)?.app != null) next.next() else v } }   // so the Play Next row stays a row (laid out i / cols, it broke the grid: the user, 2026-10-26)
        val gap = dp(GRID_GAP)
        for (r in 0 until grid.childCount) (grid.getChildAt(r) as ViewGroup).removeAllViews()
        laid.forEachIndexed { r, row ->                               // the same rows (a step moves no row): their fade and the page stay, nothing is re-made or veiled
            row.forEachIndexed { i, v -> (grid.getChildAt(r) as ViewGroup).addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = gap }) }
        }
        stepping = true; try { t.requestFocus() } finally { stepping = false }
        t.carry(true)
        grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                grid.viewTreeObserver.removeOnPreDrawListener(this)
                val now = IntArray(2)
                for ((v, o) in before) {                              // the others slide from where they stood
                    v.getLocationOnScreen(now)
                    val dx = (o[0] - now[0]).toFloat(); val dy = (o[1] - now[1]).toFloat()
                    if (dx == 0f && dy == 0f) continue
                    v.animate().cancel()
                    if (!Motion.on) { v.translationX = 0f; v.translationY = 0f; continue }
                    v.translationX = dx; v.translationY = dy
                    v.animate().translationX(0f).translationY(0f).setDuration(150).setInterpolator(Motion.glide).start()
                }
                if (moving === t) reveal(t)                           // the rows are laid out now: a carried tile is followed down and up the page like focus (it used the old layout's numbers before)
                return true
            }
        })
        return true
    }

    private fun endMove(save: Boolean) {
        val t = moving ?: return
        moving = null
        t.carry(false); t.glassOn(false)
        hint(false)
        for (o in tiles()) o.animate().cancel().also { o.animate().alpha(1f).setDuration(if (Motion.on) 150L else 0L).start() }
        if (save) {                                                   // the first places are the favourites, as many as before: an app carried in pushes the last one down
            val m = movable().mapNotNull { it.app?.pkg }; val k = movingFavs.coerceAtMost(m.size)
            val here = (apps.map { it.pkg } + pins.map { it.pkg }).toSet()
            Store.change(this, JSONObject()                           // one write: one rebuild
                .put("dock", org.json.JSONArray(m.take(k) + Store.list(this, "dock").filter { it !in here }))   // an app no longer installed keeps its place at the end
                .put("order", org.json.JSONArray(m.drop(k))))           // the grid's order; apps that leave drop out of it by not being listed
        } else { shownKey = ""; show() }                              // the saved order, tile by tile as it was
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val t = moving
        if (t != null && !locked) {
            if (e.action == KeyEvent.ACTION_DOWN) { if (e.repeatCount == 0 || e.keyCode != KeyEvent.KEYCODE_DPAD_CENTER) moveKey(t, e.keyCode).let { if (it) return true } }
            else if (moveKeys.contains(e.keyCode)) return true        // the key-up of what was taken
        }
        return super.dispatchKeyEvent(e)
    }
    /** Colour keys and 0-9 given an app in the settings (Keys): answered here on TV Home's own screen; elsewhere TV Setup's key filter does. */
    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean = (!locked && moving == null && Keys.onKey(this, e)) || super.onKeyDown(keyCode, e)

    private val moveKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)

    private fun tiles(): List<Tile> {
        val out = ArrayList<Tile>()
        for (r in 0 until grid.childCount) (grid.getChildAt(r) as ViewGroup).let { row -> for (i in 0 until row.childCount) (row.getChildAt(i) as? Tile)?.let { out.add(it) } }
        return out
    }

    /**
     * The D-pad on a tile, decided here, not by the nearest view ([Nav]): Right on a row's last tile goes to the next row's first,
     * Left the other way, Up and Down keep the column they started in as far as the next row has it (else its last tile); at the ends
     * focus stays (the nearest view out there would be a header button). True = handled. Up from the first row goes to the buttons.
     */
    /** Up from the top row of tiles: the button on the head nearest above it. */
    private fun up(v: View) {
        val at = IntArray(2); v.getLocationOnScreen(at)
        val x = at[0] + v.width / 2
        bar.buttons.filter { it.isShown }.minByOrNull { p ->
            p.getLocationOnScreen(at); Math.abs(at[0] + p.width / 2 - x)
        }?.requestFocus()
    }

    private fun step(v: Tile, code: Int): Boolean {
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> Nav.Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_LEFT -> Nav.Dir.LEFT
            KeyEvent.KEYCODE_DPAD_DOWN -> Nav.Dir.DOWN
            KeyEvent.KEYCODE_DPAD_UP -> Nav.Dir.UP
            else -> return false
        }
        val rows = IntArray(grid.childCount) { (grid.getChildAt(it) as ViewGroup).childCount }
        val row = (0 until grid.childCount).firstOrNull { (grid.getChildAt(it) as ViewGroup).indexOfChild(v) >= 0 } ?: return false
        val col = (grid.getChildAt(row) as ViewGroup).indexOfChild(v)
        if (row == 0 && tipOn && !tipPast && dir == (if (tipBelow) Nav.Dir.DOWN else Nav.Dir.UP)) { go(tipClose); return true }   // the tip's ✕ is the next stop away from row 1 (below or above it); the key again goes on
        val here = grid.getChildAt(row) as? Strip
        val sideways = dir == Nav.Dir.LEFT || dir == Nav.Dir.RIGHT
        var to = Nav.grid(rows, row, col, wantCol, dir)
        if (to != null && sideways && (here != null && to.row != row || grid.getChildAt(to.row) is Strip && to.row != row)) to = null   // a sideways row ends at its ends: the next row is Up / Down
        val vert = to != null && !sideways && (here != null || grid.getChildAt(to.row) is Strip)
        if (vert && to != null) to = Nav.At(to.row, maxOf(0, Nav.nearest(centres(grid.getChildAt(to.row) as ViewGroup), centre(v))))   // to or from a scrolled row: the nearest tile by position, not by column number
        if (to == null) {
            if (dir == Nav.Dir.UP) { val hot = widgets.hotAt(centre(v), fromBelow = true); topAt = v; topVia = hot; (hot ?: run { up(v); null })?.requestFocus() }               // Android 9 (a BRAVIA) found nothing above the first row by itself
            else v.nudge(if (dir == Nav.Dir.RIGHT) 1 else if (dir == Nav.Dir.LEFT) -1 else 0, if (dir == Nav.Dir.DOWN) 1 else 0)
            return true
        }
        val want = if (vert) -1 else Nav.wantAfter(dir, col, wantCol)
        go((grid.getChildAt(to.row) as ViewGroup).getChildAt(to.col))
        wantCol = want                                                // only a move made here sets the column to keep
        return true
    }

    private fun go(t: View) { stepping = true; try { t.requestFocus() } finally { stepping = false } }

    private fun centre(v: View): Int { val xy = IntArray(2); v.getLocationInWindow(xy); return xy[0] + v.width / 2 }
    private fun centres(p: ViewGroup) = IntArray(p.childCount) { centre(p.getChildAt(it)) }

    // ---- folders ([Folders]): a tile of the grid that holds apps; OK opens a panel of them, each app keeping its own menu ----

    private var folderDlg: android.app.Dialog? = null
    private var folderLive: List<Tile> = emptyList()                 // the open panel's live widgets: read once when it opens (no timer for a panel), and again by a press
    private var folderFill: (() -> Unit)? = null                      // the open panel drawn again from the settings (null while none is open)

    private fun folderDo(change: JSONObject?) { if (change != null) Store.change(this, change) }

    /** A folder's own menu (its tile's long OK or Menu): open, move on the grid, rename, remove (its apps go back to the grid). */
    private fun folderMenu(a: App) {
        val id = HomeConfig.folderId(a.pkg)
        elementMenu(a.label, listOf(
            gi(R.drawable.ic_apps, getString(R.string.folder_open)) { ui.post { openFolder(a) } },
            gi(R.drawable.ic_expand, getString(R.string.move)) { ui.post { startMove(a.pkg) } },
            gi(R.drawable.ic_pen, getString(R.string.rename)) { ui.post { nameDialog(R.string.folder_name, a.label) { n -> folderDo(Folders.rename(conf, id, n.ifEmpty { getString(R.string.folder_default) })) } } },
            gi(R.drawable.ic_trash, getString(R.string.folder_delete)) { folderDo(Folders.delete(conf, id)) },
            editItem()), getString(R.string.folder_note))
    }

    /** Where to put [pkg]: an existing folder (not [from]) or a new one; a new folder asks for its name first. */
    private fun folderPicker(pkg: String, from: String?) {
        val items = ArrayList<TvGlass.Item>()
        if (Folders.canCreate(conf)) items.add(TvGlass.Item(R.drawable.ic_folder_plus, getString(R.string.folder_new), getString(R.string.folder_new_with)) {   // first: the tile goes into a folder made for it
            ui.post { nameDialog(R.string.folder_name, getString(R.string.folder_default)) { n -> folderDo(Folders.create(conf, Folders.newId(conf), n.ifEmpty { getString(R.string.folder_default) }, pkg)) } }
        })
        for ((id, f) in Folders.of(conf)) if (id != from && f.apps.size < HomeConfig.FOLDER_APPS_MAX)
            items.add(TvGlass.Item(R.drawable.ic_apps, f.name.ifEmpty { getString(R.string.folder_default) }, resources.getQuantityString(R.plurals.folder_items, f.apps.size, f.apps.size)) { folderDo(Folders.add(conf, id, pkg)) })
        if (items.isEmpty()) { toast(R.string.folder_full); return }
        open = TvGlass.menu(this, R.style.Menu, getString(R.string.folder_put), items, checkIcon = R.drawable.ic_check, font = glassFont)
    }

    /** "+ Add" → New folder: its name, then its tiles (apps, live widgets, shortcuts) ticked in the picker; one write makes it (it takes the first app's place). */
    private fun newFolder() = nameDialog(R.string.folder_name, getString(R.string.folder_default)) { n ->
        val name = n.ifEmpty { getString(R.string.folder_default) }
        ui.post { open = Picker.show(this, Picker.Mode.CHOOSE, name, pick = { chosen ->
            val id = Folders.newId(conf); var c = JSONObject(conf.toString()); var change: JSONObject? = null
            for ((i, p) in chosen.withIndex()) {                       // create with the first, add the rest, each on the settings the last one left
                val step = (if (i == 0) Folders.create(c, id, name, p) else Folders.add(c, id, p)) ?: continue
                for (k in step.keys()) c.put(k, step.get(k)); change = step
            }
            folderDo(change)
        }, max = HomeConfig.FOLDER_APPS_MAX) }
    }

    /** A name asked for with the TV's keyboard (a new folder, a rename); an empty one is passed on as "" (the caller picks the default). */
    private fun nameDialog(title: Int, initial: String, done: (String) -> Unit) {
        val box = dev.tvremote.tvui.TvField(this).apply {
            Font.on(this); textSize = 16f; isSingleLine = true; maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(HomeConfig.NAME_MAX))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(initial); setSelection(text.length)
        }
        val dlg = android.app.AlertDialog.Builder(this, R.style.Menu).setTitle(title)
            .setView(FrameLayout(this).apply { setPadding(dp(24), dp(4), dp(24), 0); addView(box) })
            .setPositiveButton(R.string.ok) { _, _ -> done(Folders.cleanName(box.text.toString())) }.setNegativeButton(android.R.string.cancel, null).create()
        box.setOnEditorActionListener { _, _, _ -> done(Folders.cleanName(box.text.toString())); dlg.dismiss(); true }
        open = dlg
        dlg.show()
    }

    /** The folder's panel over Home: its apps as tiles (D-pad as anywhere, OK opens, long OK / Menu their own menu), Back closes. */
    private fun openFolder(a: App) {
        val id = HomeConfig.folderId(a.pkg)
        if (Folders.of(conf)[id] == null) return
        folderDlg?.dismiss()
        val dlg = android.app.Dialog(this, R.style.Menu)
        val cols = 4; val gap = dp(24)
        val w = ((resources.displayMetrics.widthPixels * 0.6f).toInt() - (cols - 1) * gap) / cols
        val title = Font.on(TvUi.text(this, "", 22f, bold = true))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(16), dp(16), dp(16), dp(16 + GRID_GAP)) }   // room for a focused tile's growth and the name under it
        val scroll = ScrollView(this).apply { addView(body); clipChildren = false; clipToPadding = false; isVerticalScrollBarEnabled = false }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(20)); clipChildren = false
            background = TvUi.round(TvUi.SHEET_FILL, dp(TvUi.R_SHEET.toInt()).toFloat(), 1, TvUi.SHEET_EDGE)
            addView(title); addView(scroll, LinearLayout.LayoutParams(-2, -2))
        }
        fun build(mem: List<App>, focusPkg: String?, focusAt: Int) {
            if (!dlg.isShowing) return
            body.removeAllViews()
            val lives = ArrayList<Tile>()
            var row: LinearLayout? = null
            mem.forEachIndexed { i, m ->
                if (i % cols == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }; body.addView(row, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) topMargin = dp(GRID_GAP) }) }
                val t = PanelTile(this, m, w, m.label, ::dp).apply {
                    setOnClickListener { dlg.dismiss(); ui.post { launch(m) } }
                    setOnLongClickListener { menu(m); true }
                    setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_MENU) { if (e.action == KeyEvent.ACTION_UP) menu(m); true } else false }
                    onFocusChangeListener = View.OnFocusChangeListener { v, f -> (v as Tile).focus(f) }
                }
                row!!.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i % cols > 0) leftMargin = gap })
                if (m.live) lives.add(t)
            }
            folderLive = lives
            if (lives.isNotEmpty()) bg.execute { for (k in lives.mapNotNull { it.app?.pkg }.distinct()) { val st = LiveWidgets.fetch(this, k); runOnUiThread { liveShow(k, st) } } }
            // as wide as the columns in use: the TV dialog theme keeps a window of 880 px whatever its content (a folder of 2 apps was half empty)
            val used = minOf(cols, mem.size); val tiles = used * w + (used - 1) * gap + body.paddingLeft + body.paddingRight
            val head = title.paint.measureText(title.text.toString()).toInt()
            dlg.window?.setLayout(maxOf(tiles, head) + card.paddingLeft + card.paddingRight, -2)
            val all = (0 until body.childCount).flatMap { r -> (body.getChildAt(r) as ViewGroup).let { g -> (0 until g.childCount).map { g.getChildAt(it) } } }
            (all.firstOrNull { (it as? Tile)?.app?.pkg == focusPkg } ?: all.getOrNull(minOf(focusAt, all.size - 1).coerceAtLeast(0)))?.requestFocus()
        }
        fun fill() {
            val f = Folders.of(conf)[id]
            if (f == null || f.apps.isEmpty()) { dlg.dismiss(); return }
            title.text = f.name.ifEmpty { getString(R.string.folder_default) }
            val at = (dlg.currentFocus as? Tile)?.app?.pkg
            val was = (0 until body.childCount).flatMap { r -> (body.getChildAt(r) as ViewGroup).let { g -> (0 until g.childCount).map { g.getChildAt(it) } } }.indexOf(dlg.currentFocus)
            val mem = f.apps.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
            if (mem.none { it.banner == null && it.icon == null }) build(mem, at, was)
            else bg.execute { mem.forEach { if (it.banner == null && it.icon == null) it.load(this) }; runOnUiThread { if (!isDestroyed) build(mem, at, was) } }   // pictures off the main thread
        }
        dlg.setContentView(card)
        dlg.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setLayout(-2, -2); setGravity(Gravity.CENTER); setDimAmount(.6f); addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
        dlg.setOnDismissListener { if (folderDlg === dlg) { folderDlg = null; folderFill = null; folderLive = emptyList() } }
        folderDlg = dlg; folderFill = ::fill; open = dlg
        dlg.show()
        fill()
    }

    // ---- live widgets ([LiveWidgets]): read every few seconds while Home is on screen, never while it is not ----

    private fun liveSays(mode: String) = getString(if (mode == LiveWidgets.UNTRUSTED) R.string.live_untrusted else R.string.live_need_setup)
    private fun liveViews(): List<Tile> = tiles().filter { it.app?.live == true }
    private fun animateLive() = Motion.on && !Motion.calm            // a weak box (calm by itself under 1.5 GB) just changes the numbers

    private val liveTick = object : Runnable { override fun run() { liveRead(); if (liveViews().isNotEmpty()) ui.postDelayed(this, LiveWidgets.EVERY_MS) } }

    /** Every live widget on the grid asks its provider for its state, off the main thread. */
    private fun liveRead() {
        val keys = liveViews().mapNotNull { it.app?.pkg }.distinct()
        if (keys.isEmpty() || isDestroyed) return
        bg.execute { for (k in keys) { val s = LiveWidgets.fetch(this, k); runOnUiThread { liveShow(k, s) } } }
    }

    private fun liveShow(key: String, s: JSONObject) { if (!isDestroyed) for (t in liveViews() + folderLive) if (t.app?.pkg == key) t.live(s, animateLive()) }

    /** The press: the provider does its job and the live widget follows it until it is done (the count-down is the widget's). */
    private fun pressLive(a: App) {
        Thread {                                                  // its own thread: the follow-up polls for up to 14 s, and on [bg] the wallpaper, widgets and apps waited behind it
            val ok = LiveWidgets.press(this, a.pkg) { s -> runOnUiThread { liveShow(a.pkg, s) } }
            if (!ok) runOnUiThread { if (!isDestroyed) needSetup(LiveWidgets.status(this) == LiveWidgets.UNTRUSTED) }
        }.start()
    }

    /** A live widget TV Setup could not answer: a question with the way out (a toast left people looking for TV Setup themselves). */
    private fun needSetup(untrusted: Boolean) {
        val have = packageManager.getLaunchIntentForPackage(Store.OWN[0])
        val acts = listOfNotNull(
            if (untrusted) null else TvUi.Item(R.drawable.ic_download, getString(if (have != null) R.string.live_update_setup else R.string.suggest_install)) { getSetup() },
            have?.let { i -> TvUi.Item(R.drawable.ic_zap, getString(R.string.live_open_setup)) { try { startActivity(i) } catch (e: Exception) { } } })
        open = TvUi.menu(this, R.style.TvSheet, getString(R.string.live_need_setup), acts, R.drawable.ic_check,
            note = getString(if (untrusted) R.string.power_untrusted else R.string.live_need_setup_long))
    }

    /** The live widgets in the config ([HomeConfig] "tiles") are among the apps, so move, hide and the order work on them as on any app. */
    private fun syncLive() {
        val live = LiveWidgets.apps(this, conf)
        val now = apps.filter { it.grid }
        if (live.map { Triple(it.pkg, it.label, it.members) } == now.map { Triple(it.pkg, it.label, it.members) }) return   // a folder's apps too: its picture shows them
        apps = (apps.filter { !it.grid } + live).sortedBy { it.label.lowercase() }
    }

    private fun tile(a: App, w: Int, made: HashMap<String, Tile>): Tile {
        val k = "${a.pkg}|$w|${a.label}"
        val t = tileCache[k]?.takeIf { it.app === a } ?: makeTile(a, w)
        made[k] = t
        return t
    }

    /** The person's names for apps into [Apps.names]; a tile is cached by its name too, so a renamed one is made again. */
    private fun readNames() {
        val g = conf.optJSONObject("names")
        Apps.names = if (g == null) emptyMap() else g.keys().asSequence().associateWith { g.optString(it) }
        apps = apps.sortedBy { it.label.lowercase() }
    }

    private fun makeTile(a: App, w: Int) = Tile(this, a, w, a.label, ::dp, ::liveSays, art = if (a.folder) FolderArt.of(this, a.members) else null).apply {
        setOnClickListener {
            if (editing) { menu(a); return@setOnClickListener }
            if (a.folder) { openFolder(a); return@setOnClickListener }   // a folder opens its panel: no app, no zoom
            if (a.live) { pressLive(a); return@setOnClickListener }   // a live widget does its job in place: no app opens, no zoom
            fun go(o: Bundle?) { if (!(if (a.pin != null) Pins.open(this@HomeActivity, a, o) else Apps.launch(this@HomeActivity, a.pkg, o))) { zoom.end(); toast(R.string.no_open) } }
            fun start() { lastOpened = a.pkg; if (!zoom.open(this, ::go)) go(null) }
            if (a.pin == null && HomeLock.isLocked(this@HomeActivity, a.pkg)) askPin(a.label) { start() } else start()   // locked by TV Home itself: the PIN pad first
        }
        setOnLongClickListener { menu(a); true }
        moves(this) { menu(a) }
    }

    /** The D-pad, the menu key and the focus ring of a tile; the hidden apps' tile too, or focus on it showed nowhere. */
    private fun moves(t: Tile, menu: (() -> Unit)?) = t.apply {
        nudged = { x, y -> if (Motion.glass) glass.nudge(x, y) }
        setOnKeyListener { v, code, e ->
            if (code == KeyEvent.KEYCODE_MENU && menu != null) { if (e.action == KeyEvent.ACTION_UP) menu(); e.action == KeyEvent.ACTION_UP }
            else if (e.action == KeyEvent.ACTION_DOWN) step(v as Tile, code) else false
        }
        onFocusChangeListener = View.OnFocusChangeListener { v, f ->
            val slide = if (f) (v.parent as? Strip)?.reveal(v) else null   // before the pane is placed: it goes to where the row will be
            (v as Tile).focus(f)
            if (f) v.glassOn(true) else glassOff()                    // to a panel: the pane goes; to a widget or a button it follows
            slide?.invoke()
            if (f) {
                if (!stepping) wantCol = -1                           // focus came another way: the column to keep starts over here
                reveal(v)
            }
        }
    }

    /** [v] in view: the first row takes the page up with it; a row that is cut off comes to the upper third; one in full view stays put. */
    private fun reveal(v: View) {
        if (grid.indexOfChild(v.parent as? View) <= 0) { scrollToY(0); return }
        var y = 0; var p: View? = v
        while (p != null && p !== scroll) { y += p.top; p = p.parent as? View }
        val margin = dp(40)
        if (y - margin < scroll.scrollY || y + v.height + margin > scroll.scrollY + scroll.height)
            scrollToY(maxOf(0, y - scroll.height / 3))
    }

    private fun shadeK() = conf.optInt("shade", HomeConfig.SHADE_DEFAULT).toFloat() / HomeConfig.SHADE_DEFAULT   // Backdrop.shade's: 1 the usual veil

    /** The background of [conf], when it is not the one on screen: a new choice, the rotation, a new photo, a new Bing picture. */
    private fun wall() {
        val id = conf.optString("wall")
        val key = id + (Walls.file(this, id)?.let { it.name + it.lastModified() } ?: "") + conf.optLong("photo") + conf.optString("wfit") + (if (id == HomeConfig.WEB) conf.optString("wurl") + conf.optString("wmode") + conf.optInt("wsnap") + conf.optBoolean("wkeep") else "") + (if (id == HomeConfig.OWN) conf.optString("wcol") + conf.optString("wdir") else "") + (if (id == HomeConfig.SKY) Sky.load(this).let { Sky.stamp() } else "")   // a photo put over the old file, another day's Bing picture: a new one
        val shade = conf.optInt("shade")
        if (key == wallShown && shade == wallShade) return
        // the veil alone moved (the stepper): the picture kept is painted again; read and decoded again, each step took ~0.4 s of the
        // FPT box's CPU (a slideshow's list and slide too) and the steps behind the number lagged
        val veilOnly = key == wallShown
        wallShown = key; wallShade = shade
        if (trimmed) { soft = null; return }          // away and short of memory: [untrim] brings it back
        if (veilOnly && web == null && video == null && backdrop.reshade(shadeK())) return
        loadWall(id)
    }

    /**
     * Puts background [id] on the [backdrop]: a gradient at once; a scene or a picture read / drawn off the main thread (only the
     * next one, the one before is let go) and then painted, faded in over the old one when [fade]. A photo that cannot be read
     * (deleted, Bing not fetched yet) leaves what stood in for it.
     */
    private fun loadWall(id: String) {
        val dm = resources.displayMetrics
        val colors = wallColors(id, conf)
        backdrop.dir = wallDir(id, conf)
        val key = wallShown
        val url = conf.optString("wurl")
        val snap = id == HomeConfig.WEB && conf.optString("wmode") == "snap"   // the page as a picture: read and painted like a photo
        val again = snap && lastWall == HomeConfig.WEB                // the next picture of the page: the one on screen stays until it is read (no flash of the gradient every few minutes)
        lastWall = id
        backdrop.strength = shadeK()
        backdrop.fit = conf.optString("wfit", "cover")
        val netPic = if (id == HomeConfig.WEB && !snap) NetPicture.direct(url) else null   // the site's picture theme: a native picture wall, no WebView
        val show = if (id == HomeConfig.WEB && !snap && netPic == null) Slideshow.ref(url) else null   // the site's slideshow theme (Việt Nam): native too
        if (show == null) endSlides()
        val livePage = id == HomeConfig.WEB && !snap && netPic == null && show == null
        val wasLive = web != null || video != null                   // a page or a video was the background: its colours stand in, not a stale picture
        if (HomeConfig.isVideo(id)) { netVideo = ""; attachVideo(id) } else if (!livePage) { netVideo = ""; detachVideo() }
        if (livePage) {                                               // a live page: no bitmap, so nothing to blur for the widgets either (as a gradient)
            soft = null
            Frost.show(null, dm.widthPixels, dm.heightPixels, live = true)
            backdrop.show(Backdrop.Look(colors))                      // the gradient is what shows while the page loads, and if it never does
            livePage(url)
            return
        }
        if (netPic != null) { loadNetPicture(netPic, colors, wasLive, key); return }
        if (show != null) { loadSlideshow(show, colors, wasLive, key); return }
        hideLoading()
        if (snap) attachSnap() else {
            detachWeb()
            bg.execute { WebSnap.clear(this) }                        // not the web wall any more: its last picture is only a file taking room
        }
        if (!snap && !Walls.isPicture(id) && !WallArt.has(id)) {
            soft = null
            Frost.show(null, dm.widthPixels, dm.heightPixels)
            val look = Backdrop.Look(colors)
            backdrop.show(look)
            return
        }
        if (!again) backdrop.show(Backdrop.Look(colors))              // its colours stand in while it is made
        bg.execute {
            val big = if (snap) Wall.load(WebSnap.file(this, url), dm.widthPixels, dm.heightPixels) else Walls.bitmap(this, id, dm.widthPixels, dm.heightPixels)
            val small = if (snap) big?.let { Wall.soft(it) } else Walls.soft(this, id, big, dm.widthPixels, dm.heightPixels)
            runOnUiThread {
                if (isDestroyed || trimmed || key != wallShown) return@runOnUiThread   // another choice came meanwhile: its own load shows
                if ((snap || Walls.isPicture(id)) && big == null) return@runOnUiThread   // no picture yet: the gradient stays
                soft = small
                val look = if (big != null) Backdrop.Look(colors, big) else Backdrop.Look(colors, null, id)
                val moving = HomeConfig.isVideo(id)                      // the widgets' blurred copy is still, the video is not: translucent widgets like over the live page
                backdrop.show(look) { Frost.show(if (moving) null else small, dm.widthPixels, dm.heightPixels, live = moving) }
            }
        }
    }

    private val CHIP_INK = 0xF2FFFFFF.toInt()                          // the loading chip: DESIGN-TV chip words and a dark glass fill
    private val CHIP_FILL = 0x99000000.toInt()
    private var loadingChip: android.widget.TextView? = null

    /** A small "loading the background" chip in the bottom corner (DESIGN-TV chip look). */
    private fun showLoading() {
        if (loadingChip != null) return
        val root = scroll.parent as? ViewGroup ?: return
        val d = resources.displayMetrics.density
        val t = android.widget.TextView(this).apply {
            setText(R.string.wall_loading); setTextColor(CHIP_INK); textSize = 13f
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(CHIP_FILL); cornerRadius = 30 * d }
            isFocusable = false; isClickable = false; elevation = 8 * d
        }
        root.addView(t, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { setMargins(0, 0, (32 * d).toInt(), (24 * d).toInt()) })
        loadingChip = t
    }

    private fun hideLoading() { loadingChip?.let { (it.parent as? ViewGroup)?.removeView(it) }; loadingChip = null }

    /**
     * The site's `loop.html?i=<jpg>` theme as a native picture ([NetPicture]): the old background stays (colours when a page or video
     * was there) with the chip until the file is read, then it fades in; a failure after the retries keeps the old wall quietly.
     */
    private fun loadNetPicture(pic: String, colors: IntArray, wasLive: Boolean, key: String) {
        val dm = resources.displayMetrics
        detachWeb(); detachVideo(); netVideo = ""
        bg.execute { WebSnap.clear(this) }
        val f = NetPicture.file(this, pic)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val cached = f.isFile && f.length() > 0
        if (!cached) { showLoading(); if (wasLive) backdrop.show(Backdrop.Look(colors)) }
        Thread({                                                       // its own thread: the download (3 tries) must not hold up the shared one
            val file = NetPicture.get(this, pic)
            val big = file?.let { Wall.load(it, dm.widthPixels, dm.heightPixels) }
            val small = big?.let { Wall.soft(it) }
            if (big == null) file?.delete()                            // not a picture (a page the site answered with): not kept
            runOnUiThread {
                if (isDestroyed || trimmed || key != wallShown) return@runOnUiThread
                hideLoading()
                if (big == null) { android.util.Log.i("TvHomeWall", "picture wall failed: $pic"); return@runOnUiThread }
                soft = small
                backdrop.show(Backdrop.Look(colors, big)) { Frost.show(small, dm.widthPixels, dm.heightPixels) }
                android.util.Log.i("TvHomeWall", "picture wall shown in ${android.os.SystemClock.elapsedRealtime() - t0} ms (${if (cached) "cache" else "net"})")
            }
        }, "netpic").apply { isDaemon = true }.start()
    }

    // ---- the site's slideshow theme (loop.html?s=<name>): pictures from NetPicture's cache, a caption, the next one when the clock's slot turns ----

    private var slides: List<Slideshow.Slide> = emptyList()
    private var slideRef: Slideshow.Ref? = null
    private var slideAt = -1
    private var slideCap: View? = null
    private val slideTick = Runnable { slideGo() }

    /** The list is read (a kept copy, a day old at most), then [slideGo] shows the slide of this slot. The old background stays until the picture is read. */
    private fun loadSlideshow(ref: Slideshow.Ref, colors: IntArray, wasLive: Boolean, key: String) {
        detachWeb(); detachVideo(); netVideo = ""
        bg.execute { WebSnap.clear(this) }
        if (wasLive) backdrop.show(Backdrop.Look(colors))
        Thread({
            val list = Slideshow.load(this, ref.name)
            runOnUiThread {
                if (isDestroyed || key != wallShown) return@runOnUiThread
                slideRef = ref; slides = list; slideAt = -1
                slideGo()
            }
        }, "slides").apply { isDaemon = true }.start()
    }

    /** The slide of the clock's slot on screen, the timer set for the next slot; nothing runs while Home is away ([onPause] stops it, [onResume] comes here). */
    private fun slideGo() {
        ui.removeCallbacks(slideTick)
        val ref = slideRef ?: return
        val list = slides
        if (list.isEmpty() || isDestroyed || away) return
        val now = System.currentTimeMillis()
        ui.postDelayed(slideTick, ref.every - now % ref.every + 50)
        val k = Slideshow.index(now, ref.every, list.size)
        if (k == slideAt || trimmed) return
        slideAt = k
        val s = list[k]; val next = list[(k + 1) % list.size]
        val key = wallShown
        val dm = resources.displayMetrics
        if (!NetPicture.file(this, s.pic).isFile) showLoading()
        Thread({
            val file = NetPicture.get(this, s.pic)
            val big = file?.let { Wall.load(it, dm.widthPixels, dm.heightPixels) }
            val small = big?.let { Wall.soft(it) }
            if (big == null) file?.delete()
            runOnUiThread {
                if (isDestroyed || trimmed || key != wallShown) return@runOnUiThread
                hideLoading()
                if (big == null) { slideAt = -1; return@runOnUiThread }   // not read: the old picture stays, the next slot tries again
                soft = small
                backdrop.show(Backdrop.Look(wallColors(conf.optString("wall"), conf), big)) { Frost.show(small, dm.widthPixels, dm.heightPixels) }
                caption(s)
            }
            NetPicture.get(this, next.pic)                              // the next one into the cache now, so its turn is a read from the file
        }, "slide").apply { isDaemon = true }.start()
    }

    /** The place's name (the person's language first) and the photo's credit, small and soft in the top right corner; fades in. */
    private fun caption(s: Slideshow.Slide) {
        val root = scroll.parent as? ViewGroup ?: return
        slideCap?.let { root.removeView(it) }
        val vi = (Lang.shown.ifEmpty { Locale.getDefault().language }) == "vi"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = false; isClickable = false; alpha = 0f
            addView(text(14f, CAP_INK).apply { typeface = Font.medium; text = s.line(vi); gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (s.credit.isNotEmpty() && !s.credit.endsWith("/ Unsplash")) addView(text(11f, CAP_DIM).apply { text = "© " + s.credit;   // Unsplash asks no credit (the user wanted none); CC BY photos must keep theirs
                gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        root.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(CAP_X * 2), dp(CAP_Y), dp(CAP_X), 0) })
        box.translationY = -scroll.scrollY.toFloat()
        box.animate().alpha(1f).setDuration(800).start()
        slideCap = box
    }

    /** The wall is not a slideshow (any more): no timer, no caption, the list let go. */
    private fun endSlides() {
        ui.removeCallbacks(slideTick)
        slideCap?.let { (it.parent as? ViewGroup)?.removeView(it) }
        slideCap = null; slideRef = null; slides = emptyList(); slideAt = -1
    }

    private val videoRetry = Runnable { if (!isDestroyed && !trimmed && !away && HomeConfig.isVideo(conf.optString("wall"))) { detachVideo(); attachVideo(conf.optString("wall")) } }

    /** The player of a video wall behind the grid. A file the codec cannot play leaves the poster (a picture), tried again in 2 minutes. */
    private fun attachVideo(id: String) {
        val f = Videos.file(this, id).takeIf { it.isFile } ?: return detachVideo()   // deleted: the poster (or the gradient) stays
        if (Videos.off(this, id)) return detachVideo()                // too slow for this TV twice: the poster stays until the video is chosen again
        val root = scroll.parent as? ViewGroup ?: return
        val v = video ?: videoWall(root)
        v.show(f, shadeK())
        if (away) v.stop()                                            // made while away (a rotation, a phone): [onStart] plays it
    }

    /** 20 s of the video are measured: kept for the clients; slow twice in a row and the poster takes over (a still picture beats a stutter). */
    private fun videoMeasured(id: String, m: VideoWall.Measure) {
        val r = Videos.measured(this, id, m.fps, m.want, m.cpu, System.currentTimeMillis())
        if (r.optBoolean("off")) detachVideo()
    }

    private fun detachVideo() {
        ui.removeCallbacks(videoRetry)
        video?.detach(); video = null
    }

    /**
     * The live "web" wall: a page that is only one video is played by the platform's player on a SurfaceView ([WebVideo] decides, once
     * per address); a real page gets its WebView. Until the answer is in, neither is made (the gradient shows): the WebView is the cost.
     */
    private fun livePage(url: String) {
        WebVideo.resolve(this, url) { media ->
            android.util.Log.i("TvHomeVideo", "web wall: " + (media?.let { "video, " + (if (it in netBad) "failed before" else "played natively") } ?: "a page, WebView"))
            if (isDestroyed || conf.optString("wall") != HomeConfig.WEB || conf.optString("wmode") == "snap" || conf.optString("wurl") != url) return@resolve
            if (media != null && media !in netBad) {
                detachWeb()
                val root = scroll.parent as? ViewGroup ?: return@resolve
                val v = video ?: videoWall(root)
                netVideo = media
                v.showUrl(media, shadeK())
                if (away) v.stop()
            } else {
                netVideo = ""; detachVideo()
                attachWeb()
                webDim?.background = WebWall.veil(shadeK())           // the same page, a new strength: attachWeb kept the page as it was
            }
        }
    }

    /** The player of a video that would not play from the net: the page is shown as it is, and the address is not tried again until Home restarts. */
    private fun netFailed() {
        netBad += netVideo; netVideo = ""
        detachVideo()
        if (!isDestroyed && !trimmed && conf.optString("wall") == HomeConfig.WEB) livePage(conf.optString("wurl"))
    }

    private fun videoWall(root: ViewGroup) = VideoWall(this, root, ui, { m -> if (netVideo.isEmpty()) videoMeasured(conf.optString("wall"), m) }) {
        if (netVideo.isNotEmpty()) netFailed() else { detachVideo(); ui.postDelayed(videoRetry, 120_000) }
    }.also { video = it }

    private val webRetry = Runnable { if (!isDestroyed && !trimmed && !away && web == null && conf.optString("wall") == HomeConfig.WEB && conf.optString("wmode") != "snap") attachWeb() }

    /** The page of the "web" wall behind the grid; a page that cannot load is taken away (the gradient shows, no dialog) and tried again in 2 minutes. */
    private fun attachWeb() {
        val url = conf.optString("wurl")
        if (web != null && webOf == url) return
        detachWeb()
        val root = scroll.parent as? ViewGroup ?: return
        val v = WebWall.make(this, url, { detachWeb(); ui.postDelayed(webRetry, 120_000) })
            ?: return dev.tvremote.tvui.NoWebView.fix(this) { if (it) webRetry.run() }   // WebView switched off on this TV: TV Setup turns it back on
        val dim = WebWall.dim(this, shadeK())
        root.addView(v, 0, FrameLayout.LayoutParams(-1, -1))
        root.addView(dim, 1, FrameLayout.LayoutParams(-1, -1))
        web = v; webDim = dim; webOf = url
    }

    /** The live web wall drawn small (a theme's preview picture), or null when no page is up; main thread. */
    private fun webShot(): android.graphics.Bitmap? = web?.takeIf { it.width > 0 && it.height > 0 }?.let { v ->
        val k = ThemeShots.W.toFloat() / v.width
        android.graphics.Bitmap.createBitmap(ThemeShots.W, (v.height * k).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888).also { b -> val cv = android.graphics.Canvas(b); cv.scale(k, k); v.draw(cv) }
    }

    /** The snapshots of the page every `wsnap` minutes; kept as it is while the choice is the same (a change of the wall does not start the wait again). */
    private fun attachSnap() {
        val url = conf.optString("wurl")
        val every = conf.optInt("wsnap", HomeConfig.WSNAP_DEFAULT) * 60_000L
        val keep = conf.optBoolean("wkeep", false)
        detachWeb(snap = false)                                       // a live page gives way to the pictures
        val now = snapper
        if (now != null && now.sig == "$url|$every|$keep") return
        now?.stop()
        snapper = WebSnapper(this, url, every, keep, { scroll.parent as? ViewGroup }, ui, bg) {
            if (!isDestroyed && !trimmed && conf.optString("wall") == HomeConfig.WEB) { wallShown = ""; wall() }   // a new picture: read and painted
        }.also { if (!away) it.resume() }                             // away: [onStart] starts it
    }

    /** Everything of the web wall let go: the page (and its dim), the picture-taker and its page; [snap] false leaves the picture-taker. */
    private fun detachWeb(snap: Boolean = true) {
        webDim?.let { (it.parent as? ViewGroup)?.removeView(it) }
        ui.removeCallbacks(webRetry)
        WebWall.stop(web)
        web = null; webDim = null; webOf = ""
        if (snap) { snapper?.stop(); snapper = null }
    }

    // ---- turning through the set ----

    private val wallTick = Runnable { Store.advance(this, false); wallTimer() }

    // The sky background repaints every few minutes and burn-in care steps once a minute: each timer exists only while its option is on
    // and Home is in front (onPause removes it); with both off nothing here runs.
    private val skyTick = Runnable { wall(); skyTimer() }
    private fun skyTimer() {
        ui.removeCallbacks(skyTick)
        if (conf.optString("wall") == HomeConfig.SKY) ui.postDelayed(skyTick, Sun.REPAINT_MS)
    }

    private val shiftTick = Runnable { shiftStep(); shiftTimer() }
    private fun shiftTimer() {
        ui.removeCallbacks(shiftTick)
        if (conf.optBoolean("shift", false)) { shiftStep(); ui.postDelayed(shiftTick, PixelShift.untilNext()) }
        else (scroll.parent as? View)?.let { it.translationX = 0f; it.translationY = 0f }
    }
    /** Everything drawn on the wallpaper moves together (the glass pane rides inside it); the wallpaper itself stays. */
    private fun shiftStep() {
        val (x, y) = PixelShift.at(System.currentTimeMillis())
        (scroll.parent as? View)?.let { it.translationX = dp(x).toFloat(); it.translationY = dp(y).toFloat() }
    }

    /** The one timer of the rotation, set while Home is on screen only (nothing runs when it is not); a change sets it again. */
    private fun wallTimer() {
        skyTimer(); shiftTimer()
        ui.removeCallbacks(wallTick)
        val every = conf.optString("wevery")
        val walls = conf.optJSONArray("walls")?.let { a -> List(a.length()) { a.optString(it) } } ?: return
        if (every == "off" || every == "show" || !Store.turns(this, walls)) return
        val now = System.currentTimeMillis()
        val wait = HomeConfig.wait(every, Store.last(this), now, java.util.TimeZone.getDefault().getOffset(now).toLong()) ?: return
        ui.postDelayed(wallTick, if (wait <= 0L) 600_000L else wait)  // due but nothing else to show (a photo gone, Bing not here yet): look again in 10 minutes
    }

    /** Bing's pictures when they are shown or turned to ([Online.wanted] keeps it to every few hours, never for anything else). */
    private fun bingCheck() {
        val a = conf.optJSONArray("walls") ?: return
        if ((0 until a.length()).none { a.optString(it) == HomeConfig.BING } || !Online.wanted(this)) return
        Thread { if (Online.refresh(this)) runOnUiThread { if (!isDestroyed) wall() } }.start()
    }

    /** The Wi-Fi's name; null without the location grant (a client gives it over ADB) or with location off: then just "Wi-Fi". */
    private fun ssid(): String? = try {
        @Suppress("DEPRECATION") (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
            ?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (e: Exception) { null }

    /** What a header line can say now (HomeConfig.TOKENS + "lunarLine"): the day's words in the TV's language, the lunar date. */
    private fun headWords(day: String, greet: String): Map<String, String> {
        val now = Date(); val loc = Locale.getDefault()
        val c = Calendar.getInstance()
        val moon = dev.tvremote.home.widgets.Lunar.of(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
        val (can, chi) = dev.tvremote.home.widgets.Lunar.canChi(moon.year)
        val canchi = getString(R.string.lunar_year, resources.getStringArray(R.array.lunar_can)[can], resources.getStringArray(R.array.lunar_chi)[chi]).trim()
        val lunar = "${moon.day}/${moon.month}"
        fun f(p: String) = DateFormat.format(p, now).toString()
        return mapOf("greet" to greet, "date" to day, "weekday" to f("EEEE").replaceFirstChar { it.uppercase(loc) }, "day" to f("d"),
            "month" to f("M"), "year" to f("yyyy"), "lunar" to lunar, "canchi" to canchi, "lunarLine" to getString(R.string.head_lunar, lunar, canchi))
    }

    /** A header line's type, chosen per line (HomeConfig.HEAD_TYPE / HEAD_FLAGS): size, weight, slant, capitals, colour. */
    private fun headType(v: TextView, w: String, plain: android.graphics.Typeface) {
        v.textSize = when (conf.optString(w + "size")) { "s" -> 16f; "l" -> 30f; else -> 22f }
        val tf = if (conf.optBoolean(w + "bold")) Font.bold else plain
        v.setTypeface(tf, if (conf.optBoolean(w + "italic")) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL)
        val caps = conf.optBoolean(w + "caps")
        v.isAllCaps = caps; v.letterSpacing = if (caps) 0.08f else 0f
        v.setTextColor(when (conf.optString(w + "color")) { "dim" -> HEAD_DIM; "red" -> Widgets.ACCENT; else -> Color.WHITE })
    }

    private fun clockAndDay() {
        val h24 = conf.optBoolean("clock24", true)
        clock.format24Hour = if (h24) "HH:mm" else "h:mm"
        clock.format12Hour = clock.format24Hour
        val now = Calendar.getInstance()
        val day = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Date()).toString()
            .replaceFirstChar { it.uppercase() }
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val greet = getString(if (hour in 4..11) R.string.morning else if (hour in 12..17) R.string.afternoon else R.string.evening)
        val t = headWords(day, greet)
        dateLine.text = HomeConfig.headLine(conf, "d", t)
        greetLine.text = HomeConfig.headLine(conf, "g", t)
        headType(greetLine, "g", Font.regular); headType(dateLine, "d", Font.medium)
        greetLine.visibility = if (conf.optBoolean("greet", true)) View.VISIBLE else View.GONE
        clock.visibility = if (conf.optBoolean("showClock", true)) View.VISIBLE else View.GONE
        dateLine.visibility = if (conf.optBoolean("showDate", true)) View.VISIBLE else View.GONE
        rule.visibility = if (clock.isShown && (dateLine.isShown || greetLine.isShown)) View.VISIBLE else View.GONE   // the line between them only while there are two sides
        netPill.visibility = if (conf.optBoolean("showNet", true)) View.VISIBLE else View.GONE
        settingsPill.visibility = if (conf.optBoolean("showGear", true)) View.VISIBLE else View.GONE
        headLeft.isFocusable = clock.isShown || dateLine.isShown || greetLine.isShown   // a stop (the user, 2026-10-27): OK is its menu
        val ends = bar.buttons.filter { it.visibility == View.VISIBLE }
        for (b in ends.drop(1)) b.nextFocusLeftId = View.NO_ID
        ends.firstOrNull()?.nextFocusLeftId = if (headLeft.isFocusable) headLeft.id else ends.first().id   // the row's end stays put, or goes to the clock
        bar.visibility = if (bar.buttons.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE   // no capsule round nothing
        @Suppress("DEPRECATION") val net = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
        val (label, off) = @Suppress("DEPRECATION") when {
            net == null || !net.isConnected -> getString(R.string.offline) to true
            net.type == ConnectivityManager.TYPE_ETHERNET -> getString(R.string.cable) to false
            else -> (ssid() ?: getString(R.string.wifi)) to false
        }
        netPill.label = label; netPill.contentDescription = label; netPill.alpha = if (off) 0.5f else 1f
        netPill.setImageResource(if (net?.type == ConnectivityManager.TYPE_ETHERNET) R.drawable.ic_globe else R.drawable.ic_wifi)
        bar.relabel(netPill)
    }

    // ---- what the buttons and the menu do ----

    /** One group of the menu: a row on the first level (its [hint] says how it is set) and a page of rows behind it. */
    private class Sub(val id: String, val icon: Int, val title: Int, val hint: String, val items: List<TvGlass.Item>)

    /**
     * The long-press / Menu menu of an app: the glass panel of Customize ([TvGlass.menu]). "Open" first, then groups that open a page
     * of rows in the same panel (Back comes up a level, onto the group just left). A real app first asks TV Setup how it is locked and
     * limited ([AppRules]); no answer = no Lock & limits group and no force stop / cache, everything else is here without it.
     */
    private fun menu(a: App) {
        Store.tipDone(this); tip()                                    // they found the menu: the tip has done its job
        if (a.folder) { folderMenu(a); return }
        if (a.grid || a.pin != null || a.pkg == packageName) { menu(a, null); return }
        AppRules.read(this, a.pkg) { st -> menu(a, st) }
    }

    private var amGroup = ""                                          // the page shown now ("lock", "maint", "week"...): an answer that comes late draws only on its own page
    private var rowNote: Pair<String, String>? = null                 // the last maintenance act's result, as the hint on its row
    private var cacheStop: (() -> Unit)? = null                       // cancels the cache clearing that is running
    private var amPanel: TvGlass.Panel? = null                         // the one dialog of the app menu: its pages swap inside it, so the dim behind never fades in and out

    private fun menu(a: App, st: JSONObject?) {
        val p = TvGlass.Panel(this, R.style.Menu, R.drawable.ic_left) { it.typeface = if (it.typeface?.isBold == true) Font.bold else Font.regular }
        amPanel = p; open = p.dialog; amGroup = ""; rowNote = null; cacheStop?.invoke(); cacheStop = null
        p.dialog.setOnDismissListener { capture(false); AppRules.forget(); cacheStop?.invoke(); cacheStop = null; if (amPanel === p) amPanel = null }   // the parent's code is kept for this panel only
        top(a, st, 0)
        p.show()
    }

    /** The first page: "Open", a row per group (the one at [at] focused: where Back from a group lands), Customize. */
    private fun top(a: App, st: JSONObject?, at: Int) {
        val p = amPanel ?: return
        capture(false); p.dialog.setOnKeyListener(null); amGroup = ""
        val rows = ArrayList<TvGlass.Item>()
        rows.add(TvGlass.Item(R.drawable.ic_play, getString(R.string.am_open)) { launch(a) })
        val source = if (a.live) HomeConfig.liveProvider(a.pkg) else Pins.appOf(this, a)
        if (source != null) {                                         // a live widget or a pinned shortcut belongs to an app: its name and own icon, a press opens it
            val prov = source
            val pm = packageManager
            val info = try { pm.getApplicationInfo(prov, 0) } catch (e: Exception) { null }
            if (info != null) rows.add(TvGlass.Item(0, info.loadLabel(pm).toString(), "", false, false) {
                if (!Apps.launch(this, prov, null)) toast(R.string.no_open)
            }.also { it.picture = try { pm.getApplicationIcon(prov) } catch (e: Exception) { null } })
        }
        Pins.ownIdOf(this, a)?.let { sid ->   // a pinned link (YouTube): which of the apps taking it opens it, one choice for all such pins
            val grp = LinkApps.groupOf(this, sid); val url = LinkApps.urlOf(this, sid)
            if (grp != null && url != null && LinkApps.handlers(this, url, grp).size > 1) rows.add(TvGlass.Item(R.drawable.ic_play, getString(R.string.yt_open_with), "", false, false) {
                val hs = LinkApps.handlers(this, url, grp); val now = LinkApps.choice(this, grp)
                val at = hs.indexOfFirst { it.pkg == now }                    // -1: automatic; after the last, automatic again
                LinkApps.choose(this, grp, if (at + 1 < hs.size) hs[at + 1].pkg else "")
            }.also { it.live = {
                val hs = LinkApps.handlers(this, url, grp); val now = LinkApps.choice(this, grp)
                if (hs.any { it.pkg == now }) hs.first { it.pkg == now }.label else getString(R.string.yt_auto, hs.first().label)
            } })
        }
        val link = Pins.ownIdOf(this, a)?.let { Pins.linkOf(this, it) }
        if (link != null) rows.add(TvGlass.Item(R.drawable.ic_link, link.removePrefix("https://").removePrefix("http://").removeSuffix("/"), "", false, false) { ui.post { linkEdit(a, link) } }.also { r ->
            // where it goes, and a press changes it (only delete + add again did); the app that opens it is the row above
            r.live = { if (LinkApps.preview(this, link).apps.isEmpty()) getString(R.string.link_none) else getString(R.string.link_edit) }
        })
        subs(a, st).forEachIndexed { i, s -> rows.add(TvGlass.Item(s.icon, getString(s.title), s.hint, true, true) { sub(a, st, i) }) }
        rows.add(TvGlass.Item(R.drawable.ic_sliders, getString(R.string.customize)) { customize() })
        p.page(a.label, rows, at)
    }

    /** A group's page, in the same amPanel; Back (the row on top, or the remote's key) returns to the first page on that group. */
    private fun sub(a: App, st: JSONObject?, i: Int, focus: Int = 0) {
        val p = amPanel ?: return
        capture(false); p.dialog.setOnKeyListener(null)
        val s = subs(a, st).getOrNull(i) ?: return top(a, st, 0)
        if (s.id == "lock" && !AppRules.unlocked) return pinPage(a, st, i, "", false)   // the parent's code once, on the way in
        amGroup = s.id
        p.page(ttl(a, getString(s.title)), s.items, focus, back = { top(a, st, i + 1) })
    }

    /** TV Setup lets the remote's mapped keys through to the screen while the assign-a-key page is open (an older one: they open their apps as ever). */
    private fun capture(on: Boolean) {
        if (!on && !capturing) return
        capturing = on
        Keys.capture(this, on)
    }
    private var capturing = false
    private var rowToggled = ""                                     // the app whose Play Next items were just hidden or shown from its menu

    /** What opening the app by its tile does, without the zoom. */
    private fun launch(a: App) {
        if (a.live) { pressLive(a); return }
        if (a.folder) { openFolder(a); return }
        fun start() { lastOpened = a.pkg; if (!(if (a.pin != null) Pins.open(this, a, null) else Apps.launch(this, a.pkg, null))) toast(R.string.no_open) }
        if (a.pin == null && HomeLock.isLocked(this, a.pkg)) askPin(a.label) { start() } else start()
    }

    private fun subs(a: App, st: JSONObject?): List<Sub> {
        val out = ArrayList<Sub>()
        fun item(icon: Int, words: Int, hint: String = "", go: () -> Unit) = TvGlass.Item(icon, getString(words), hint, false, false, go)
        fun stays(icon: Int, words: Int, hint: String = "", more: Boolean = false, go: () -> Unit) = TvGlass.Item(icon, getString(words), hint, more, true, go)   // the panel stays: the action swaps its page
        fun again(s: JSONObject?, sub: Int) = sub(a, s ?: st, sub)       // the same page, with what TV Setup said now
        val dockedPin = a.pin != null && !a.grid
        // 1. where it sits on the home screen
        val home = ArrayList<TvGlass.Item>()
        val inFolder = if (a.folder) null else Folders.folderOf(conf, a.pkg)   // a tile in a folder (app, live widget, shortcut) is moved inside it, not on the grid
        if (inFolder == null) home.add(item(R.drawable.ic_expand, R.string.move) { ui.post { startMove(a.pkg) } })
        else {
            val mine = Folders.of(conf)[inFolder]?.apps.orEmpty(); val at = mine.indexOf(a.pkg)
            home.add(item(R.drawable.ic_up, R.string.folder_out) { folderDo(Folders.remove(conf, inFolder, a.pkg)) })
            if (at > 0) home.add(item(R.drawable.ic_left, R.string.folder_earlier) { folderDo(Folders.reorder(conf, inFolder, a.pkg, at - 1)) })
            if (at in 0 until mine.size - 1) home.add(item(R.drawable.ic_right, R.string.folder_later) { folderDo(Folders.reorder(conf, inFolder, a.pkg, at + 1)) })
        }
        if (!a.folder && !dockedPin && a.pkg != packageName && HomeConfig.folderMember(a.pkg) && (!a.grid || a.pkg in Store.list(this, "tiles"))) home.add(item(R.drawable.ic_apps, if (inFolder == null) R.string.folder_put else R.string.folder_other) { ui.post { folderPicker(a.pkg, inFolder) } })
        if (dockedPin) home.add(item(R.drawable.ic_close, R.string.unpin) { Pins.remove(this, a); pins = pins.filter { it !== a }; shownKey = ""; show() })
        if (!a.live) home.add(item(R.drawable.ic_pen, R.string.rename) { ui.post { rename(a) } })
        val rowOff = a.pkg in Store.rowsOff(this)
        if (!a.live && a.pin == null && conf.optBoolean("showWatch", true) && (rowOff || a.pkg == rowToggled || watch.any { it.first.pkg == a.pkg })) {   // its Play Next items, hidden or brought back where people look for them (found only under + Add, the user, 2026-10-27)
            val me = home.size
            home.add(stays(if (rowOff) R.drawable.ic_eye else R.drawable.ic_eye_off, if (rowOff) R.string.am_watch_show else R.string.am_watch_hide) {
                if (rowOff) Store.rowsOn(this, a.pkg) else Store.rowsOff(this, a.pkg)
                rowToggled = a.pkg                                   // the row is read again in the background: the item stays meanwhile, to undo
                watchRead(); sub(a, st, 0, me)
            })
        }
        if (!dockedPin && inFolder == null) {
            home.add(item(R.drawable.ic_eye_off, R.string.hide) { Store.change(this, JSONObject().put("hidden", org.json.JSONArray(Store.list(this, "hidden") + a.pkg))
                .put("dock", org.json.JSONArray(Store.list(this, "dock") - a.pkg))) })
            if (a.grid) home.add(item(R.drawable.ic_trash, R.string.remove_from_grid) {   // off the grid: it leaves "tiles" and its places in the dock, the order and the hidden list
                if (Target.isPin(a.pkg)) { Pins.off(this, a.pkg); pinsRead(); return@item }   // its system pin too, or it came back as that
                fun without(k: String) = org.json.JSONArray(Store.list(this, k) - a.pkg)
                Store.change(this, JSONObject().put("tiles", org.json.JSONArray(Store.list(this, "tiles") - a.pkg)).put("dock", without("dock")).put("hidden", without("hidden")).put("order", without("order")))
            })
        }
        home.add(editItem())                                          // the whole screen, as the menu of a widget has it: found from an app too
        out.add(Sub("home", R.drawable.ic_home, R.string.am_g_home, "", home))
        // 2. what starts it: the power-on app, a remote key
        if (!dockedPin) {
            val start = ArrayList<TvGlass.Item>()
            val powerApp = conf.optBoolean("autoon") && conf.optString("autoapp") == a.pkg
            val me = out.size
            start.add(stays(R.drawable.ic_power, R.string.auto_on, getString(if (powerApp) R.string.am_on else R.string.am_off)) {   // the app that opens when the TV turns on: TV Setup does it (Keys.push); a toggle, so the panel stays and the row shows the new state
                Store.change(this, if (powerApp) JSONObject().put("autoon", false) else JSONObject().put("autoapp", a.pkg).put("autoon", true))
                if (!Keys.setup(this)) toast(R.string.auto_needs_setup)   // saved anyway: it works the day TV Setup is there
                conf = Store.get(this)
                sub(a, st, me, 0)
            })
            val mine = HomeConfig.keyApps(conf.optJSONObject("keyapps")).filterValues { it == a.pkg }.keys.sorted()
            val keys = mine.take(2).joinToString(" ") { Keys.name(this, it) } + if (mine.size > 2) " +${mine.size - 2}" else ""   // a short line: the page has them all
            start.add(stays(R.drawable.ic_keyboard, R.string.am_key, keys, true) { keyPicker(a, st, null, "") })
            out.add(Sub("start", R.drawable.ic_zap, R.string.am_g_start, keys, start))
        }
        // 3. lock and limits (TV Setup's rules, a code first; with no TV Setup, TV Home's own lock: [HomeLock])
        val own = st == null && !a.grid && a.pin == null && a.pkg != packageName
        if (own) {
            val lock = ArrayList<TvGlass.Item>()
            val me = out.size
            val locked = HomeLock.isLocked(this, a.pkg)
            lock.add(stays(R.drawable.ic_lock, if (locked) R.string.am_unlock else R.string.am_lock, if (locked) getString(R.string.am_locked) else "") {
                HomeLock.set(this, a.pkg, !locked); badgesSoon(); sub(a, st, me, 0)
            })
            lock.add(stays(R.drawable.ic_hourglass, R.string.am_limit, getString(R.string.am_limit_need), true) { noteMsg(a, st, me, 1, getString(R.string.am_limit_need_long)) })
            lock.add(stays(R.drawable.ic_info, R.string.am_lock_scope, "", true) { noteMsg(a, st, me, 2, getString(R.string.am_lock_scope_text)) })
            out.add(Sub("lock", R.drawable.ic_shield, R.string.am_g_lock, if (locked) getString(R.string.am_locked) else "", lock))
        } else if (st != null && !st.optBoolean("prot")) {
            val lock = ArrayList<TvGlass.Item>()
            val me = out.size                                         // this page's place among the groups, for coming back to it
            val locked = st.optBoolean("locked"); val limit = st.optInt("limit")
            fun mins(m: Int) = if (m > 0) getString(R.string.am_limit_min, m) else getString(R.string.am_limit_off)
            lock.add(stays(R.drawable.ic_lock, if (locked) R.string.am_unlock else R.string.am_lock, if (locked) getString(R.string.am_locked) else "") {
                AppRules.change(this, a.pkg, "lock", { put("on", !locked) }) { r -> changed(a, st, me, r) { n -> again(n, me) } }
            })
            lock.add(stays(R.drawable.ic_hourglass, R.string.am_limit, mins(limit), true) { limitMenu(a, st) })
            lock.add(stays(R.drawable.ic_chart, R.string.am_used, getString(R.string.am_limit_min, (st.optLong("used") + 30) / 60), true) { weekPage(a, st, "") })
            out.add(Sub("lock", R.drawable.ic_shield, R.string.am_g_lock, if (locked) getString(R.string.am_locked) else if (limit > 0) mins(limit) else "", lock))
        }
        // 4. maintenance: an app's own screens and, with TV Setup's ADB, stop and cache
        if (!a.grid && a.pin == null) {
            val fix = ArrayList<TvGlass.Item>()
            if (st != null && st.optBoolean("adb") && !st.optBoolean("prot")) {
                val me = out.size
                val noteOf = { op: String -> rowNote?.takeIf { it.first == op }?.second ?: "" }
                fix.add(stays(R.drawable.ic_stop, R.string.am_stop, noteOf("stop")) {
                    AppRules.ask(this, a.pkg, "stop") { r ->
                        rowNote = "stop" to getString(when (r?.optString("result")) { "ok" -> R.string.am_stopped; "noadb" -> R.string.am_noadb; else -> R.string.am_failed })
                        if (amPanel != null && amGroup == "maint") sub(a, st, me, 0)
                    }
                })
                fix.add(stays(R.drawable.ic_eraser, if (cacheStop != null) R.string.am_cache_cancel else R.string.am_cache, noteOf("cache")) { cacheTap(a, st, me) })
            }
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                .takeIf { packageManager.resolveActivity(it, 0) != null }   // a CVTE TV has no App info page: no entry rather than one that fails (cleanyourtv #1202)
                ?.let { i -> fix.add(item(R.drawable.ic_info, R.string.info) { try { startActivity(i) } catch (e: Exception) { toast(R.string.no_open) } }) }
            val removable = try { packageManager.getApplicationInfo(a.pkg, 0).let { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 } } catch (e: Exception) { false }
            if (a.pkg != packageName && removable) Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))
                .takeIf { packageManager.resolveActivity(it, 0) != null }   // the same CVTE gap: no installer page, no entry
                ?.let { i -> fix.add(item(R.drawable.ic_trash, R.string.uninstall) { try { startActivity(i) } catch (e: Exception) { toast(R.string.no_open) } }) }
            out.add(Sub("maint", R.drawable.ic_wrench, R.string.am_g_maint, "", fix))
        }
        return out.filter { it.items.isNotEmpty() }
    }

    /** A page's title: "App · page" while it fits the panel's head, else the page's own words (the app is named on the first page). */
    private fun ttl(a: App, page: String) = "${a.label} · $page".let { if (it.length <= 28) it else page }

    /** Where group [id] sits among the menu's groups. */
    private fun at(a: App, st: JSONObject?, id: String) = subs(a, st).indexOfFirst { it.id == id }.coerceAtLeast(0)

    /** The daily limit's choices, a page of the same panel; the parent's code was asked on the way into the group (kept, [AppRules.change]). Back returns to the Lock & limits page. */
    private fun limitMenu(a: App, st: JSONObject) {
        val p = amPanel ?: return
        val cur = st.optInt("limit")
        val me = at(a, st, "lock")
        val choices = listOf(15, 30, 60, 90, 120, 0)
        val rows = choices.map { m ->
            TvGlass.Item(if (m == cur) R.drawable.ic_check else 0, if (m > 0) getString(R.string.am_limit_min, m) else getString(R.string.am_limit_off), "", false, true) {
                if (m == cur) sub(a, st, me, 1)
                else AppRules.change(this, a.pkg, "limit", { put("minutes", m) }) { r -> changed(a, st, me, r) { n -> sub(a, n, me, 1) } }
            }
        }
        amGroup = "limit"
        p.page(ttl(a, getString(R.string.am_limit)), rows, choices.indexOf(cur).coerceAtLeast(0), back = { sub(a, st, me, 1) })
    }

    /**
     * What TV Setup answered to a lock / limit change: "ok" runs [ok]; anything else says so ON the keypad page (never a toast alone):
     * the code no longer opens it (wrong, a wait, or none set since) and is asked again.
     */
    private fun changed(a: App, st: JSONObject?, at: Int, r: JSONObject?, ok: (JSONObject) -> Unit) {
        if (amPanel == null) return
        when (r?.optString("result")) {
            "ok" -> { badgesSoon(); ok(r) }
            else -> pinPage(a, st, at, null, true, r)
        }
    }

    /** The words of a refused code: tries left, the wait, no PIN yet, no answer. */
    private fun pinSaid(r: JSONObject?): String = when (r?.optString("result")) {
        "wrong", "code" -> getString(R.string.am_pin_wrong, r.optInt("left", 0))
        "wait" -> r.optLong("wait").let { getString(R.string.am_pin_wait, "%d:%02d".format(it / 60, it % 60)) }
        null -> getString(R.string.am_pin_noanswer)
        else -> getString(R.string.am_failed)
    }

    /** The words of a refused code of TV Home's own lock. */
    private fun ownSaid(v: HomeLock.Verdict): String = when (v) {
        is HomeLock.Verdict.Wrong -> getString(R.string.am_pin_wrong, v.left)
        is HomeLock.Verdict.Wait -> getString(R.string.am_pin_wait, "%d:%02d".format(v.seconds / 60, v.seconds % 60))
        else -> ""
    }

    /** A keypad for TV Home's own PIN in [p]: a wrong code or a wait is said over the dots and the pad is drawn again; a right one goes to [done]. */
    private fun ownPad(p: TvGlass.Panel, title: String, said: String?, bad: Boolean, back: () -> Unit, done: (String) -> Unit) {
        val wait = HomeLock.waiting(this)
        val line = if (said == null && wait > 0) getString(R.string.am_pin_wait, "%d:%02d".format(wait / 60, wait % 60)) else said
        val pad = AppPages.pin(this, line ?: getString(R.string.am_pin_enter), bad || (said == null && wait > 0), HomeLock.LEN, false) { code ->
            val v = HomeLock.check(this, code)
            if (v is HomeLock.Verdict.Ok) done(code) else if (p.dialog.isShowing) ownPad(p, title, ownSaid(v), true, back, done)
        }
        p.dialog.setOnKeyListener { _, k, e -> pad.onKey(k, e) }
        p.view(title, pad.view, 420, pad.focus, back)
    }

    /** Opening an app TV Home locked by itself: its own panel with the PIN pad; the right code runs [then]. */
    fun askPin(label: String, then: () -> Unit) {
        val p = TvGlass.Panel(this, R.style.Menu, R.drawable.ic_left) { it.typeface = if (it.typeface?.isBold == true) Font.bold else Font.regular }
        open = p.dialog
        ownPad(p, label, null, false, { p.dialog.dismiss() }) { p.dialog.dismiss(); then() }
        p.show()
    }

    /** A page of words in the panel (one OK button back to the group page [me], row [row]). */
    private fun noteMsg(a: App, st: JSONObject?, me: Int, row: Int, text: String) {
        val p = amPanel ?: return
        p.dialog.setOnKeyListener(null); amGroup = "note"
        val back = { sub(a, st, me, row) }
        val (v, b) = AppPages.message(this, text, false, getString(R.string.ok), back)
        p.view(ttl(a, getString(R.string.am_g_lock)), v, 420, b, back)
    }

    /**
     * Lock & limits' door: the parent's PIN on a keypad like TV Setup's own PIN screen ([AppPages.pin]). A wrong code, a wait or no answer
     * is said over the dots (nothing about how to reset it: a child could follow it). With TV Setup it checks the code; with none, TV
     * Home's own ([HomeLock]). With no PIN yet the TV makes one ([createPage]); a PIN of TV Home's own that meets a TV Setup is moved
     * to it with the apps it locked ([AppRules.migrate]). A right code opens the group; the same panel keeps it.
     */
    private fun pinPage(a: App, st: JSONObject?, at: Int, said: String?, bad: Boolean, answer: JSONObject? = null) {
        val p = amPanel ?: return
        capture(false)
        amGroup = "pin"
        val back = { top(a, st, at + 1) }
        val title = ttl(a, getString(R.string.am_g_lock))
        val setupPin = st != null && st.optBoolean("pin") && answer?.optString("result") != "nopin"
        val mine = HomeLock.has(this)
        if (!setupPin && !mine) return createPage(a, st, at, null, null, false)
        if (!setupPin) {                                          // TV Home's own PIN (with a TV Setup that has none: its rules win, this moves there)
            ownPad(p, title, said, bad, back) { code ->
                if (st == null) { AppRules.keep(code); sub(a, st, at) }
                else AppRules.migrate(this, a.pkg, code, true, HomeLock.locked(this)) { ok -> moved(a, at, ok) }
            }
            return
        }
        val line = said ?: pinSaid(answer)
        val pad = AppPages.pin(this, if (bad) line else getString(R.string.am_pin_enter), bad, st!!.optInt("len", 4), st.optBoolean("totp")) { code ->
            AppRules.verify(this, a.pkg, code) { r ->
                if (amPanel !== p || !p.dialog.isShowing || amGroup != "pin") return@verify
                if (r?.optString("result") != "ok") pinPage(a, st, at, null, true, r)
                else if (mine || HomeLock.locked(this).isNotEmpty()) AppRules.migrate(this, a.pkg, code, false, HomeLock.locked(this)) { ok -> moved(a, at, ok) }   // TV Setup's rules win
                else sub(a, st, at)
            }
        }
        p.dialog.setOnKeyListener { _, k, e -> pad.onKey(k, e) }
        p.view(title, pad.view, 420, pad.focus, back)
    }

    /** TV Home's own lock moved to TV Setup (or not): read what TV Setup says now and open the group. */
    private fun moved(a: App, at: Int, ok: Boolean) {
        if (ok) { HomeLock.clear(this); badgesSoon() }
        AppRules.read(this, a.pkg) { n -> if (amPanel != null) sub(a, n, at) }
    }

    /**
     * Making the first PIN on the TV: 4 digits twice (a mismatch starts again, said in red). With TV Setup it keeps it ([AppRules.migrate]
     * with nothing to move), without, [HomeLock] does. The one place that says how to recover it is the page after, for whoever made it.
     */
    private fun createPage(a: App, st: JSONObject?, at: Int, first: String?, said: String?, bad: Boolean) {
        val p = amPanel ?: return
        amGroup = "pin"
        val back = { top(a, st, at + 1) }
        val title = ttl(a, getString(R.string.am_g_lock))
        val pad = AppPages.pin(this, said ?: getString(if (first == null) R.string.am_pin_new else R.string.am_pin_again), bad, HomeLock.LEN, false) { code ->
            if (first == null) createPage(a, st, at, code, null, false)
            else if (code != first) createPage(a, st, at, null, getString(R.string.am_pin_mismatch), true)
            else if (st != null) AppRules.migrate(this, a.pkg, code, true, emptyList()) { ok ->
                if (!ok) createPage(a, st, at, null, getString(R.string.am_failed), true)
                else AppRules.read(this, a.pkg) { n -> made(a, n, at, R.string.am_pin_made_setup) }
            } else { HomeLock.create(this, code); AppRules.keep(code); made(a, st, at, R.string.am_pin_made_own) }
        }
        p.dialog.setOnKeyListener { _, k, e -> pad.onKey(k, e) }
        p.view(title, pad.view, 420, pad.focus, back)
    }

    /** The PIN is made: how to recover it (once, here), then the group. */
    private fun made(a: App, st: JSONObject?, at: Int, words: Int) {
        val p = amPanel ?: return
        p.dialog.setOnKeyListener(null)
        val (v, b) = AppPages.message(this, getString(words), false, getString(R.string.ok)) { sub(a, st, at) }
        p.view(ttl(a, getString(R.string.am_g_lock)), v, 420, b) { sub(a, st, at) }
    }

    /** "Used today" opens this page: a bar per day for the last 7 (TV Setup's "week"), and a button that says where to see more. */
    private fun weekPage(a: App, st: JSONObject?, note: String) {
        val p = amPanel ?: return
        capture(false); p.dialog.setOnKeyListener(null)
        val me = at(a, st, "lock")
        val back = { sub(a, st, me, 2) }
        val title = ttl(a, getString(R.string.am_week_title))
        amGroup = "week"
        AppRules.ask(this, a.pkg, "week") { r ->
            if (amPanel !== p || amGroup != "week") return@ask
            if (r == null) {
                val (v, b) = AppPages.message(this, getString(R.string.am_pin_noanswer), true, getString(R.string.ok), back)
                p.view(title, v, 420, b, back); return@ask
            }
            val fmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
            val now = System.currentTimeMillis()
            val names = (0..6).map { d -> if (d == 0) getString(R.string.am_week_today) else fmt.format(java.util.Date(now - d * 86_400_000L)) }
            val (v, b) = AppPages.week(this, r.optJSONArray("days") ?: org.json.JSONArray(), names, getString(R.string.am_week_more), note.ifEmpty { getString(R.string.am_week_more_hint) }) {
                bg.execute {                                          // TV Setup hands its Health screen back to start from here (SetupHand); an older one opens it itself
                    val r = SetupHand.control(this, "manage:health")
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (r?.has("error") == false) p.dialog.dismiss()
                        else AppRules.ask(this, a.pkg, "manage") { m ->
                            if (m?.optString("result") == "ok") p.dialog.dismiss() else weekPage(a, st, getString(R.string.am_week_more_fail))
                        }
                    }
                }
            }
            p.view(title, v, 460, b, back)
        }
    }

    /** The Clear cache row: starts it (TV Setup runs it on a thread: seconds), shows "Clearing…" on the row, pressing it again cancels. */
    private fun cacheTap(a: App, st: JSONObject?, me: Int) {
        cacheStop?.let {
            it(); cacheStop = null; rowNote = "cache" to getString(R.string.am_cache_cancelled)
            if (amPanel != null && amGroup == "maint") sub(a, st, me, 1)
            return
        }
        val p = amPanel ?: return
        cacheStop = AppRules.cache(this, a.pkg) { r ->
            if (amPanel !== p) return@cache
            val res = r?.optString("result")
            if (res == "running") { rowNote = "cache" to getString(R.string.am_cache_running); if (amGroup == "maint") sub(a, st, me, 1); return@cache }
            cacheStop = null
            val kb = r?.optLong("kb") ?: 0L
            val size = if (kb >= 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB"
            if (res == "partial") {                                  // too long for a hint: a page of words, with the way that does work
                rowNote = null
                amGroup = "cachemsg"
                val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)).takeIf { packageManager.resolveActivity(it, 0) != null }
                val back = { sub(a, st, me, 1) }
                val (v, b) = AppPages.message(this, getString(if (kb > 0) R.string.am_cache_partial else R.string.am_cache_partial0, size), false,
                    getString(if (info != null) R.string.info else R.string.ok)) { if (info != null) { try { startActivity(info) } catch (e: Exception) { toast(R.string.no_open) } } else back() }
                p.view(ttl(a, getString(R.string.am_cache)), v, 460, b, back)
                return@cache
            }
            rowNote = "cache" to getString(when (res) { "ok" -> R.string.am_cached; "noadb" -> R.string.am_noadb; else -> R.string.am_failed })
            if (amGroup == "maint") sub(a, st, me, 1)
        }
    }

    /** LiveWidgets' marks (lock, hourglass) re-read once TV Setup has the change. */
    private fun badgesSoon() = ui.postDelayed({ badgesRead() }, 400)

    /**
     * The assign-a-key page: the remote as a keypad (1-9, 0 under, the four colour keys as a row), each key a [PickTile] showing the app
     * on it now (empty looks empty), this app's own marked. A press or the physical key itself gives the key to this app (taking it from
     * another one) or, on its own, takes it away; the page stays open and says what happened in [said]. TV Setup passes the keys through
     * to this screen meanwhile ([capture]). [focusCode]: the key to leave focused after a change.
     */
    private fun keyPicker(a: App, st: JSONObject?, focusCode: Int?, said: String) {
        val p = amPanel ?: return
        val conf = Store.get(this)
        val map = HomeConfig.keyApps(conf.optJSONObject("keyapps"))
        val have = Keys.have(this)?.let { j -> (0 until j.length()).map { j.getInt(it) }.toSet() }
        val dead = Keys.dead(this).filter { it in HomeConfig.KEY_DEAD }
        Thread({
            val art = HashMap<Int, App?>()
            for ((code, who) in map) if (who.isNotEmpty()) art[code] = try { dev.tvremote.home.keys.Targets.app(this, who) } catch (e: Exception) { null }
            runOnUiThread { if (!isDestroyed && amPanel === p && p.dialog.isShowing) drawKeypad(p, a, st, map, art, have, dead, focusCode, said) }
        }, "home-keypad").apply { isDaemon = true }.start()
    }

    private fun drawKeypad(p: TvGlass.Panel, a: App, st: JSONObject?, map: Map<Int, String>, art: Map<Int, App?>, have: Set<Int>?, dead: List<Int>, focusCode: Int?, said: String) {
        fun dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()
        val gap = dp(6)
        val tiles = HashMap<Int, dev.tvremote.home.keys.PickTile>()
        fun toggle(code: Int) {
            val mine = map[code] == a.pkg
            val o = JSONObject(); for ((k, v) in (if (mine) map - code else map + (code to a.pkg))) o.put(k.toString(), v)
            Store.change(this, JSONObject().put("keyapps", o).also { if (!mine) it.put("keyon", true) })   // the first one chosen switches the keys on, like the quick keys screen
            capture(true)
            keyPicker(a, st, code, if (mine) getString(R.string.am_key_gone) else getString(R.string.am_key_set, Keys.name(this, code), a.label))
        }
        fun slot(code: Int): dev.tvremote.home.keys.Keypad.Slot {
            val who = map[code].orEmpty(); val pic0 = art[code]
            val info = if (who.isNotEmpty() && dev.tvremote.home.Target.kind(who) == dev.tvremote.home.Target.Kind.TILE) dev.tvremote.home.apps.LiveWidgets.available(this).firstOrNull { it.key == who } else null
            val pic = if (info != null) null else pic0                                          // a live widget has no app picture: its own icon
            val missing = who.isNotEmpty() && pic == null
            return dev.tvremote.home.keys.Keypad.Slot(if (pic != null) pic.label else if (missing) dev.tvremote.home.keys.Targets.label(this, who) else "", pic, Keys.name(this, code), who.isEmpty(),
                if (pic == null) dev.tvremote.home.keys.QuickKeys.COLOUR[code] ?: dev.tvremote.home.keys.PK_EMPTY else 0,
                icon = info?.let { dev.tvremote.home.apps.LiveWidgets.iconRes(this, it) } ?: 0,
                dead = who.isEmpty() && have != null && code !in have, mark = who == a.pkg)
        }
        fun note(t: String, colour: Int) = TextView(this).apply { Font.on(this); text = t; textSize = 13f; setTextColor(colour); gravity = Gravity.CENTER; maxLines = 2 }
        val digits = HomeConfig.KEY_NUMBERS.drop(1) + HomeConfig.KEY_NUMBERS.first()
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumWidth = dp(560) }              // fixed above the keypad: only the keys scroll
        head.addView(note(getString(R.string.am_key_hint), TvGlass.HINT), LinearLayout.LayoutParams(-1, -2))
        head.addView(note(said.ifEmpty { getString(R.string.am_key_move) }, if (said.isEmpty()) TvGlass.HINT else Color.WHITE), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })   // what the last key did, in the place of the note
        pad.addView(dev.tvremote.home.keys.Keypad.build(this, ::dp, emptyList(), ::slot, ::toggle, tiles), LinearLayout.LayoutParams(-1, -2))
        p.dialog.setOnKeyListener { _, code, e ->
            if (code in tiles.keys && code in HomeConfig.KEY_NUMBERS + HomeConfig.KEY_COLOURS) {   // the real key: assign it without walking to its button; its up and repeats are eaten too
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) toggle(code)
                true
            } else false
        }
        capture(true)
        p.view("${a.label} · ${getString(R.string.am_key)}", pad, 600, focusCode?.let { tiles[it] } ?: tiles[digits[0]], fixed = head, back = { sub(a, st, 1, 1) })
    }

    /** Rename [a]: a field with the TV's keyboard, prefilled with the name shown; "Original name" takes the person's name away. */
    private fun rename(a: App) {
        val box = dev.tvremote.tvui.TvField(this).apply {
            Font.on(this); textSize = 16f; isSingleLine = true; maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(HomeConfig.NAME_MAX))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(a.label); setSelection(text.length)
        }
        fun save(name: String) {
            val g = JSONObject(conf.optJSONObject("names")?.toString() ?: "{}")
            if (name.isEmpty() || name == a.orig) g.remove(a.pkg) else g.put(a.pkg, name)
            Store.change(this, JSONObject().put("names", g))
        }
        val b = android.app.AlertDialog.Builder(this, R.style.Menu).setTitle(R.string.rename_q)
            .setView(FrameLayout(this).apply { setPadding(dp(24), dp(4), dp(24), 0); addView(box) })
            .setPositiveButton(R.string.ok) { _, _ -> save(box.text.toString().trim()) }.setNegativeButton(android.R.string.cancel, null)
        if (a.renamed) b.setNeutralButton(R.string.rename_orig) { _, _ -> save("") }
        val dlg = b.create()
        box.setOnEditorActionListener { _, _, _ -> save(box.text.toString().trim()); dlg.dismiss(); true }
        open = dlg
        dlg.show()
    }

    /**
     * Changes a pinned link in place: the new link is pinned (YouTube or any other, as from the phone: [HomeApi.pin]) and takes the old
     * tile's place, name and keys ([Pins.replace]). A link nothing opens, or not a link, is said and the old one stays.
     */
    private fun linkEdit(a: App, link: String) {
        val box = dev.tvremote.tvui.TvField(this).apply {
            Font.on(this); textSize = 16f; isSingleLine = true; maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(link); setSelection(text.length)
        }
        fun save(raw: String) {
            val u = dev.tvremote.home.LinkUrl.clean(raw)
            if (u == null) { toast(R.string.pq_web_bad); return }
            if (u == link) return
            bg.execute {
                val r = HomeApi.pin(this, JSONObject().put("url", u))       // named by the new link (its page's title); a name the person gave moves with "names"
                val now = Target.pin(packageName, Pins.ownId(LinkApps.intent(this, YouTube.parse(u)?.url ?: u)))
                val ch = if (r == "ok") Pins.replace(this, a.pkg, now) else null
                val ok = ch != null
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (ch != null && ch.length() > 0) Store.change(this, ch)
                    toast(if (ok) R.string.link_changed else if (r == YouTube.NO_APP) R.string.link_no_app else if (r == YouTube.NO_LINK) R.string.pq_web_bad else R.string.watch_pin_fail)
                    conf = Store.get(this); pinsRead()
                }
            }
        }
        val dlg = android.app.AlertDialog.Builder(this, R.style.Menu).setTitle(R.string.link_change_q)
            .setView(FrameLayout(this).apply { setPadding(dp(24), dp(4), dp(24), 0); addView(box) })
            .setPositiveButton(R.string.ok) { _, _ -> save(box.text.toString()) }.setNegativeButton(android.R.string.cancel, null).create()
        box.setOnEditorActionListener { _, _, _ -> save(box.text.toString()); dlg.dismiss(); true }
        open = dlg
        dlg.show()
    }

    private var panel: android.app.Dialog? = null
    private fun customize(at: String? = null) { open = Customize.show(this, at).also { panel = it } }

    /** The remote's Menu key anywhere but on an app (an app's own Menu opens its menu) opens the settings. */
    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        if (locked) return super.onKeyUp(code, e)
        if (code == KeyEvent.KEYCODE_MENU) { customize(); return true }
        // The Input key, where it reaches the home (cleanyourtv #342: some Realtek remotes send TV_INPUT, others TV). A box whose
        // system sends it to Google's GlobalKeyReceiver (the FPT Play box) never gives it to any app
        if (code == KeyEvent.KEYCODE_TV_INPUT || code == KeyEvent.KEYCODE_TV) { inputs(); return true }
        return super.onKeyUp(code, e)
    }

    /** Puts [key] at the end of the grid (out of the dock, the hidden list), focused for a move; [own] also adds it to the grid's own items. */
    private fun addToGrid(key: String, own: Boolean) {
        fun l(k: String) = org.json.JSONArray(Store.list(this, k) - key)
        val c = JSONObject().put("hidden", l("hidden")).put("dock", l("dock")).put("order", org.json.JSONArray(Store.list(this, "order") - key + key))
        if (own) c.put("tiles", org.json.JSONArray(Store.list(this, "tiles") - key + key))
        focusNext = key
        Store.change(this, c)
    }

    /**
     * "+ Add": what is not on the grid, a row per kind, each kind of more than one a page of its own (Back comes up to its row): after
     * the first run hid every app but the well-known ones, a row per hidden app pushed the rest of the list off the screen. The hidden
     * apps are the choice grid (many ticked at once), not rows here.
     */
    private fun addPanel() {
        val hidden = Store.list(this, "hidden"); val onGrid = Store.list(this, "tiles") + Pins.list(this).map { it.pkg }   // a pinned shortcut is on the grid already
        fun icon(pkg: String) = try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
        val live = ArrayList<TvGlass.Item>(); val pins = ArrayList<TvGlass.Item>(); val rowsOff = ArrayList<TvGlass.Item>()
        // every live widget, the ones on the grid too (a press goes to it, once the sheet gave the focus back): with all but one placed, that one alone said nothing of what it was
        if (LiveWidgets.status(this) == LiveWidgets.OK) for (t in LiveWidgets.available(this)) { val on = t.key in onGrid
            live.add(TvGlass.Item(LiveWidgets.iconRes(this, t), t.title.ifEmpty { HomeConfig.liveId(t.key) }, getString(if (on) R.string.watch_pin_same else R.string.add), false, false) {
                if (on) grid.postDelayed({ tiles().firstOrNull { it.app?.pkg == t.key }?.let { go(it) } }, 200) else addToGrid(t.key, true) }.also { it.picture = icon(HomeConfig.liveProvider(t.key)) }) }
        for (p in Store.rowsOff(this)) { val l = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { continue }
            rowsOff.add(TvGlass.Item(0, l, getString(R.string.add_row), false, false) { Store.rowsOn(this, p); open?.dismiss(); watchRead() }.also { it.picture = icon(p) }) }   // a row hidden from its menu comes back here
        if (Pins.can(this)) {
            val l = Pins.report(this, JSONObject()).optJSONArray("pinsAvail")
            for (i in 0 until (l?.length() ?: 0)) { val o = l!!.getJSONObject(i); val k = o.getString("key")
                if (k in onGrid || pins.size >= 40) continue
                pins.add(TvGlass.Item(0, o.optString("title"), getString(R.string.add_pin), false, false) { addToGrid(k, true) }.also { it.picture = Pins.rowIcon(this, k, o.optString("app"), o.optLong("t")) ?: icon(o.optString("app")) }) }
        }
        val p = TvGlass.Panel(this, R.style.Menu, R.drawable.ic_left) { it.typeface = if (it.typeface?.isBold == true) Font.bold else Font.regular }
        fun top(at: Int) {
            val rows = ArrayList<TvGlass.Item>()
            rows.add(TvGlass.Item(R.drawable.ic_apps, getString(R.string.add_choose), getString(R.string.add_choose_hint, hidden.count { h -> apps.any { it.pkg == h } }), false, false) { open?.dismiss(); open = Picker.show(this, Picker.Mode.CHOOSE, getString(R.string.pk_choose_title)) })
            if (Folders.canCreate(conf)) rows.add(TvGlass.Item(R.drawable.ic_folder_plus, getString(R.string.folder_new), getString(R.string.folder_new_hint), false, false) { open?.dismiss(); newFolder() })
            if (Pins.can(this)) rows.add(TvGlass.Item(R.drawable.ic_link, getString(R.string.add_link), getString(R.string.add_link_hint), false, false) {   // any link: the QR to the paste page (a page, an app's deep link, YouTube)
                open?.dismiss(); open = Picker.show(this, Picker.Mode.GRID, getString(R.string.add_link), only = "link", paste = true)
            })
            val chans = ArrayList<TvGlass.Item>()                  // pins inside other apps (VTVgo, TV360, YouTube) and TV Channels: one group
            if (Pins.can(this)) for ((pkg, l) in Links.installed(this).groupBy { it.group })   // another app's channels (VTVgo, TV360): the picker with those alone
                chans.add(TvGlass.Item(0, getString(if (l[0].any) R.string.add_from_app else R.string.add_app_channel, l[0].app), getString(if (l[0].any) R.string.add_from_hint else R.string.add_app_channel_hint, l.size), false, false) {
                    open?.dismiss(); open = Picker.show(this, Picker.Mode.GRID, getString(if (l[0].any) R.string.add_from_app else R.string.add_app_channel, l[0].app), only = pkg, paste = l[0].any)
                }.also { it.picture = icon(if (l[0].any) Links.source(this, l[0]) ?: "" else pkg) })
            if (Apps.one(this, "dev.tvremote.channels") != null)
                chans.add(TvGlass.Item(R.drawable.ic_plus, getString(R.string.add_channel), getString(R.string.add_channel_hint), false, false) { if (!Apps.launch(this, "dev.tvremote.channels")) toast(R.string.no_open) })
            for ((name, kind, items) in listOf(Triple(R.string.add_g_chans, R.drawable.ic_play, chans), Triple(R.string.add_g_live, R.drawable.ic_zap, live), Triple(R.string.add_g_pins, R.drawable.ic_star, pins), Triple(R.string.add_g_rows, R.drawable.ic_eye, rowsOff))) {
                if (items.size == 1 && name != R.string.add_g_live) { rows.add(items[0]); continue }   // one alone: no page to open first (live widgets keep theirs)
                if (items.isEmpty()) continue
                val i = rows.size
                rows.add(TvGlass.Item(kind, getString(name), items.size.toString(), true, true) { p.page(getString(name), items, back = { top(i) }) })
            }
            p.page(getString(R.string.add_title), rows, at)
        }
        top(0)
        open = p.dialog
        p.show()
    }

    /** The inputs' menu under the top bar (the list is [Inputs.rows], the Control Center counts the same): the TV's own settings when it lists none. */
    private fun inputs() {
        val rows = Inputs.rows(this, conf.optBoolean("infold")) { toast(R.string.no_open) }
        if (rows.isEmpty()) { if (Inputs.liveApp(this)?.let { Apps.launch(this, it) } == true || Inputs.panel(this)) return; toast(R.string.no_inputs); open(Settings.ACTION_SETTINGS); return }   // a Live TV app is the TV's own input picker
        open?.dismiss()
        open = Drop.show(this, bar.capsule, rows)
    }

    private fun open(action: String) {
        try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: SecurityException) {
            // Android TV's pairing screen wants BLUETOOTH_CONNECT: given over ADB (HomeConfig.GRANT_WIFI); installed without it, asked once here
            // (it fell to Settings' main page on an FPT Play box: "Permission Denial ... requires android.permission.BLUETOOTH_CONNECT")
            if (Build.VERSION.SDK_INT >= 31 && afterGrant == null && checkSelfPermission(BT_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                afterGrant = action; requestPermissions(arrayOf(BT_CONNECT), REQ_BT)
            } else settings()
        }
        catch (e: Exception) { settings() }
    }

    private fun settings() { dev.tvremote.home.apps.SystemSettings.open(this) }   // the vendors' Settings when the standard one is missing, else a toast

    private var afterGrant: String? = null                            // the screen [open] goes on to once the person answered

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != REQ_BT) return
        val then = afterGrant ?: return
        if (res.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) open(then) else settings()   // still set: no second question
        afterGrant = null
    }

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()

    private fun text(size: Float, color: Int) = Font.on(TextView(this)).apply { textSize = size; setTextColor(color); shadow(this) }

    private fun shadow(t: TextView) = t.setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x80000000.toInt())

    /** Never scrolls by itself to the focused view: [tile] and the buttons say where to, so a row is not pressed to the edge. */
    private class Scroller(c: Context, private val moved: () -> Unit) : ScrollView(c) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?) = 0
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) { super.onScrollChanged(l, t, oldl, oldt); moved() }
    }

    /** A column whose alpha is applied to each child, not through a layer the size of the whole grid. */
    private class Flat(c: Context) : LinearLayout(c) { override fun hasOverlappingRendering() = false }

}

/**
 * One app: its banner (or icon on a colour, with the name; or, for a live widget, its own render: [LiveFace]), the name under it shown while focused. Focus grows it and rings it
 * ([TvTile]: the look is tvui's, TV Home's own moved there). [app] null: the "hidden apps" tile.
 */
open class Tile(c: Context, val app: App?, w: Int, label: String, dp: (Number) -> Int, say: (String) -> String = { it }, mark: Int = R.drawable.ic_eye_off,
           private val art: android.graphics.drawable.Drawable? = null, corner: String? = null) : TvTile(c, w, dp) {
    private val content: View
    private var pic: ImageView? = null                              // the picture, so it can be let go and put back ([release])
    private val k = (w / dp(150).toFloat()).coerceIn(0.6f, 1f)        // the tile's size against 5 columns: the badge, the names and the pill shrink with the picture (many columns: they covered it, the user 2026-10-26)
    private val badge = BadgeView(c, dp, k)
    private var shownMark: Badges.Mark? = null
    private val keyBadge = BadgeView(c, dp, k)
    private var shownKeys: List<Int> = emptyList()

    init {
        Font.on(name); name.textSize = 13f * k
        val h = w * 9 / 16
        val banner = app?.banner ?: art                                // art: a tile that is not an app (TV Setup suggested)
        val liveFace = if (app?.live == true) LiveFace(c, w, h, label, say).also { it.set(LiveWidgets.cached(app.pkg), animate = false) } else null
        faceColor(if (banner != null) 0xFF1E2230.toInt() else shade(app?.pkg ?: ""))
        if (liveFace != null) {                                       // a live widget draws itself ([LiveWidgets]); its state comes from [live]
            content = liveFace
            fill(content)
        } else if (banner != null) {
            content = ImageView(c).apply { setImageDrawable(banner); scaleType = ImageView.ScaleType.CENTER_CROP }
            pic = content as ImageView
            fill(content)
        } else {
            val inner = LinearLayout(c).apply { orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(4), dp(6), dp(4)) }
            val ts = (12f * k).coerceAtLeast(9f)                      // many columns make small tiles: the name was cut under the icon (the user, 2026-10-26)
            val icon = minOf(h * 45 / 100, h - dp(12) - ((ts + if (corner != null) ts - 2 else 0f) * 1.35f * c.resources.displayMetrics.scaledDensity).toInt() - dp(2))   // what is left once the name and paddings fit
            inner.addView(ImageView(c).apply {
                if (app?.icon != null) { setImageDrawable(app.icon); pic = this } else { setImageResource(mark); imageTintList = ColorStateList.valueOf(Color.WHITE) }
            }, LayoutParams(icon, icon))
            inner.addView(Font.on(TextView(c)).apply {
                text = label; textSize = ts; includeFontPadding = false; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }, LayoutParams(-1, -2))
            if (corner != null) inner.addView(Font.on(TextView(c)).apply {   // whose it is, under the name: as a corner pill it sat on the icon of a small tile (the user, 2026-10-26)
                text = corner; textSize = ts - 2; setTextColor(SUB_INK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; includeFontPadding = false
            }, LayoutParams(-1, -2))
            fill(inner)
            content = inner
        }
        face.addView(LinearLayout(c).apply {                          // one corner for the marks: the other one held the banner's words (a key's mark on the left covered "TV Games")
            addView(keyBadge, LayoutParams(-2, -2)); addView(badge, LayoutParams(-2, -2).apply { leftMargin = (dp(4) * k).toInt() })
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, (dp(6) * k).toInt(), (dp(6) * k).toInt(), 0) })
        if (corner != null && banner != null) face.addView(Font.on(TextView(c)).apply {    // a suggestion, not an app on this TV: says so on its face
            text = corner; textSize = 12f * k; setTextColor(Color.WHITE); setPadding((dp(10) * k).toInt(), (dp(3) * k).toInt(), (dp(10) * k).toInt(), (dp(4) * k).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(PILL_BG); setStroke(dp(1), PILL_EDGE) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, (dp(8) * k).toInt(), (dp(8) * k).toInt(), 0) })
        name.text = if (banner != null) label else ""                 // a tile without a banner already shows its name inside
    }

    /** A live widget's newest state from its provider (null: no answer); [animate] when motion is on and not calm. */
    fun live(s: org.json.JSONObject?, animate: Boolean) { (content as? LiveFace)?.set(s, animate) }

    /** The picture let go (memory is short while the screen is hidden) and put back; the tile keeps its place and its colour meanwhile. */
    fun release() { pic?.setImageDrawable(null) }
    fun restore() { pic?.setImageDrawable(app?.banner ?: app?.icon ?: art) }

    /** The lock or the time left on the corner; nothing when [m] is null. Set only when it differs, so nothing is laid out for the same mark. */
    fun mark(m: Badges.Mark?, label: (Int) -> String) {
        if (m == shownMark) return
        shownMark = m
        badge.set(m, m?.let { if (it.locked || it.power) "" else label(it.minutes) } ?: "")
    }

    /** The remote keys that open this tile (Settings > Remote keys), beside the lock; none: hidden. Set only when they differ. */
    fun keys(codes: List<Int>, name: (Int) -> String) {
        if (codes == shownKeys) return
        shownKeys = codes
        keyBadge.keys(codes, name)
    }

    /** A dark colour of its own per app, for those without a TV banner. */
    private fun shade(pkg: String): Int {
        val hues = intArrayOf(0xFF27324A.toInt(), 0xFF2E3B2F.toInt(), 0xFF43293A.toInt(), 0xFF2B3A45.toInt(), 0xFF3D3424.toInt(), 0xFF2F2D4A.toInt())
        return hues[(pkg.hashCode() and 0x7fffffff) % hues.size]
    }
}

/** A folder panel's tile: the ring is its own (the gliding glass belongs to Home's grid, behind this window). */
class PanelTile(c: Context, app: App, w: Int, label: String, dp: (Number) -> Int) : Tile(c, app, w, label, dp) { override val glassy get() = false }

const val DIM = 0.6f                                                  // the tiles that are not being moved
/** The most rows of apps the first screen can show (to tenths), as the last layout measured; 0 = not measured yet. */
@Volatile var rowsFit = 0.0

const val STRIP_PAD = 28                                              // dp a Play Next row's bounds reach past the grid each side
const val STRIP_PAD_V = 16
const val WATCH_ROW = 20                                             // Play Next items a row of their own holds (it scrolls sideways)
const val GRID_GAP = 32                                               // dp between tiles, across and down: room under a focused tile for its name
private val OPENS = setOf("weather", "news", "market")             // the widgets that open a screen on OK; the others open their menu
private const val EDIT_EDGE = 0x80FFFFFF.toInt()                       // an element's outline in edit mode
private const val HEAD_DIM = 0xB3FFFFFF.toInt()                       // the greeting over the day
private const val PILL_BG = 0xE6101420.toInt()                          // the dark pill: the move hint, the menu tip, the name tags
private const val PILL_EDGE = 0x33FFFFFF                                // its 1 dp edge
private const val SUB_INK = 0xB3FFFFFF.toInt()                          // a second, softer line on a tile: whose it is
private const val PILL_INK = 0xFF111318.toInt()                         // a dark glyph on a white (focused) circle
private const val HEAD_RULE = 0x40FFFFFF                             // the line between the clock and the day
private const val POWER_INK = 0xFF7EE0A1.toInt()                    // the power mark: the green of "on", apart from the lock's red and the limit's amber

/**
 * A pill on a tile's corner: a lock, an hourglass and the time left, the power mark of the app opened at power-on ([set]), or the
 * remote keys that open the tile ([keys]). Drawn by hand with the paints made once, and measured only when it changes: nothing is
 * allocated while it is drawn (or while the tile grows). An icon alone is a circle.
 */
class BadgeView(c: Context, dp0: (Number) -> Int, k: Float = 1f) : View(c) {
    private val dp: (Number) -> Int = { n -> (dp0(n) * k).toInt() }        // a smaller tile, a smaller badge
    private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = PILL_BG }
    private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(13).toFloat(); typeface = Font.medium
    }
    private fun icon(id: Int, tint: Int = Color.WHITE): Drawable = context.getDrawable(id)!!.mutate().also { it.setTint(tint) }
    private val lock by lazy { icon(R.drawable.ic_lock, 0xFFFF8A80.toInt()) }
    private val glass by lazy { icon(R.drawable.ic_hourglass, 0xFFFFD27A.toInt()) }
    private val power by lazy { icon(R.drawable.ic_power, POWER_INK) }
    private val remote by lazy { icon(R.drawable.ic_remote) }
    private val box = android.graphics.RectF()
    private class Part(val text: String = "", val colour: Int? = null, val icon: Drawable? = null)
    private var parts: List<Part> = emptyList()

    init { visibility = GONE }

    fun set(m: Badges.Mark?, label: String) {
        if (m == null) { show(emptyList(), ""); return }
        val i = if (m.locked) lock else if (m.power) power else glass
        show(listOfNotNull(Part(icon = i), label.takeIf { it.isNotEmpty() }?.let { Part(it) }),
            if (m.locked) context.getString(R.string.b_locked) else if (m.power) context.getString(R.string.b_power) else label)
    }

    /**
     * The keys of the remote that open the tile, as on the remote: a colour key its coloured dot, the TV / Input / Settings / All apps
     * keys their line icon, a maker's button its name ("Netflix"), a number itself; after a small remote; up to three. Short on purpose:
     * "TV key" in words ran over the banner's own words ("TV Games"); the full names are the content description.
     */
    fun keys(codes: List<Int>, name: (Int) -> String) =
        show(if (codes.isEmpty()) emptyList() else listOf(Part(icon = remote)) + codes.take(3).map { key(it) }, codes.joinToString(", ") { name(it) })

    private fun key(code: Int): Part = dev.tvremote.home.keys.QuickKeys.COLOUR[code]?.let { Part(colour = it) } ?: when (code) {
        170 -> Part(icon = icon(R.drawable.ic_tv)); 178 -> Part(icon = icon(R.drawable.ic_input))
        176 -> Part(icon = icon(R.drawable.ic_settings)); 284 -> Part(icon = icon(R.drawable.ic_apps))
        190 -> Part("YouTube"); 191 -> Part("Netflix"); 195 -> Part("Prime"); 192 -> Part("PatchWall")      // the makers' names, the same in every language
        else -> Part((code - KeyEvent.KEYCODE_0).toString())
    }

    private fun show(p: List<Part>, desc: String) {
        parts = p
        visibility = if (p.isEmpty()) GONE else VISIBLE
        contentDescription = desc
        requestLayout(); invalidate()
    }

    private fun partW(p: Part) = if (p.colour != null) dp(12).toFloat() else if (p.icon != null) dp(15).toFloat() else text.measureText(p.text)
    private val alone get() = parts.size == 1 && parts[0].icon != null

    override fun onMeasure(w: Int, h: Int) {
        val inner = parts.fold(0f) { a, p -> a + partW(p) } + dp(5) * (parts.size - 1).coerceAtLeast(0)
        setMeasuredDimension(if (alone) dp(24) else (dp(8) + inner + dp(8)).toInt(), dp(24))   // an icon alone: a circle
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(box, height / 2f, height / 2f, bgPaint)
        val s = dp(15); val y = (height - s) / 2
        var x = if (alone) ((width - s) / 2).toFloat() else dp(8).toFloat()
        for (p in parts) {
            val col = p.colour; val ic = p.icon
            if (col != null) { dot.color = col; canvas.drawCircle(x + dp(6), height / 2f, dp(6).toFloat(), dot) }
            else if (ic != null) { ic.setBounds(x.toInt(), y, x.toInt() + s, y + s); ic.draw(canvas) }
            else canvas.drawText(p.text, x, height / 2f - (text.ascent() + text.descent()) / 2f, text)
            x += partW(p) + dp(5)
        }
    }
}

/** TV Home's settings into tvui's [Motion] (the object lives there, shared): "anim", "motion" and "focus" of the config. */
fun Motion.read(c: Context, conf: JSONObject) = read(c, conf.optBoolean("anim", true), conf.optString("motion"), conf.optString("focus", "glass"))

/** The colours of a built-in background (the page's small tiles copy them: js/home.js HOME_WALLS). */
fun wallColors(wall: String, conf: JSONObject? = null): IntArray = when (wall) {
    HomeConfig.OWN -> HomeConfig.ownColors(conf)
    "ocean" -> intArrayOf(0xFF03192B.toInt(), 0xFF0A4A66.toInt(), 0xFF05233A.toInt())
    "dusk" -> intArrayOf(0xFF1A0F2E.toInt(), 0xFF4A2150.toInt(), 0xFF7A3B3B.toInt())
    "forest" -> intArrayOf(0xFF06140F.toInt(), 0xFF133B2D.toInt(), 0xFF0A1F18.toInt())
    "aurora" -> intArrayOf(0xFF071A1F.toInt(), 0xFF0F4C4A.toInt(), 0xFF2B2A6B.toInt())
    "black" -> intArrayOf(Color.BLACK, Color.BLACK)
    HomeConfig.SKY -> Sky.colors()
    "sunset" -> intArrayOf(0xFF1F0D1C.toInt(), 0xFF6B2D4A.toInt(), 0xFFA14A4A.toInt())
    "ember" -> intArrayOf(0xFF190B08.toInt(), 0xFF4A1E10.toInt(), 0xFF7A2E12.toInt())
    "plum" -> intArrayOf(0xFF150B22.toInt(), 0xFF3A1F56.toInt(), 0xFF5A2F7A.toInt())
    "midnight" -> intArrayOf(0xFF050A1A.toInt(), 0xFF0B1B45.toInt(), 0xFF14307A.toInt())
    "teal" -> intArrayOf(0xFF041A1A.toInt(), 0xFF08413F.toInt(), 0xFF0E5F5F.toInt())
    "slate" -> intArrayOf(0xFF0F1218.toInt(), 0xFF252C3A.toInt(), 0xFF3A4558.toInt())
    "graphite" -> intArrayOf(0xFF1C1C1E.toInt(), 0xFF1C1C1E.toInt())
    "navy" -> intArrayOf(0xFF0B1D3A.toInt(), 0xFF0B1D3A.toInt())
    "wine" -> intArrayOf(0xFF3A0F1F.toInt(), 0xFF3A0F1F.toInt())
    "moss" -> intArrayOf(0xFF14261B.toInt(), 0xFF14261B.toInt())
    else -> if (WallArt.has(wall)) WallArt.colors(wall) else intArrayOf(0xFF0B0F1A.toInt(), 0xFF18223F.toInt(), 0xFF0B0F1A.toInt())
}

/** Which way a wall's gradient runs ([HomeConfig.WDIRS]): the person's own wall's "wdir"; every built-in one top-left to bottom-right. */
fun wallDir(wall: String, conf: JSONObject?) = if (wall == HomeConfig.OWN) conf?.optString("wdir")?.takeIf { it in HomeConfig.WDIRS } ?: "tl_br" else if (wall == HomeConfig.SKY) "tb" else "tl_br"

private val ORIENT = mapOf("tl_br" to GradientDrawable.Orientation.TL_BR, "tb" to GradientDrawable.Orientation.TOP_BOTTOM, "lr" to GradientDrawable.Orientation.LEFT_RIGHT,
    "bl_tr" to GradientDrawable.Orientation.BL_TR, "br_tl" to GradientDrawable.Orientation.BR_TL, "bt" to GradientDrawable.Orientation.BOTTOM_TOP,
    "rl" to GradientDrawable.Orientation.RIGHT_LEFT, "tr_bl" to GradientDrawable.Orientation.TR_BL)

/** [colors] running [dir]: one colour is a solid fill; "radial" spreads from the middle to the corners (its radius follows the bounds). */
fun wallGradient(colors: IntArray, dir: String = "tl_br"): GradientDrawable {
    val cs = if (colors.size == 1) intArrayOf(colors[0], colors[0]) else colors
    return if (dir == "radial") object : GradientDrawable(Orientation.TL_BR, cs) {
        init { gradientType = RADIAL_GRADIENT; setDither(true) }
        override fun onBoundsChange(r: Rect) { gradientRadius = maxOf(1f, Math.hypot(r.width().toDouble(), r.height().toDouble()).toFloat() / 2); super.onBoundsChange(r) }
    } else GradientDrawable(ORIENT[dir] ?: GradientDrawable.Orientation.TL_BR, cs).apply { setDither(true) }
}

/** For the screensaver, whose screen rarely changes; the home screen paints its wallpaper once on a [Backdrop] instead. */
fun wallDrawable(colors: IntArray, dir: String = "tl_br"): Drawable =
    if (colors.all { it == colors[0] }) ColorDrawable(colors[0]) else wallGradient(colors, dir)
