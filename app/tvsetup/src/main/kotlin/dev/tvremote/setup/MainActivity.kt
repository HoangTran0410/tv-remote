package dev.tvremote.setup

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.tvremote.core.T
import dev.tvremote.core.Words
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.ontv.SetupUpdate
import dev.tvremote.core.ontv.Tailscale
import dev.tvremote.setup.health.AutoUpdate
import dev.tvremote.setup.health.Support
import dev.tvremote.setup.health.SupportThread
import dev.tvremote.setup.keys.HoldKeys
import dev.tvremote.setup.keys.KeyFix
import dev.tvremote.setup.keys.KeysAddon
import dev.tvremote.setup.link.KeepAdb
import dev.tvremote.setup.link.LinkFailure
import dev.tvremote.setup.link.OnPhone
import dev.tvremote.setup.link.Paired
import dev.tvremote.setup.link.Tls
import dev.tvremote.setup.rules.Guard
import dev.tvremote.setup.rules.Rules
import dev.tvremote.tvui.Marks
import dev.tvremote.tvui.TvUi
import dev.tvremote.update.Release
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import dev.tvremote.tvui.TvScroll
import dev.tvremote.tvui.TvHScroll

/**
 * One screen, driven with the TV remote. Left: where things stand (ADB, the TV's addresses) and six actions as tiles.
 * Right: how a phone or computer takes over (the QR code of the web remote this TV serves, the phone app). Below: what happened.
 * Words follow the TV's language (Vietnamese, otherwise English).
 */
class MainActivity : Activity() {
    companion object {
        @Volatile var shown = false                                   // on screen: no update in the background meanwhile (AutoUpdate)
        private val steppedFor = java.util.Collections.synchronizedSet(HashSet<String>())   // the questions it stepped aside for once ([unblock])
        private val QUESTION = Regex("ActivityRecord\\{(\\w+)[^}]*UsbDebuggingActivity")
    }

    private lateinit var dot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var pairLine: TextView                          // the pairing code, whenever this screen is in front instead of PairActivity
    private val pairPoll = object : Runnable {
        override fun run() {
            val c = RemoteService.pairingCode()
            if (c == null) pairLine.visibility = View.GONE
            else { pairLine.text = T("tv_main.pair_code_line", "code" to c.substring(0, 3) + " " + c.substring(3)); pairLine.visibility = View.VISIBLE }
            if (shown) pairLine.postDelayed(this, 1000)
        }
    }
    private lateinit var logView: TextView
    private lateinit var bar: ProgressBar
    /** The bar at [pct] (0-100), or hidden with -1; from any thread. */
    private fun barAt(pct: Int) = ui { if (pct < 0 || pct >= 100) bar.visibility = View.GONE else { bar.visibility = View.VISIBLE; bar.progress = pct } }
    private lateinit var scroll: ScrollView
    private lateinit var spinner: ProgressBar
    private lateinit var tiles: List<Tile>
    private lateinit var connTile: Tile
    private var tsStatus = ""                                         // Tailscale's line in the Connection menu

    /** The remote's buttons the stock launcher answered (KeyFix): on, and brought back now, only while one is dead. */
    private fun keysSub() = if (!KeyFix.wanted(this)) T("keyfix.off") else if (!KeyFix.can(this)) T("keyfix.need_adb")
        else if (KeyFix.current(this).isNotEmpty()) T("keyfix.working") else T("keyfix.ok")
    private lateinit var updateTile: Tile
    private lateinit var webCol: LinearLayout
    private lateinit var webBtn: Button
    private var lanIp = ""
    private var tsIp = ""
    private var adb: Adb? = null
    /** Opened by TV Home's "Home screen" (extra open=launchers): the launcher picker as soon as ADB is up. */
    private var openLaunchers = false
    private var setup: Setup? = null
    private var busy = false
    private var focusedOnce = false

    private val TILE = Color.rgb(34, 39, 47)
    private val MUTED = Color.rgb(140, 150, 163)
    private val SCRIM = Color.argb(215, 5, 7, 10)                     // behind a popup: the screen dimmed, still there
    private val ACCENT = Color.rgb(59, 115, 246)
    private val ACCENT_SOFT = Color.argb(64, 59, 115, 246)              // the person's bubble in Support replies, as on the phone page
    private val OK = Color.rgb(70, 210, 131)
    private val WARN = Color.rgb(240, 179, 74)
    private val BAD = Color.rgb(255, 112, 112)
    private val HOWTO = Color.rgb(138, 180, 248)                       // what to do next: a light blue, apart from white info and the three states
    private val BG_CLEAR = TvUi.BG and 0x00FFFFFF                     // the page's colour, see-through: a fade to it, not to black
    private val PILL = Color.argb(153, 0, 0, 0)                        // the pill on an app's banner: TV Home's name scrim
    private val PILL_ON = Color.argb(230, 22, 128, 72)                // ...on a green that says it is on this TV
    private val PILL_NEW = Color.argb(235, 196, 110, 12)              // ...on amber: a newer one is in the latest release
    private val ECO_W = 112                                           // an app's banner, dp: small, so the log below keeps its lines

    /**
     * One of the project's other TV apps (Bundle.apps) as TV Home draws a tile: its banner, the same rise and ring on focus, and a
     * pill saying whether it is on this TV. OK opens it, or offers to install it, with no ADB too (the user, 2026-10-25: TV Home first).
     */
    private inner class Eco(val app: dev.tvremote.core.ontv.Bundle.App, banner: Int) :
        dev.tvremote.tvui.TvTile(this@MainActivity, dp(ECO_W), { n -> (n.toFloat() * resources.displayMetrics.density).roundToInt() }) {
        init { name.visibility = View.GONE }                          // what it does goes in the words beside it
        override val grows get() = false                             // focus = the ring only, no growth (the user, 2026-11-03: cut at top and bottom)
        val key = app.pkg.substringAfterLast('.')                    // home, channels, games, apps
        val sub = when (key) { "home" -> T("eco.home_sub"); "channels" -> T("eco.channels_sub"); "apps" -> T("eco.tvapps_sub"); "voice" -> T("eco.voice_sub"); "setup" -> T("eco.setup_sub"); else -> T("eco.games_sub") }   // what it is, beside the banners while focused
        override val glassy get() = false                         // no gliding glass on this screen: the ring
        var on = false; private set
        var version = ""; private set                               // on this TV ("" when not)
        /** The latest release's number when newer than this TV's copy, else null. */
        val newer get() = ecoLatest?.optString(app.key)?.takeIf { on && it.isNotEmpty() && dev.tvremote.home.HomeConfig.newer(it, version) }
        private val pill = TextView(context)
        init {
            faceColor(TvUi.CARD)                                      // the face's rounded outline, which clips the banner
            fill(ImageView(context).apply { setImageResource(banner); scaleType = ImageView.ScaleType.CENTER_CROP })
            pill.textSize = 10f; pill.setTextColor(Color.WHITE); pill.setPadding(dp(7), dp(2), dp(7), dp(2))
            face.addView(pill, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            setOnFocusChangeListener { _, f -> focus(f); ecoNote?.text = if (f) said() else ecoText() }
            setOnClickListener { if (!busy) ecoPress(this) else log(T("tv_main.busy")) }
        }
        fun paint() {
            version = try { packageManager.getPackageInfo(app.pkg, 0).versionName ?: "0" } catch (e: Exception) { "" }
            on = version.isNotEmpty()
            val up = newer != null
            pill.text = if (up) T("eco.new") else if (on) T("eco.on", "v" to version) else T("eco.off")   // its number: which TV app is behind, at a glance
            pill.background = box(if (up) PILL_NEW else if (on) PILL_ON else PILL, 10, 0)
            if (hasFocus()) ecoNote?.text = said()
        }
        /** Beside the banners, not under them (no gap to keep for a name: the log below keeps its lines): what OK does, what it is. */
        fun said(): CharSequence {
            val act = if (app === SELF) (if (this@MainActivity.newer.isNullOrEmpty()) "TV Setup $version" + (if (this@MainActivity.newer == "") " · " + T("tv_main.up_to_date") else "") else T("tv_main.update_to", "v" to this@MainActivity.newer!!))
                else newer?.let { T("eco.update", "name" to app.name, "from" to version, "to" to it) } ?: if (on) T("eco.open", "name" to app.name, "v" to version) else T("eco.get", "name" to app.name)
            return android.text.SpannableString("$act\n$sub").apply { setSpan(android.text.style.ForegroundColorSpan(TvUi.TEXT), 0, act.length, 0) }
        }
    }
    private var eco: List<Eco> = emptyList()
    /** TV Setup Voice in the row too (the user, 2026-11-03: not seen there), never in [dev.tvremote.core.ontv.Bundle]: that installs by itself, this only when asked. */
    private val VOICE = dev.tvremote.core.ontv.Bundle.App("TV Setup Voice", dev.tvremote.core.ontv.VoiceAddon.PKG, "tv", dev.tvremote.core.ontv.VoiceAddon.APK) { a, d, l, p -> dev.tvremote.core.ontv.VoiceAddon.install(a, d, l, progress = p, voice = MicVoice.lang(this)) }
    /** TV Setup itself, first in the row (the user, 2026-11-03: it is one of the apps too); OK opens its Update menu, as the tile at the top. */
    private val SELF = dev.tvremote.core.ontv.Bundle.App("TV Setup", "dev.tvremote.setup", "tv", "tv-setup.apk") { _, _, _, _ -> org.json.JSONObject() }
    private lateinit var kidsOffTile: Tile
    private var ecoNote: TextView? = null
    private var ecoWaiting: Eco? = null                               // Android's "install unknown apps" switch was opened for this one

    /** An action: an icon, a title, and one line beside it saying what it does or where it stands. */
    private inner class Tile(icon: Int, title: String, sub: String, run: () -> Unit) {
        val view = LinearLayout(this@MainActivity)
        private val subView: TextView
        private val headView: TextView
        private val badge = ImageView(this@MainActivity)
        private val wheel = ProgressBar(this@MainActivity)              // in place of the icon while this tile's work runs
        init {
            view.gravity = Gravity.CENTER_VERTICAL
            view.setPadding(dp(10), dp(4), dp(10), dp(4))
            view.isFocusable = true
            view.isClickable = true
            view.background = box(TILE, 14, 0)
            badge.setImageResource(icon)
            badge.setColorFilter(TvUi.TEXT)
            badge.background = box(TvUi.CARD, 10, 0)
            badge.setPadding(dp(5), dp(5), dp(5), dp(5))
            val slot = android.widget.FrameLayout(this@MainActivity)
            slot.addView(badge, android.widget.FrameLayout.LayoutParams(dp(26), dp(26)))
            wheel.isIndeterminate = true
            wheel.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            wheel.visibility = View.GONE
            slot.addView(wheel, android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            view.addView(slot, LinearLayout.LayoutParams(dp(26), dp(26)))
            val words = LinearLayout(this@MainActivity)
            words.orientation = LinearLayout.VERTICAL
            words.setPadding(dp(10), 0, 0, 0)
            val head = text(title, 13, TvUi.TEXT, true)
            headView = head
            head.maxLines = 2                                         // four tiles across: a long name wraps, not "Manage on th…"
            head.ellipsize = TextUtils.TruncateAt.END
            words.addView(head)
            subView = text(sub, 11, MUTED, false)
            subView.maxLines = 2
            subView.ellipsize = TextUtils.TruncateAt.END
            words.addView(subView)
            view.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            view.setOnClickListener { if (!busy) run() else log(T("tv_main.busy")) }
            view.setOnFocusChangeListener { v, f ->
                v.background = if (f) TvUi.ring(this@MainActivity, TILE, dp(14).toFloat()) else box(TILE, 14, 0)
                badge.background = box(if (f) Color.argb(60, 255, 255, 255) else badgeFill, 10, 0)
                subView.setTextColor(if (f) Color.WHITE else MUTED)
                v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start()
            }
        }
        private var idle = sub
        private var working = false
        fun say(s: String) { idle = s; if (!working) subView.text = s }
        fun progress(s: String) { if (working) subView.text = s }
        fun work(on: Boolean) {
            working = on
            subView.text = if (on) T("setup.ts_working") else idle
            wheel.visibility = if (on) View.VISIBLE else View.GONE
            badge.imageAlpha = if (on) 0 else 255
        }
        fun enable(on: Boolean) { view.isEnabled = on; view.alpha = if (on) 1f else 0.4f }
        /** A new title and icon; [hot] fills the icon's square with the accent: something is waiting to be done here. */
        fun show(title: String, sub: String, icon: Int, hot: Boolean = false) {
            headView.text = title; say(sub); badge.setImageResource(icon); badgeFill = if (hot) ACCENT else TvUi.CARD
            if (!view.hasFocus()) badge.background = box(badgeFill, 10, 0)
        }
        private var badgeFill = TvUi.CARD
    }

    /**
     * A phone (TV Setup runs there too, see README): one scrolling column instead of the TV's two, whichever way it is held.
     * Not the screen size alone: a 1080p TV is only 540dp on its short side.
     */
    private val phone by lazy {
        !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) && resources.configuration.smallestScreenWidthDp < 600
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        openLaunchers = intent?.getStringExtra("open") == "launchers"
        dev.tvremote.core.adb.Adb.holdFirstAsk(10000)                       // a phone turning ADB on asks first, then allows us too (AdbPrompt.helpSetup)
        val page = LinearLayout(this)
        page.setBackgroundColor(TvUi.BG)
        if (phone) {
            page.orientation = LinearLayout.VERTICAL
            page.setPadding(dp(16), dp(20), dp(16), dp(24))
        } else page.setPadding(resources.getDimension(R.dimen.tv_margin_x).toInt(), resources.getDimension(R.dimen.tv_margin_y).toInt(),
            resources.getDimension(R.dimen.tv_margin_x).toInt(), resources.getDimension(R.dimen.tv_margin_y).toInt())   // app/DESIGN-TV.md

        // ---- left: header, status, actions, activity
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.clipChildren = false                                     // a focused tile grows 3%: drawn into the page's margin, not cut
        page.clipChildren = false; page.clipToPadding = false

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.icon)
        logo.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, resources.getDimension(R.dimen.tv_head_radius)) }
        }
        logo.clipToOutline = true
        val ic = if (phone) dp(38) else resources.getDimension(R.dimen.tv_head_icon).toInt()
        head.addView(logo, LinearLayout.LayoutParams(ic, ic))
        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        names.setPadding(resources.getDimension(R.dimen.tv_head_gap).toInt(), 0, 0, 0)
        names.addView(text("TV Setup", if (phone) 20 else 24, TvUi.TEXT, true))
        names.addView(text("v" + version() + released().let { if (it.isEmpty()) "" else " · $it" }
            + " · Hoang Tran · tvremote.pages.dev", 12, MUTED, false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })   // the version in use here; the Update tile says whether a newer one exists
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        left.addView(head)

        val status = LinearLayout(this)
        status.gravity = Gravity.CENTER_VERTICAL
        status.background = box(TvUi.CARD, 16, 0)
        status.setPadding(dp(16), dp(10), dp(16), dp(10))
        dot = View(this)
        status.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        val st = LinearLayout(this)
        st.orientation = LinearLayout.VERTICAL
        st.setPadding(dp(14), 0, 0, 0)
        statusTitle = text("", 15, TvUi.TEXT, true)
        statusDetail = text("", 12, MUTED, false)
        st.addView(statusTitle)
        st.addView(statusDetail)
        status.addView(st, LinearLayout.LayoutParams(0, -2, 1f))
        left.addView(status, margins(top = 12))
        // Since Android 10 a service cannot open PairActivity over another app, and the shell route fails when ADB is off: without
        // this line a phone asking to pair would leave the person with a code nobody can see.
        pairLine = text("", 20, TvUi.TEXT, true).apply {
            background = box(TvUi.CARD, 16, 0); setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE
        }
        left.addView(pairLine, margins(top = 8))

        if (phone) left.addView(section(T("tv_main.actions")), margins(top = 14))   // TV: no section names, the log keeps the room (the user, 2026-10-25)
        tsStatus = T("tv_main.away_from_home")
        updateTile = Tile(R.drawable.ic_download, T("setup.update_ok"), T("tv_main.checking_for_a_new")) { updateMenu() }
        connTile = Tile(R.drawable.ic_refresh, T("tv_main.connection"), T("tv_main.connection_sub")) { connectionMenu() }
        // four tiles, not nine: the rest sit in short menus. The code below counts these by place: 0 needs ADB, 1 and 2 work without
        tiles = listOf(
            Tile(R.drawable.ic_zap, T("tv_main.optimize_tv"), T("tv_main.group_optimize_sub")) { optimizeMenu() },
            connTile,
            updateTile)
        // the web UI right here, for when no phone or computer is at hand (ManageActivity): first, as it does most
        val manage = Tile(R.drawable.ic_apps, T("tv_main.manage_on_this_tv"), T("tv_main.apps_files_health_automation")) {
            RemoteService.start(this)                                 // serves 127.0.0.1 even with the web remote off
            startActivity(android.content.Intent(this, ManageActivity::class.java).putExtra(ManageActivity.PASS, RemoteService.selfPass))
        }
        // a TV has no Update tile: TV Setup's own banner, first in the More TV apps row, is it (the user, 2026-11-03: twice was too many);
        // updateTile stays, unseen, as the state it paints from
        // kids mode on: its way off right here, the PIN asked first (PinActivity), as in TV Home's Quick settings; gone once off (onResume)
        kidsOffTile = Tile(R.drawable.ic_lock, T("pin.kids_off_title"), T("tv_main.kids_off_sub")) {
            startActivity(android.content.Intent(this, PinActivity::class.java).putExtra("kids_off", true))
        }
        kidsOffTile.view.visibility = View.GONE
        left.addView(kidsOffTile.view, LinearLayout.LayoutParams(-1, dp(if (phone) 76 else 60)).apply { topMargin = dp(if (phone) 8 else 12) })
        val grid = listOf(manage) + if (phone) tiles else tiles.filter { it !== updateTile }   // Manage first, in the grid: no ragged last row
        val cols = if (phone) 2 else 3                                 // TV: one row of three; phone: 2 x 2
        for (row in grid.chunked(cols)) {
            val r = LinearLayout(this)
            r.clipChildren = false
            row.forEachIndexed { i, tile -> r.addView(tile.view, LinearLayout.LayoutParams(0, dp(if (phone) 76 else 60), 1f).apply { if (i > 0) leftMargin = dp(10) }) }
            left.addView(r, margins(top = if (phone) 8 else 12))
        }

        if (!phone) {                                                 // the project's TV apps: what a phone running TV Setup has no use for
            val row = LinearLayout(this)
            row.clipChildren = false
            row.gravity = Gravity.CENTER_VERTICAL
            val pics = mapOf("home" to R.drawable.eco_home, "channels" to R.drawable.eco_channels, "games" to R.drawable.eco_games, "apps" to R.drawable.eco_tvapps, "voice" to R.drawable.eco_voice, "setup" to R.drawable.banner)
            eco = (listOf(SELF) + dev.tvremote.core.ontv.Bundle.apps.let { b -> val g = b.indexOfFirst { it.pkg.endsWith(".games") }.let { if (it < 0) b.size else it }; b.take(g) + VOICE + b.drop(g) })   // TV Voice before TV Games (the user, 2026-11-03)
                .mapNotNull { a -> pics[a.pkg.substringAfterLast('.')]?.let { Eco(a, it) } }   // an app with no banner here yet is left out
            eco.forEachIndexed { i, e -> row.addView(e, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(14) }) }
            ecoNote = text("", 12, MUTED, false).also { it.minLines = 2; it.maxLines = 2; it.ellipsize = TextUtils.TruncateAt.END }   // two lines held: no jump on focus
            // a row that scrolls sideways: more apps than the column holds (TV Setup Voice made five, the user 2026-11-03); focus scrolls it
            val strip = object : TvHScroll(this) {
                override fun getLeftFadingEdgeStrength() = 0f         // Android's own fade drew a hard dark band: the shades below instead,
                override fun getRightFadingEdgeStrength() = 0f        // while its length still scrolls the focused banner clear of them
            }.apply {
                isHorizontalScrollBarEnabled = false; isFillViewport = false
                isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(44))
                isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                val m = dp(14); row.setPadding(m, m, m, m); row.clipToPadding = false; row.clipChildren = false   // the ring lies outside the banner              // a scroll view cuts at its own edge: room inside it for a focused banner's growth, ring and shadow (the user, 2026-11-03: cut);
                                                              // on the row, not the scroll view, whose focus scrolling leaves its own padding out (the last banner stopped flush)
                addView(row, android.widget.FrameLayout.LayoutParams(-2, -2))
            }
            // the page's colour fading out over an edge with more apps past it (the user, 2026-11-03), none at an end
            fun shade(end: Boolean) = object : View(this) {               // no height of its own: the row's (else it took the log's room)
                override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else 0)
            }.apply {
                background = android.graphics.drawable.GradientDrawable(if (end) android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT else android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(TvUi.BG, BG_CLEAR))
                visibility = View.INVISIBLE
            }
            val shadeL = shade(false); val shadeR = shade(true)
            val shades = { val max = row.width - strip.width
                shadeL.visibility = if (strip.scrollX > 0) View.VISIBLE else View.INVISIBLE
                shadeR.visibility = if (strip.scrollX < max - 1) View.VISIBLE else View.INVISIBLE }
            strip.viewTreeObserver.addOnScrollChangedListener { shades() }
            strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> shades() }
            val box = android.widget.FrameLayout(this).apply {
                addView(strip, android.widget.FrameLayout.LayoutParams(-1, -2))
                addView(shadeL, android.widget.FrameLayout.LayoutParams(dp(36), -1, Gravity.START).apply { topMargin = dp(10); bottomMargin = dp(10) })   // over the banners only,
                addView(shadeR, android.widget.FrameLayout.LayoutParams(dp(36), -1, Gravity.END).apply { topMargin = dp(10); bottomMargin = dp(10) })     // not the tiles above
            }
            left.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) - dp(14); leftMargin = -dp(14); bottomMargin = -dp(8) })   // the padding outside on the left (first tile in line with the column); the right edge is the column's
            left.addView(ecoNote, margins(top = 6))                   // under the banners: four of them leave no room beside
            ecoPaint()
        }

        if (phone) left.addView(section(T("tv_main.activity")), margins(top = 18))
        spinner = ProgressBar(this)                                   // in the log's corner: work going on shows where its lines come
        spinner.isIndeterminate = true
        spinner.visibility = View.INVISIBLE
        logView = text(T("tv_main.activity_empty"), 14, MUTED, false)   // until the first line
        logView.setLineSpacing(dp(3).toFloat(), 1f)
        scroll = TvScroll(this)
        scroll.background = box(TvUi.CARD, 14, 0)
        scroll.setPadding(dp(16), dp(10), dp(16), dp(10))
        scroll.isFocusable = true                                     // D-pad can scroll back to an error that went by
        scroll.onFocusChangeListener = View.OnFocusChangeListener { v, on -> v.background = if (on) TvUi.ring(this@MainActivity, TvUi.CARD, dp(14).toFloat()) else box(TvUi.CARD, 14, 0) }
        scroll.addView(logView)
        val logBox = android.widget.FrameLayout(this)
        logBox.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        logBox.addView(spinner, android.widget.FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(12), 0) })
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {   // a download's percent, under the lines: TV Setup Voice is 39 MB
            max = 100; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(TvUi.FOCUS_FILL)
        }
        logBox.addView(bar, android.widget.FrameLayout.LayoutParams(-1, dp(6), Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(10)) })
        // ---- right: taking over from a phone or a computer
        webCol = LinearLayout(this)
        webCol.orientation = LinearLayout.VERTICAL
        webCol.background = box(TvUi.CARD, 18, 0)
        webCol.setPadding(dp(18), dp(14), dp(18), dp(14))
        webBtn = smallButton("") { RemoteService.setEnabled(this, !RemoteService.enabled(this)); showWeb(); webBtn.requestFocus() }
        if (phone) {                                                  // header, status, then the card (what a phone is mostly here for), the tiles, the log
            left.addView(webCol, 3, margins(top = 16))
            scroll.isNestedScrollingEnabled = true
            left.addView(logBox, LinearLayout.LayoutParams(-1, dp(240)).apply { topMargin = dp(8) })
            page.addView(left, LinearLayout.LayoutParams(-1, -2))
            val outer = TvScroll(this)
            outer.setBackgroundColor(TvUi.BG)
            outer.isFillViewport = true
            outer.addView(page)
            setContentView(outer)
        } else {
            left.addView(logBox, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(if (eco.isEmpty()) 12 else 22) })   // under a focused banner's rise
            page.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
            page.addView(webCol, LinearLayout.LayoutParams(dp(260), -1).apply { leftMargin = dp(20) })
            setContentView(page)
        }

        if (RemoteService.enabled(this)) RemoteService.start(this)
        showWeb()
        connect()
        Thread {                                                      // asks GitHub for the latest release, nothing else is sent
            try {
                val r = Release.newer(version(), "tv", "tv-setup.apk")
                ui { newer = r?.version ?: ""; showUpdate() }
            } catch (ignored: Exception) { ui { showUpdate() } }      // offline: the tile still checks on demand
        }.start()
    }

    /** Opened by touch or by another app, nothing has the focus yet: the first arrow key lands on the first tile that works. */
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        val arrow = e.keyCode in intArrayOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        if (arrow && e.action == android.view.KeyEvent.ACTION_DOWN && (currentFocus == null || window.decorView.isInTouchMode)) {
            (tiles.firstOrNull { it.view.isEnabled } ?: tiles[1]).view.let { it.isFocusableInTouchMode = true; it.requestFocus(); it.isFocusableInTouchMode = false }
            return true
        }
        if (e.keyCode == android.view.KeyEvent.KEYCODE_BACK && scroll.hasFocus()) {   // Back from the log goes up to the buttons; the log keeps its place
            if (e.action == android.view.KeyEvent.ACTION_UP) (scroll.focusSearch(View.FOCUS_UP) ?: tiles[0].view).requestFocus()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    /** The day this build was made, as dd/mm/yyyy ("" when unknown). */
    private fun released(): String = try {
        getString(resources.getIdentifier("released", "string", packageName)).split("-").let { it[2] + "/" + it[1] + "/" + it[0] }
    } catch (e: Exception) { "" }

    private fun version(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    /** The newest release when it is newer than this one, "" when this is the newest, null when not known (offline, not asked yet). */
    private var newer: String? = null

    /** The Update tile says what is true now: a newer version to install (lit), up to date, or not known (the user, 2026-10-26:
     *  an "Update" tile always there, with a version number nobody could tell was this one or the next, was confusing). */
    private fun showUpdate() { eco.firstOrNull()?.paint(); when (val v = newer) {   // TV Setup's own banner says it too
        null -> updateTile.show(T("setup.update_ok"), T("tv_main.update_check"), R.drawable.ic_download)
        "" -> updateTile.show(T("tv_main.up_to_date"), autoText(), R.drawable.ic_check)
        else -> updateTile.show(T("tv_main.update_to", "v" to v), T("tv_main.update_from", "v" to version()), R.drawable.ic_download, hot = true)
    } }

    private fun autoText() = if (AutoUpdate.on(this)) T("tv_main.automatic_updates_on") else T("tv_main.automatic_updates_off")

    /**
     * Downloads this app's APK from the latest GitHub release and installs it through the TV's own ADB (no "unknown sources"
     * needed), with SetupUpdate's script: it opens this screen again once the new version runs, or the old one if it does not.
     */
    /** The Update tile: update now, automatic updates on or off, and removing this app, kept out of the way here. */
    private fun updateMenu() {
        if (busy) return
        menu("TV Setup " + version(), listOf(
            TvUi.Item(R.drawable.ic_download, T("tv_main.update_now")) { if (adb == null) selfUpdate() else update() },
            TvUi.Item(R.drawable.ic_refresh, T("tv_main.automatic_updates"), on = { AutoUpdate.on(this) }) { AutoUpdate.set(this, !AutoUpdate.on(this)); log(autoText()); showUpdate() },
            TvUi.Item(R.drawable.ic_trash, T("tv_main.remove_tv_setup")) { askRemove() }))
    }

    private fun update() {
        job { _ ->
            log(T("tv_main.checking_for_a_new"))
            val r = Release.newer(version(), "tv", "tv-setup.apk")
            ui { newer = r?.version ?: ""; showUpdate() }
            if (r == null) { log(T("tv_main.newest_version", "v" to version())); return@job }
            val a = adb ?: throw java.io.IOException("ADB")
            if (SetupUpdate.bad(SetupUpdate.state(a), r.version)) {
                log(T("tv_main.version_bad_back", "v" to r.version))
                return@job
            }
            log(T("tv_main.downloading_version", "v" to r.version))
            val apk = File(getExternalFilesDir("update"), "tv-setup-" + r.version + ".apk")
            var last = 0
            r.download(apk, Release.Progress { pct -> if (pct >= last + 25) { last = pct; log("  $pct%") } })
            log(T("tv_main.installing_tv_setup_opens"))
            SetupUpdate.start(a, apk.absolutePath, r.version, true)
        }
    }

    private val self by lazy { dev.tvremote.update.SelfUpdate(this, "tv", "tv-setup.apk") }

    /**
     * No ADB of its own (off, its key refused, a box that blocks it): Android's installer instead, which asks once, as TV Home
     * updates. Asking for ADB here was a loop for the very TVs whose ADB is the trouble (the user, 2026-10-25).
     */
    private fun selfUpdate() {
        if (busy) return
        busy = true
        log(T("tv_main.checking_for_a_new"))
        Thread {
            try {
                val r = Release.newer(version(), "tv", "tv-setup.apk")
                ui { newer = r?.version ?: ""; showUpdate() }
                if (r == null) log(T("tv_main.newest_version", "v" to version())) else ui { install(r) }
            } catch (e: Exception) { log("✗ " + reason(e)) }
            busy = false
        }.start()
    }

    private fun install(r: Release) {
        var last = -25
        val going = self.install(r, { pct -> if (pct >= last + 25) { last = pct; log("  $pct%"); if (pct >= 100) log(T("tv_main.installer_asks")) } },
            { m -> log("✗ " + m.ifEmpty { "Android" }) })
        log(if (going) T("tv_main.downloading_version", "v" to r.version) else T("tv_main.allow_installs"))   // not going: Android's switch is open, onResume goes on
    }

    /** How the last update went, said once on this screen when it is recent: a version that was undone is worth knowing about. */
    private fun lastUpdate(a: Adb) {
        val s = SetupUpdate.state(a)
        if (System.currentTimeMillis() - s.optLong("at") > 24 * 3600_000L) return
        val seen = getSharedPreferences("remote", MODE_PRIVATE)
        val key = s.optString("state") + s.optLong("at")
        if (seen.getString("update_seen", "") == key) return
        val line = when (s.optString("state")) {
            "ok" -> if (s.optString("version") == version()) T("tv_main.updated_to", "v" to version()) else null
            "rolledback" -> T("tv_main.rolled_back", "v" to s.optString("version"), "old" to s.optString("from"))
            "broken" -> T("tv_main.version_shaky", "v" to s.optString("version"))
            "failed" -> T("tv_main.install_failed", "v" to s.optString("version"), "detail" to s.optString("detail"))
            else -> null
        } ?: return
        seen.edit().putString("update_seen", key).apply()
        log(line)
    }

    override fun onPause() { shown = false; super.onPause() }

    /** TV Setup Voice installs on its own thread ([MicVoice.syncOffline]: started by its screen, TV Home's mic tile or a phone),
     *  so the log and the bar follow it from here while this screen shows (the user, 2026-11-03: the log said nothing). */
    private var voiceWatching = false
    private var voiceBefore: String? = null                           // its state when its screen was opened from here
    private fun voiceWatch() {
        val before = voiceBefore.also { voiceBefore = null }
        val now = MicVoice.offlineState(this)
        if (before != null && before != now && now != "busy") {      // installed (or failed) all while its screen was in front
            if (now == "ready") log("✓ " + T("tv_main.bundle_installed", "name" to VOICE.name)) else if (now.startsWith("failed:")) log("✗ " + VOICE.name + ": " + MicVoice.why(this, now))
            ecoPaint()
        }
        if (voiceWatching || now != "busy") return
        voiceWatching = true
        log(T("tv_main.bundle_installing", "name" to VOICE.name))
        val tick = object : Runnable { override fun run() {
            if (isFinishing) return
            val st = MicVoice.offlineState(this@MainActivity)
            if (st == "busy") { barAt(MicVoice.offlinePct); scroll.postDelayed(this, 500); return }
            voiceWatching = false; barAt(-1)
            if (st == "ready") log("✓ " + T("tv_main.bundle_installed", "name" to VOICE.name)) else if (st.startsWith("failed:")) log("✗ " + VOICE.name + ": " + MicVoice.why(this@MainActivity, st))
            ecoPaint()
        } }
        scroll.post(tick)
    }

    private var onPhoneShown = false
    /** A phone: once, "this is for TVs; the phone app is tv-remote.apk". "Use it here" is remembered and never asked again. */
    private fun askOnPhone() {
        val prefs = getSharedPreferences("remote", MODE_PRIVATE)
        val isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        if (onPhoneShown || !OnPhone.shouldAsk(isTv, prefs.getBoolean("onphone_stay", false))) return
        onPhoneShown = true
        val installed = packageManager.getLaunchIntentForPackage(OnPhone.PHONE_PKG)
        val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(SCRIM)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = box(TvUi.CARD, 22, 0)
        card.setPadding(dp(24), dp(22), dp(24), dp(18))
        card.addView(text(T("onphone.title"), 22, TvUi.TEXT, true))
        card.addView(text(T("onphone.body"), 15, MUTED, false), margins(top = 10))
        val go = smallButton(if (installed != null) T("onphone.open") else T("onphone.get")) {
            d.dismiss()
            try {
                startActivity(installed ?: android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(OnPhone.APK)))
            } catch (ignored: Exception) { }
        }
        val stay = smallButton(T("onphone.stay")) { prefs.edit().putBoolean("onphone_stay", true).apply(); d.dismiss() }
        card.addView(go, margins(top = 18))
        card.addView(stay, margins(top = 8))
        root.addView(card, android.widget.FrameLayout.LayoutParams(dp(340), -2, Gravity.CENTER))
        d.setContentView(root)
        d.show()
        go.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (Rules.hasCode(Rules.load(this)) && !Guard.isOpen(packageName) && PinActivity.showing == null) {   // a child must not turn the lock off here
            startActivity(android.content.Intent(this, PinActivity::class.java).putExtra("pkg", packageName))
        }
        shown = true
        kidsOffTile.view.visibility = if (Rules.kidsMode(Rules.load(this))) View.VISIBLE else View.GONE
        voiceWatch()
        if (MicVoice.on(this)) Thread({ micBlockLog() }, "mic-block").apply { isDaemon = true }.start()   // off: nothing is read
        self.check({ }, { r -> install(r) })                          // back from "install unknown apps" for an update: on with it
        askOnPhone()
        pairLine.removeCallbacks(pairPoll); pairPoll.run()
        showWeb()
        ecoPaint()                                                    // back from an app's install or from the app itself
        ecoWaiting?.let { e ->                                        // back from "install unknown apps": on with it when it was switched on
            ecoWaiting = null
            if (android.os.Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) ecoInstall(e)
        }
        val s = setup
        if (s != null && !busy) Thread { try { showStatus(s) } catch (ignored: Exception) { } }.start()   // back from Tailscale / Play Store: refresh the IPs
        else if (setup == null && retryOnResume && !connecting) { retryOnResume = false; connect() }   // back from Developer options: ADB may be on now
    }

    override fun onDestroy() {
        val a = adb                                                   // this screen's own connection; RemoteService has its own
        adb = null; setup = null
        Thread { try { a?.close() } catch (ignored: Exception) { } }.start()
        super.onDestroy()
    }

    private val APP_LINK = "https://tvremote.pages.dev/dl/tv-remote.apk"   // the site sends it on to the latest release (docs/_worker.js): a smaller QR

    /** This TV's address on the home network, read from the network interfaces (ADB not needed). */
    private fun wifiIp(): String = RemoteService.lanIp()

    /** The right-hand card: the web remote's QR code and address, pairing, and the phone app. */
    private fun showWeb() {
        val on = RemoteService.enabled(this)
        val ip = lanIp.ifEmpty { wifiIp() }
        val focused = currentFocus
        webCol.removeAllViews()
        webCol.addView(text(T("tv_main.control_from_a_phone"), 16, TvUi.TEXT, true).apply { gravity = Gravity.CENTER_HORIZONTAL },   // over the centred QR and address
            LinearLayout.LayoutParams(-1, -2))
        if (on && ip.isNotEmpty()) {
            val secure = RemoteService.secure()                       // HTTPS once its certificate exists; plain HTTP before and on an older browser's bookmark
            val scheme = if (secure) "https://" else "http://"
            val port = if (secure) RemoteService.HTTPS_PORT else RemoteService.PORT
            val link = scheme + ip + ":" + port
            qr(link)?.let { webCol.addView(it, LinearLayout.LayoutParams(dp(136), dp(136)).apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL }) }
            val addr = text(link.removePrefix(scheme), 15, TvUi.TEXT, true)
            addr.gravity = Gravity.CENTER
            webCol.addView(addr, margins(top = 8))
            if (secure) Tls.shortFingerprintIfReady(this)?.let {      // to compare with what the browser shows for the certificate
                webCol.addView(text(T("tv_main.fingerprint", "fp" to it), 12, MUTED, false).apply { gravity = Gravity.CENTER })
            }
            if (tsIp.isNotEmpty()) {
                val ts = text(T("tv_main.away_addr", "addr" to "$tsIp:$port"), 13, MUTED, false)
                ts.gravity = Gravity.CENTER
                webCol.addView(ts)
            }
        } else {
            webCol.addView(text(if (on) T("tv_main.the_tv_has_no")
                else T("tv_main.off"), 14, MUTED, false), margins(top = 8))
        }
        webBtn.text = if (on) T("tv_main.web_remote_on") else T("tv_main.web_remote_off")
        (webBtn.parent as? LinearLayout)?.removeView(webBtn)
        webCol.addView(webBtn, margins(top = 10))
        if (on) webCol.addView(smallButton(T("tv_main.paired_devices_n", "n" to RemoteService.pairedCount(this))) { pairedMenu() }, margins(top = 6))

        webCol.addView(View(this), if (phone) LinearLayout.LayoutParams(1, dp(16)) else LinearLayout.LayoutParams(1, 0, 1f))
        // behind a button: two QR codes on one screen, and "scan the QR" (pairing, the web remote) was read off the wrong one
        webCol.addView(smallButton(T("tv_main.tv_remote_android_app")) { appQr() }, margins(top = 6))
        if (focused != null && focused.parent == null) webBtn.requestFocus()   // the focused button was rebuilt: keep the focus on this card
    }

    /** The phone app's download QR, alone on a card over the screen. */
    private fun appQr() {
        val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(SCRIM)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = box(TvUi.CARD, 22, 0)
        card.setPadding(dp(32), dp(26), dp(32), dp(22))
        card.addView(text(T("tv_main.tv_remote_android_app"), 22, TvUi.TEXT, true).apply { gravity = Gravity.CENTER_HORIZONTAL }, margins(top = 0))
        card.addView(text(T("tv_main.scan_to_download"), 14, MUTED, false).apply { gravity = Gravity.CENTER_HORIZONTAL }, margins(top = 4))
        qr(APP_LINK)?.let { card.addView(it, LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(16) }) }
        val close = smallButton(T("common.close")) { d.dismiss() }
        card.addView(close, margins(top = 18))
        root.addView(card, android.widget.FrameLayout.LayoutParams(dp(520), -2, Gravity.CENTER))
        d.setContentView(root)
        d.show()
        close.requestFocus()
    }

    /** The devices let in, one row each with when it was paired and its own Forget: the same list the TV web page shows (Paired.kt). */
    private fun pairedMenu() {
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        val now = System.currentTimeMillis()
        val list = Paired.list(this)
        val items = ArrayList<TvUi.Item>()
        for (d in list) {
            val days = ((now - d.seen) / (24 * 3600_000L)).toInt()
            val seen = if (days <= 0) T("tv_main.today") else if (days == 1) T("tv_main.yesterday") else T("tv_main.days_ago", "n" to days)
            val name = d.name.ifEmpty { T("tv_main.older_device") }
            // the details go on the second line (one per line, no " · " join); pressing the row asks to unpair that device
            val sub = listOf(if (d.created > 0) T("tv_main.paired_at", "at" to fmt.format(java.util.Date(d.created))) else "", T("tv_main.used_when", "when" to seen), d.ip)
                .filter { it.isNotEmpty() }.joinToString("\n")
            items.add(TvUi.Item(R.drawable.ic_phone, name, sub) { confirmForget(name, { pairedMenu() }) { Paired.forget(this, d.id) } })
        }
        if (list.isEmpty()) items.add(TvUi.Item(R.drawable.ic_info, T("tv_main.no_device_yet")) { })
        if (list.size > 1) items.add(TvUi.Item(R.drawable.ic_unlink, T("tv_main.unpair_all")) { confirmForget(T("tv_main.every_device")) { RemoteService.forgetAll(this) } })
        menu(T("tv_main.paired_devices_2"), items)
    }

    /** Unpair asks first (Cancel has the focus); [again] runs after the unpairing, to show the list without that device. */
    private fun confirmForget(what: String, again: (() -> Unit)? = null, forget: () -> Unit) {
        TvUi.confirm(this, R.style.TvSheet, "", T("tv_main.unpair_what_it_will", "what" to what), T("setup.unpair"), T("common.cancel"), danger = true, accent = ACCENT) {
            forget(); log(T("tv_main.unpaired_what", "what" to what)); showWeb(); again?.invoke()
        }
    }

    private fun qr(link: String): View? {
        // 2 modules of white and no padding: with the standard 4 plus padding the code itself was a third smaller and hard to scan from the sofa
        val bmp = dev.tvremote.core.web.Qr.bitmap(link, 1, quiet = 2) ?: return null
        val img = ImageView(this)
        img.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp).apply { isFilterBitmap = false })   // sharp module edges when scaled up
        img.scaleType = ImageView.ScaleType.FIT_XY
        img.background = box(Color.WHITE, 8, 0)
        return img
    }

    @Volatile private var connecting = false
    private var watching = false

    /** The TV's "Allow USB debugging?" screens in its history, by their record ids. */
    private fun questions(a: dev.tvremote.core.adb.Adb): Set<String> =
        QUESTION.findAll(a.shell("dumpsys activity activities | grep 'Hist.*UsbDebuggingActivity'", 10000)).map { it.groupValues[1] }.toSet()
    private fun questionUp(a: dev.tvremote.core.adb.Adb) = questions(a).isNotEmpty()
    private fun questionShown(a: dev.tvremote.core.adb.Adb) = (a.shell("dumpsys activity activities | grep -c 'ResumedActivity.*UsbDebuggingActivity'", 10000).trim().toIntOrNull() ?: 0) > 0

    /** While this screen is in front, never hide the TV's question: one left behind it (it opened over one, or one queued
     *  came up just as it returned) keeps the TV from showing any other, a phone's included, and nobody sees why. With ADB
     *  at hand this screen looks every 3 s: it steps aside for such a question, and answers a leftover copy of its own.
     *  Once per question: a TCL (Android 12) keeps one in its history that never comes up, and stepping aside for it every
     *  3 s sent this screen to TV Home each time it opened; one still not on screen after the step aside brings it back. */
    private fun unblock(a: dev.tvremote.core.adb.Adb) = Thread {
        val mine = a.fingerprint()
        while (adb === a) {
            try {
                Thread.sleep(3000)
                if (!shown) continue
                val q = questions(a) - steppedFor
                if (q.isEmpty()) continue
                steppedFor += q
                ui { if (shown) moveTaskToBack(true) }
                val r = dev.tvremote.core.adb.AdbPrompt.allow(a, mine, 6000, false)   // a copy of its own: already trusted
                val hidden = r != "allowed" && !questionShown(a)                   // not on screen behind us either: a dead one
                dev.tvremote.core.WireLog.all.add("·", "TV Setup stepped aside for a question: $r" + if (hidden) ", none on screen: back" else "")
                if (r == "allowed" && !questionUp(a) || hidden) a.shell("am start -n $packageName/.MainActivity", 10000)
            } catch (e: InterruptedException) { return@Thread } catch (ignored: Exception) { }
        }
    }.apply { isDaemon = true }.start()
    @Volatile private var backed = false                              // stepped aside for the TV's question (uncover)
    @Volatile private var steppedAside = false                                 // once until ADB answers: see uncover

    /** The TV's question comes up over this screen. Still in front 4 s into asking: the TV dropped it, as another question
     *  (a dead connection's, from before this screen opened) is up behind this one. Stepping aside shows it; once it is
     *  answered, Adb asks again and ours comes up. Then this screen comes back. */
    private fun uncover() {
        if (watching) return
        watching = true
        var askingFor = 0; var hinted = false
        val v = window.decorView
        val tick = object : Runnable {
            override fun run() {
                if (!connecting) { watching = false; return }
                askingFor = if (dev.tvremote.core.adb.Adb.askState() != "ask") 0 else if (shown) askingFor + 1 else askingFor   // in front, no question over it
                // once: when nothing was behind (Android keeps a question it never shows, measured on the API 34 emulator), each
                // ADB retry sent this screen back to Home again and TV Setup could not be used; the hint below says what to do
                if (askingFor == 2 && !steppedAside) { steppedAside = true; backed = true; moveTaskToBack(true) }
                // still no answer: the TV's ADB can stay sure a question is up when none is on screen; off and on clears that
                if (askingFor >= 20 && !hinted) {
                    hinted = true
                    setStatus(WARN, T("tv_main.no_question_on_the"),
                        T("tv_main.turn_usb_debugging_off"))
                    tiles[1].view.requestFocus()
                }
                v.postDelayed(this, 2000)
            }
        }
        v.postDelayed(tick, 2000)
    }

    private fun connect() {
        setEnabled(false)
        connecting = true
        setStatus(WARN, T("tv_main.connecting_adb"), "")
        Thread {
            try {
                adb?.close()
                val a = Adb(filesDir)
                a.connectTo("127.0.0.1", Adb.Prompt {                      // 5555, or the TLS port once paired through Wireless debugging
                    ui { setStatus(WARN, T("allow.the_tv_is_asking"),
                        T("tv_main.tick_always_allow_then"))
                        uncover() }
                })
                adb = a
                steppedAside = false                                      // answered: a later question may be stepped aside for again
                // back in front: Android lets an app in the background start no screen, the shell it just got may; but
                // never over a question still up (another key's, queued): that one would then block every later one
                if (backed) { backed = false; try { if (!questionUp(a)) a.shell("am start -n $packageName/.MainActivity", 10000) } catch (ignored: Exception) { } }
                unblock(a)
                val s = Setup(a, getExternalFilesDir(null), Words.lang, Setup.Log { log(it) })
                setup = s
                try { lastUpdate(a) } catch (ignored: Exception) { }
                bundle(s)
                showStatus(s)
                connecting = false
                ui { setEnabled(true); ecoPaint(); if (openLaunchers) { openLaunchers = false; chooseLauncher() } }
            } catch (e: Exception) {
                adb = null; setup = null
                connecting = false
                ui {
                    ecoPaint()
                    if ((e.message ?: "").contains(Adb.UNANSWERED)) {   // ADB is on; the question ran out of time: "turn on ADB" would send the person in circles
                        setStatus(WARN, T("allow.the_tv_is_asking"), T("tv_main.tick_always_allow_then"))
                        log("✗ " + reason(e))
                        val wifiOn = try { android.provider.Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1 } catch (x: Exception) { false }
                        LinkFailure.unansweredHowto(android.os.Build.VERSION.SDK_INT, wifiOn).forEach { howto(T(it)) }
                        tiles[1].enable(true)
                        tiles[1].view.requestFocus()
                        return@ui
                    }
                    if (LinkFailure.fireBlocked(Build.MANUFACTURER, e.toString() + " " + e.cause)) {   // Amazon's block: no switch on the TV turns it back on
                        setStatus(BAD, T("tv_main.no_adb_connection"), "")
                        log("✗ " + T("tv_main.fire_adb_blocked"))
                        howto(T("tv_main.fire_adb_use_phone"))
                        tiles[1].enable(true)
                        return@ui
                    }
                    setStatus(BAD, T("tv_main.no_adb_connection"), T("tv_main.turn_on_adb_debugging"))
                    val notPaired = (e.message ?: "").contains("not paired")   // no 5555, Wireless debugging on: the code is what is missing
                    val refused = notPaired || (e.message ?: "").contains("refused", ignoreCase = true)   // on this very TV a refused port is ADB off, never a slow network
                    // ...or ADB on over USB only: some boxes (an FPT Play box) open no port 5555, and "turn it on" would send the person in circles
                    val adbOn = try { android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1 } catch (x: Exception) { false }
                    if (refused && adbOn) {
                        setStatus(BAD, T("tv_main.no_adb_connection"), "")
                        log("✗ " + T("tv_main.adb_no_port"))
                        // Android 11+ with no 5555 (a Chromecast with Google TV): only Wireless debugging; a paired phone or computer opens 5555 for TV Setup
                        val wifiOn = try { android.provider.Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1 } catch (x: Exception) { false }
                        val shield = LinkFailure.shield(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
                        val opens = !openedWireless && LinkFailure.opensWireless(wifiOn, android.os.Build.VERSION.SDK_INT, shield)   // once: straight to the switch
                        LinkFailure.noPortHowto(wifiOn, android.os.Build.VERSION.SDK_INT, opens, shield).forEach { howto(T(it)) }
                        if (opens) { openedWireless = true; wirelessDebugging() }
                        else if (shield && !openedDev) { openedDev = true; devOptions() }   // the switch is in Developer options
                        tiles[1].enable(true)
                        tiles[1].view.requestFocus()
                        return@ui
                    }
                    log("✗ " + if (refused) T("tv_main.adb_refused") else reason(e))
                    if (android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) howto(T("tv_main.xiaomi_adb"))
                    else if (android.os.Build.MANUFACTURER.equals("TCL", ignoreCase = true)) howto(T("setup.tcl_hidden_adb"))
                    else if (LinkFailure.shield(android.os.Build.MANUFACTURER, android.os.Build.MODEL)) howto(T("tv_main.shield_adb"))   // TCL China: a key sequence, no Developer options   // China's PatchWall has no Developer options: ADB is under Account & Security
                    howto(T(if (ispBox()) "tv_main.isp_no_taps" else "tv_main.enable_adb_howto"))
                    howto(T("tv_main.then_come_back_here"))
                    tiles[1].enable(true)
                    tiles[1].view.requestFocus()
                    val off = (e.message ?: "").lowercase().let { it.contains("refused") }   // ADB is off; not: the person said no to the prompt
                    if (!openedDev && off) {                          // once: straight to where ADB is turned on, no hunting through menus
                        openedDev = true
                        log(T("tv_main.opening_developer_options_to"))
                        devOptions()
                    }
                }
            }
        }.start()
    }

    @Throws(Exception::class)
    private fun showStatus(setup: Setup) {
        val name = setup.describe()
        val ts = setup.tailscaleIp()
        val lan = setup.lanIp()
        val tsInstalled = setup.has(Setup.TS_PKG)
        lanIp = lan; tsIp = ts
        ui {
            showWeb()
            setStatus(OK, T("tv_main.adb_connected"), name + "\n" + T("tv_main.adb_networks", "lan" to lan.ifEmpty { "?" }, "ts" to (if (ts.isNotEmpty()) ts else if (tsInstalled) T("tv_main.signed_out") else T("tv_main.not_installed"))))
            tsStatus = if (ts.isNotEmpty()) T("tv_main.connected_ip", "ip" to ts) else if (tsInstalled) T("tv_main.signed_out_select_to")
                else T("tv_main.not_installed_select_to")
        }
    }

    private fun setStatus(color: Int, title: String, detail: String) {
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        statusTitle.text = title
        statusTitle.setTextColor(if (color == OK) TvUi.TEXT else color)
        statusDetail.text = detail
        statusDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Opens the TV's Developer options straight away. Some operator boxes hide Settings on their home screen, or ask for a
     * password of their own when "Build" is pressed seven times; the screen itself often opens when asked for by name.
     */
    private var openedDev = false
    private var openedWireless = false
    private var retryOnResume = false                                // one more try when the person comes back from the screen we sent them to

    private fun keepText() = if (!KeepAdb.on(this)) T("tv_main.turn_back_on_when_2")
        else if (!KeepAdb.can(this)) T("tv_main.turn_back_on_cannot")      // wanted, but the permission is not there: not shown as working
        else T("tv_main.turn_back_on_when")

    /** The tiles' menus: icons and a second line, a switch's state at the end (TvUi.menu, the TV apps' one list dialog). */
    private fun menu(title: String, items: List<TvUi.Item>) { TvUi.menu(this, R.style.TvSheet, title, items, R.drawable.ic_check, ACCENT) }

    /** Connection: the TV's ADB again, Developer options and whether TV Setup turns ADB debugging back on (KeepAdb), Tailscale. */
    private fun connectionMenu() {
        if (busy) return
        menu(T("tv_main.connection"), listOf(
            TvUi.Item(R.drawable.ic_refresh, T("tv_main.reconnect"), T("tv_main.the_tv_s_adb")) { connect() },
            TvUi.Item(R.drawable.ic_code, T("tv_main.open_developer_options")) { devOptions() },
            TvUi.Item(R.drawable.ic_power, T("tv_main.turn_back_on"), on = { KeepAdb.on(this) && KeepAdb.can(this) }) { KeepAdb.set(this, !KeepAdb.on(this)); log(keepText()) },
            TvUi.Item(R.drawable.ic_globe, "Tailscale", tsStatus) { if (adb == null) log(T("tv_main.needs_adb_turn_on")) else tailscale() },
            TvUi.Item(R.drawable.ic_unlink, T("tv_main.forget_adb_keys")) { forgetAdbKeys() },
            TvUi.Item(R.drawable.ic_send, T("support.send"), T("support.send_sub")) { askSaid { supportSend(it) } },
            if (Support.open()) TvUi.Item(R.drawable.ic_stop, T("support.stop"), T("support.stop_sub", "code" to spaced(Support.code), "min" to supportLeft())) {
                Support.stop(this); log(T("support.stopped")) }
            else TvUi.Item(R.drawable.ic_eye, T("support.open", "min" to Support.MINUTES), T("support.open_sub")) { askSaid { supportOpen(it) } },
            *(if (SupportThread.on(this)) arrayOf(TvUi.Item(R.drawable.ic_info, T("support.replies"), if (SupportThread.unread(this)) T("support.replies_new") else T("support.replies_sub")) { supportReplies() }) else emptyArray())))
    }

    // Support (Support.kt): a code to read out to whoever helps, and for a while their reading of this app's own state
    private fun ecoPaint() { eco.forEach { it.paint() }; if (eco.none { it.hasFocus() }) ecoNote?.text = ecoText(); ecoCheck() }

    // The release's versions.json ({"home": "0.13.0", ...}), asked at most every 6 h: only for the pills, the install itself
    // checks the signed SHA256SUMS. A plain download link, not GitHub's API, whose 60 calls an hour TV Setup's own check shares.
    @Volatile private var ecoLatest: org.json.JSONObject? = null
    @Volatile private var ecoAsked = 0L
    private fun ecoCheck() {
        val now = System.currentTimeMillis()
        if (eco.isEmpty() || now - ecoAsked in 0 until (if (ecoLatest != null) 6 * 3600_000L else 60_000L)) return
        ecoAsked = now
        Thread {
            try {
                val c = java.net.URL("https://github.com/HoangTran0410/tv-remote/releases/latest/download/versions.json").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
                val o = try { if (c.responseCode == 200) org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText().take(4096) }) else null } finally { c.disconnect() }
                if (o != null) { ecoLatest = o; ui { if (!isFinishing) { eco.forEach { it.paint() } } } }
            } catch (e: Exception) { }                                // offline: the pills say installed / not, asked again in a minute
        }.start()
    }

    /** Beside the banners: TV Home first, and that it needs no ADB, on the TVs that have none (the user, 2026-10-25). */
    private fun ecoText(): String = when {
        eco.firstOrNull()?.on == true -> ""                       // all set: no line to read, the tiles say it
        adb == null && !connecting -> T("eco.note_noadb")
        else -> T("eco.note_adb")
    }

    /** On this TV: opened. Not yet: install it (and for TV Home with ADB, make it the home screen too). */
    /** OK on a banner: its menu, as TV Setup's own (the user, 2026-11-03: not straight into the app). Open, update, its own automatic
     *  updates and remove; TV Home adds "make it the home screen". TV Setup Voice has its own screen ([VoiceActivity]). */
    private fun ecoPress(e: Eco) {
        if (e.app === SELF) return updateMenu()                    // this very app: its own update path (ADB script, or Android's installer)
        if (e.app.pkg == VOICE.pkg) { ecoOpen(e); return }            // TV Setup Voice: installed from its screen, which also makes it the mic's
        if (busy) return
        val items = mutableListOf<TvUi.Item>()
        if (!e.on) items += TvUi.Item(R.drawable.ic_download, T("eco.get", "name" to e.app.name), { e.sub }) { ecoInstall(e) }
        else {
            items += TvUi.Item(R.drawable.ic_play, T("eco.open_only", "name" to e.app.name)) { if (!ecoOpen(e)) log("✗ " + e.app.name) }
            val v = e.newer
            items += if (v != null) TvUi.Item(R.drawable.ic_download, T("tv_main.update_to", "v" to v), { T("tv_main.update_from", "v" to e.version) }) { ecoInstall(e) }
                     else TvUi.Item(R.drawable.ic_download, T("tv_main.update_now"), { T("eco.on", "v" to e.version) }) { ecoInstall(e) }
            items += TvUi.Item(R.drawable.ic_refresh, T("tv_main.automatic_updates"), on = { AutoUpdate.on(this, e.app.pkg) }) {
                AutoUpdate.set(this, e.app.pkg, !AutoUpdate.on(this, e.app.pkg))
                log(e.app.name + ": " + (if (AutoUpdate.on(this, e.app.pkg)) T("tv_main.automatic_updates_on") else T("tv_main.automatic_updates_off")))
            }
        }
        if (e.app.pkg == dev.tvremote.home.HomeConfig.PKG && !e.on && adb != null)
            items += TvUi.Item(R.drawable.ic_home, T("eco.home_set"), { T("eco.home_set_sub") }) { chooseLauncher(dev.tvremote.home.HomeConfig.PKG) }
        if (e.app.pkg == dev.tvremote.home.HomeConfig.PKG && e.on && adb != null && !isHome(e.app.pkg))
            items += TvUi.Item(R.drawable.ic_home, T("eco.home_make"), { T("eco.home_set_sub") }) { chooseLauncher(dev.tvremote.home.HomeConfig.PKG) }
        if (e.on) items += TvUi.Item(R.drawable.ic_trash, T("eco.remove", "name" to e.app.name)) { ecoRemove(e) }
        menu(e.app.name + if (e.on) " " + e.version else "", items)
    }

    /** The home screen now is [pkg]: Android's answer for HOME. */
    private fun isHome(pkg: String): Boolean = try {
        packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == pkg
    } catch (x: Exception) { false }

    /** Removed over ADB (no question), else Android's own uninstall screen. TV Home while it is the home screen: another one first,
     *  or the TV would be left with whatever Android picks. */
    private fun ecoRemove(e: Eco) {
        if (e.app.pkg == dev.tvremote.home.HomeConfig.PKG && isHome(e.app.pkg)) { log(T("eco.remove_home_first")); if (adb != null) chooseLauncher(); return }
        TvUi.confirm(this, R.style.TvSheet, T("eco.remove_ask", "name" to e.app.name), T("eco.remove_note"), T("fleet.remove"), T("common.cancel"), danger = true, accent = ACCENT) {
            val a = adb
            if (a == null || setup == null) {
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:${e.app.pkg}"))) } catch (x: Exception) { log("✗ " + reason(x)) }
                return@confirm
            }
            job { _ ->
                val out = a.shell("pm uninstall ${e.app.pkg} 2>&1").trim()
                if (out.contains("Success")) log("✓ " + T("eco.removed", "name" to e.app.name)) else log("✗ " + e.app.name + ": " + out.lines().lastOrNull().orEmpty().take(120))
                ui { ecoPaint() }
            }
        }
    }

    private fun ecoOpen(e: Eco): Boolean {
        if (e.app.pkg == VOICE.pkg) return try { voiceBefore = MicVoice.offlineState(this); startActivity(android.content.Intent(this, VoiceActivity::class.java)); true } catch (x: Exception) { false }   // no screen of its own: ours offers it, switches it, removes it
        val i = packageManager.getLeanbackLaunchIntentForPackage(e.app.pkg) ?: packageManager.getLaunchIntentForPackage(e.app.pkg) ?: return false
        return try { startActivity(i); true } catch (x: Exception) { false }
    }

    /** Over ADB as Bundle does, with no question; without it, Android's installer asks (once allowed to install apps, as [selfUpdate]). */
    private fun ecoInstall(e: Eco) {
        val a = adb
        if (a != null && setup != null) {
            job { _ ->
                log(T("tv_main.bundle_installing", "name" to e.app.name))
                val was = if (e.on) e.version else null
                val out = try { e.app.install(a, getExternalFilesDir("update"), Words.lang) { barAt(it) } } finally { barAt(-1) }   // 100% = downloaded: the install's own lines follow
                if (was != null && out.optString("version") == was) log(e.app.name + ": " + T("tv_main.newest_version", "v" to was))   // "Update now" with nothing newer
                else log("✓ " + T("tv_main.bundle_installed", "name" to e.app.name))
                ui { ecoPaint() }
            }
            return
        }
        if (busy) return
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) try {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
            ecoWaiting = e
            log(T("tv_main.allow_installs"))
            return
        } catch (x: Exception) { }                                    // no such screen on this TV: Android's installer asks its own way
        busy = true
        spinner.visibility = View.VISIBLE
        log(T("tv_main.bundle_installing", "name" to e.app.name))
        val before = dev.tvremote.update.SelfUpdate.Installed.failed   // one slot, TV Setup's own update's too: put back after this install
        dev.tvremote.update.SelfUpdate.Installed.failed = { m -> ui { log("✗ " + e.app.name + ": " + m.ifEmpty { "Android" }) }; dev.tvremote.update.SelfUpdate.Installed.failed = before }
        Thread {
            try {
                val r = Release.newer("0", e.app.key, e.app.apk) ?: throw java.io.IOException(T("eco.none", "name" to e.app.name))
                var last = -25
                dev.tvremote.update.SelfUpdate.stage(this, r, android.content.Intent(this, dev.tvremote.update.SelfUpdate.Installed::class.java), false) { pct ->
                    barAt(pct); if (pct >= last + 25) { last = pct; log("  $pct%") }
                }
                log(T("eco.installer_asks"))
            } catch (x: Exception) { log("✗ " + reason(x)) }
            busy = false
            barAt(-1)
            ui { spinner.visibility = View.INVISIBLE }
        }.start()
    }

    private fun spaced(c: String) = if (c.length == 8) c.substring(0, 4) + " " + c.substring(4) else c
    private fun supportLeft() = ((Support.until - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(0)
    private fun showCode(code: String, sub: String) = menu(T("support.code_title"), listOf(TvUi.Item(R.drawable.ic_check, spaced(code),
        if (Support.left >= 0) sub + " " + T("support.left", "n" to Support.left) else sub) { }))
    /** Nothing sent: no sends left today (the worker said so), or no Internet. */
    private fun notSent() = log(T(if (Support.left == 0) "support.none_left" else "support.no_net"))

    /**
     * "What went wrong?" before a send (the page's askSaid): pills the D-pad walks, a field for a few words, Send. Both optional;
     * Back sends nothing. The Send button has the focus, so one OK is a send with nothing said.
     */
    private fun askSaid(go: (dev.tvremote.core.SupportRelay.Said) -> Unit) {
        val (dlg, box) = TvUi.sheet(this, R.style.TvSheet)
        TvUi.sheetTitle(box, T("said.title"))
        var picked = ""
        val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        fun fill(focus: String) {                                    // a pill is drawn once: the chosen one is made again
            list.removeAllViews()
            dev.tvremote.core.SupportRelay.PROBLEMS.forEach { id ->
                val pill = TvUi.pill(this, T("said.$id"), id == picked) { picked = if (picked == id) "" else id; fill(id) }
                list.addView(pill, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = TvUi.dp(this@MainActivity, 6).toInt() })
                if (id == focus) pill.requestFocus()
            }
        }
        fill("")
        box.addView(list)
        val field = TvUi.field(this, T("said.text"), 16f, android.view.inputmethod.EditorInfo.IME_ACTION_DONE, TvUi.FIELD_REST, TvUi.R_FIELD, 14, 8)
        box.addView(field, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = TvUi.dp(this@MainActivity, 6).toInt() })
        val send = TvUi.button(this, T("said.send"), 0, ACCENT) { dlg.dismiss(); go(dev.tvremote.core.SupportRelay.Said(picked, field.text.toString())) }
        box.addView(send, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = TvUi.dp(this@MainActivity, 10).toInt() })
        dlg.setOnShowListener { send.requestFocus() }
        dlg.show()
    }

    /**
     * Support's replies: the thread (both sides, newest last, plain text) and the QR of the page where a phone answers. The code
     * and the QR sit first so a long reply never pushes them off; each message takes the focus so the D-pad scrolls a long one.
     */
    private fun supportReplies() {
        // Laid out like the "Control from a phone" panel: the talk on the left as bubbles (support on the left, the person on the
        // right in the accent, as on the phone page), the QR, its words and Close in a column on the right.
        val (dlg, box) = TvUi.sheet(this, R.style.TvSheet, 780)
        TvUi.sheetTitle(box, T("support.replies"))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(4), dp(6)) }
        val talk = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val m = SupportThread.msgs(this)
        if (m.length() == 0) talk.addView(TvUi.text(this, T("support.replies_none"), TvUi.SP_ROW, TvUi.DIM))
        val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        for (i in 0 until m.length()) {
            val o = m.getJSONObject(i); val us = o.optString("who") == "us"
            val fill = if (us) TvUi.PILL_REST else ACCENT_SOFT
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true      // focusable: the D-pad walks (and scrolls) a long talk
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = TvUi.round(fill, dp(14).toFloat())
                setOnFocusChangeListener { v, f -> v.background = TvUi.round(fill, dp(14).toFloat(), if (f) dp(2) else 0, Color.WHITE) }
                addView(TvUi.text(context, o.optString("text"), TvUi.SP_ROW), LinearLayout.LayoutParams(-2, -2))   // setText: plain text, never HTML;
                // -2: a vertical row's default is match_parent, which took the stamp's width and cut the words
                val at = o.optLong("at")
                addView(TvUi.text(context, (if (us) T("support.who_us") else T("support.who_you")) + (if (at > 0) " · " + stamp.format(java.util.Date(at)) else ""),
                    TvUi.SP_COUNT, TvUi.DIM), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }
            val row = android.widget.FrameLayout(this)
            row.addView(bubble, android.widget.FrameLayout.LayoutParams(-2, -2, if (us) Gravity.START else Gravity.END).apply { if (us) rightMargin = dp(56) else leftMargin = dp(56) })
            talk.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(10) })
        }
        body.addView(talk, LinearLayout.LayoutParams(0, -2, 1f))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        SupportThread.pageUrl(this)?.let { url -> qr(url)?.let { side.addView(it, LinearLayout.LayoutParams(dp(176), dp(176))) } }
        side.addView(TvUi.text(this, T("support.scan_to_answer"), TvUi.SP_ROW, TvUi.DIM).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(16) })
        val close = TvUi.button(this, T("common.close"), 0, TvUi.PILL_REST) { dlg.dismiss() }
        side.addView(close, LinearLayout.LayoutParams(-1, -2))
        body.addView(side, LinearLayout.LayoutParams(dp(200), -2).apply { leftMargin = dp(24) })
        box.addView(body, LinearLayout.LayoutParams(-1, -2))
        SupportThread.markRead(this)
        dlg.setOnShowListener { close.requestFocus() }
        dlg.show()
    }

    private fun supportSend(said: dev.tvremote.core.SupportRelay.Said) {
        log(T("support.sending"))
        Thread({
            val code = Support.send(this, said)
            ui { if (code == null) notSent() else { log(T("support.sent", "code" to spaced(code))); showCode(code, T("support.read_out")) } }
        }, "support-send").apply { isDaemon = true }.start()
    }

    private fun supportOpen(said: dev.tvremote.core.SupportRelay.Said) {
        log(T("support.sending"))
        Support.start(this, said) { code -> ui {
            if (code == null) notSent()
            else { log(T("support.opened", "code" to spaced(code), "min" to Support.MINUTES)); showCode(code, T("support.opened_sub", "min" to Support.MINUTES)) }
        } }
    }

    /**
     * Revoking every ADB key is not something an app can do: /data/misc/adb/adb_keys is system-only and the shell user is denied
     * too (measured on an Android 14 emulator: "Permission denied"). Developer options has the row that does it, so open that.
     * TV Setup's own link is revoked as well and asks again on the next connect.
     */
    private fun forgetAdbKeys() {
        TvUi.confirm(this, R.style.TvSheet, T("tv_main.forget_adb_keys"), T("tv_main.forget_adb_keys_ask"), T("tv_main.open_developer_options"), T("common.cancel"), accent = ACCENT) {
            howto(T("tv_main.forget_adb_keys_howto")); devOptions()
        }
    }

    /** Optimize TV: the clean-up and its undo, the home screen to use, the remote buttons the stock launcher answered (KeyFix). */
    private fun optimizeMenu() {
        if (busy) return
        fun needsAdb(go: () -> Unit): () -> Unit = { if (adb == null) log(T("tv_main.needs_adb_turn_on")) else go() }
        menu(T("tv_main.optimize_tv"), listOf(
            TvUi.Item(R.drawable.ic_sparkle, T("tv_main.optimize_tv"), T("tv_main.fewer_extra_apps"), go = needsAdb { chooseOptimize() }),
            TvUi.Item(R.drawable.ic_undo, T("setup.undo_ok"), T("tv_main.as_before_optimize"), go = needsAdb { job { it.undo() } }),
            TvUi.Item(R.drawable.ic_home, T("launcher.tile"), T("launcher.tile_sub"), go = needsAdb { chooseLauncher() }),
            TvUi.Item(R.drawable.ic_wrench, T("keyfix.tile"), { keysSub() }, on = { KeyFix.wanted(this) }) { KeyFix.want(this, !KeyFix.wanted(this)); log(T("keyfix.tile") + " · " + keysSub()) },
            TvUi.Item(R.drawable.ic_input, T("hold.tile"), { T("hold.sub_short") }, on = { HoldKeys.on(this) }) {   // HoldKeys: stored as a rule, so the phone and web switch it too
                val on = !HoldKeys.on(this)
                Rules.change(this, org.json.JSONObject().put("do", "holdkeys").put("on", on))
                log(T("hold.tile") + " · " + T(if (on) "hold.on" else "hold.off") + (if (on && !KeyFix.can(this)) " · " + T("keyfix.need_adb") else ""))
            },
            TvUi.Item(R.drawable.ic_mic, T("mic.tile"), { T("mic.sub_short") }, on = { MicVoice.on(this) }) {   // MicVoice: a rule too, so the phone and web switch it as well
                val on = !MicVoice.on(this)
                Rules.change(this, org.json.JSONObject().put("do", "mic").put("on", on))
                log(T("mic.tile") + " · " + T(if (on) "mic.on" else "mic.off") + (if (on && !KeyFix.can(this)) " · " + T("keyfix.need_adb") else ""))
                if (on) micBlockLog()
            },
            TvUi.Item(R.drawable.ic_mic, T("mic.offline"), { voiceSub() }, on = { MicVoice.offlineOn(this) }) {   // TV Setup Voice: installed while on (MicVoice.syncOffline)
                val on = !MicVoice.offlineOn(this)
                Rules.change(this, org.json.JSONObject().put("do", "mic_offline").put("on", on))
                log(T("mic.offline") + " · " + T(if (on) "mic.offline_installing" else "mic.offline_removed"))
            },
            TvUi.Item(R.drawable.ic_keyboard, T("keytest.tile"), T("keytest.sub")) { startActivity(android.content.Intent(this, KeyTestActivity::class.java)) }) + listOfNotNull(micFixItem()))   // the key tester, and the way out when the mic key cannot reach us
    }

    /** Why the remote's mic key cannot reach TV Setup ([MicVoice.block]; nothing is read while the rule is off), in words. */
    private fun micBlockText(b: MicVoice.Block) = if (b.reason == MicVoice.GOOGLE) T("mic.block_google") else T("mic.block_other", "app" to b.label)

    /** A row that fixes it: the Google app off over ADB (Optimize TV's Undo turns it on), else that app's settings page. Absent when nothing blocks. */
    private fun micFixItem(): TvUi.Item? {
        val b = MicVoice.block(this).takeIf { it.reason.isNotEmpty() } ?: return null
        val off = b.reason == MicVoice.GOOGLE && adb != null
        return TvUi.Item(R.drawable.ic_mic, if (off) T("mic.block_off_google") else T("mic.block_open", "app" to (b.label.ifEmpty { "Google" })), micBlockText(b)) {
            Thread({ val done = MicVoice.free(this, adb); ui { log(if (done == "off") T("mic.block_done") else T("mic.block_opened")) } }, "mic-free").apply { isDaemon = true }.start()
        }
    }

    /** The same reason in the log, once per screen visit, so a person who switched the mic on sees why it does not answer. */
    private fun micBlockLog() {
        val b = MicVoice.block(this).takeIf { it.reason.isNotEmpty() } ?: return
        howto(micBlockText(b) + if (b.reason == MicVoice.GOOGLE) " " + T("mic.block_note") else "")
    }

    private fun voiceSub(): String = MicVoice.offlineState(this).let { v -> when {
        v == "busy" -> T("mic.offline_busy")
        v == "ready" -> T("mic.offline_ready")
        v.startsWith("failed:") && MicVoice.offlineOn(this) -> T("mic.offline_failed", "why" to MicVoice.why(this, v))
        else -> T("mic.offline_sub")
    } }

    /** A box from an Internet provider (FPT Play, Viettel; names as the census sees them): Build's 7 presses ask the ISP's password. */
    private fun ispBox() = listOf(android.os.Build.BRAND, android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        .joinToString(" ").lowercase().let { "fpt" in it || "viettel" in it }

    /** The Wireless debugging page itself ([LinkFailure.wirelessScreens]); a screen that does not open or closes at once = the next, then Developer options. */
    private fun wirelessDebugging() {
        retryOnResume = true
        val tries = LinkFailure.wirelessScreens(Build.VERSION.SDK_INT)
        fun attempt(i: Int) {
            if (i >= tries.size) { devOptions(); return }
            val t = tries[i]
            val intent = if (t.cls.isNotEmpty()) android.content.Intent().setClassName(t.pkg, t.cls)
                else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:show_fragment", t.fragment)
            try { startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { attempt(i + 1); return }
            window.decorView.postDelayed({ if (hasWindowFocus()) attempt(i + 1) }, 1200)
        }
        attempt(0)
    }

    private fun devOptions() {
        retryOnResume = true
        // Android TV's own Developer options page only says "not available for this user" when they are still off, on a second
        // profile, or locked (a box seen from FPT): say which, and when they are only off open About for the 7 presses first.
        val um = getSystemService(USER_SERVICE) as? android.os.UserManager
        if (um != null && (um.hasUserRestriction(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES) || (Build.VERSION.SDK_INT >= 23 && !um.isSystemUser))) {
            log(T("tv_main.dev_locked")); return
        }
        val isp = ispBox()
        val off = android.os.Build.TYPE == "user" &&
            android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 1
        val dev = T("tv_main.developer_options")
        // the page itself first, always: it opens on boxes whose 7 presses are guarded (FPT); still off elsewhere, it only says
        // "not available", and the words then point at About. An ISP box never gets About: its 7 presses ask the ISP's password.
        val tries = if (isp) listOf(
            dev to android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            dev to android.content.Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.system.development.DevelopmentActivity"),
            dev to android.content.Intent().setClassName("com.android.settings", "com.android.settings.DevelopmentSettings")) else listOf(
            dev to android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            dev to android.content.Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.system.development.DevelopmentActivity"),
            dev to android.content.Intent().setClassName("com.android.settings", "com.android.settings.DevelopmentSettings"),
            T("tv_main.about_press_ok_7") to android.content.Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS),
            T("page.settings") to android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
        fun attempt(i: Int) {
            if (i >= tries.size) { log(T(if (isp) "tv_main.isp_locked" else "tv_main.this_tv_does_not")); return }
            val (name, intent) = tries[i]
            try {
                startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {                                  // not on this TV, or not allowed: the next one
                attempt(i + 1)
                return
            }
            // a screen that is not allowed to open closes itself at once and leaves us in front: then try the next one
            window.decorView.postDelayed({
                if (hasWindowFocus()) attempt(i + 1)
                else {
                    howto(if (off && name != dev) T("tv_main.dev_off_opened", "name" to name) else T("tv_main.opened_turn_on", "name" to name))
                    if (isp) howto(T("tv_main.isp_locked"))                    // the page only says "not available": the ISP locked it
                    else if (off && name == dev) howto(T("tv_main.dev_off_try"))
                }
            }, 1200)
        }
        attempt(0)
    }

    /**
     * TV Home, Channels and Games onto the TV once ADB works ([Bundle]): the site shows them, the install guide only installs TV
     * Setup. Twice at most per TV, as a box may drop an app it does not allow the moment it lands; never on a phone.
     */
    private fun bundle(s: Setup) {
        if (!packageManager.hasSystemFeature("android.software.leanback")) return
        val p = getSharedPreferences("remote", MODE_PRIVATE)
        val tries = p.getInt("bundle_tries", 0)
        if (tries >= 2) return
        val a = adb ?: return
        val todo = try { dev.tvremote.core.ontv.Bundle.missing { a.shell(it, 10000) } } catch (e: Exception) { return }
        if (todo.isEmpty()) { p.edit().putInt("bundle_tries", 2).apply(); homeOnce(a); return }
        p.edit().putInt("bundle_tries", tries + 1).apply()
        ui {
            job { _ ->
                var all = true
                for (app in todo) {
                    log(T("tv_main.bundle_installing", "name" to app.name))
                    try {
                        try { app.install(a, getExternalFilesDir("update"), Words.lang) { barAt(it) } } finally { barAt(-1) }
                        log("✓ " + T("tv_main.bundle_installed", "name" to app.name))
                    } catch (e: Exception) { all = false; log("✗ " + app.name + ": " + reason(e)) }
                }
                if (all) p.edit().putInt("bundle_tries", 2).apply()
                homeOnce(a)
            }
        }
    }

    /**
     * TV Home as the home screen, offered once per TV: the launcher list opens and swaps to it at once, and the TV's own
     * Keep / Go back question (with its countdown, back by itself when nobody answers) is the confirmation.
     */
    private fun homeOnce(a: Adb) {
        val p = getSharedPreferences("remote", MODE_PRIVATE)
        if (p.getBoolean("home_offered", false)) return
        if (busy) { Thread { while (busy) Thread.sleep(300); homeOnce(a) }.start(); return }   // the installs' job: the list waits for it
        val (now, has) = try { dev.tvremote.core.ontv.LauncherSwap({ a.shell(it) }).homeNow() to a.shell("pm path ${dev.tvremote.home.HomeConfig.PKG}").contains("package:") }
            catch (e: Exception) { return }                           // the link dropped (census 82bbd434): asked again on the next one
        if (now.startsWith(dev.tvremote.home.HomeConfig.PKG + "/") || !has) return
        p.edit().putBoolean("home_offered", true).apply()
        ui { chooseLauncher(dev.tvremote.home.HomeConfig.PKG) }
    }

    private fun interface Job { @Throws(Exception::class) fun run(setup: Setup) }

    /** Runs one piece of work on the ADB connection in the background, tiles greyed out and a spinner meanwhile. */
    /** "Optimize TV": first what this TV has, then the person ticks what to do (suggested apps ticked, other apps not). */
    private fun chooseOptimize() {
        val s = setup ?: return
        if (busy) return
        log(T("tv_main.looking_at_what_this"))
        Thread {
            val plan = try {
                s.plan { pkg -> try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { null } }
            } catch (e: Exception) { ui { log("✗ " + reason(e)) }; return@Thread }
            ui { showOptimize(plan) }
        }.start()
    }

    /** The choices, in this app's own look: rows to tick with the remote's OK, sections, and the two buttons at the bottom. */
    private fun showOptimize(plan: org.json.JSONObject) {
        val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(SCRIM)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = box(TvUi.CARD, 22, 0)
        card.setPadding(dp(32), dp(26), dp(32), dp(22))

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val badge = ImageView(this)
        badge.setImageResource(R.drawable.ic_zap)
        badge.setColorFilter(Color.WHITE)
        badge.background = box(ACCENT, 12, 0)
        badge.setPadding(dp(9), dp(9), dp(9), dp(9))
        head.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        val words = LinearLayout(this)
        words.orientation = LinearLayout.VERTICAL
        words.setPadding(dp(14), 0, 0, 0)
        words.addView(text(T("tv_main.optimize_tv"), 22, TvUi.TEXT, true))
        words.addView(text(T("tv_main.turning_an_app_off"), 13, MUTED, false))
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val rows = ArrayList<Pair<String?, BooleanArray>>()             // (package or null, ticked)
        val count = text("", 13, MUTED, false)
        fun recount() {
            val n = rows.count { it.first != null && it.second[0] }
            count.text = if (n == 0) T("tv_main.no_apps_turned_off") else T("tv_main.apps_off_n", "n" to n)
        }
        fun row(title: String, sub: String, on: Boolean, pkg: String?): View {
            val ticked = booleanArrayOf(on)
            rows.add(pkg to ticked)
            val r = LinearLayout(this)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(14), dp(10), dp(14), dp(10))
            r.isFocusable = true
            r.isClickable = true
            val mark = ImageView(this)                                  // the tick is a line icon, drawn only while ticked
            mark.setPadding(dp(4), dp(4), dp(4), dp(4))
            r.addView(mark, LinearLayout.LayoutParams(dp(26), dp(26)))
            val w = LinearLayout(this)
            w.orientation = LinearLayout.VERTICAL
            w.setPadding(dp(14), 0, 0, 0)
            val tt = text(title, 16, TvUi.TEXT, true)
            tt.isSingleLine = true
            tt.ellipsize = TextUtils.TruncateAt.END
            w.addView(tt)
            val st = text(sub, 12, MUTED, false)
            st.isSingleLine = true
            st.ellipsize = TextUtils.TruncateAt.END
            if (sub.isNotEmpty()) w.addView(st)
            r.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
            fun paint() {
                val f = r.isFocused
                r.background = if (f) box(ACCENT, 12, 2) else box(TILE, 12, 0)
                mark.setImageResource(if (ticked[0]) R.drawable.ic_check else 0)
                mark.imageTintList = android.content.res.ColorStateList.valueOf(if (f) ACCENT else Color.WHITE)
                mark.background = if (ticked[0]) box(if (f) Color.WHITE else ACCENT, 7, 0) else GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setStroke(dp(2), if (f) Color.WHITE else MUTED) }
                st.setTextColor(if (f) Color.WHITE else MUTED)
            }
            r.setOnClickListener { ticked[0] = !ticked[0]; paint(); recount() }
            r.setOnFocusChangeListener { _, _ -> paint() }
            paint()
            return r
        }
        fun add(v: View) = list.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        fun header(s: String) = list.addView(section(s), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        val usingHome = plan.optString("home") == Setup.HOME_PKG
        header(T("tv_main.general"))
        add(row(T("setup.anim_off"),
            if (plan.optBoolean("anim", true)) T("tv_main.faster") else T("setup.anim_already"), true, null))
        add(row(if (usingHome) T("setup.tvhome_in_use") else T("setup.tvhome_use"),
            (if (plan.optBoolean("tvhome")) T("tv_main.light_no_ads") else T("tv_main.light_no_ads_download")), true, null))
        val sug = plan.optJSONArray("suggest") ?: org.json.JSONArray()
        if (sug.length() > 0) header(T("tv_main.suggested_apps_to_turn"))
        for (i in 0 until sug.length()) { val a = sug.getJSONObject(i); add(row(a.optString("label"), a.optString("pkg"), true, a.optString("pkg"))) }
        val more = plan.optJSONArray("apps") ?: org.json.JSONArray()
        if (more.length() > 0) header(T("tv_main.turn_off_other_apps"))
        for (i in 0 until more.length()) { val a = more.getJSONObject(i); add(row(a.optString("label"), a.optString("pkg"), false, a.optString("pkg"))) }
        recount()

        val most = (resources.displayMetrics.heightPixels * 0.62).toInt()  // the list grows with its rows, then scrolls
        val scroll = object : TvScroll(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(most, View.MeasureSpec.AT_MOST))
        }
        scroll.addView(list)
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val foot = LinearLayout(this)
        foot.gravity = Gravity.CENTER_VERTICAL
        foot.setPadding(0, dp(18), 0, 0)
        val go = smallButton(T("tv_main.optimize")) {
            val ticks = rows.map { it.second[0] }
            d.dismiss()
            job { it.apply(Setup.Choice(ticks[0], ticks[1], rows.filter { r -> r.first != null && r.second[0] }.map { r -> r.first!! })) }
        }
        go.textSize = 16f
        go.setPadding(dp(22), dp(12), dp(22), dp(12))
        go.id = View.generateViewId()
        go.background = box(Color.rgb(36, 58, 108), 10, 0)                   // dim until focused: only one button ever looks "on"
        go.setOnFocusChangeListener { v, f -> v.background = if (f) box(ACCENT, 10, 2) else box(Color.rgb(36, 58, 108), 10, 0) }
        list.getChildAt(list.childCount - 1)?.nextFocusDownId = go.id
        val cancel = smallButton(T("common.cancel")) { d.dismiss() }
        cancel.textSize = 16f
        cancel.setPadding(dp(22), dp(12), dp(22), dp(12))
        foot.addView(go)
        foot.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        foot.addView(count, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(18) })
        card.addView(foot)

        root.addView(card, android.widget.FrameLayout.LayoutParams(dp(820), -2, Gravity.CENTER))
        d.setContentView(root)
        d.show()
        list.getChildAt(1)?.requestFocus()                                // the first choice, under its heading
    }

    /** The launcher gallery on the TV itself: signed list, each card focusable with the remote, install checked and put back by [LauncherInstall]. */
    private fun chooseLauncher(auto: String? = null) {   // [auto]: that one swapped to at once (the TV still asks Keep / Go back)
        val a = adb ?: return
        if (busy) return
        log(T("launcher.loading"))
        val wait = launcherWait()                                      // the list takes a moment: the popup is there at once
        Thread {
            val g0: dev.tvremote.core.ontv.Launchers.Gallery
            val g: dev.tvremote.core.ontv.Launchers.Gallery
            val facts: dev.tvremote.core.ontv.Launchers.Facts
            val have: Set<String>
            val home: String
            try {
                // the list (network) and the TV's three answers (adb streams run side by side on one connection) all at once
                fun <T> go(f: () -> T) = java.util.concurrent.FutureTask(f).also { Thread(it).start() }
                fun <T> java.util.concurrent.FutureTask<T>.take(): T = try { get() } catch (x: java.util.concurrent.ExecutionException) { throw (x.cause as? Exception) ?: x }
                val list = go { dev.tvremote.core.ontv.Launchers.fetch() }
                val pkgs = go { a.shell("pm list packages").split("\n").map { it.removePrefix("package:").trim() }.toSet() }
                val now = go { dev.tvremote.core.ontv.LauncherSwap({ a.shell(it) }).homeNow().substringBefore('/') }
                facts = dev.tvremote.core.ontv.Launchers.facts { a.shell(it) }
                have = pkgs.take()
                home = now.take()
                g0 = list.take()
            } catch (e: Exception) { ui { log("✗ " + reason(e)); wait.second.text = marked("✗ " + reason(e)); wait.third.visibility = View.GONE }; return@Thread }
            g = dev.tvremote.core.ontv.Launchers.Gallery(g0.updated, g0.entries + installedLaunchers(g0), g0.base)
            ui { if (wait.first.isShowing) { showLaunchers(a, g, facts, have, home, auto); wait.first.dismiss() } }   // closed while loading: nothing opens
        }.start()
    }

    /**
     * A gallery picture into its card once it is here: four at a time (they came one by one, ~4 s for 13, and the list waited for
     * all of them), decoded at the card's size (960x540 each was 2 MB of memory for a 128 dp picture).
     */
    private fun launcherPics(g: dev.tvremote.core.ontv.Launchers.Gallery, into: List<Pair<dev.tvremote.core.ontv.Launchers.Entry, ImageView>>) {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        for ((e, pic) in into) pool.execute {
            val b = try { dev.tvremote.core.ontv.Launchers.thumb(g, e) } catch (x: Exception) { return@execute }
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var n = 1
            while (o.outWidth / (n * 2) >= dp(128)) n *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = n }) ?: return@execute
            ui { pic.setImageBitmap(bmp) }
        }
        pool.shutdown()
    }

    /** The launcher popup's frame while its list loads: the same card, a wheel and Close; the list opens over it, then it goes. */
    private fun launcherWait(): Triple<android.app.Dialog, TextView, ProgressBar> {
        val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(SCRIM)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = box(TvUi.CARD, 22, 0)
        card.setPadding(dp(32), dp(26), dp(32), dp(22))
        card.addView(text(T("launcher.title"), 22, TvUi.TEXT, true))
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(28), 0, dp(28))
        val wheel = ProgressBar(this)
        row.addView(wheel, LinearLayout.LayoutParams(dp(32), dp(32)))
        val say = text(T("launcher.loading"), 15, MUTED, false)
        row.addView(say, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        card.addView(row)
        val close = smallButton(T("common.close")) { d.dismiss() }
        card.addView(close, LinearLayout.LayoutParams(-2, -2))
        root.addView(card, android.widget.FrameLayout.LayoutParams(dp(820), -2, Gravity.CENTER))
        d.setContentView(root)
        d.show()
        close.requestFocus()
        return Triple(d, say, wheel)
    }

    /**
     * The launchers already on the TV that the list does not have (one put on by hand, a brand's spare): picked like the others, through
     * the same safe swap ([LauncherInstall] finds them installed). The TV's own ones are the way back, not a choice here.
     */
    private fun installedLaunchers(g: dev.tvremote.core.ontv.Launchers.Gallery): List<dev.tvremote.core.ontv.Launchers.Entry> {
        val comps = try {
            packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName + "/" + it.activityInfo.name }
        } catch (e: Exception) { emptyList() }
        return dev.tvremote.core.ontv.LauncherSwap.pickable(comps, g.entries.map { it.pkg }.toSet()).map { pkg ->
            val name = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            dev.tvremote.core.ontv.Launchers.Entry(pkg, pkg, name, mapOf(Words.lang to T("launcher.installed")), "", "", "", "", "installed", "",
                emptyList(), "", true, true, 0, true, emptyMap())
        }
    }

    private fun showLaunchers(a: Adb, g: dev.tvremote.core.ontv.Launchers.Gallery, facts: dev.tvremote.core.ontv.Launchers.Facts, have: Set<String>, home: String, auto: String? = null) {
        val pics = ArrayList<Pair<dev.tvremote.core.ontv.Launchers.Entry, ImageView>>()
        val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(SCRIM)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = box(TvUi.CARD, 22, 0)
        card.setPadding(dp(32), dp(26), dp(32), dp(22))
        card.addView(text(T("launcher.title"), 22, TvUi.TEXT, true))
        val status = text("", 13, MUTED, false)
        card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        var stopped = false
        var running = false
        fun why(e: Exception): String {
            val m = e.message ?: ""
            val w = m.substringAfter("launcher:rolledback:", "")
            return if (w.isNotEmpty()) T(dev.tvremote.core.ontv.LauncherSwap.backId(w), "why" to T(dev.tvremote.core.ontv.LauncherSwap.whyId(w)))
            else reason(e)
        }
        fun install(e: dev.tvremote.core.ontv.Launchers.Entry) {
            running = true; stopped = false
            status.text = T("launcher.step_download", "pct" to 0)
            Thread {
                try {
                    val inst = dev.tvremote.core.ontv.LauncherInstall(a, cacheDir, g, Words.lang)
                    val state = inst.run(e, dev.tvremote.core.ontv.LauncherSwap.CONFIRM_SEC, { step, pct ->
                        ui { status.text = when (step) { "download" -> T("launcher.step_download", "pct" to pct); "install" -> T("launcher.step_install"); else -> T("launcher.step_swap") } }
                    }, { stopped })
                    if (state == "pending") {
                        inst.askOnTv(e)                                   // the TV's own question (Keep / Go back), over this screen
                        val end = inst.await({ left -> ui { status.text = T("launcher.step_confirm", "s" to left) } }, { stopped })
                        if (end != "kept") throw java.io.IOException("launcher:rolledback:" + (dev.tvremote.core.ontv.LauncherSwap({ a.shell(it) }).state()?.why ?: "timeout"))
                    }
                    ui { log("✓ " + e.name); d.dismiss() }
                } catch (x: Exception) { ui { running = false; status.text = marked("✗ " + why(x)); log("✗ " + why(x)) } }
            }.start()
        }
        fun card(e: dev.tvremote.core.ontv.Launchers.Entry): View {
            val fit = dev.tvremote.core.ontv.Launchers.fits(e, facts)
            val using = e.pkg == home
            val r = LinearLayout(this)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(14), dp(10), dp(14), dp(10))
            r.isFocusable = true
            r.isClickable = true
            val pic = ImageView(this)
            pic.scaleType = ImageView.ScaleType.CENTER_CROP
            pic.background = box(TILE, 8, 0)                             // the picture's place while it loads
            if (e.thumb.isNotEmpty()) pics.add(e to pic) else try {       // one already on the TV: its own banner or icon
                pic.scaleType = ImageView.ScaleType.FIT_CENTER
                pic.setImageDrawable(packageManager.getApplicationBanner(e.pkg) ?: packageManager.getApplicationIcon(e.pkg))
            } catch (x: Exception) { }
            r.addView(pic, LinearLayout.LayoutParams(dp(128), dp(72)))
            val w = LinearLayout(this)
            w.orientation = LinearLayout.VERTICAL
            w.setPadding(dp(16), 0, 0, 0)
            w.addView(text(if (e.kind == "play") e.name + "  ▶ " + T("launcher.badge_play") else e.name, 17, TvUi.TEXT, true))
            val ds = text(e.desc(Words.lang), 12, MUTED, false)
            ds.maxLines = 2
            ds.ellipsize = TextUtils.TruncateAt.END
            w.addView(ds)
            val tag = if (using) T("launcher.in_use") else if (!fit.ok) when (fit.why) {
                "sdk" -> T("launcher.why_sdk", "android" to e.minSdk); "abi" -> T("launcher.why_abi"); "fire" -> T("launcher.why_fire")
                "atv" -> T("launcher.why_atv"); "gtv" -> T("launcher.why_gtv"); else -> T("launcher.why_not_tv") }
                else if (e.kind == "play" && e.pkg !in have) T("launcher.play") else if (e.pkg in have) T("launcher.use") else T("launcher.install")
            val st = text(tag, 13, if (!fit.ok) WARN else if (using) OK else ACCENT, true)
            w.addView(st, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            r.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
            val tagColor = if (!fit.ok) WARN else if (using) OK else ACCENT
            fun paint() {                                                 // on the blue focus the words turn white: blue on blue is lost
                val f = r.isFocused
                r.background = if (f) box(ACCENT, 12, 2) else box(TILE, 12, 0)
                st.setTextColor(if (f) Color.WHITE else tagColor); ds.setTextColor(if (f) Color.WHITE else MUTED)
            }
            r.setOnFocusChangeListener { _, _ -> paint() }
            paint()
            r.setOnClickListener {
                if (running || using || !fit.ok) return@setOnClickListener
                if (e.kind == "play" && e.pkg !in have) {             // once it is on the TV it goes through the same safe swap as the others
                    Thread {                                              // ADB is a socket: on the click's thread it is NetworkOnMainThread (41dd31f7, d789ac92)
                        try { a.shell("am start -a android.intent.action.VIEW -d '" + e.playUrl + "'"); ui { status.text = T("launcher.play_opened") } }
                        catch (x: Exception) { ui { status.text = marked("✗ " + reason(x)) } }
                    }.start()
                } else install(e)
            }
            return r
        }
        for (e in g.entries) list.addView(card(e), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        launcherPics(g, pics)
        val most = (resources.displayMetrics.heightPixels * 0.62).toInt()
        val scroll = object : TvScroll(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(most, View.MeasureSpec.AT_MOST))
        }
        scroll.addView(list)
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val foot = LinearLayout(this)
        foot.setPadding(0, dp(16), 0, 0)
        val back = smallButton(T("launcher.back")) {
            if (running) return@smallButton
            TvUi.confirm(this, R.style.TvSheet, T("launcher.back"), T("launcher.restore_ask"), T("launcher.restore"), T("common.cancel"), accent = ACCENT) {   // it sat beside Close and was pressed as "back" (the user, 2026-10-26)
                Thread { try { dev.tvremote.core.ontv.LauncherSwap({ a.shell(it) }).rollback("user"); ui { log(T("launcher.back")); d.dismiss() } } catch (x: Exception) { ui { status.text = marked("✗ " + reason(x)) } } }.start()
            }
        }
        val close = smallButton(T("common.close")) { if (running) stopped = true else d.dismiss() }
        foot.addView(back)
        foot.addView(close, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        card.addView(foot)
        root.addView(card, android.widget.FrameLayout.LayoutParams(dp(820), -2, Gravity.CENTER))
        d.setContentView(root)
        d.setOnDismissListener { stopped = true }                      // Back while installing stops it, same as Close
        d.show()
        list.getChildAt(0)?.requestFocus()
        g.entries.firstOrNull { it.pkg == auto && it.pkg != home && dev.tvremote.core.ontv.Launchers.fits(it, facts).ok }?.let { install(it) }
    }

    /** Removing this app leaves the TV as it was before it: apps Optimize TV turned off come back on first (Undo). */
    private fun askRemove() {
        if (busy) return
        TvUi.confirm(this, R.style.TvSheet, T("tv_main.remove_tv_setup_2"), T("tv_main.apps_turned_off_by"), T("fleet.remove"), T("common.cancel"), danger = true, accent = ACCENT) {
                val s = setup
                val a = adb
                if (s == null || a == null) {                         // no ADB here: Android's own way
                    try {
                        if (KeysAddon.installed(this)) startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:${dev.tvremote.setup.keys.KeysProtocol.ADDON}")))   // its key add-on first
                    } catch (e: Exception) { }
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))) } catch (e: Exception) { log("✗ " + reason(e)) }
                    return@confirm
                }
                busy = true
                spinner.visibility = View.VISIBLE
                log(T("tv_main.removing_tv_setup"))
                Thread {
                    try {
                        if (s.optimized()) s.undo()
                        a.shell("pm uninstall ${dev.tvremote.setup.keys.KeysProtocol.ADDON} 2>&1")   // its key add-on (KeysAddon) goes too: it is useless alone
                        a.shell("pm uninstall $packageName 2>&1")              // the end of this app: nothing after this runs
                    } catch (e: Exception) { ui { log("✗ " + reason(e)) } }
                    busy = false
                    ui { spinner.visibility = View.INVISIBLE }
                }.start()
        }
    }

    private fun job(job: Job) {
        val s = setup
        if (s == null || busy) return
        busy = true
        val running = currentFocus
        tiles.forEach { it.view.alpha = if (it.view === running) 1f else 0.4f; if (it.view === running) it.work(true) }   // focus stays where it is; the others wait
        spinner.visibility = View.VISIBLE
        Thread {
            try {
                job.run(s)
                showStatus(s)
            } catch (e: Exception) {
                log("✗ " + reason(e))
            }
            busy = false
            ui { tiles.forEach { it.work(false) }; setEnabled(true); spinner.visibility = View.INVISIBLE }
        }.start()
    }

    /**
     * One press: installs the official Tailscale when missing (through this TV's own ADB, no Play Store), lets it make its VPN
     * without asking, switches it on, and when it still has to be signed in, leaves its sign-in screen up: a QR code that a phone
     * signed in to Tailscale scans to add the TV. Nobody types an account on the TV.
     */
    private fun tailscale() {
        job { _ ->
            val ts = Tailscale(adb ?: return@job, getExternalFilesDir(null))
            if (!ts.installed()) log(T("tv_main.downloading_and_installing_the"))
            val url = ts.start { what, pct ->
                ui { connTile.progress(when (what) {
                    "download" -> T("tv_main.downloading_pct", "pct" to ((pct - 5) * 100 / 55).coerceIn(0, 100))
                    "install" -> T("tv_main.installing")
                    else -> T("tv_main.switching_on") }) }
                if (what == "start") log(T("tv_main.switching_tailscale_on"))
            }
            if (url.isEmpty()) log(T("tv_main.in_tailscale", "ip" to ts.ip()))
            else log(T("tv_main.tailscale_qr", "url" to url))
        }
    }

    private var logUsed = false
    /** A step the person has to do on the TV: its own colour, so it stands out from the plain lines around it. */
    private fun howto(line: String) = log(line, HOWTO)

    private fun log(line: String, kind: Int? = null) {
        val at = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())   // when it happened, not when it is drawn
        ui {
            if (!logUsed) { logUsed = true; logView.setText("", android.widget.TextView.BufferType.EDITABLE); logView.setTextColor(TvUi.TEXT) }
            // one colour per kind, read from the line's own mark before a font without it drops the mark: one look tells a failure from a step
            val tint = kind ?: when (val m = Marks.lead(line)) { null -> line.trimStart().firstOrNull().let { if (it == '❌') BAD else if (it == '✅') OK else null }; '✗', '✕', '✖' -> BAD; '⚠' -> WARN; else -> OK.takeIf { m == '✓' || m == '✔' } }
            val row = Marks.text(this, "$at  ${drawable(line)}\n")   // the marks are drawn as line icons
            row.setSpan(android.text.style.ForegroundColorSpan(TvUi.DIM), 0, at.length, 0)   // the time stays quiet
            if (tint != null) row.setSpan(android.text.style.ForegroundColorSpan(tint), at.length, row.length, 0)
            val atEnd = scroll.scrollY + scroll.height >= logView.bottom + scroll.paddingBottom - dp(8)   // read before the append: a reader higher up is not yanked down
            logView.append(row)
            if (logView.lineCount > 300) logView.editableText?.let { e ->        // the oldest go, the colours of the rest stay
                var cut = 0; repeat(100) { cut = e.indexOf('\n', cut) + 1 }
                e.delete(0, cut)
            }
            // not fullScroll(): when nothing can scroll it hands the focus to the log (it is focusable), which is what pulled the focus down on its own
            if (atEnd || !scroll.hasFocus()) scroll.post { scroll.scrollTo(0, maxOf(0, logView.bottom + scroll.paddingBottom - scroll.height)); scroll.invalidate() }
        }
    }

    private fun marked(s: String) = Marks.text(this, s)


    /** [line] without the symbols this TV's font has no glyph for (emoji; the plain marks ✗ ✓ ⚠ are icon spans, [Marks]): the words say it anyway. */
    private fun drawable(line: String): String {
        if (android.os.Build.VERSION.SDK_INT < 23) return line
        val p = logView.paint
        val sb = StringBuilder(); var i = 0
        while (i < line.length) {
            val n = Character.charCount(line.codePointAt(i)); val ch = line.substring(i, i + n)
            if (line.codePointAt(i) < 0x2000 || Marks.isMark(line, i) || p.hasGlyph(ch)) sb.append(ch) else if (i + n < line.length && line[i + n] == ' ') i++   // its space goes with it
            i += n
        }
        return sb.toString()
    }

    private fun reason(e: Exception): String = dev.tvremote.core.Errors.say(e, Words.lang)

    private fun ui(r: Runnable) { runOnUiThread(r) }

    /** Tiles that need ADB follow it; "Developer options" always works. Focus stays where it was, or goes to a tile that works. */
    private fun setEnabled(on: Boolean) {
        val had = currentFocus
        tiles.forEachIndexed { i, tile -> tile.enable(on || i >= 1) }   // without ADB: Connection (Debugging turns it on) and Update (removing works without ADB)
        if (on && !focusedOnce) { focusedOnce = true; tiles[0].view.requestFocus() }   // ready for the first time: start on the first action
        else if (had == null || !had.isEnabled) (if (on) tiles[0] else tiles[1]).view.requestFocus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun margins(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun box(color: Int, radius: Int, stroke: Int): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(radius).toFloat()
        d.setColor(color)
        if (stroke > 0) d.setStroke(dp(stroke), Color.WHITE)
        return d
    }

    private fun section(s: String): TextView {
        val v = text(s, 12, MUTED, true)
        v.letterSpacing = 0.08f
        return v
    }

    private fun text(s: String, sp: Int, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = marked(s)                                    // words from the i18n files may carry a mark
        t.textSize = sp.toFloat()
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    /** The smaller buttons of the right-hand card; the focused one is unmistakable on a TV. */
    private fun smallButton(label: String, l: View.OnClickListener): Button {
        val b = Button(this)
        b.text = marked(label)
        b.textSize = 13f
        b.isAllCaps = false
        b.setOnClickListener(l)
        b.isFocusable = true
        b.setTextColor(Color.WHITE)
        b.background = box(TILE, 10, 0)
        b.setOnFocusChangeListener { v, f -> v.background = if (f) box(ACCENT, 10, 2) else box(TILE, 10, 0) }
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(12), dp(7), dp(12), dp(7))
        b.stateListAnimator = null                                   // no raised shadow, and none of the theme's extra room around it
        return b
    }
}
