package dev.tvremote.home

import dev.tvremote.home.wall.NetPicture
import dev.tvremote.tvui.Motion
import dev.tvremote.tvui.TvGlass
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.Bitmap
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import dev.tvremote.home.manage.HomeApi
import dev.tvremote.home.keys.Keys
import dev.tvremote.home.wall.Online
import dev.tvremote.home.manage.PhotoScreen
import dev.tvremote.home.wall.Videos
import dev.tvremote.home.manage.TelegramScreen
import dev.tvremote.home.ui.Drop
import dev.tvremote.home.keys.Picker
import dev.tvremote.home.keys.QuickKeys
import dev.tvremote.home.wall.WallArt
import dev.tvremote.home.wall.Walls

/**
 * TV Home's own settings, on the TV with the D-pad: a glass panel from the right, the home screen still showing beside it so
 * each change is seen at once. The background, the clock, the greeting, motion, the widgets. Every change goes through
 * [Store.change] ([HomeConfig.clean]), the path the phone's broadcast takes, so the page's dialog reads the same config. The
 * rest (favourites, hidden apps, pictures from the gallery) stays on the phone or computer: the gallery's signature check lives there.
 */
object Customize {
    // a row's focus is its lighter background alone, no 1.06 growth: the growth's animation re-drew the panel every frame,
    // 24 moves cost ~5.3 s of CPU on the FPT box
    private fun lit(v: View, f: Boolean) { v.animate().cancel(); v.scaleX = 1f; v.scaleY = 1f }
    private const val ACCENT = 0xFF8AB4F8.toInt()
    private const val STEP_FILL = 0xF0161A24.toInt()                   // the rows stepper: nearly opaque, its number read over any background
    private const val DIM = 0xA6FFFFFF.toInt()
    private const val DOOR_FOCUS = 0x2EFFFFFF                          // a settings row that opens a screen: fill under focus, at rest, its edge
    private const val DOOR_REST = 0x12FFFFFF
    private const val OPAQUE = 0xFF000000L                             // "rrggbb" of the own wall made a colour
    private const val SWATCH_EDGE = 0x40FFFFFF                         // the own wall's colour chips: seen on a black one too
    private const val DOOR_EDGE = 0x1FFFFFFF
    private const val NO_PIC = 0xFF2A2F3A.toInt()                      // a theme tile whose picture is not made yet
    private const val CHECK_INK = 0xFF0B1020.toInt()
    const val NAME_SCRIM = 0x99000000.toInt()
    private const val RING_REST = 0x33FFFFFF
    private const val ADD = "+add"                                 // the add cell among the wall tiles (not a wall)

    /** One group of settings: a row on the first level and a page of controls behind it. [last] is where the focus was when it was left. */
    private val PREVIEW = setOf("themes", "bg", "look", "widgets")              // pages seen on Home as they change: no dim over it (cols / rows open their own stepper, the panel hidden)

    private class Group(c: Context, val key: String, val name: Int, val icon: Int) {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val scroll = ScrollView(c).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(col) }
        val page = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        var row: View? = null; var head: View? = null; var sum: TextView? = null; var last: View? = null
        var start: (() -> View?)? = null                                 // where a first visit lands, else the first control (Background: its first control, not the photo in use further down)
        var fill: (() -> Unit)? = null                                 // puts the controls in [col]; dropped once it ran
        var made: (() -> Unit)? = null                                 // the fill that ran, for a page made again
        var seen: String? = ""                                         // the settings as this page last showed them ([Store.stamp])
    }

    private val EVERY_WORDS = mapOf("off" to R.string.rot_off, "30m" to R.string.rot_30m, "1h" to R.string.rot_1h, "6h" to R.string.rot_6h, "day" to R.string.rot_day, "show" to R.string.rot_show)

    /** The line under a group's name on the first level, from the config as it is now. */
    private fun summary(a: Context, key: String, c: JSONObject): String {
        fun on(k: String) = a.getString(if (c.optBoolean(k, true)) R.string.w_on else R.string.w_off)
        return when (key) {
            "themes" -> c.optString("theme").let { id -> if (id.isEmpty()) a.getString(R.string.th_custom) else themeName(a, c, id) }
            "bg" -> {
                val set = c.optString("wevery") != "off"
                val what = if (set) a.getString(R.string.g_bg_set, c.getJSONArray("walls").length(), a.getString(EVERY_WORDS.getValue(c.optString("wevery"))).lowercase()) else wallName(a, c.optString("wall"))
                what + " · " + a.getString(R.string.g_shade_n, c.optInt("shade", HomeConfig.SHADE_DEFAULT))
            }
            "look" -> a.getString(if (c.optString("focus", "glass") == "ring") R.string.c_focus_ring else R.string.c_focus_glass) + " · " +
                a.getString(R.string.g_cols_n, c.optInt("cols", HomeConfig.COLS_DEFAULT)) + " · " +
                (c.optDouble("rows", 0.0).let { if (it < 1) a.getString(R.string.g_rows_auto) else a.getString(R.string.g_rows_n, rowsWord(a, it)) })
            "widgets" -> a.getString(R.string.g_widgets_n, c.optJSONArray("widgets")?.length() ?: 0)
            "system" -> a.getString(R.string.g_dream) + ": " + on("dream")
            "keys" -> a.getString(R.string.g_keys_sum, HomeConfig.keyApps(c.optJSONObject("keyapps")).size.takeIf { c.optBoolean("keyon") } ?: 0,
                a.getString(if (c.optBoolean("autoon")) R.string.w_on else R.string.w_off))
            else -> dev.tvremote.i18n.Langs.NAMES[c.optString("lang")] ?: a.getString(R.string.c_lang_tv)
        }
    }

    /** The first view that can take the focus in [v], top to bottom (a hidden one is passed over). */
    private fun firstFocusable(v: View): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.isFocusable) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun rowsWord(a: Context, v: Double) = if (v < 1) a.getString(R.string.c_rows_auto) else String.format(java.util.Locale.ROOT, "%.1f", v)

    /**
     * "rows" or "cols" with the remote: rows ◀ ▶ a tenth, ▲ ▼ a whole row; columns ◀ ▶ ▲ ▼ one. OK or Back done. The panel steps
     * aside ([panel] hidden) and the number sits in the clock's corner with no dim, so each step shows on the home screen at once
     * (saved a moment after the last press: holding a key does not rebuild it every repeat). Then the panel is back as it was.
     * Rows below 1 are "by itself"; the most is what fits under the clock or the widgets ([rowsFit]). "shade" in percent (◀ ▶ 5,
     * ▲ ▼ 10) with a bar under it, as a slider: the veil changes behind it as it moves.
     */
    private fun stepper(a: Activity, panel: Dialog, key: String, done: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        val rows = key == "rows"
        val shade = key == "shade"
        var v = if (rows) Store.get(a).optDouble("rows", 0.0) else if (shade) Store.get(a).optInt("shade", HomeConfig.SHADE_DEFAULT).toDouble() else Store.get(a).optInt("cols", HomeConfig.COLS_DEFAULT).toDouble()
        val most = if (shade) 100.0 else if (!rows) HomeConfig.COLS_MAX.toDouble() else if (rowsFit >= 1) rowsFit else HomeConfig.ROWS_MAX
        val bar = android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(ACCENT); progressBackgroundTintList = ColorStateList.valueOf(TvGlass.ROW_FOCUS)
            visibility = if (shade) View.VISIBLE else View.GONE
        }
        val dialog = Dialog(a, R.style.Menu)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val num = Font.on(TextView(a)).apply { textSize = 44f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Font.light }
        val note = Font.on(TextView(a)).apply { textSize = 13f; setTextColor(DIM); gravity = Gravity.CENTER }
        fun say() {
            num.text = if (rows) rowsWord(a, v) else if (shade) "${v.toInt()}%" else v.toInt().toString()
            bar.progress = v.toInt()
            note.text = a.getString(if (shade) R.string.c_shade_keys else if (rows && v >= 1 && v >= most - 0.05) R.string.c_rows_most else if (rows) R.string.c_rows_keys else R.string.c_cols_keys)
        }
        val save = Runnable { Store.change(a, JSONObject().put(key, if (rows) v else v.toInt())) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; isFocusableInTouchMode = true   // opened by a touch (a mouse, adb), it still takes the keys
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(STEP_FILL); setStroke(dp(1), ACCENT) }
            addView(Font.on(TextView(a)).apply { text = a.getString(if (shade) R.string.c_shade else if (rows) R.string.c_rows else R.string.c_cols); textSize = 15f; setTextColor(DIM); gravity = Gravity.CENTER })
            addView(num, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(6) })
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        box.setOnKeyListener { _, code, e ->
            if (e.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (rows) -0.1 else if (shade) -5.0 else -1.0; android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (rows) 0.1 else if (shade) 5.0 else 1.0
                android.view.KeyEvent.KEYCODE_DPAD_UP -> if (shade) 10.0 else 1.0; android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (shade) -10.0 else -1.0
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> { dialog.dismiss(); return@setOnKeyListener true }
                else -> return@setOnKeyListener false
            }
            v = if (shade) (v + step).coerceIn(0.0, most) else if (!rows) (v + step).coerceIn(HomeConfig.COLS_MIN.toDouble(), most)
                else if (v < 1) (if (step > 0) 1.0 else 0.0) else (v + step).let { if (it < 0.95) 0.0 else HomeConfig.rows(minOf(it, most)) }
            say(); box.removeCallbacks(save); box.postDelayed(save, 200); true
        }
        say()
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setDimAmount(0f)
            setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { gravity = Gravity.TOP or Gravity.START; x = dp(48); y = dp(40) }
        }
        if (shade) dev.tvremote.home.wall.Backdrop.hold = true
        dialog.setOnDismissListener { box.removeCallbacks(save); save.run(); dev.tvremote.home.wall.Backdrop.hold = false; panel.show(); done() }
        // the panel out of sight only once the number has the window focus: hidden first, the focus fell to the home screen behind
        dialog.setOnShowListener { box.requestFocus(); panel.hide(); box.post { box.requestFocus() } }
        dialog.show()
    }

    /**
     * The person's own wall (HomeConfig "own", "wcol", "wdir") with the remote, as [stepper]: the panel steps aside and the wall itself
     * changes behind the box. Rows: how many colours (one is a solid fill), each colour (◀ ▶ through [PALETTE]), the direction. OK or Back done.
     */
    private fun ownColours(a: Activity, panel: Dialog, done: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        val c0 = Store.get(a)
        val cols = HomeConfig.wcol(c0.optString("wcol")).toMutableList()
        var dir = c0.optString("wdir").takeIf { it in HomeConfig.WDIRS } ?: HomeConfig.WDIRS[0]
        val save = Runnable {
            val ch = JSONObject().put("wcol", cols.joinToString(",")).put("wdir", dir)
            if (Store.get(a).optString("wevery") == "off") ch.put("wall", HomeConfig.OWN)    // seen at once; in a set it is one of the set already, or picked there
            Store.change(a, ch)
        }
        val dialog = Dialog(a, R.style.Menu)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(STEP_FILL); setStroke(dp(1), ACCENT) }
        }
        box.addView(Font.on(TextView(a)).apply { text = a.getString(R.string.wall_own); textSize = 15f; setTextColor(DIM); gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val rows = ArrayList<Pair<View, () -> Unit>>()
        lateinit var paintAll: () -> Unit
        // one line: its name, what it is now; ◀ ▶ steps it, ▲ ▼ to the next line (the system's own focus search)
        fun line(name: () -> String, value: (TextView, View) -> Unit, step: (Int) -> Unit): View {
            val word = Font.on(TextView(a)).apply { textSize = 15f; setTextColor(Color.WHITE) }
            val now = Font.on(TextView(a)).apply { textSize = 14f; setTextColor(ACCENT); gravity = Gravity.END }
            val chip = View(a)
            val v = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isFocusableInTouchMode = true
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(DOOR_FOCUS); setStroke(dp(1), Color.WHITE) })
                    addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(DOOR_REST) })
                }
                addView(word, LinearLayout.LayoutParams(0, -2, 1f))
                addView(now, LinearLayout.LayoutParams(-2, -2))
                addView(chip, LinearLayout.LayoutParams(dp(44), dp(24)).apply { leftMargin = dp(10) })
            }
            v.setOnKeyListener { _, code, e ->
                if (e.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> step(-1); android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> { dialog.dismiss(); return@setOnKeyListener true }
                    else -> return@setOnKeyListener false
                }
                paintAll(); box.removeCallbacks(save); box.postDelayed(save, 200); true
            }
            rows.add(v to { word.text = name(); value(now, chip) })
            box.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            return v
        }
        fun hex(s: String) = (s.toLong(16) or OPAQUE).toInt()
        fun swatch(chip: View, fill: Drawable?) { chip.background = fill; chip.visibility = if (fill == null) View.GONE else View.VISIBLE }
        line({ a.getString(R.string.own_n) }, { t, chip -> t.text = if (cols.size == 1) a.getString(R.string.own_solid) else "${cols.size}"; swatch(chip, null) }) { s ->
            val n = (cols.size + s).coerceIn(1, HomeConfig.OWN_MAX)
            while (cols.size < n) cols.add(PALETTE[(PALETTE.indexOf(cols.last()).coerceAtLeast(0) + 7) % PALETTE.size])   // a new colour a little along the palette from the last
            while (cols.size > n) cols.removeAt(cols.size - 1)
        }
        val stops = (0 until HomeConfig.OWN_MAX).map { i ->
            line({ a.getString(R.string.own_stop, i + 1) }, { t, chip ->
                t.text = ""; swatch(chip, cols.getOrNull(i)?.let { GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(hex(it)); setStroke(dp(1), SWATCH_EDGE) } })
            }) { s ->
                val at = PALETTE.indexOf(cols[i]).let { if (it >= 0) it else nearest(hex(cols[i])) }   // a colour typed on a phone: the palette from the nearest of its own
                cols[i] = PALETTE[(at + s + PALETTE.size) % PALETTE.size]
            }
        }
        val dirLine = line({ a.getString(R.string.own_dir) }, { t, chip ->
            t.text = a.getString(a.resources.getIdentifier("wdir_$dir", "string", a.packageName))
            swatch(chip, wallGradient(cols.map { hex(it) }.toIntArray(), dir).apply { cornerRadius = dp(6).toFloat() })
        }) { s -> dir = HomeConfig.WDIRS[(HomeConfig.WDIRS.indexOf(dir) + s + HomeConfig.WDIRS.size) % HomeConfig.WDIRS.size] }
        box.addView(Font.on(TextView(a)).apply { text = a.getString(R.string.own_keys); textSize = 12f; setTextColor(DIM); gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        paintAll = {
            stops.forEachIndexed { i, v -> v.visibility = if (i < cols.size) View.VISIBLE else View.GONE }
            dirLine.visibility = if (cols.size > 1) View.VISIBLE else View.GONE
            rows.forEach { it.second() }
        }
        paintAll()
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setDimAmount(0f)
            setLayout(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { gravity = Gravity.TOP or Gravity.START; x = dp(48); y = dp(40) }
        }
        dialog.setOnDismissListener { box.removeCallbacks(save); save.run(); panel.show(); done() }
        dialog.setOnShowListener { rows[0].first.requestFocus(); panel.hide(); box.post { rows[0].first.requestFocus() } }
        dialog.show()
    }

    /** The own wall's colours, walked with ◀ ▶: twelve hues each deep, mid and soft (deep first: a wall dark enough for white words), then greys. */
    private val PALETTE: List<String> = (0 until 12).flatMap { h ->
        listOf(floatArrayOf(0.85f, 0.32f), floatArrayOf(0.75f, 0.58f), floatArrayOf(0.45f, 0.85f)).map { (s, v) ->
            "%06x".format(Color.HSVToColor(floatArrayOf(h * 30f, s, v)) and 0xFFFFFF)
        }
    } + listOf("000000", "1c1c1e", "3a3a3c", "636366", "aeaeb2", "ffffff")

    private fun nearest(c: Int): Int = PALETTE.indices.minByOrNull { i ->
        val p = (PALETTE[i].toLong(16) or OPAQUE).toInt()
        listOf(16, 8, 0).sumOf { sh -> val x = (p shr sh and 0xFF) - (c shr sh and 0xFF); x * x }
    } ?: 0

    private var ready: Pair<Dialog, () -> Unit>? = null
    private var readyFor: Activity? = null

    /**
     * The panel built while Home is idle, so MENU only shows it: built on the press it took ~300 ms on the FPT box before it began
     * to slide in (seven rows of views, the settings read three times). Its pages still fill on first use, from the settings then.
     */
    fun prepare(a: Activity) {
        android.os.Looper.myQueue().addIdleHandler {                 // between frames and keys: a key pressed meanwhile is not held
            if (readyFor !== a && !a.isDestroyed && !a.isFinishing) { ready = make(a, null); readyFor = a }
            false
        }
    }
    fun forget(a: Activity) { if (readyFor === a) { ready = null; readyFor = null }; if (panelOwner === a) { panelDialog?.dismiss(); panelDialog = null; panelOwner = null } }   // a Dialog's context is a wrapper round the activity, never the activity: the owner is kept

    /** [at] a page's key: the panel opens on it, without a slide (it reopens on Language after a new language built Home again). */
    fun show(a: Activity, at: String? = null): Dialog {
        val (dialog, before) = ready?.takeIf { at == null && readyFor === a } ?: make(a, at)
        panelDialog = dialog; panelOwner = a                                       // the one on screen (a spare is made ahead), for [hidePanel]
        ready = null; readyFor = null
        before()
        dialog.show()
        return dialog
    }

    private fun make(a: Activity, at: String?): Pair<Dialog, () -> Unit> {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        val dm = a.resources.displayMetrics
        val edge = dp(24)
        val panelW = panelW(a)
        var stepBack: () -> Boolean = { false }                        // Back on a page goes up a level (set below); on the first level it closes the panel
        val dialog = object : Dialog(a, R.style.Menu) {
            @Suppress("OVERRIDE_DEPRECATION") override fun onBackPressed() { if (!stepBack()) super.onBackPressed() }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val groups = listOf(
            Group(a, "themes", R.string.g_themes, R.drawable.ic_star),
            Group(a, "bg", R.string.bg, R.drawable.ic_image), Group(a, "look", R.string.g_look, R.drawable.ic_sparkle),
            Group(a, "widgets", R.string.w_widgets, R.drawable.ic_gauge),
            Group(a, "system", R.string.g_system, R.drawable.ic_power), Group(a, "keys", R.string.g_keys, R.drawable.ic_keyboard),
            Group(a, "lang", R.string.c_lang, R.drawable.ic_globe))
        for (g in groups) g.col.setPadding(dp(28), dp(8), dp(28), dp(28))
        val top = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(28), dp(26), dp(28), dp(28)) }
        var col = groups[0].col                                        // the page being filled; each page's controls go into its own column
        fun label(t: String, size: Float, color: Int) = Font.on(TextView(a)).apply { text = t; textSize = size; setTextColor(color) }
        fun icon(res: Int, color: Int, size: Int) = ImageView(a).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(color) }.let { it to size }
        fun section(t: String) = col.addView(label(t.uppercase(), 12f, DIM).apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22); bottomMargin = dp(10) })
        fun glass(radius: Int, fill: Int, stroke: Int) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(fill); setStroke(dp(1), stroke) }

        // backgrounds, five to a row, a ✓ on the one in use (rotation off) or on each of the set it turns through (rotation on)
        var conf = Store.get(a)
        // read when the backgrounds' page fills, not when the panel is built ahead: a photo sent meanwhile is in it
        val keys by lazy {
            val have = Walls.photos(a); val all = HomeConfig.WALLS.filter { !HomeConfig.isPhoto(it) || it in have }
            val mine = all.takeWhile { it == HomeConfig.BING || it == HomeConfig.WEB || HomeConfig.isPhoto(it) }       // the person's own first, the videos of the library among them
            mine + Videos.list(a).map { it.wall } + all.drop(mine.size)
        }
        val tiles = ArrayList<Pair<String, View>>()
        var ownFace: () -> Unit = {}                                  // the own wall's tile, painted again after its colours changed
        val cols = WALL_COLS; val gap = dp(10); val w = tileW(a)
        val shots = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue(),
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())   // the scenes drawn and the photos read small, off the main thread; a tile shown after the panel closed is dropped, not thrown (census 4823c228)
        dialog.setOnDismissListener { if (panelDialog === dialog) { panelDialog = null; panelOwner = null }; shots.shutdownNow(); Keys.onState = null; a.window.decorView.postDelayed({ prepare(a) }, 1500) }   // the next one ready
        // the pictures are put in together once the panel has slid in: one by one during the slide each drew the whole panel again
        val waiting = ArrayList<Pair<ImageView, Bitmap>>()
        var slid = !Motion.on
        fun put(v: ImageView, b: Bitmap) { if (slid) v.setImageBitmap(b) else waiting.add(v to b) }
        val rotNote = label("", 12f, DIM)
        val nowNote = label("", 14f, ACCENT)                           // at the top of the page while it rotates: how many, how often, which is on screen
        fun wallOn(c: JSONObject, k: String) =
            if (k.startsWith(Suggest.PREFIX)) c.optString("wall") == HomeConfig.WEB && Suggest.byKey(k)?.url == c.optString("wurl")   // a suggestion is the page it sets
            else if (c.optString("wevery") == "off") c.optString("wall") == k
            else c.getJSONArray("walls").let { s -> (0 until s.length()).any { s.getString(it) == k } }
        // a tile's ✓ (in the set) and "showing now" (filled, only while it rotates): made the first time one is shown, not on all
        // ~90 tiles of the backgrounds page (3 of a tile's 8 views)
        fun badge(v: View, tag: String, show: Boolean) {
            val b = v.findViewWithTag<View>(tag)
            if (b != null) { b.visibility = if (show) View.VISIBLE else View.GONE; return }
            if (!show) return
            (v as FrameLayout).addView(if (tag == "check") FrameLayout(a).apply {
                this.tag = tag
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
                val (iv, s) = icon(R.drawable.ic_check, CHECK_INK, dp(14)); addView(iv, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
            } else Font.on(TextView(a)).apply {
                this.tag = tag; text = a.getString(R.string.wall_now); textSize = 10f; setTextColor(CHECK_INK); maxLines = 1
                setPadding(dp(7), dp(2), dp(7), dp(2)); background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(8).toFloat() }
            }, if (tag == "check") FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) }
               else FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), 0, 0, dp(6)) })
        }
        fun markAll() {
            val c = Store.get(a)                                         // once: reading it per tile cost 18 ms on the FPT box
            val rotating = c.optString("wevery") != "off"
            val shown = c.optString("wall")
            // two marks when it rotates: a ✓ on each wall in the set, and "showing now" on the one on screen; one wall only: just the ✓
            for ((k, v) in tiles) {
                val on = wallOn(c, k); v.isSelected = on
                badge(v, "check", on); badge(v, "now", rotating && k == shown)
            }
            rotNote.visibility = if (rotating) View.VISIBLE else View.GONE
            rotNote.text = a.getString(if (c.getJSONArray("walls").length() < 2) R.string.rot_pick else R.string.rot_set)
            nowNote.visibility = if (rotating) View.VISIBLE else View.GONE
            nowNote.text = a.getString(R.string.wall_rot_state, c.getJSONArray("walls").length(), a.getString(EVERY_WORDS.getValue(c.optString("wevery"))).lowercase(),
                if (shown.isEmpty()) "" else wallName(a, shown))
        }
        // how often it moves on: two rows of three chips, the one in use tinted like the switches below
        val everyWords = EVERY_WORDS
        val chips = ArrayList<Pair<String, TextView>>()
        fun markEvery() { val e = Store.get(a).optString("wevery"); for ((k, v) in chips) v.isSelected = k == e }
        // switches as tiles, one to a row (two side by side were ~200 dp each: a name on two lines left its neighbour short): an icon, a name, a line under it; on = a blue tint with a blue edge, as everywhere in the app
        // with [choices] (value to its words) the tile steps through them on each press instead of switching on / off; on = not the first
        fun tile(res: Int, text: String, sub: String?, key: String, choices: List<Pair<String, String>>? = null, then: () -> Unit = {}) {
            var pick = choices?.indexOfFirst { it.first == conf.optString(key) }?.coerceAtLeast(0) ?: 0
            var viaSetup = false                                       // "stats": TV Setup is on the TV and holds the switch (OwnReport)
            var on = if (key == "stats") dev.tvremote.report.OwnReport.own(a) else if (choices != null || key == "rows" || key == "shade") pick > 0 || key == "cols" || key == "watchAt" || key == "rows" || key == "shade" else conf.optBoolean(key, true)
            val ic = ImageView(a).apply { setImageResource(res) }
            val state = label("", 12f, DIM)
            val t = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(ic, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(text, 15f, Color.WHITE).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(state.apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            }
            fun paint() {
                t.background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), glass(16, if (on) 0x408AB4F8 else 0x2EFFFFFF, Color.WHITE))
                    addState(intArrayOf(), glass(16, if (on) 0x268AB4F8 else 0x12FFFFFF, if (on) ACCENT else 0x1FFFFFFF))
                }
                ic.imageTintList = ColorStateList.valueOf(if (on) ACCENT else DIM)
                val word = a.getString(if (on) R.string.w_on else R.string.w_off).replaceFirstChar { it.uppercase() }
                state.text = if (key == "stats" && viaSetup) a.getString(R.string.stats_setup) else if (key == "rows") rowsWord(a, Store.get(a).optDouble("rows", 0.0)) else if (key == "shade") "${Store.get(a).optInt("shade", HomeConfig.SHADE_DEFAULT)}%" else choices?.get(pick)?.second ?: if (sub != null) "$word · $sub" else word
                state.setTextColor(if (on) ACCENT else DIM)
            }
            paint()
            if (key == "stats") Thread({ dev.tvremote.report.OwnReport.setup(a).first?.let { s -> a.runOnUiThread { viaSetup = true; on = s; paint() } } }, "stats-setup").apply { isDaemon = true }.start()
            t.onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
            t.setOnClickListener {
                if (key == "stats") { if (!viaSetup) { on = !on; dev.tvremote.report.OwnReport.setOwn(a, on); paint() }; return@setOnClickListener }
                if (key == "wcol") { ownColours(a, dialog) { ownFace(); markAll() }; return@setOnClickListener }
                if (key == "rows" || key == "cols" || key == "shade") {
                    stepper(a, dialog, key) { pick = choices?.indexOfFirst { it.first == Store.get(a).optString(key) }?.coerceAtLeast(0) ?: 0; paint() }
                    return@setOnClickListener
                }
                if (choices != null) {                                 // the next one in turn (motion, focus)
                    pick = (pick + 1) % choices.size; on = pick > 0 || key == "cols"; paint()
                    Store.change(a, JSONObject().put(key, choices[pick].first)); then(); return@setOnClickListener
                }
                on = !on
                try { Store.change(a, JSONObject().put(key, on)) } catch (e: SecurityException) { on = !on; systemDream(a) }
                paint()
            }
            col.addView(t, LinearLayout.LayoutParams(-1, -2).apply { if (col.childCount > 0) topMargin = gap })   // below anything: the veil after the photo row too
        }

        // each page is filled when it is first opened, or once the panel has slid in: all seven built before it showed took
        // ~650 ms on the FPT box, which held its first frame for a second
        groups[0].fill = {
            val page = col
            val shotsW = (panelW - dp(56) - gap) / 2                   // two to a row: the wall's picture and the theme's name over it
            val themeTiles = ArrayList<Pair<String, View>>()
            fun mark() { val now = Store.get(a).optString("theme"); for ((k, v) in themeTiles) { v.isSelected = k == now; v.findViewWithTag<View>("check")?.visibility = if (k == now) View.VISIBLE else View.GONE } }
            fun apply(op: JSONObject) { Store.change(a, JSONObject().put("theme", op)) }
            lateinit var draw: () -> Unit
            // a preview is drawn only when its tile is within one tile of the screen (a long list of community themes costs nothing until scrolled to)
            val pending = ArrayList<Pair<View, () -> Unit>>()
            val loc = IntArray(2)
            fun pump() {
                val sh = a.resources.displayMetrics.heightPixels
                val it = pending.iterator()
                while (it.hasNext()) {
                    val (v, go) = it.next()
                    if (!v.isAttachedToWindow) continue
                    v.getLocationOnScreen(loc)
                    if (loc[1] + v.height > -v.height && loc[1] < sh + v.height) { it.remove(); try { go() } catch (e: java.util.concurrent.RejectedExecutionException) { } }
                }
            }
            page.viewTreeObserver.addOnScrollChangedListener { pump() }
            page.viewTreeObserver.addOnGlobalLayoutListener { if (pending.isNotEmpty()) pump() }
            // the preview is the theme's home screen in miniature (ThemePreview): drawn once off the main thread, kept by theme + list rev + size + picture
            fun themeTile(id: String, name: String, conf: JSONObject, live: List<String>, look: String, shot: java.io.File?, mine: Boolean, by: String = ""): View {
                val t = FrameLayout(a).apply {
                    isFocusable = true; isClickable = true
                    background = GradientDrawable().apply { setColor(NO_PIC); cornerRadius = dp(12).toFloat() }
                    val pv = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_XY }
                    addView(pv, FrameLayout.LayoutParams(-1, -1))
                    val key = ThemePreview.key(id, conf, live, shotsW, shot)
                    val drawn = ThemePreview.cached(key)
                    if (drawn != null) pv.setImageBitmap(drawn)
                    else pending.add(this to { shots.execute { val b = ThemePreview.get(a, key, conf, live, shot, look, shotsW, shotsW * 9 / 16, dp(12).toFloat()); a.runOnUiThread { if (!a.isDestroyed) put(pv, b) } } })
                    fg(StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), ring(d, 3, Color.WHITE))
                        addState(intArrayOf(android.R.attr.state_selected), ring(d, 2, ACCENT))
                        addState(intArrayOf(), ring(d, 1, RING_REST))
                    })
                    addView(LinearLayout(a).apply {                      // the name, and under it "by <sender>" on a community theme
                        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(5))
                        background = GradientDrawable().apply { setColor(NAME_SCRIM); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat()) }
                        addView(label(name, 13f, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        if (by.isNotEmpty()) addView(label(a.getString(R.string.th_by, by), 11f, DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    // a line icon and a word each: an emoji draws differently on every TV's font, and some have none
                    val tags = ThemePreview.badges(conf).map { b -> when { b == "video" -> R.drawable.ic_play to a.getString(R.string.th_badge_video)
                        b == "slides" -> R.drawable.ic_image to a.getString(R.string.th_badge_slides)
                        else -> R.drawable.ic_image to a.getString(R.string.th_badge_photos, b.substringAfter(':').toInt()) } }
                    if (tags.isNotEmpty()) addView(LinearLayout(a).apply {     // top left: the check is top right
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(2), dp(8), dp(2))
                        background = GradientDrawable().apply { setColor(NAME_SCRIM); cornerRadius = dp(10).toFloat() }
                        tags.forEachIndexed { i, (res, words) ->
                            val (iv, sz) = icon(res, Color.WHITE, dp(11))
                            addView(iv, LinearLayout.LayoutParams(sz, sz).apply { if (i > 0) marginStart = dp(8); marginEnd = dp(4) })
                            addView(label(words, 11f, Color.WHITE).apply { maxLines = 1 })
                        }
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
                    addView(FrameLayout(a).apply {
                        tag = "check"
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
                        val (iv, sz) = icon(R.drawable.ic_check, CHECK_INK, dp(14)); addView(iv, FrameLayout.LayoutParams(sz, sz, Gravity.CENTER))
                    }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
                    onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                    setOnClickListener { apply(JSONObject().put("apply", id)); mark() }
                    setOnLongClickListener {                            // OK held: what the theme sets, read only; one of mine can be deleted from there
                        val conf = HomeConfig.theme(Store.get(a), id) ?: return@setOnLongClickListener true
                        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
                        for ((k, v) in themeRows(a, conf)) body.addView(LinearLayout(a).apply {
                            addView(label(k, 14f, DIM), LinearLayout.LayoutParams(dp(230), -2))
                            addView(label(v, 14f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                        val b = android.app.AlertDialog.Builder(a, R.style.Menu).setTitle(name).setView(android.widget.ScrollView(a).apply { addView(body) })
                            .setPositiveButton(android.R.string.ok, null)
                        if (mine) b.setNeutralButton(R.string.photo_del) { _, _ ->
                            android.app.AlertDialog.Builder(a, R.style.Menu).setTitle(R.string.th_del_q).setMessage(R.string.th_del_note)
                                .setPositiveButton(R.string.photo_del) { _, _ -> apply(JSONObject().put("del", id)); draw() }.setNegativeButton(android.R.string.cancel, null).show()
                        }
                        b.showOnOk()
                        true
                    }
                }
                themeTiles.add(id to t)
                return t
            }
            fun grid(items: List<View>) {
                items.chunked(2).forEachIndexed { i, pair ->
                    val row = LinearLayout(a).apply { clipChildren = false; clipToPadding = false }
                    pair.forEachIndexed { j, v -> row.addView(v, LinearLayout.LayoutParams(shotsW, shotsW * 9 / 16).apply { if (j > 0) leftMargin = gap }) }
                    page.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i > 0) gap else 0 })
                }
            }
            draw = {
                page.removeAllViews(); themeTiles.clear(); pending.clear()
                val c = Store.get(a)
                col = page
                val mine = c.optJSONArray("themes")
                page.addView(label(a.getString(R.string.th_hold), 12f, DIM), LinearLayout.LayoutParams(-2, -2))   // the hint first, before every list
                val own = ArrayList<View>()
                c.optJSONObject(HomeConfig.BEFORE)?.let { own += themeTile(HomeConfig.BEFORE, a.getString(R.string.th_before), it, emptyList(), "", null, true) }   // the look before the last switch
                if (mine != null) for (i in 0 until mine.length()) mine.getJSONObject(i).let { own += themeTile(it.getString("id"), it.getString("name"), it.getJSONObject("conf"), emptyList(), "", ThemeShots.mine(a, it.getString("id")), true) }
                if (own.isNotEmpty()) {
                    section(a.getString(R.string.th_mine))
                    grid(own)
                }
                val full = (mine?.length() ?: 0) >= HomeConfig.THEMES_MAX
                val save = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                    setPadding(dp(14), dp(12), dp(14), dp(12)); background = TvGlass.rowBg(a, 16)
                    val (iv, sz) = icon(R.drawable.ic_save, ACCENT, dp(26)); addView(iv, LinearLayout.LayoutParams(sz, sz))
                    addView(label(a.getString(R.string.th_save_look), 15f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                    onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                    setOnClickListener {
                        if (full) { android.widget.Toast.makeText(a, R.string.th_full, android.widget.Toast.LENGTH_LONG).show(); return@setOnClickListener }
                        // named here, with the TV's keyboard; the field starts with a default ("My theme 3"), the same name saves over that theme
                        val box = dev.tvremote.tvui.TvField(a).apply {
                            Font.on(this); textSize = 16f; isSingleLine = true; maxLines = 1
                            filters = arrayOf(android.text.InputFilter.LengthFilter(HomeConfig.THEME_NAME_MAX))
                            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            setText(a.getString(R.string.th_name_n, (mine?.length() ?: 0) + 1)); setSelection(text.length)
                        }
                        fun save() {
                            val n = box.text.toString().trim().ifEmpty { return }
                            apply(JSONObject().put("save", n)); draw()
                            Store.get(a).optJSONArray("themes")?.let { m -> m.optJSONObject(m.length() - 1)?.optString("id") }?.let { id -> themeTiles.firstOrNull { it.first == id }?.second?.requestFocus() }
                        }
                        val dlg = android.app.AlertDialog.Builder(a, R.style.Menu).setTitle(R.string.th_name_q)
                            .setView(FrameLayout(a).apply { setPadding(dp(24), dp(4), dp(24), 0); addView(box) })   // in line with the title
                            .setPositiveButton(R.string.th_save_ok) { _, _ -> save() }.setNegativeButton(android.R.string.cancel, null).create()
                        box.setOnEditorActionListener { _, _, _ -> save(); dlg.dismiss(); true }
                        dlg.show()
                    }
                }
                page.addView(save, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (own.isEmpty()) 10 else 14) })
                section(a.getString(R.string.th_ready))
                grid(HomeConfig.ready.filter { !it.community }.map { r -> themeTile(r.id, themeName(a, c, r.id), r.conf, r.live, r.look, ThemeShots.ready(a, r), false) })
                val shared = HomeConfig.ready.filter { it.community }
                if (shared.isNotEmpty()) {                       // the store's approved themes (the signed list's "community"), each with its sender
                    section(a.getString(R.string.th_community))
                    grid(shared.map { r -> themeTile(r.id, themeName(a, c, r.id), r.conf, r.live, r.look, ThemeShots.ready(a, r), false, r.by) })
                }
                mark()
            }
            draw()
            Themes.onThumbs = { a.runOnUiThread { if (!a.isDestroyed && page.isAttachedToWindow) draw() } }   // the ready-made pictures came while the page is open
            Themes.fetchThumbs(a)
            Themes.refresh(a, 3600_000L)                         // a list an hour old is asked again; a new one redraws through onThumbs
            val pics = HomeConfig.ready.mapNotNull { r -> r.conf.optString("wurl").takeIf { it.isNotEmpty() }?.let { NetPicture.direct(it) } }   // their pictures are cached before one is picked
            if (pics.isNotEmpty()) Thread({ NetPicture.prefetch(a.applicationContext, pics) }, "themepics").apply { isDaemon = true }.start()
        }
        groups[1].fill = {
            // a photo from a phone on the same Wi-Fi is the first cell of "your photos" (a QR screen, no ADB and no TV Setup; when it was used the panel closes to show the new background)
            col.addView(nowNote, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            // the veil over a photo, a scene or the page: a busy one fights the clock and the names (HomeConfig "shade")
            tile(R.drawable.ic_contrast, a.getString(R.string.c_shade), null, "shade")
            // a photo cropped to fill the screen, or whole over itself blurred (HomeConfig "wfit": a portrait photo)
            tile(R.drawable.ic_image, a.getString(R.string.c_fit), null, "wfit", listOf("cover" to a.getString(R.string.fit_cover), "contain" to a.getString(R.string.fit_contain)))
            col.addView(label(a.getString(R.string.rot), 13f, Color.WHITE), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18); bottomMargin = dp(8) })
            var chipRow: LinearLayout? = null
            HomeConfig.EVERY.forEachIndexed { i, k ->
                if (i % 3 == 0) { chipRow = LinearLayout(a).apply { clipChildren = false; clipToPadding = false }; col.addView(chipRow, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8) }) }
                val chip = Font.on(TextView(a)).apply {
                    text = a.getString(everyWords.getValue(k)); textSize = 14f; gravity = Gravity.CENTER; maxLines = 1
                    setTextColor(Color.WHITE); isFocusable = true; isClickable = true; setPadding(dp(8), dp(11), dp(8), dp(11))
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), glass(14, 0x2EFFFFFF, Color.WHITE))
                        addState(intArrayOf(android.R.attr.state_selected), glass(14, 0x268AB4F8, ACCENT))
                        addState(intArrayOf(), glass(14, 0x12FFFFFF, 0x1FFFFFFF))
                    }
                    onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                    setOnClickListener { Store.change(a, JSONObject().put("wevery", k)); markEvery(); markAll() }
                }
                chips.add(k to chip)
                chipRow!!.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i % 3 > 0) leftMargin = dp(8) })
            }
            col.addView(rotNote, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            // the wall tiles by where they come from, each with the settings that belong to it (a video's sound only when there is a video)
            // the "add" cell: same size and rings as a background, a big + on it; OK opens the QR screen
            fun addTile() = FrameLayout(a).apply {
                isFocusable = true; isClickable = true
                background = GradientDrawable().apply { setColor(DOOR_REST); cornerRadius = dp(12).toFloat() }
                fg(StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), ring(d, 3, Color.WHITE))
                    addState(intArrayOf(), ring(d, 1, RING_REST))
                })
                val (iv, sz) = icon(R.drawable.ic_plus, ACCENT, dp(40)); addView(iv, FrameLayout.LayoutParams(sz, sz, Gravity.CENTER))
                onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                setOnClickListener { PhotoScreen.show(a) { dialog.dismiss() } }
            }
            fun wallRows(list: List<String>) {
            var row: LinearLayout? = null
            list.forEachIndexed { i, k ->
                if (i % cols == 0) { row = LinearLayout(a).apply { clipChildren = false; clipToPadding = false }; col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = gap }) }
                val cell = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
                val t = if (k == ADD) addTile() else FrameLayout(a).apply {
                    isFocusable = true; isClickable = true
                    fun face(cf: JSONObject): Drawable {
                        val c = if (HomeConfig.isPhoto(k) || HomeConfig.isVideo(k) || k == HomeConfig.BING || k == HomeConfig.WEB) intArrayOf(0xFF2A2F3A.toInt()) else wallColors(k, cf)
                        return wallGradient(c, wallDir(k, cf)).apply { cornerRadius = dp(12).toFloat() }   // rounded, so not [wallDrawable]'s ColorDrawable
                    }
                    background = face(conf)
                    if (k == HomeConfig.OWN) ownFace = { background = face(Store.get(a)) }
                    if (WallArt.has(k) || Walls.isPicture(k) || k.startsWith(Suggest.PREFIX)) {                   // the picture itself, in the tile's rounded corners: the same painter as the screen
                        val pv = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_XY }   // the picture is made at the tile's size with its corners: nothing scaled or clipped per frame
                        addView(pv, FrameLayout.LayoutParams(-1, -1))
                        val drawn = thumbs[thumbKey(a, k, w)]                    // made beforehand ([warm]) or on an earlier opening: there as it slides in
                        if (drawn != null) pv.setImageBitmap(drawn)
                        else shots.execute { val b = thumb(a, k, w); a.runOnUiThread {
                            if (b != null) put(pv, b)
                            else if (HomeConfig.isPhoto(k) || HomeConfig.isVideo(k)) { val (iv, sz) = icon(R.drawable.ic_image, DIM, dp(22)); addView(iv, FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)) }   // a photo that cannot be read: a mark, not a blank grey
                        } }
                    }
                    fg(StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), ring(d, 3, Color.WHITE))
                        addState(intArrayOf(android.R.attr.state_selected), ring(d, 2, ACCENT))
                        addState(intArrayOf(), ring(d, 1, RING_REST))
                    })
                    if (k == HomeConfig.WEB || Suggest.byKey(k)?.let { it.kind == "page" && it.thumb.isEmpty() } == true) { val (iv, s) = icon(R.drawable.ic_globe, ACCENT, dp(26)); addView(iv, FrameLayout.LayoutParams(s, s, Gravity.CENTER)) }
                    Suggest.byKey(k)?.takeIf { it.kind == "video" || it.kind == "slides" }?.let {          // it moves: said on the tile, as the page's badge
                        val (iv, s) = icon(if (it.kind == "video") R.drawable.ic_play else R.drawable.ic_image, Color.WHITE, dp(16))
                        addView(iv, FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
                    }
                    if (k == HomeConfig.BING && !Online.file(a).isFile) { val (iv, s) = icon(R.drawable.ic_image, DIM, dp(22)); addView(iv, FrameLayout.LayoutParams(s, s, Gravity.CENTER)) }   // not fetched yet
                    onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                    setOnClickListener {
                        val c = Store.get(a)
                        Suggest.byKey(k)?.let { sg ->                               // the page it is: one choice, as the web page tile (clean() keeps "web" alone in a set)
                            Store.change(a, JSONObject().put("wall", HomeConfig.WEB).put("wurl", sg.url).put("wmode", "live")); markAll()
                            return@setOnClickListener
                        }
                        if (k == HomeConfig.WEB) {                                // the link is pasted on a phone (a QR), how it updates is picked there with the D-pad
                            PhotoScreen.show(a, web = true) { dialog.dismiss() }
                            return@setOnClickListener
                        }
                        if (k == HomeConfig.BING && c.optString("wall") == k && c.optString("wevery") == "off" && Online.count(a) > 1) {   // Bing on already: its next (older) day, round to the newest
                            Online.step(a)
                            Store.change(a, JSONObject().put("wall", k))
                            val pv = getChildAt(0) as? ImageView
                            shots.execute { val b = thumb(a, k, w); a.runOnUiThread { if (b != null) pv?.setImageBitmap(b) } }
                        }
                        else if (c.optString("wevery") == "off") Store.change(a, JSONObject().put("wall", k))       // one choice
                        else {                                                                                      // a set: a tile in or out, never the last one out
                            val s = c.getJSONArray("walls"); val now = List(s.length()) { s.getString(it) }
                            val next = if (k in now) now - k else now + k
                            if (next.isNotEmpty()) Store.change(a, JSONObject().put("walls", org.json.JSONArray(next)))
                        }
                        markAll()
                    }
                    // a photo sent from a phone: hold OK to delete it, after a question (the same as the phone's ✕: HomeReceiver.drop)
                    if (HomeConfig.isPhoto(k) || HomeConfig.isVideo(k)) setOnLongClickListener {
                        android.app.AlertDialog.Builder(a, R.style.Menu).setTitle(if (HomeConfig.isVideo(k)) R.string.video_del_q else R.string.photo_del_q).setMessage(R.string.photo_del_note)
                            .setPositiveButton(R.string.photo_del) { _, _ ->
                                if (HomeConfig.isVideo(k)) {
                                    // the library keeps what the setting (or a theme) still shows: said, not silently kept
                                    if (!Videos.delete(a, k, Store.get(a))) { android.widget.Toast.makeText(a, R.string.video_inuse, android.widget.Toast.LENGTH_LONG).show(); return@setPositiveButton }
                                    Store.change(a, JSONObject())
                                } else if (Videos.kept(k)) { android.widget.Toast.makeText(a, R.string.photo_inuse, android.widget.Toast.LENGTH_LONG).show(); return@setPositiveButton }
                                else HomeApi.drop(a, k)
                                thumbs.keys.removeAll { it.startsWith("$k:") }
                                // the focus to its neighbour: the hidden tile's focus fell to the panel's first control, far up
                                val at = tiles.indexOfFirst { it.first == k }
                                val near = tiles.getOrNull(at + 1)?.second ?: tiles.getOrNull(at - 1)?.second
                                (parent as? View)?.visibility = View.GONE
                                tiles.removeAll { it.first == k }
                                markAll()
                                near?.requestFocus()
                            }
                            .setNegativeButton(android.R.string.cancel, null).show()
                        true
                    }
                }
                if (k != ADD) tiles.add(k to t)
                cell.addView(t, LinearLayout.LayoutParams(w, w * 9 / 16))
                cell.addView(label(if (k == ADD) a.getString(R.string.wall_add) else Suggest.byKey(k)?.name(a.resources.locale0().language) ?: wallName(a, k), 12f, DIM).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(5) })
                row!!.addView(cell, LinearLayout.LayoutParams(w, -2).apply { if (i % cols > 0) leftMargin = gap })
            }
            }
            val videos = keys.filter { HomeConfig.isVideo(it) }
            val online = keys.filter { it == HomeConfig.BING || it == HomeConfig.WEB }
            val photos = keys.filter { HomeConfig.isPhoto(it) }
            val scenes = keys.filter { it in HomeConfig.ART }
            val colours = keys.filter { it !in online && it !in photos && it !in videos && it !in scenes }
            fun part(title: Int, list: List<String>, more: () -> Unit = {}) {
                if (list.isEmpty()) return
                section(a.getString(title)); wallRows(list); more()
            }
            part(R.string.wall_h_online, online)
            // the site's suggestions (Suggest): drawn from the list kept, again when a newer one comes while the page is open
            val sugBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
            col.addView(sugBox, LinearLayout.LayoutParams(-1, -2))
            fun sugFill() {
                val list = Suggest.items.map { it.key }
                tiles.removeAll { it.first.startsWith(Suggest.PREFIX) }; sugBox.removeAllViews()
                if (list.isEmpty()) return
                val was = col; col = sugBox
                section(a.getString(R.string.wall_h_suggest)); wallRows(list)
                col.addView(label(a.getString(R.string.sug_note), 12f, DIM), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                col = was; markAll()
            }
            Suggest.onChange = { a.runOnUiThread { if (a.isDestroyed) return@runOnUiThread; if (!dialog.isShowing || sugBox.findFocus() == null) sugFill() } }   // never under the person's focus
            Suggest.load(a)                                                   // the kept list first: a fresh process has none in memory yet
            sugFill()
            section(a.getString(R.string.wall_h_photos)); wallRows(listOf(ADD) + photos)         // always there: with no photo yet, the grid is the + cell alone
            part(R.string.wall_h_videos, videos) {
                // a video or GIF background (HomeConfig vfps / vres; no sound: a wall is a picture, the user 2026-10-27): frame rate and size for the NEXT upload, which is made on a phone through the QR above
                col.addView(View(a), LinearLayout.LayoutParams(1, dp(10)))
                tile(R.drawable.ic_play, a.getString(R.string.v_fps), null, "vfps", HomeConfig.VFPS.map { it to if (it.isEmpty()) a.getString(R.string.v_orig) else a.getString(R.string.v_fps_n, it.toInt()) })
                tile(R.drawable.ic_image, a.getString(R.string.v_res), null, "vres", HomeConfig.VRES.map { it to if (it.isEmpty()) a.getString(R.string.v_res_screen) else "${it}p" })
            }
            part(R.string.wall_h_scenes, scenes)
            part(R.string.wall_h_colours, colours) {
                col.addView(View(a), LinearLayout.LayoutParams(1, dp(10)))
                tile(R.drawable.ic_contrast, a.getString(R.string.own_edit), null, "wcol", listOf("" to a.getString(R.string.own_edit_sub)))
            }
            markAll(); markEvery()
            conf.takeIf { it.optString("wevery") != "off" }?.optString("wall")?.let { now -> tiles.firstOrNull { it.first == now }?.let { t -> groups[1].start = { t.second } } }   // rotating: the page opens on the one on screen
            if (keys.any { HomeConfig.isPhoto(it) || HomeConfig.isVideo(it) }) col.addView(label(a.getString(R.string.photo_del_hint), 12f, DIM), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        groups[2].fill = {
            col.addView(label(a.getString(R.string.g_sec_show).uppercase(), 12f, DIM).apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(2) })
            tile(R.drawable.ic_timer, a.getString(R.string.clock24), null, "clock24")
            section(a.getString(R.string.sec_head))                     // the two header lines, each with its own type: blocks, or the two sets of tiles read as one
            tile(R.drawable.ic_message, a.getString(R.string.greet), null, "greet")
            // its words (HomeConfig "gstyle"): typed here with the TV's keyboard, or on a phone (Home settings)
            fun field(key: String, max: Int, hint: Int) = dev.tvremote.tvui.TvField(a).apply {
                Font.on(this); textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(DIM); this.hint = a.getString(hint)
                isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(max))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setText(conf.optString(key)); setPadding(dp(14), dp(12), dp(14), dp(12))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), glass(16, DOOR_FOCUS, Color.WHITE))
                    addState(intArrayOf(), glass(16, DOOR_REST, DOOR_EDGE))
                }
                done = { Store.change(a, JSONObject().put(key, text.toString().trim())) }
            }
            val gname = field("gname", HomeConfig.GNAME_MAX, R.string.gname_hint); val gtext = field("gtext", HomeConfig.GTEXT_MAX, R.string.gtext_hint)
            fun says(k: String) = a.getString(when (k) { "name" -> R.string.gstyle_name; "own" -> R.string.gstyle_own; "date" -> R.string.gstyle_date; "lunar" -> R.string.gstyle_lunar; else -> R.string.gstyle_time })
            // each header line's type (HomeConfig.HEAD_TYPE / HEAD_FLAGS): own text with {date} {lunar} … is easier typed on a phone
            fun type(w: String) {
                tile(R.drawable.ic_type, a.getString(R.string.h_size), null, w + "size", HomeConfig.HEAD_SIZES.map { it to a.getString(when (it) { "s" -> R.string.h_s; "l" -> R.string.h_l; else -> R.string.h_m }) })
                tile(R.drawable.ic_contrast, a.getString(R.string.h_color), null, w + "color", HomeConfig.HEAD_COLORS.map { it to a.getString(when (it) { "dim" -> R.string.h_dim; "red" -> R.string.h_red; else -> R.string.h_white }) })
                tile(R.drawable.ic_type, a.getString(R.string.h_bold), null, w + "bold")
                tile(R.drawable.ic_type, a.getString(R.string.h_italic), null, w + "italic")
                tile(R.drawable.ic_type, a.getString(R.string.h_caps), null, w + "caps")
            }
            val dtext = field("dtext", HomeConfig.GTEXT_MAX, R.string.dtext_hint)
            fun words() { val st = Store.get(a).optString("gstyle"); gname.visibility = if (st == "name") View.VISIBLE else View.GONE; gtext.visibility = if (st == "own") View.VISIBLE else View.GONE
                dtext.visibility = if (Store.get(a).optString("dstyle") == "own") View.VISIBLE else View.GONE }
            // each line a row that opens its own rows under it (the user, 2026-10-27: twelve rows in a column read as one long list)
            fun fold(icon: Int, title: Int, w: String, fill: () -> Unit) {
                val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(18), 0, 0, 0) }
                val line = label("", 12f, DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                val arrow = ImageView(a).apply { setImageResource(R.drawable.ic_down); imageTintList = ColorStateList.valueOf(DIM) }
                fun say() { val c = Store.get(a); line.text = listOf(says(c.optString(w + "style").ifEmpty { if (w == "d") "date" else HomeConfig.GSTYLES[0] }),
                    a.getString(when (c.optString(w + "size")) { "s" -> R.string.h_s; "l" -> R.string.h_l; else -> R.string.h_m })).joinToString(" · ") }
                col.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), glass(16, DOOR_FOCUS, Color.WHITE))
                        addState(intArrayOf(), glass(16, DOOR_REST, DOOR_EDGE))
                    }
                    addView(ImageView(a).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(a.getString(title), 15f, Color.WHITE))
                        addView(line)
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                    addView(arrow, LinearLayout.LayoutParams(dp(22), dp(22)))
                    setOnClickListener { val open = box.visibility != View.VISIBLE; box.visibility = if (open) View.VISIBLE else View.GONE; arrow.rotation = if (open) 180f else 0f; say()
                        if (open) box.post { box.requestRectangleOnScreen(android.graphics.Rect(0, 0, box.width, minOf(box.height, dp(240)))) } }   // its first rows in sight
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
                col.addView(box, LinearLayout.LayoutParams(-1, -2))
                val was = col; col = box; fill(); col = was
                say()
            }
            fold(R.drawable.ic_pen, R.string.gstyle, "g") {
                tile(R.drawable.ic_pen, a.getString(R.string.h_what), null, "gstyle", HomeConfig.GSTYLES.map { it to says(it) }, ::words)
                for (f in listOf(gname, gtext)) col.addView(f, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
                type("g")
            }
            fold(R.drawable.ic_calendar, R.string.dstyle, "d") {
                tile(R.drawable.ic_calendar, a.getString(R.string.h_what), null, "dstyle", HomeConfig.GSTYLES.filter { it != "name" }.map { it to says(it) }, ::words)
                col.addView(dtext, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
                type("d")
            }
            words()
            section(a.getString(R.string.g_sec_shown))                // nothing on the first screen is forced: each can go (HomeConfig.SHOWS)
            tile(R.drawable.ic_timer, a.getString(R.string.show_clock), null, "showClock")
            tile(R.drawable.ic_calendar, a.getString(R.string.show_date), null, "showDate")
            tile(R.drawable.ic_wifi, a.getString(R.string.show_net), null, "showNet")
            tile(R.drawable.ic_settings, a.getString(R.string.show_gear), null, "showGear")
            tile(R.drawable.ic_plus, a.getString(R.string.show_add), null, "showAdd")
            tile(R.drawable.ic_download, a.getString(R.string.show_suggest), null, "showSuggest")
            tile(R.drawable.ic_play, a.getString(R.string.show_watch), null, "showWatch")
            tile(R.drawable.ic_input, a.getString(R.string.show_infold), null, "infold")   // default off (HomeConfig "infold")
            tile(R.drawable.ic_updown, a.getString(R.string.watch_at), null, "watchAt", HomeConfig.WATCH_AT.map { it to a.getString(when (it) { "top" -> R.string.watch_at_top; "under1" -> R.string.watch_at_under1; "tiles" -> R.string.watch_at_tiles; else -> R.string.watch_at_bottom }) })
            tile(R.drawable.ic_sparkle, a.getString(R.string.motion), null, "anim")
            tile(R.drawable.ic_eye, a.getString(R.string.show_shift), null, "shift")   // burn-in care, default off (HomeConfig "shift")
            tile(R.drawable.ic_sparkle, a.getString(R.string.c_motion), null, "motion",
                listOf("" to a.getString(R.string.c_motion_auto), "calm" to a.getString(R.string.c_motion_calm), "full" to a.getString(R.string.c_motion_full)))
            tile(R.drawable.ic_eye, a.getString(R.string.c_focus), null, "focus",
                listOf("ring" to a.getString(R.string.c_focus_ring), "glass" to a.getString(R.string.c_focus_glass)))
            section(a.getString(R.string.g_sec_grid))
            tile(R.drawable.ic_apps, a.getString(R.string.c_cols), null, "cols", (HomeConfig.COLS_MIN..HomeConfig.COLS_MAX).map { "$it" to "$it" })
            tile(R.drawable.ic_updown, a.getString(R.string.c_rows), null, "rows")
        }
        groups[3].fill = {
            val preview = PackGlyph(a)
            // the result first, where it is seen as the widgets below change (at the end it took scrolling past every widget)
            col.addView(label(a.getString(R.string.w_layout).uppercase(), 12f, DIM).apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
            col.addView(preview, LinearLayout.LayoutParams(-1, (64 * a.resources.displayMetrics.density).toInt()).apply { bottomMargin = dp(16) })
            widgetRows(a, col, gap, ::glass, ::label, preview)
            val note = conf.optString("note")
            if (note.isNotEmpty()) {                                       // written on the phone; here it can only be taken down
                val sub = label(a.getString(R.string.c_note_clear), 12f, DIM).apply { maxLines = 1 }
                col.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), glass(16, DOOR_FOCUS, Color.WHITE))
                        addState(intArrayOf(), glass(16, DOOR_REST, DOOR_EDGE))
                    }
                    addView(ImageView(a).apply { setImageResource(R.drawable.ic_message); imageTintList = ColorStateList.valueOf(0xFFFFD27A.toInt()) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(note, 15f, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(sub)
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                    onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                    setOnClickListener { Store.change(a, JSONObject().put("note", "")); sub.text = a.getString(R.string.c_note_gone); alpha = 0.6f }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            }
        }
        groups[4].fill = {
            tile(R.drawable.ic_moon, a.getString(R.string.dream), a.getString(R.string.dream_sub), "dream")
            tile(R.drawable.ic_clock, a.getString(R.string.dface), null, "dface", HomeConfig.DFACES.map { it to a.getString(when (it) { "modern" -> R.string.home_ov_modern; "digital" -> R.string.home_ov_digital; else -> R.string.dface_big }) })
            tile(R.drawable.ic_gauge, a.getString(R.string.stats), null, "stats")   // anonymous stats (OwnReport): TV Setup's switch when it is installed, else this one
            // another launcher as the home screen: TV Home has no ADB to turn the TV's own one off (without that Home goes on landing on it,
            // a BRAVIA on Android 9), so TV Setup's picker does it, opened on that screen; without TV Setup the row says how to get it
            val setup = a.packageManager.getLaunchIntentForPackage("dev.tvremote.setup")?.putExtra("open", "launchers")
            col.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), glass(16, DOOR_FOCUS, Color.WHITE))
                    addState(intArrayOf(), glass(16, DOOR_REST, DOOR_EDGE))
                }
                addView(ImageView(a).apply { setImageResource(R.drawable.ic_home); imageTintList = ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(a.getString(R.string.c_home_screen), 15f, Color.WHITE))
                    addView(label(if (setup != null) a.getString(R.string.c_home_screen_sub) else a.getString(R.string.c_home_screen_need, HomeActivity.SETUP_CODE), 12f, DIM))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                setOnClickListener {
                    val go = setup ?: a.packageManager.getLaunchIntentForPackage("com.esaba.downloader") ?: return@setOnClickListener
                    try { a.startActivity(go.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); dialog.dismiss() } catch (e: Exception) { }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        }
        groups[5].fill = {
            launchPage(a, col, ::glass, ::label, gap)
        }
        groups[6].fill = {
            // every language at once, each in its own words, the one in use ticked: one tile stepping through them hid which
            // there were, and each press built Home again before the next could be seen
            val sys = android.content.res.Resources.getSystem().locale0()
            val now = Store.get(a).optString("lang")
            for ((k, name) in (listOf("" to a.getString(R.string.c_lang_tv)) + dev.tvremote.i18n.Langs.NAMES.toList())) {
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                    setPadding(dp(16), dp(13), dp(16), dp(13))
                    background = TvGlass.rowBg(a, 16)
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(name, 16f, Color.WHITE))
                        if (k.isEmpty()) addView(label(sys.getDisplayLanguage(sys).replaceFirstChar { it.uppercase() }, 12f, DIM))   // which one that is now
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (k == now) { val (iv, sz) = icon(R.drawable.ic_check, ACCENT, dp(20)); addView(iv, LinearLayout.LayoutParams(sz, sz)) }
                    onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                    setOnClickListener { if (k != Store.get(a).optString("lang")) Store.change(a, JSONObject().put("lang", k)) }   // Home is built again in it
                }
                if (k == now) groups[6].start = { row }
                col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (col.childCount > 0) topMargin = dp(8) })
            }
        }
        fun build(g: Group) {
            val f = g.fill ?: return
            g.fill = null; g.made = f; conf = Store.get(a); col = g.col   // as it is now: another page may have changed it
            f()
            g.seen = Store.stamp(a)
            // laid out now, hidden, not in the slide's first frame: the backgrounds page's ~1100 views made it a 117 ms frame on the FPT box
            if (g.page.visibility == View.GONE) g.page.visibility = View.INVISIBLE
        }
        // a page made earlier and changed by another page since (a theme applied: the widgets page still
        // listed the widgets before it while its preview, read live, was empty) is made again as it is now
        fun fresh(g: Group) {
            val f = g.made ?: return
            if (Store.stamp(a) == g.seen) return
            val now = Store.get(a)
            g.col.removeAllViews(); g.last = null; g.start = null
            conf = now; col = g.col
            f()
            g.seen = Store.stamp(a)
        }
        // a page is made when its row has held the focus a moment (the one about to be opened), not all seven when the panel opens:
        // those were ~1300 views and +16 MB on the FPT box, and their making took the CPU from the first keys
        var moved = false                                             // the first row's focus at opening is not a choice: Themes' pictures cost ~0.4 s of CPU
        fun soon(g: Group, row: View) { if (g.fill != null && moved) row.postDelayed({ if (row.isFocused && dialog.isShowing) build(g) }, 250) }

        // the first level: a row per group (its icon in a tinted chip, the name, what it is set to now) and the way out
        top.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, 0, 0)
            addView(label(a.getString(R.string.customize), 20f, Color.WHITE).apply { typeface = Font.bold })
            addView(label(a.getString(R.string.c_sub), 12f, TvGlass.HINT))
        })
        val topScroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(top) }
        val stack = FrameLayout(a).apply { clipChildren = false }
        stack.addView(topScroll, FrameLayout.LayoutParams(-1, -1))
        var cur: Group? = null                                         // the page open, null on the first level
        var keepDim = false                                            // a dialog over this one dims Home itself: see the focus listener
        var gen = 0                                                    // a slide that was cut short by the next one does not finish its hiding
        val shift = panelW / 6f
        var still = at != null                                        // reopened on a page: it is simply there
        fun slides() = !still && Motion.on && !Motion.calm                       // calm and off: the page is simply there
        fun refresh() { val c = Store.get(a); for (g in groups) g.sum?.text = summary(a, g.key, c) }   // one read: ~7 ms each on the FPT box
        fun enter(g: Group) {
            build(g); fresh(g)
            cur = g; val n = ++gen
            // a page whose change shows on Home (background, veil, look, layout, widgets) lifts the dim: it is seen as it is
            if (g.key in PREVIEW) dialog.window?.setDimAmount(0f)
            topScroll.animate().cancel(); g.page.animate().cancel()
            topScroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS   // while it fades the keys already belong to the page
            g.page.visibility = View.VISIBLE
            (g.last?.takeIf { it.isShown } ?: g.start?.invoke() ?: firstFocusable(g.col))?.requestFocus()
            if (slides()) {
                g.page.translationX = shift; g.page.alpha = 0f
                g.page.animate().translationX(0f).alpha(1f).setDuration(Motion.ms(120)).setInterpolator(Motion.glide).withLayer().start()
                topScroll.animate().translationX(-shift / 2).alpha(0f).setDuration(Motion.ms(120)).setInterpolator(Motion.glide).withLayer()
                    .withEndAction { if (n == gen) topScroll.visibility = View.GONE }.start()
            } else { g.page.translationX = 0f; g.page.alpha = 1f; topScroll.visibility = View.GONE }
        }
        fun leave(): Boolean {
            val g = cur ?: return false
            cur = null; val n = ++gen
            dialog.window?.setDimAmount(Drop.MENU_DIM)
            g.last = g.page.findFocus()
            g.seen = Store.stamp(a)                                    // its own changes are already on it
            topScroll.animate().cancel(); g.page.animate().cancel()
            topScroll.visibility = View.VISIBLE; topScroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            refresh()
            g.row?.requestFocus()                                      // back on the group just left
            if (slides()) {
                topScroll.translationX = -shift / 2; topScroll.alpha = 0f
                topScroll.animate().translationX(0f).alpha(1f).setDuration(Motion.ms(120)).setInterpolator(Motion.glide).withLayer().start()
                g.page.animate().translationX(shift).alpha(0f).setDuration(Motion.ms(120)).setInterpolator(Motion.glide).withLayer()
                    .withEndAction { if (n == gen) g.page.visibility = View.INVISIBLE }.start()
            } else { topScroll.translationX = 0f; topScroll.alpha = 1f; g.page.visibility = View.INVISIBLE }
            return true
        }
        stepBack = ::leave
        for (g in groups) {
            val chevron = ImageView(a).apply { setImageResource(R.drawable.ic_right); imageTintList = ColorStateList.valueOf(DIM) }
            g.sum = label("", 12f, TvGlass.HINT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = TvGlass.rowBg(a, 16)
                addView(icon(g.icon, Color.WHITE, dp(26)).first, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(a.getString(g.name), 15f, Color.WHITE).apply { maxLines = 1 })
                    addView(g.sum!!)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                addView(chevron, LinearLayout.LayoutParams(dp(22), dp(22)))
                onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f); chevron.imageTintList = ColorStateList.valueOf(if (f) Color.WHITE else DIM); if (f) soon(g, v) }
                setOnClickListener { enter(g) }
                setOnKeyListener { _, code, e -> if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && e.action == android.view.KeyEvent.ACTION_DOWN) { enter(g); true } else false }
            }
            g.row = row
            top.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (g === groups[0]) dp(16) else dp(8) })
            // the page: a way back as its head ("‹ name"), then the controls
            g.head = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                contentDescription = a.getString(R.string.g_back)
                setPadding(dp(10), dp(8), dp(16), dp(8))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), glass(16, TvGlass.ROW_FOCUS, Color.WHITE))
                    addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                }
                addView(ImageView(a).apply { setImageResource(R.drawable.ic_left); imageTintList = ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(label(a.getString(g.name), 20f, Color.WHITE).apply { typeface = Font.medium }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                setOnClickListener { leave() }
            }
            g.page.addView(g.head, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(18), dp(20), 0, dp(4)) })
            g.page.addView(g.scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            stack.addView(g.page, FrameLayout.LayoutParams(-1, -1))
        }
        refresh()
        // one QR for every setting: the same page as TV Setup's settings, served by TV Home itself (ManageServer)
        top.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = TvGlass.rowBg(a, 16)
            addView(icon(R.drawable.ic_qr, Color.WHITE, dp(26)).first, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(a.getString(R.string.mg_row), 15f, Color.WHITE).apply { maxLines = 1 })
                addView(label(a.getString(R.string.mg_row_sub), 12f, TvGlass.HINT).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
            setOnClickListener { keepDim = true; PhotoScreen.show(a) { dialog.dismiss() } }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // Telegram: a plain row (TV Setup is asked only once it is opened, so Customize stays light); without TV Setup the screen says how to get it
        top.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = TvGlass.rowBg(a, 16)
            addView(icon(R.drawable.ic_send, Color.WHITE, dp(26)).first, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(a.getString(R.string.tg_row), 15f, Color.WHITE).apply { maxLines = 1 })
                addView(label(a.getString(R.string.tg_row_sub), 12f, TvGlass.HINT).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
            setOnClickListener { keepDim = true; TelegramScreen.show(a) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val done = Font.on(TextView(a)).apply {
            text = a.getString(R.string.done); textSize = 16f; setTextColor(0xFF111318.toInt()); gravity = Gravity.CENTER
            typeface = Font.medium
            isFocusable = true; isClickable = true; setPadding(dp(28), dp(11), dp(28), dp(11))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.WHITE) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xFFB8C7E6.toInt()) })
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
            setOnClickListener { dialog.dismiss() }
        }
        top.addView(done, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val panel = object : FrameLayout(a) {
            // Left from a control with nothing to its left, on a page: back to the first level (a held key does it once)
            override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
                if (cur != null && e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    val f = findFocus()
                    if (f == null) { if (e.action == android.view.KeyEvent.ACTION_DOWN) cur?.let { g -> (firstFocusable(g.col) ?: g.page)?.requestFocus() }; return true }
                    val west = f.focusSearch(View.FOCUS_LEFT)
                    if (west == null || west === cur?.head) {                  // the head sits up-left of the first control: the way back, not a neighbour
                        if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) leave(); return true
                    }
                }
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            // solid: the home screen's text must not show through
            background = TvGlass.panelBg(a)
            // no clip and no fading edge: clipped to its corners it cost the FPT box ~10 ms a frame, the ScrollView's fading edge drew
            // the whole list into a layer every frame (55 ms a frame while a tile grew); the list stops 1 dp inside the border instead
            addView(stack, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
        }
        val height = dm.heightPixels - 2 * edge
        dialog.setContentView(panel, ViewGroup.LayoutParams(panelW, height))
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(panelW, height)
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            attributes = attributes.apply { x = edge }
            setDimAmount(Drop.MENU_DIM)                                     // as the menu it came from: no flash of light between them
            setWindowAnimations(0)                                     // its own slide below, or none when motion is off
        }
        // a window of its own on top (the app picker, quick keys, a stepper, a QR) takes the dim off: the stepper's Home is then seen as it is
        // A dialog with a dim of its own (Telegram, Manage from phone: [keepDim]) keeps it on: taken off, closing that dialog showed
        // Home undimmed for a frame or two before this window had the focus back (the dim flashed off and on)
        panel.viewTreeObserver.addOnWindowFocusChangeListener { has ->
            dialog.window?.setDimAmount(if ((has || keepDim) && cur?.key !in PREVIEW) Drop.MENU_DIM else 0f)
            if (has) keepDim = false
        }
        dialog.setOnShowListener {
            groups.firstOrNull { it.key == at }?.let { enter(it); still = false; slid = true; return@setOnShowListener }
            groups[0].row?.let {
                it.requestFocus()
            }
            moved = true
            if (Motion.on) {                                           // on a layer: drawn once, then only moved and faded (drawn each frame it took 40-57 ms on the FPT box)
                panel.animate().translationX(0f).alpha(1f).setDuration(130).setInterpolator(Motion.glide).withLayer().withEndAction {
                    slid = true; for ((v, b) in waiting) v.setImageBitmap(b); waiting.clear()
                }.start()
            }
        }
        return dialog to {
            refresh()                                                  // what changed since it was built (a phone, another panel)
            // before show: set in the show listener, the first frame had shown it in place. Here, not when built: motion may have changed since
            if (Motion.on && at == null) { panel.translationX = panelW / 3f; panel.alpha = 0f }
        }
    }

    /**
     * Two doors to full screens built of tvui tiles: "Add to home screen" ([Picker] in grid mode: apps, their shortcuts, live widgets,
     * the hidden apps) and "Quick keys" ([QuickKeys]: 0-9, colour keys and the power-on app, each shown by the logo of its [Target]).
     * They replaced a row list here that could not show logos nor shortcuts. Lines under say what needs TV Setup.
     */
    private fun launchPage(a: Activity, col: LinearLayout, glass: (Int, Int, Int) -> GradientDrawable, label: (String, Float, Int) -> TextView, gap: Int) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        fun door(icon: Int, title: String, sub: () -> String, open: () -> Unit) {
            val line = label(sub(), 12f, DIM).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            col.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), glass(16, DOOR_FOCUS, Color.WHITE))
                    addState(intArrayOf(), glass(16, DOOR_REST, DOOR_EDGE))
                }
                addView(ImageView(a).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, 15f, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(line)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                setOnClickListener { open() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        }
        door(R.drawable.ic_apps, a.getString(R.string.c_add_home), { a.getString(R.string.pk_grid_hint) }) { Picker.show(a, Picker.Mode.GRID, a.getString(R.string.c_add_home)) }
        door(R.drawable.ic_pen, a.getString(R.string.el_edit), { a.getString(R.string.el_edit_sub) }) { panelDialog?.dismiss(); (a as? HomeActivity)?.edit() }
        door(R.drawable.ic_keyboard, a.getString(R.string.c_quickkeys), { a.getString(if (Keys.setup(a)) R.string.k_everywhere else R.string.k_need_setup) }) { QuickKeys.show(a) }
    }

    /** The screensaver switch cannot write the setting (no WRITE_SECURE_SETTINGS yet; a phone or computer grants it): the system's own screen for it instead. */
    private fun systemDream(a: Activity) {
        val go = {
            try { a.startActivity(android.content.Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)) }
            catch (e: Exception) { dev.tvremote.home.apps.SystemSettings.open(a) }
        }
        // a Toast was hidden by the settings screen opened at once; a question the person reads, then goes on
        android.app.AlertDialog.Builder(a, R.style.Menu).setMessage(R.string.dream_system).setPositiveButton(R.string.ok) { _, _ -> go() }.showOnOk()
    }

    private const val GLYPH_SOLID = 0xE60E1016.toInt(); private const val GLYPH_NONE = 0x14FFFFFF   // a "solid" widget, darker than the strip; "none": only its place
    private const val GLYPH_TONAL = 0xCCF6E9E0.toInt(); private const val GLYPH_PALE = 0xCCF4F4F6.toInt(); private const val GLYPH_INK = 0xFF2A2320.toInt()   // the strip's widgets in the tonal look / a light clock
    private const val CHIP_FOCUS = 0x2EFFFFFF; private const val CHIP_REST = 0x12FFFFFF; private const val CHIP_EDGE = 0x1FFFFFFF   // a widget row's look button, as its size button
    private val WIDGET_ROWS = listOf(                                  // key, icon, name, line under it
        arrayOf<Any>("system", R.drawable.ic_gauge, R.string.w_system, R.string.c_system_sub),
        arrayOf<Any>("usage", R.drawable.ic_chart, R.string.w_usage, R.string.c_usage_sub),
        arrayOf<Any>("calendar", R.drawable.ic_calendar, R.string.w_calendar, R.string.c_calendar_sub),
        arrayOf<Any>("lunar", R.drawable.ic_moon, R.string.w_lunar, R.string.c_lunar_sub),
        arrayOf<Any>("timer", R.drawable.ic_timer, R.string.w_timer, R.string.c_timer_sub),
        arrayOf<Any>("weather", R.drawable.ic_weather, R.string.w_weather, R.string.c_weather_sub),
        arrayOf<Any>("air", R.drawable.ic_wind, R.string.w_air, R.string.c_air_sub),
        arrayOf<Any>("recent", R.drawable.ic_apps, R.string.w_recent, R.string.c_recent_sub),
        arrayOf<Any>("adblock", R.drawable.ic_shield_ok, R.string.w_adblock, R.string.c_adblock_sub),
        arrayOf<Any>("market", R.drawable.ic_chart, R.string.w_market, R.string.c_market_sub),
        arrayOf<Any>("news", R.drawable.ic_message, R.string.w_news, R.string.c_news_sub),
        arrayOf<Any>("clock", R.drawable.ic_clock, R.string.w_clock, R.string.c_clock_sub),
    )

    private val QR_ROW = arrayOf<Any>("qr", R.drawable.ic_qr, R.string.w_qr, R.string.c_qr_sub)

    /** What a QR widget holds, in a word: "Wi-Fi", "Link" … (its fields are written on the phone or the web page). */
    private fun qrKind(a: Context, widget: JSONObject) = a.getString(when (widget.optString("tpl")) {
        "wifi" -> R.string.qr_t_wifi; "link" -> R.string.qr_t_link; "tel" -> R.string.qr_t_tel; "sms" -> R.string.qr_t_sms
        "mail" -> R.string.qr_t_mail; "contact" -> R.string.qr_t_contact; else -> R.string.qr_t_text })

    /**
     * The widgets in the order they have on the screen, the chosen ones first. OK on a widget switches it; the button at its right
     * is the move mode: OK turns it on, Up / Down move the widget, OK or Back (or leaving it) turn it off. One column, so Up / Down
     * mean the same as on the screen's list, and a widget switched on goes to the end.
     */
    private fun widgetRows(a: Activity, col: LinearLayout, gap: Int, glass: (Int, Int, Int) -> GradientDrawable, label: (String, Float, Int) -> TextView, preview: View) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(box, LinearLayout.LayoutParams(-1, -2))
        var moving: String? = null; var building = false
        fun fill(focus: String? = null, focusMove: Boolean = false, focusGear: Boolean = false) {
            building = true                                            // taking the rows down loses their focus: not a wander
            box.removeAllViews()
            val on = Store.list(a, "widgets")
            val conf = Store.get(a)
            val qrs = conf.optJSONObject("qrs") ?: JSONObject()
            val order = (on + HomeConfig.WIDGETS.filter { it !in on }).filter { (it !in HomeQr.KEYS || qrs.has(it)) && (it != "adblock" || android.os.Build.VERSION.SDK_INT >= HomeConfig.ADBLOCK_MIN_SDK) }   // Private DNS is Android 9+; a QR widget is written on the phone or the web page: only those that exist
            val big = HomeConfig.bigs(conf)
            val waiting = HomeConfig.pack(on, big, conf.optString("note").isNotEmpty()).waiting.toSet()
            var want: View? = null
            order.forEachIndexed { i, k ->
                val r = WIDGET_ROWS.firstOrNull { it[0] == k } ?: QR_ROW
                val qr = if (k in HomeQr.KEYS) qrs.getJSONObject(k) else null
                val isOn = k in on
                val line = LinearLayout(a).apply { clipChildren = false; clipToPadding = false }
                val ic = ImageView(a).apply { setImageResource(r[1] as Int); imageTintList = ColorStateList.valueOf(if (isOn) ACCENT else DIM) }
                val state = label("", 12f, if (isOn) ACCENT else DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                val sub = if (qr != null) qrKind(a, qr) else a.getString(r[3] as Int)
                val word = a.getString(if (isOn) R.string.w_on else R.string.w_off).replaceFirstChar { it.uppercase() }
                // on: what the gear holds (Big · Minimal · Light); off: what the widget is
                state.text = if (moving == k) a.getString(R.string.c_moving) else if (k in waiting) "$word · ${a.getString(R.string.c_waits)}" else if (isOn) "$word · ${WidgetSettings.summary(a, conf, k)}" else "$word · $sub"
                val t = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), glass(16, if (isOn) 0x408AB4F8 else 0x2EFFFFFF, Color.WHITE))
                        addState(intArrayOf(), glass(16, if (isOn) 0x268AB4F8 else 0x12FFFFFF, if (isOn) ACCENT else 0x1FFFFFFF))
                    }
                    addView(ic, LinearLayout.LayoutParams(dp(26), dp(26)))
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(if (qr != null) a.getString(R.string.w_qr_named, qr.optString("cap").ifEmpty { qrKind(a, qr) }) else a.getString(r[2] as Int), 15f, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(state)
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                    onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                    setOnClickListener {
                        moving = null
                        Store.putList(a, "widgets", if (isOn) on - k else on + k)
                        fill(k)
                    }
                }
                line.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                if (isOn) {                                            // one gear: the widget's own settings, drawn from HomeConfig.options, with the widget beside them
                    val gear = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                        contentDescription = a.getString(R.string.c_wsettings)
                        background = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), glass(16, CHIP_FOCUS, Color.WHITE))
                            addState(intArrayOf(), glass(16, CHIP_REST, CHIP_EDGE))
                        }
                        addView(ImageView(a).apply { setImageResource(R.drawable.ic_gear); imageTintList = ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                        onFocusChangeListener = View.OnFocusChangeListener { v, f -> lit(v, f) }
                        setOnClickListener {
                            moving = null
                            val title = if (qr != null) a.getString(R.string.w_qr_named, qr.optString("cap").ifEmpty { qrKind(a, qr) }) else a.getString(r[2] as Int)
                            WidgetSettings.show(a, k, title) { preview.invalidate(); fill(k, focusGear = true) }
                        }
                    }
                    line.addView(gear, LinearLayout.LayoutParams(dp(56), -1).apply { leftMargin = dp(8) })
                    if (focus == k && focusGear) want = gear
                }
                if (isOn && on.size > 1) {
                    val m = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                        contentDescription = a.getString(R.string.c_move)
                        val live = moving == k
                        background = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), glass(16, if (live) 0x668AB4F8 else 0x2EFFFFFF, Color.WHITE))
                            addState(intArrayOf(), glass(16, if (live) 0x408AB4F8 else 0x12FFFFFF, if (live) ACCENT else 0x1FFFFFFF))
                        }
                        for (res in intArrayOf(R.drawable.ic_up, R.drawable.ic_down)) addView(ImageView(a).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(if (live) ACCENT else DIM) }, LinearLayout.LayoutParams(dp(18), dp(18)))
                        onFocusChangeListener = View.OnFocusChangeListener { v, f ->
                            lit(v, f)
                            if (!f && !building && moving == k) { moving = null; v.postDelayed({ if (v.isAttachedToWindow) fill() }, 0) }   // wandered off: back to plain
                        }
                        setOnClickListener { moving = if (moving == k) null else k; fill(k, true) }
                        setOnKeyListener { _, code, ev ->
                            if (moving != k) return@setOnKeyListener false
                            val j = when (code) { android.view.KeyEvent.KEYCODE_DPAD_UP -> on.indexOf(k) - 1; android.view.KeyEvent.KEYCODE_DPAD_DOWN -> on.indexOf(k) + 1; else -> -2 }
                            if (code == android.view.KeyEvent.KEYCODE_BACK) { if (ev.action == android.view.KeyEvent.ACTION_UP) { moving = null; fill(k, true) }; return@setOnKeyListener true }
                            if (j == -2) return@setOnKeyListener false
                            if (ev.action == android.view.KeyEvent.ACTION_DOWN && j in on.indices) {
                                val n = on.toMutableList(); n[on.indexOf(k)] = n[j]; n[j] = k
                                Store.putList(a, "widgets", n); fill(k, true)
                            }
                            true
                        }
                    }
                    line.addView(m, LinearLayout.LayoutParams(dp(56), -1).apply { leftMargin = dp(8) })
                    if (focus == k && focusMove) want = m
                }
                if (focus == k && !focusMove && !focusGear) want = t
                box.addView(line, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = gap })
            }
            want?.requestFocus()
            preview.invalidate()
            building = false
        }
        fill()
    }

    /**
     * The result in miniature: the columns [HomeConfig.pack] makes of the widgets on, a big widget in the accent, small ones pale; what
     * waits is not drawn. Reads the settings when painted (the rows call invalidate after every change), never animated.
     */
    private var panelDialog: Dialog? = null
    private var panelOwner: Activity? = null
    /** The panel is hidden (not closed) while a widget's settings are open over it, and back when they close. */
    internal fun hidePanel(hide: Boolean) { panelDialog?.window?.decorView?.alpha = if (hide) 0f else 1f }

    internal class PackGlyph(c: Context) : View(c) {
        /** Each block shows its widget's icon and name (the settings dialog's strip); the Widget page's own preview stays plain. */
        var named = false
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        /** A widget drawn with an accent edge (the one whose settings are open), and each widget's own look: tonal warm, a light clock pale. */
        var mark: String? = null
        private fun drawName(cv: Canvas, k: String, x: Float, y: Float, w: Float, h: Float, lightBlock: Boolean) {
            val r = WIDGET_ROWS.firstOrNull { it[0] == k } ?: QR_ROW
            val d = resources.displayMetrics.density
            val ink = if (lightBlock) GLYPH_INK else Color.WHITE
            val ic = context.getDrawable(r[1] as Int)?.mutate()
            val s = minOf(h * 0.45f, 22 * d)
            ic?.setTint(ink); ic?.setBounds((x + 8 * d).toInt(), (y + (h - s) / 2).toInt(), (x + 8 * d + s).toInt(), (y + (h + s) / 2).toInt()); ic?.draw(cv)
            paint.style = Paint.Style.FILL; paint.color = ink; paint.textSize = 12 * resources.displayMetrics.scaledDensity; paint.textAlign = Paint.Align.LEFT
            val room = w - s - 20 * d
            if (room < 24 * d) return
            val name = context.getString(r[2] as Int)
            val n = paint.breakText(name, true, room, null)
            cv.drawText(if (n < name.length) name.take(maxOf(1, n - 1)) + "…" else name, x + 14 * d + s, y + h / 2 - (paint.descent() + paint.ascent()) / 2, paint)
        }

        override fun onDraw(cv: Canvas) {
            val conf = Store.get(context)
            val big = HomeConfig.bigs(conf)
            val p = HomeConfig.pack(Store.list(context, "widgets"), big, conf.optString("note").isNotEmpty())
            val g = resources.displayMetrics.density * 4f
            val sum = maxOf(HomeConfig.FLOOR, p.cols.sumOf { it.weight })
            if (p.cols.isEmpty()) {                                         // nothing on: the frame of an empty row and why, not a bare title over nothing
                paint.style = Paint.Style.STROKE; paint.strokeWidth = g / 2; paint.color = DOOR_EDGE
                paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(g * 2, g * 1.5f), 0f)
                cv.drawRoundRect(g / 2, g / 2, width - g / 2, height - g / 2, g * 2, g * 2, paint)
                paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.color = DIM; paint.textSize = 14 * resources.displayMetrics.scaledDensity
                paint.textAlign = Paint.Align.CENTER
                cv.drawText(context.getString(R.string.w_layout_empty), width / 2f, height / 2f - (paint.descent() + paint.ascent()) / 2, paint)
                return
            }
            val room = width - g * (p.cols.size - 1)
            var x = 0f
            for (col in p.cols) {
                val w = room * col.weight / sum
                val h = (height - g * (col.keys.size - 1)) / col.keys.size
                col.keys.forEachIndexed { j, k ->
                    val look = HomeConfig.optValue(conf, k, "look"); val tonal = look == "tonal"
                    val pale = k == "clock" && HomeConfig.optValue(conf, k, "theme") == "light"
                    paint.style = Paint.Style.FILL
                    paint.color = if (tonal) GLYPH_TONAL else if (pale) GLYPH_PALE else if (look == "solid") GLYPH_SOLID else if (look == "none") GLYPH_NONE else if (k in big) 0x668AB4F8 else 0x30FFFFFF
                    cv.drawRoundRect(x, j * (h + g), x + w, j * (h + g) + h, g * 2, g * 2, paint)
                    if (named) drawName(cv, k, x, j * (h + g), w, h, pale || tonal)
                    if (k == mark) {
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = g * 0.6f; paint.color = ACCENT
                        cv.drawRoundRect(x, j * (h + g), x + w, j * (h + g) + h, g * 2, g * 2, paint)
                    }
                }
                x += w + g
            }
        }
    }

    /** The backgrounds' small pictures, kept between openings of the panel (about 400 KB each); let go when memory is short ([forget]). */
    private val thumbs: MutableMap<String, Bitmap> = java.util.Collections.synchronizedMap(HashMap())
    fun forget() { thumbs.clear(); ThemePreview.forget() }

    /** A background tile's width in px, the same as [show] lays them out: five to a row in the panel. */
    // 460 dp, not 600: the rows' words take under half of 600, and a wider panel hid most of the Home its changes show on
    private fun panelW(a: Activity): Int = a.resources.displayMetrics.let { minOf(it.widthPixels - (64 * it.density).toInt(), (460 * it.density).toInt()) }
    private const val WALL_COLS = 4

    private fun tileW(a: Activity): Int {
        val d = a.resources.displayMetrics.density
        val inner = panelW(a) - (56 * d).toInt()
        return (inner - (WALL_COLS - 1) * (10 * d).toInt()) / WALL_COLS
    }

    /** A new photo or another day's Bing picture is another key: made again. */
    private fun thumbKey(a: Context, k: String, w: Int) = "$k:$w" + (Walls.file(a, k)?.let { ":" + it.name + it.lastModified() } ?: "")

    /** [k]'s tile picture, made once at the tile's size with its corners (off the main thread). */
    private fun thumb(a: Context, k: String, w: Int): Bitmap? {
        val key = thumbKey(a, k, w)
        thumbs[key]?.let { return it }
        val b = (Suggest.byKey(k)?.let { Suggest.picture(a, it, w) } ?: if (k.startsWith(Suggest.PREFIX)) null else Walls.small(a, k, w, w * 9 / 16))?.let { round(it, w, w * 9 / 16, 12 * a.resources.displayMetrics.density) } ?: return null
        synchronized(thumbs) { thumbs.keys.removeAll { it.startsWith("$k:") }; thumbs[key] = b }
        return b
    }

    /** Makes the tiles' pictures beforehand (Home calls it on its own thread once it is shown), so the panel opens with them: ~100 KB each. */
    fun warm(a: Activity) {
        val w = tileW(a)
        val have = Walls.photos(a)
        for (k in HomeConfig.WALLS) if (WallArt.has(k) || k == HomeConfig.BING || k in have) thumb(a, k, w)
        for (v in Videos.list(a)) thumb(a, v.wall, w)
    }

    /** [b] cropped to [w] x [h] from its middle, with rounded corners, so its tile needs no clip or scaling of its own. */
    private fun round(b: Bitmap, w: Int, h: Int, r: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val k = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setScale(k, k); postTranslate((w - b.width * k) / 2, (h - b.height * k) / 2) })
            }
        }
        Canvas(out).drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, p)
        b.recycle()
        return out
    }

    private fun ring(d: Float, w: Int, color: Int) = GradientDrawable().apply { cornerRadius = 12 * d; setStroke((w * d).toInt(), color); setColor(Color.TRANSPARENT) }

    /** What a theme sets, as short name / value rows (read only): the long press on a theme tile. */
    private fun themeRows(a: Context, conf: JSONObject): List<Pair<String, String>> {
        fun onOff(v: Boolean) = a.getString(if (v) R.string.w_on else R.string.w_off).replaceFirstChar { it.uppercase() }
        val set = conf.optJSONArray("walls"); val sizes = conf.optJSONObject("wsize")
        val wall = wallName(a, conf.optString("wall")) + if (set != null && set.length() > 1)
            " · " + a.getString(R.string.g_bg_set, set.length(), a.getString(EVERY_WORDS.getValue(conf.optString("wevery", "off"))).lowercase()) else ""
        val ws = conf.optJSONArray("widgets"); val widgets = (0 until (ws?.length() ?: 0)).map { ws!!.getString(it) }.map { k ->
            val n = a.resources.getIdentifier("w_$k", "string", a.packageName).let { if (it != 0) a.getString(it) else k }
            val parts = listOf(a.getString(if (sizes?.optString(k) == "big") R.string.th_big else R.string.th_small)) + WidgetSettings.looks(a, conf, k)
            "$n (${parts.joinToString(", ")})"
        }
        return listOf(
            a.getString(R.string.bg) to wall,
            a.getString(R.string.c_shade) to "${conf.optInt("shade", HomeConfig.SHADE_DEFAULT)}%",
            a.getString(R.string.w_widgets) to (if (widgets.isEmpty()) a.getString(R.string.th_none) else widgets.joinToString(", ")),
            a.getString(R.string.c_cols) to "${conf.optInt("cols", HomeConfig.COLS_DEFAULT)}",
            a.getString(R.string.c_rows) to rowsWord(a, conf.optDouble("rows", 0.0)),
            a.getString(R.string.c_focus) to a.getString(if (conf.optString("focus") == "ring") R.string.c_focus_ring else R.string.c_focus_glass),
            a.getString(R.string.motion) to onOff(conf.optBoolean("anim", true)),
            a.getString(R.string.c_motion) to a.getString(when (conf.optString("motion")) { "calm" -> R.string.c_motion_calm; "full" -> R.string.c_motion_full; else -> R.string.c_motion_auto }),
            a.getString(R.string.clock24) to onOff(conf.optBoolean("clock24", true)),
            a.getString(R.string.greet) to onOff(conf.optBoolean("greet", true)),
        )
    }

    /** Shows a dialog whose main action is one button with the focus on it: one press of OK answers it (never for a delete: those keep the safe choice). */
    private fun android.app.AlertDialog.Builder.showOnOk() = create().also { d ->
        d.setOnShowListener { d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
        d.show()
    }

    /** A theme's name: the person's own as saved, a ready-made one from the strings. */
    private fun themeName(a: Context, c: JSONObject, id: String): String {
        if (id == HomeConfig.BEFORE) return a.getString(R.string.th_before)
        c.optJSONArray("themes")?.let { m -> for (i in 0 until m.length()) m.getJSONObject(i).let { if (it.optString("id") == id) return it.optString("name") } }
        return HomeConfig.ready.firstOrNull { it.id == id }?.name(a.resources.locale0().language) ?: id
    }

    /** The name under a background's tile: from the strings, "Photo 3" for the person's third photo. */
    private fun wallName(a: Context, k: String): String = when {
        k == "photo" -> a.getString(R.string.wall_photo)
        HomeConfig.isPhoto(k) -> a.getString(R.string.wall_photo_n, k.removePrefix("photo").toInt())
        HomeConfig.isVideo(k) -> Videos.list(a).firstOrNull { it.wall == k }?.name?.takeIf { it.isNotBlank() } ?: a.getString(R.string.wall_video)
        else -> a.getString(a.resources.getIdentifier("wall_$k", "string", a.packageName))
    }
}

/** FrameLayout.setForeground exists since API 1; lint reads the Kotlin property as View's (API 23). */
@android.annotation.TargetApi(23)
private fun android.widget.FrameLayout.fg(d: android.graphics.drawable.Drawable) { foreground = d }
