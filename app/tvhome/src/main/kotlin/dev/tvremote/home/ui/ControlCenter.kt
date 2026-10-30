package dev.tvremote.home.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.InputDevice
import dev.tvremote.home.apps.Inputs
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.tvremote.home.Font
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.Power
import dev.tvremote.home.R
import dev.tvremote.home.Store
import dev.tvremote.home.apps.LiveWidgets
import dev.tvremote.home.widgets.memoryUsage
import dev.tvremote.tvui.Motion
import dev.tvremote.tvui.TvGlass
import dev.tvremote.tvui.TvUi
import org.json.JSONObject
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Control Center (Trung tâm điều khiển): one glass panel under the top bar with the TV's everyday switches, after webOS's, drawn as
 * Customize is (TvGlass panel, 460 dp, 16 dp rows with an icon, a name and a line of state; app/DESIGN-TV.md). Tiles first, then the
 * power actions as one row of round buttons (power off, restart, screen off, standby, clear RAM: the top bar has no power button) and a
 * footer with CPU and RAM, read every [TICK_MS] only while it is open. It is the top bar's settings button: only what people use often is here (the owner,
 * 2026-10-25: a 24-hour clock or the sound screen is set once in a TV's life), the rest stays in TV settings and Customize. TV Home does the
 * network line and the settings screens itself; the sleep timer, kids mode, the power buttons and the CPU go through TV Setup's HomeInfo (it answers only TV Home): named words, never a
 * command line from here. Without TV Setup, or an old one, those say "Needs TV Setup" greyed. No brightness slider: measured on a real box,
 * the system brightness does nothing on TVs.
 */
object ControlCenter {
    private const val TICK_MS = 2000L
    private const val R_TILE = 16                                      // a row of the glass panel: Customize's and the long-press menu's
    /** What the last reading found, kept for the session: the panel then opens without asking the system on the UI thread (a cold open cost ~25 ms of it on the FPT box). */
    private class Seen(val inputs: Boolean, val net: Pair<Int, String>, val line: String?)
    @Volatile private var seen: Seen? = null
    @Volatile private var lastFoot = ""                          // the footer's CPU / RAM line of the last open
    @Volatile private var lastCtl: JSONObject? = null            // TV Setup's last answer: the timer and kids tiles open as they were, not greyed until it answers

    /** Reads it on a thread of its own once the home is up, so the first open is as quick as the next. */
    fun warm(a: Activity) {
        if (seen != null) return
        Thread({ try { for (r in ICONS26) glyph(a, r, 26); glyph(a, R.drawable.ic_eraser, 22); glyph(a, R.drawable.ic_power, 22); val ins = Inputs.rows(a) { }; seen = Seen(Inputs.showTile(ins.size, Inputs.liveApp(a) != null, Inputs.isBox()), network(a), inputsLine(a, ins)) } catch (e: Exception) { } }, "home-cc-warm").apply { isDaemon = true }.start()
    }

    /**
     * A line icon as a bitmap, drawn once per session: a vector icon is rasterised again by the render thread for every new ImageView (0.7 ms each,
     * 8 of them in the panel's first frame on the FPT box). The bitmap is shared, a drawable of it is new for each view.
     */
    private val bitmaps = java.util.concurrent.ConcurrentHashMap<Long, Bitmap>()
    /** TV Setup's tint colours (RGB, as Dim.COLORS in :shared, which TV Home does not pack) and their names, in its order. */
    private val TINTS: Map<String, Pair<Int, Int>> = linkedMapOf(
        "dark" to (0x000000 to R.string.cc_tint_dark),
        "amber" to (0xFF8A1E to R.string.cc_tint_amber),
        "yellow" to (0xFFC800 to R.string.cc_tint_yellow),
        "sepia" to (0x704214 to R.string.cc_tint_sepia),
        "red" to (0x9A0000 to R.string.cc_tint_red),
        "blue" to (0x10307A to R.string.cc_tint_blue))

    /** A colour's dot with a light ring, so dark shows on the glass. */
    private fun dot(color: Int, size: Int, ring: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((0xFF shl 24) or color); setStroke(ring, TvGlass.HINT); setSize(size, size) }

    private fun glyph(c: Context, res: Int, dp: Int): Drawable {
        val px = (dp * c.resources.displayMetrics.density).toInt()
        val bmp = bitmaps.getOrPut(res.toLong() shl 32 or px.toLong()) {
            val v = c.getDrawable(res)!!.mutate()
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { v.setBounds(0, 0, px, px); v.draw(Canvas(it)) }
        }
        return BitmapDrawable(c.resources, bmp)
    }
    private val ICONS26 = intArrayOf(R.drawable.ic_wifi, R.drawable.ic_router, R.drawable.ic_input, R.drawable.ic_bluetooth, R.drawable.ic_timer, R.drawable.ic_lock,
        R.drawable.ic_settings, R.drawable.ic_sliders, R.drawable.ic_clock, R.drawable.ic_eye_off, R.drawable.ic_refresh, R.drawable.ic_power)

    private fun inputsLine(a: Context, rows: List<Drop.Row>): String {
        val on = rows.filter { it.on != null }
        return if (rows.isEmpty()) a.getString(R.string.cc_open_settings) else if (on.isNotEmpty()) on.first().label + (if (on.size > 1) " +" + (on.size - 1) else "")
            else a.resources.getQuantityString(R.plurals.cc_inputs_count, rows.size, rows.size)
    }

    private val SLEEP_STEPS = intArrayOf(30, 60, 90, 120)             // minutes: HomeControl.SLEEP on TV Setup's side

    /**
     * One tile, Customize's own: a glass row ([TvGlass.rowBg], 16 dp), the icon (26), the name (15 sp) and, when it has one, a line of state
     * (12 sp). [need]: it needs TV Setup, which is missing or too old.
     */
    private class CcTile(c: Context, val dp: (Number) -> Int, icon: Int, name: String) : LinearLayout(c) {
        private val pic = ImageView(c)
        private val title = Font.on(TextView(c))
        private val sub = Font.on(TextView(c))
        var need = false
        var press: () -> Unit = {}
        var hold: (() -> Unit)? = null                                     // long OK, when the tile has a second thing to open

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), TvGlass.glass(c, R_TILE, TvGlass.ROW_FOCUS, Color.WHITE))
                addState(intArrayOf(), TvGlass.glass(c, R_TILE, TvGlass.ROW_FILL, TvGlass.ROW_EDGE))
            }
            addView(pic.apply { setImageDrawable(glyph(c, icon, 26)); imageTintList = ColorStateList.valueOf(Color.WHITE) }, LayoutParams(dp(26), dp(26)))
            val words = LinearLayout(c).apply { orientation = VERTICAL }
            words.addView(title.apply { text = name; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            words.addView(sub.apply { textSize = 12f; setTextColor(TvGlass.HINT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = GONE })
            addView(words, LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            setOnClickListener { press() }
            setOnLongClickListener { hold?.let { if (need) press() else it() }; hold != null }
            // no pop: the white edge and lighter fill say focus; scaled, a tile at the panel's edge came out cut (the user, 2026-10-25)
        }

        fun icon(res: Int) = pic.setImageDrawable(glyph(pic.context, res, 26))
        /** A colour's dot in the icon's place (the tint's colours). */
        fun swatch(color: Int) { pic.imageTintList = null; pic.setImageDrawable(dot(color, dp(22), dp(2))) }

        /** The state line; empty hides it (an action's tile is then one line). */
        fun say(text: String) { if (sub.text.toString() != text) sub.text = text; val v = if (text.isEmpty()) GONE else VISIBLE; if (sub.visibility != v) sub.visibility = v }   // the same words again (every 2 s) must not ask for a layout

        fun state(text: String, needSetup: Boolean = false) {
            need = needSetup; say(text)
            alpha = if (needSetup) 0.45f else 1f                    // greyed, still focusable: pressing it says why
        }
    }

    /**
     * The screen's brightness as TV Setup's tint takes it away ([b] 100 = no tint, down to 10 = the tint at its 90 %): one glass row like a
     * tile, the bar filled to [b]; Left / Right move it a step of 10 while it has the focus, [moved] gets each new value.
     */
    private class DimBar(c: Context, val dp: (Number) -> Int, name: String) : LinearLayout(c) {   // a sun, the track and the value: no words (the user, 2026-10-30: too many)
        var b = 100; private set
        var moved: (Int) -> Unit = {}
        private val value = Font.on(TextView(c))
        // a stock bar with a pill track and fill: no view of its own to draw
        private val track = android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isFocusable = false
            fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(4).toFloat() }
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(pill(TvGlass.ROW_EDGE),
                android.graphics.drawable.ClipDrawable(pill(Color.WHITE), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
            }
        }

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_activated), TvGlass.glass(c, R_TILE, TvGlass.ROW_FOCUS, TvUi.RING_ACCENT))
                addState(intArrayOf(android.R.attr.state_focused), TvGlass.glass(c, R_TILE, TvGlass.ROW_FOCUS, Color.WHITE))
                addState(intArrayOf(), TvGlass.glass(c, R_TILE, TvGlass.ROW_FILL, TvGlass.ROW_EDGE))
            }
            contentDescription = name
            addView(ImageView(c).apply { setImageDrawable(glyph(c, R.drawable.ic_sun, 26)); imageTintList = ColorStateList.valueOf(Color.WHITE) }, LayoutParams(dp(26), dp(26)))
            addView(track, LayoutParams(0, dp(8), 1f).apply { leftMargin = dp(16); rightMargin = dp(12) })
            addView(value.apply { textSize = 13f; setTextColor(TvGlass.HINT); gravity = Gravity.END; fontFeatureSettings = "tnum" }, LayoutParams(dp(44), -2))
            show(100)
        }

        fun show(v: Int) { b = v; value.text = "$v%"; track.progress = v }

        // OK starts changing the value, OK / Back / Up / Down end it (the user, 2026-10-30): at rest Left / Right
        // move the focus like on any row, so the colour button beside it is one press away
        private fun edit(on: Boolean) { isActivated = on; value.setTextColor(if (on) Color.WHITE else TvGlass.HINT) }

        override fun onFocusChanged(gain: Boolean, dir: Int, prev: android.graphics.Rect?) { super.onFocusChanged(gain, dir, prev); if (!gain) edit(false) }

        override fun onKeyDown(code: Int, e: android.view.KeyEvent): Boolean {
            when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> { edit(!isActivated); return true }
                android.view.KeyEvent.KEYCODE_BACK -> if (isActivated) { edit(false); return true }
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> edit(false)
            }
            if (!isActivated) return super.onKeyDown(code, e)
            val to = when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> minOf(100, (b / 10 + 1) * 10)
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> maxOf(10, ((b + 9) / 10 - 1) * 10)
                else -> return super.onKeyDown(code, e)
            }
            if (to != b) { show(to); moved(to) }
            return true                                                // while changing, the ends keep the focus
        }

        // OK's up would click the bar (it is clickable for touch): the down already did the work
        override fun onKeyUp(code: Int, e: android.view.KeyEvent) =
            if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER) true else super.onKeyUp(code, e)
    }

    /**
     * A round action (DESIGN-TV "Round action"): a 48 dp glass disc with the icon (22) and its short name under it (12 sp), three in one row.
     */
    private class Round(c: Context, val dp: (Number) -> Int, icon: Int, val name: String) : LinearLayout(c) {
        val disc = ImageView(c)
        private val word = Font.on(TextView(c))
        var press: () -> Unit = {}

        init {
            orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false   // clipToPadding clips to the bounds even with no padding: the disc's focus pop came out cut flat on top
            disc.apply {
                isFocusable = true; isClickable = true; setImageDrawable(glyph(c, icon, 22)); imageTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(dp(13), dp(13), dp(13), dp(13)); contentDescription = name
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TvGlass.ROW_FOCUS); setStroke(dp(1), Color.WHITE) })
                    addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TvGlass.ROW_FILL); setStroke(dp(1), TvGlass.ROW_EDGE) })
                }
                setOnClickListener { press() }
            }
            addView(disc, LayoutParams(dp(48), dp(48)))
            addView(word.apply { text = name; textSize = 12f; setTextColor(TvGlass.HINT); maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }, LayoutParams(-1, -2))
        }

    }

    /** Opens the panel over [anchor] (the top bar's capsule), its right edge on the anchor's. [open] starts a settings action, [inputs] is the list of sources, [customize] TV Home's own settings. */
    fun show(a: Activity, anchor: View?, open: (String) -> Unit, inputs: () -> Unit, customize: () -> Unit, closed: () -> Unit = {}): Dialog = Panel(a, anchor, open, inputs, customize, closed).dialog

    private class Panel(val a: Activity, val anchor: View?, val open: (String) -> Unit, val inputs: () -> Unit, val customize: () -> Unit, val closed: () -> Unit) {
        private val d = a.resources.displayMetrics.density
        private fun dp(v: Number) = (v.toFloat() * d).toInt()
        private val dm = a.resources.displayMetrics
        val dialog = Dialog(a, R.style.Menu)
        private val ui = Handler(Looper.getMainLooper())
        private val busy = AtomicBoolean()
        private var ctl: JSONObject? = lastCtl                           // TV Setup's {sleep_at, kids, pin}; null = no TV Setup, or one without it
        private val tiles = ArrayList<CcTile>()
        private var hold = 0L                                         // a message in the footer (clearing, what came free) keeps its place until then
        private var footText = lastFoot
        private val footer = Font.on(TextView(a)).apply { textSize = 12f; setTextColor(TvGlass.HINT); maxLines = 1; gravity = Gravity.CENTER; ellipsize = TextUtils.TruncateAt.END }
        private lateinit var net: CcTile
        private var inputTile: CcTile? = null; private var btTile: CcTile? = null
        private lateinit var kids: CcTile; private lateinit var sleep: CcTile
        private lateinit var dim: DimBar
        private lateinit var dimRow: LinearLayout
        private lateinit var tintDot: ImageView
        private var tintAt = ""
        private var dimAt = 0L                                        // the bar was moved then: an answer older than that does not move it back

        private fun toast(id: Int) = Toast.makeText(a, id, Toast.LENGTH_SHORT).show()
        private fun hasSetup() = try { a.packageManager.getPackageInfo("dev.tvremote.setup", 0); true } catch (e: Exception) { false }
        private fun resolves(action: String) = Intent(action).resolveActivity(a.packageManager) != null
        private val PAIR = "com.google.android.intent.action.CONNECT_INPUT"   // Android TV Settings' "Pair remote or accessory"
        private fun go(actions: List<String>) {
            val hit = actions.firstOrNull { resolves(it) }
            if (hit != null) { dialog.dismiss(); open(hit) }
            else { dialog.dismiss(); dev.tvremote.home.apps.SystemSettings.open(a) }   // nothing standard resolves: the vendors' Settings, else a toast
        }
        private fun say(text: String, ms: Long = 0) { footer.text = text; hold = if (ms > 0) System.currentTimeMillis() + ms else 0L }

        private fun tile(icon: Int, name: Int, press: () -> Unit) = CcTile(a, ::dp, icon, a.getString(name)).also { t -> t.press = { if (t.need) needSetup(t) else press() }; tiles.add(t) }

        /** An action TV Setup runs: greyed without it, and a tap then says how to get it. */
        private fun action(icon: Int, name: Int, run: () -> Unit) = Round(a, ::dp, icon, a.getString(name)).also { r ->
            r.press = { if (!hasSetup()) needSetup(r.disc) else run() }
            r.disc.setOnFocusChangeListener { v, f -> Motion.pop(v, f) }
            r.alpha = if (hasSetup()) 1f else 0.45f
        }

        /** "Turn off the TV": a small glass dialog with the two big choices (the person picks, nothing is armed); Back closes it, the focus starts on Screensaver. */
        /** The tint's colours (DimLayer's, Dim.COLORS), each with its name: a pick turns the tint on in that colour. */
        private fun tintMenu() {
            val m = Dialog(a, R.style.Menu)
            m.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); background = TvGlass.panelBg(a); clipChildren = false; clipToPadding = false }
            box.addView(Font.on(TvUi.text(a, a.getString(R.string.cc_tint), 20f, Color.WHITE, bold = true)).apply { typeface = Font.bold; setPadding(dp(16), dp(12), dp(16), dp(12)) })
            val col2 = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(8), dp(12), dp(8)) }
            var first: View? = null
            for ((n, e) in TINTS.entries.withIndex()) {
                val t = CcTile(a, ::dp, R.drawable.ic_sun, a.getString(e.value.second)).apply { swatch(e.value.first); press = { m.dismiss(); control("dimc:" + e.key) } }
                if (e.key == tintAt || first == null) first = t
                col2.addView(t, LinearLayout.LayoutParams(-1, -2).apply { if (n > 0) topMargin = dp(8) })
            }
            box.addView(col2)
            m.setContentView(box)
            m.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.CENTER); setDimAmount(Drop.MENU_DIM)
                attributes = attributes.apply { width = minOf(dp(380), dm.widthPixels - dp(32)); height = WindowManager.LayoutParams.WRAP_CONTENT }
            }
            m.setOnShowListener { first?.requestFocus() }
            m.show()
        }

        private fun powerMenu() {
            val m = Dialog(a, R.style.Menu)
            m.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); background = TvGlass.panelBg(a); clipChildren = false; clipToPadding = false }
            box.addView(Font.on(TvUi.text(a, a.getString(R.string.cc_power_title), 20f, Color.WHITE, bold = true)).apply { typeface = Font.bold; setPadding(dp(16), dp(12), dp(16), dp(12)) })
            val col2 = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(8), dp(12), dp(8)) }
            fun choice(icon: Int, name: Int, needs: Boolean, run: () -> Unit) = CcTile(a, ::dp, icon, a.getString(name)).apply {
                val grey = needs && !hasSetup(); alpha = if (grey) 0.45f else 1f
                press = { if (grey) needSetup(this) else { m.dismiss(); run() } }
            }
            val saver = choice(R.drawable.ic_clock, R.string.cc_screensaver, false) { dialog.dismiss(); a.startActivity(Intent(a, dev.tvremote.home.ScreensaverActivity::class.java)) }   // TV Home's own: no TV Setup
            val screenOff = choice(R.drawable.ic_eye_off, R.string.power_screen_off, true) { dialog.dismiss(); Power.run(a, Power.SCREEN_OFF) }
            val restart = choice(R.drawable.ic_refresh, R.string.cc_restart, true) { dialog.dismiss(); Power.run(a, Power.REBOOT) }
            val off = choice(R.drawable.ic_power, R.string.cc_power_off, true) { dialog.dismiss(); Power.run(a, Power.POWER_OFF) }
            for ((n, t) in listOf(saver, screenOff, restart, off).withIndex()) col2.addView(t, LinearLayout.LayoutParams(-1, -2).apply { if (n > 0) topMargin = dp(8) })
            box.addView(col2)
            m.setContentView(box)
            m.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.CENTER); setDimAmount(Drop.MENU_DIM)
                attributes = attributes.apply { width = minOf(dp(380), dm.widthPixels - dp(32)); height = WindowManager.LayoutParams.WRAP_CONTENT }
            }
            m.setOnShowListener { saver.requestFocus() }
            m.show()
        }

        /**
         * Without TV Setup (it runs the power buttons, the timer and the kids lock: TV Home has no ADB) these stay, greyed, and a tap says how to
         * get it, one menu for all: a control that vanished read as a bug. Downloader opens when the TV has it; the code is TV Setup's.
         */
        private fun needSetup(from: View) {
            val dl = a.packageManager.getLaunchIntentForPackage("com.esaba.downloader")
            Drop.show(a, from, listOf(
                Drop.Row(R.drawable.ic_info, a.getString(R.string.cc_setup_need)) { },
                Drop.Row(R.drawable.ic_download, a.getString(R.string.cc_setup_code, dev.tvremote.home.HomeActivity.SETUP_CODE)) { if (dl != null) try { a.startActivity(dl) } catch (e: Exception) { } },
                Drop.Row(R.drawable.ic_phone, a.getString(R.string.cc_setup_phone)) { }))
        }

        /** TV Setup's HomeInfo [method]; null when it is missing, too old, or does not trust this app. */
        private fun ask(method: String, arg: String?): JSONObject? = try {
            a.contentResolver.call(Uri.parse(HomeConfig.USAGE), method, arg, null)?.getString("json")?.let { JSONObject(it) }
        } catch (e: Exception) { null }

        /** The sleep timer / kids mode word to TV Setup on a thread of its own, then the state again. */
        private fun control(word: String) {
            Thread({
                val out = dev.tvremote.home.apps.SetupHand.control(a, word)   // its PIN, its lock card: started from here (SetupHand)
                a.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    ctl = out; if (out != null) lastCtl = out; paintCtl()
                    when (out?.optString("error")) {
                        "pin" -> toast(R.string.cc_kids_nopin)
                        "overlay" -> toast(R.string.cc_dim_overlay)
                        "bad", "failed" -> if (word == "kids:off" || (word == "kids:setup" && ctl?.optBoolean("kids") == true)) toast(R.string.cc_kids_unlock) else if (word == "kids:setup") toast(R.string.cc_kids_nopin)   // a TV Setup older than the word
                        else -> if (word == "kids:setup" && out != null) dialog.dismiss()                // the Manage screen is over us
                    }
                }
            }, "home-cc-control").apply { isDaemon = true }.start()
        }

        private fun paintNet(n: Pair<Int, String>) { net.icon(if (n.first == 2) R.drawable.ic_router else R.drawable.ic_wifi); net.state(when (n.first) {
            2 -> a.getString(R.string.cc_net_eth); 1 -> n.second.ifEmpty { a.getString(R.string.wifi) }; else -> a.getString(R.string.cc_net_none) }) }

        /** The Inputs tile's line, from the one reading the menu uses: what is connected, else how many there are. */
        private fun paintInputs(rows: List<Drop.Row>) { inputTile?.state(inputsLine(a, rows)) }

        /** Bluetooth with no permission: the adapter, then a connected audio device or game pad by name (the remote never counts). */
        private fun btState(): String {
            val on = try { (a.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true } catch (e: Exception) { false }
            if (!on) return a.getString(R.string.cc_off)
            val audio = if (Build.VERSION.SDK_INT < 23) null else try {
                (a.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
                }?.productName?.toString()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) { null }
            val pad = audio ?: try {
                InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.firstOrNull { d ->
                    !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) && !d.name.contains("remote", true) && !d.name.contains("virtual", true)
                }?.name
            } catch (e: Exception) { null }
            return pad ?: a.getString(R.string.cc_bt_on)
        }
        private fun paintBt() { btTile?.state(btState()) }

        /** The bar from TV Setup's tint; hidden while TV Setup is missing or too old to take "dim:" (its answer has no `dim`). */
        private fun paintDim() {
            val o = ctl?.optJSONObject("dim")
            val v = if (o == null) View.GONE else View.VISIBLE
            if (dimRow.visibility != v) dimRow.visibility = v
            if (o != null) { val k = o.optString("color"); if (k != tintAt) { tintAt = k; tintDot.setImageDrawable(dot(TINTS[k]?.first ?: 0, dp(24), dp(2))) } }
            if (o != null && System.currentTimeMillis() - dimAt > 3000) dim.show(if (o.optBoolean("on")) 100 - o.optInt("pct", 0) else 100)
        }

        /** The bar's value to TV Setup, the last one only (Left held down: one call, not ten). */
        private var dimOwed = false
        private val dimSend = Runnable {
            dimOwed = false
            val p = 100 - dim.b
            Thread({
                val out = ask("control", "dim:$p")
                a.runOnUiThread { if (out?.optString("error") == "overlay") toast(R.string.cc_dim_overlay) }
            }, "home-cc-dim").apply { isDaemon = true }.start()
        }

        private fun paintCtl() {
            paintDim()
            val c = ctl
            if (c == null) { kids.state(a.getString(R.string.cc_need_setup), true); sleep.state(a.getString(R.string.cc_need_setup), true); return }
            kids.state(a.getString(if (c.optBoolean("kids")) R.string.cc_kids_on else if (c.optBoolean("pin")) R.string.cc_off else R.string.cc_kids_nopin))
            val left = (c.optLong("sleep_at") - System.currentTimeMillis()).let { if (it > 0) (it + 59_999) / 60_000 else 0L }
            sleep.state(if (left > 0) a.getString(R.string.cc_sleep_left, left.toString()) else a.getString(R.string.cc_off))
        }

        /** One read of everything that moves, on a thread of its own (TV Setup is a binder call): the network, RAM, CPU, the timer. */
        private fun refresh() {
            if (!busy.compareAndSet(false, true)) return
            Thread({
                try {
                    val n = network(a)
                    val ins = if (inputTile != null) Inputs.rows(a) { } else emptyList()
                    val mem = memoryUsage()
                    val cpu = ask("cpu", null)?.optDouble("busy", -1.0)?.takeIf { it >= 0 }
                    val c = ask("control", null)
                    a.runOnUiThread {
                        if (!dialog.isShowing) return@runOnUiThread
                        seen = Seen(inputTile != null, n, if (inputTile != null) inputsLine(a, ins) else null)
                        paintNet(n); ctl = c; lastCtl = c; paintCtl(); paintBt(); paintInputs(ins)
                        val mm = mem?.let { String.format(Locale.US, "%.1f/%.1f GB", it.first / 1e9, it.second / 1e9) }
                        footText = if (mm == null) "" else if (cpu != null) a.getString(R.string.cc_footer, "${Math.round(cpu * 100)}%", mm)
                            else if (hasSetup()) a.getString(R.string.cc_footer, "…", mm) else a.getString(R.string.cc_footer_ram, mm)
                        lastFoot = footText
                        if (System.currentTimeMillis() >= hold) { hold = 0L; if (footer.text.toString() != footText) footer.text = footText }
                    }
                } finally { busy.set(false) }
            }, "home-cc").apply { isDaemon = true }.start()
        }

        private val tick = object : Runnable { override fun run() { if (dialog.isShowing) { refresh(); ui.postDelayed(this, TICK_MS) } } }

        init {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val bold: (TextView) -> Unit = { it.typeface = Font.bold }
            val col = LinearLayout(a).apply {                         // the glass panel of Customize and the long-press menu: padding 10, then the page's 12 / 8
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); background = TvGlass.panelBg(a)
                clipChildren = false; clipToPadding = false
            }
            col.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                // the TV's own name (Settings > Device name, e.g. "FPT Play 650"), else its model, is the title: the button that opens this says what it is
                val name = (try { Settings.Global.getString(a.contentResolver, "device_name") } catch (e: Exception) { null })?.takeIf { it.isNotBlank() } ?: Build.MODEL
                addView(Font.on(TvUi.text(a, name, 20f, Color.WHITE, bold = true)).also(bold).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Font.on(TvUi.text(a, a.getString(R.string.cc_android, Build.VERSION.RELEASE), 14f, TvGlass.HINT)).apply { maxLines = 1; setPadding(dp(12), 0, 0, 0) })
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) })

            net = tile(R.drawable.ic_wifi, R.string.cc_net) { go(listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS)) }
            // Inputs only where the TV lists some (or may keep them outside the TV Input Framework); a box with none has Bluetooth in that slot
            val known = seen ?: (Inputs.rows(a) { }.let { ins -> Seen(Inputs.showTile(ins.size, Inputs.liveApp(a) != null, Inputs.isBox()), network(a), inputsLine(a, ins)) })   // cold: asked here, once
            if (known.inputs) { inputTile = tile(R.drawable.ic_input, R.string.inputs) { dialog.dismiss(); inputs() }; known.line?.let { inputTile?.state(it) } }
            else if (a.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) { btTile = tile(R.drawable.ic_bluetooth, R.string.cc_bluetooth) { go(listOf(Settings.ACTION_BLUETOOTH_SETTINGS, PAIR, Settings.ACTION_SETTINGS)) }; paintBt() }   // FPT Play (Android 14) has no BLUETOOTH_SETTINGS: its Settings answers only the pairing screen
            sleep = tile(R.drawable.ic_timer, R.string.cc_sleep) {
                val left = ((ctl?.optLong("sleep_at") ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0).let { (it + 59_999) / 60_000L }   // rounded up, as the tile says it: 29.9 is "30 left", the next step is 60
                control("sleep:" + (if (left <= 0) SLEEP_STEPS.first() else SLEEP_STEPS.firstOrNull { it > left } ?: 0))   // off -> 30 -> 60 -> 90 -> 120 -> off
            }
            // on: TV Setup's lock card (behind its PIN), where the apps a child may open are chosen and kids mode turned off; a press
            // that only asked the PIN to turn it off left no way to that list (the user, 2026-10-26). Long OK: the list, on or off
            kids = tile(R.drawable.ic_lock, R.string.cc_kids) {
                val c = ctl
                if (c != null) control(if (c.optBoolean("kids") || !c.optBoolean("pin")) "kids:setup" else "kids:on")
            }.also { t -> t.hold = { if (ctl != null) control("kids:setup") } }
            tile(R.drawable.ic_settings, R.string.tv_settings) { go(listOf(Settings.ACTION_SETTINGS)) }.state(a.getString(R.string.cc_open_settings))
            tile(R.drawable.ic_sliders, R.string.cc_customize) { dialog.dismiss(); customize() }.state(a.getString(R.string.cc_customize_sub))
            dim = DimBar(a, ::dp, a.getString(R.string.cc_brightness)).apply { moved = { dimAt = System.currentTimeMillis(); dimOwed = true; ui.removeCallbacks(dimSend); ui.postDelayed(dimSend, 250) } }
            tintDot = ImageView(a)
            val tint = FrameLayout(a).apply {
                isFocusable = true; isClickable = true; contentDescription = a.getString(R.string.cc_tint); id = View.generateViewId()
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), TvGlass.glass(a, R_TILE, TvGlass.ROW_FOCUS, Color.WHITE))
                    addState(intArrayOf(), TvGlass.glass(a, R_TILE, TvGlass.ROW_FILL, TvGlass.ROW_EDGE))
                }
                addView(tintDot, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                setOnClickListener { tintMenu() }
            }
            dimRow = LinearLayout(a).apply { clipChildren = false; clipToPadding = false
                addView(dim, LinearLayout.LayoutParams(0, -1, 1f))
                addView(tint, LinearLayout.LayoutParams(dp(64), -1).apply { leftMargin = dp(8) })
            }
            paintCtl(); paintNet(known.net)           // the last reading; refresh() below corrects it off the UI thread
            val info = tiles.size

            // what the old power button did, through TV Setup (Power.kt): screen off, clear RAM, and "Turn off the TV" (power off / restart, asked in a dialog)
            val acts = ArrayList<Round>()
            acts += action(R.drawable.ic_eraser, R.string.cc_clear_ram) {
                say(a.getString(R.string.cc_working), 60_000)
                Thread({
                    // the same press as the Free RAM live widget: TV Setup closes what waits in the background and measures what came free
                    LiveWidgets.press(a, HomeConfig.liveKey("dev.tvremote.setup", "ram")) { s ->
                        val line = s.optString("small")
                        a.runOnUiThread { if (dialog.isShowing && line.isNotEmpty()) say(line, if (s.optString("mode") == "working") 60_000 else 6000) }
                    }
                }, "home-cc-ram").apply { isDaemon = true }.start()
            }
            acts += Round(a, ::dp, R.drawable.ic_power, a.getString(R.string.cc_power_tv)).also { r -> r.press = { powerMenu() }; r.disc.setOnFocusChangeListener { v, f -> Motion.pop(v, f) } }   // the menu has Screensaver, which needs no TV Setup

            val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(8), dp(12), dp(8)) }
            for (i in 0 until info step 2) {
                val row = LinearLayout(a).apply { clipChildren = false; clipToPadding = false }   // else it clips at its bounds: a focused tile came out cut at the sides
                row.addView(tiles[i], LinearLayout.LayoutParams(0, -2, 1f))
                if (i + 1 < info) row.addView(tiles[i + 1], LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
                else row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) })
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8) })
            }
            grid.addView(dimRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })   // full width under the tiles: a bar wants room, a 7th tile would leave half a row
            // the bar keeps Left / Right, so the colour button is reached from the tile above it (and Left from the button is the bar)
            tiles.last().id = View.generateViewId(); tiles.last().nextFocusDownId = tint.id; tint.nextFocusUpId = tiles.last().id
            val at = IntArray(2).also { if (anchor != null) anchor.getLocationOnScreen(it) else { it[0] = dm.widthPixels - dp(48); it[1] = dp(48) } }   // no anchor (over another app, ControlCenterActivity): where the top bar's would be
            val maxH = dm.heightPixels - at[1] - dp(12) - dp(170)     // the title, the round row and the footer stay on the screen
            col.addView(object : ScrollView(a) {
                override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH.coerceAtLeast(dp(140)), MeasureSpec.AT_MOST))
            }.apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(grid) }, LinearLayout.LayoutParams(-1, -2))

            val actions = LinearLayout(a).apply { clipChildren = false; clipToPadding = false; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(12), dp(4), dp(12), 0) }
            for (r in acts) actions.addView(r, LinearLayout.LayoutParams(dp(120), -2))
            col.addView(actions, LinearLayout.LayoutParams(-1, -2))
            col.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(12), dp(16), dp(8)) })

            footer.text = footText
            dialog.setContentView(col)
            val sw = dm.widthPixels
            val w = minOf(dp(460), sw - dp(32))                       // Customize's panel width
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.TOP or Gravity.END)
                setDimAmount(Drop.MENU_DIM)
                attributes = attributes.apply {
                    x = (sw - (at[0] + (anchor?.width ?: 0))).coerceAtLeast(dp(16)); y = at[1]   // its right edge on the capsule's, its top on the bar's
                    width = w; height = WindowManager.LayoutParams.WRAP_CONTENT
                }
            }
            val bar = anchor?.parent as? TopBar
            bar?.holdWord(true)                                       // the button's word would show through the dim
            dialog.setOnDismissListener { val owed = dimOwed; ui.removeCallbacksAndMessages(null); if (owed) dimSend.run(); bar?.holdWord(false); closed() }   // a step made just before Back still reaches the TV
            dialog.setOnShowListener { tiles.first().requestFocus(); refresh(); ui.postDelayed(tick, TICK_MS) }   // the first tile focused; the clock ticks only while open
            dialog.show()
        }
    }

    /** (0 none | 1 Wi-Fi | 2 Ethernet, the Wi-Fi's name or "" when unknown) of the network the TV is using. A cable wins, as the TV routes it. */
    internal fun network(a: Context): Pair<Int, String> {
        var kind = 0
        try {
            val cm = a.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION") for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (cm.getLinkProperties(n)?.linkAddresses?.any { it.address is Inet4Address && !it.address.isLoopbackAddress } != true) continue
                val wired = c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (kind == 0 || wired) kind = if (wired) 2 else 1
            }
        } catch (e: Exception) { }
        val name = if (kind != 1) "" else try {
            @Suppress("DEPRECATION") (a.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
                ?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
        } catch (e: Exception) { "" }
        return kind to name
    }
}
