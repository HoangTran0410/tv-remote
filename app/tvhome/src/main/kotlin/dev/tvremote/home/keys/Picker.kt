package dev.tvremote.home.keys

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tvremote.home.Font
import dev.tvremote.home.HomeActivity
import dev.tvremote.home.manage.ManageServer
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.R
import dev.tvremote.home.Store
import dev.tvremote.home.Target
import dev.tvremote.home.apps.App
import dev.tvremote.home.apps.Apps
import dev.tvremote.home.apps.LinkApps
import dev.tvremote.home.apps.Links
import dev.tvremote.home.apps.Pins
import dev.tvremote.home.apps.LiveWidgets
import dev.tvremote.home.apps.wide
import dev.tvremote.tvui.Glass
import dev.tvremote.tvui.Motion
import dev.tvremote.tvui.TvGlass
import dev.tvremote.tvui.TvTile
import dev.tvremote.tvui.TvUi
import org.json.JSONArray
import org.json.JSONObject

internal const val PK_DIM = 0xA6FFFFFF.toInt()                      // a section name, a hint: white at about two thirds
internal const val PK_SOFT = 0xB3FFFFFF.toInt()
internal const val PK_FAINT = 0x99FFFFFF.toInt()
/**
 * A full-screen picker is a page of one solid colour, not a panel over a dimmed Home: Home and the Customize panel showing through
 * were blended again on every frame of a focus move (emulator: 14-26% of the frames late, 6% solid; Home's own grid 0.4%).
 */
internal fun solidPage(w: android.view.Window) {
    w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(TvGlass.PANEL_BOTTOM))
    w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    w.setLayout(-1, -1); w.setGravity(Gravity.CENTER); w.setWindowAnimations(0)
}
internal const val PK_EMPTY = 0xFF232838.toInt()                     // a quick key with nothing on it
private const val PK_BAD = 0xFFFF8A80.toInt()                         // a link that could not be pinned
private const val PK_OK = 0xFF7BE0A1.toInt()                          // "connected", "pinned" under the QR card
private const val PK_CHECK = 0xFF2F7DF6.toInt()
private const val PK_TAG = 0xCC101420.toInt()
private const val PK_BANNER_BACK = 0xFF1E2230.toInt()
private val PK_HUES = intArrayOf(0xFF27324A.toInt(), 0xFF2E3B2F.toInt(), 0xFF43293A.toInt(), 0xFF2B3A45.toInt(), 0xFF3D3424.toInt(), 0xFF2F2D4A.toInt())   // a tile without a picture: a colour from its key

/**
 * One tile of the picker and of the quick keys: the logo of a [Target] (an app's banner or icon, a shortcut's picture, a live widget's
 * mark) on a colour of its own, its name inside when it has no wide picture, and TV Home's lift and ring ([TvTile]), never the
 * glass ([glassy]). [tag] is a small pill on a corner, [mark] a check, [dead] a tile that cannot be used (greyed, no focus).
 */
class PickTile(c: Context, w: Int, private val dpx: (Number) -> Int, val key: String, label: String, art: App?, hue: String,
               tag: String = "", icon: Int = 0, plus: Boolean = false, color: Int = 0, flat: Boolean = false) : TvTile(c, w, dpx, if (flat) 78 else 56) {
    private val check = ImageView(c).apply { setImageResource(R.drawable.ic_check); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PK_CHECK) }; setPadding(dpx(3), dpx(3), dpx(3), dpx(3)); visibility = View.GONE }
    private val tagView = Font.on(TextView(c)).apply { textSize = 11f; setTextColor(Color.WHITE); maxLines = 1; setPadding(dpx(7), dpx(2), dpx(7), dpx(2))
        background = GradientDrawable().apply { cornerRadius = dpx(9).toFloat(); setColor(PK_TAG) }; visibility = View.GONE }
    private val h = w * (if (flat) 78 else 56) / 100
    override val grows get() = false                                  // the ring alone: growing, 24 moves cost ~8.4 s of CPU and ~610 frames on the FPT box, ~1.2 s and ~27 without
    override val glassy get() = false                                 // a window over Home: the glass's layer, composed over two see-through windows, made half the frames late (emulator: 50% janky, 48 ms median)
    var deadAlpha = 0.38f                                             // the key pad keeps a missing key's name readable
    var dead = false
        set(v) { field = v; isFocusable = !v; isClickable = !v; alpha = if (v) deadAlpha else 1f }

    init {
        Font.on(name)
        setOnFocusChangeListener { _, on -> focus(on) }
        val banner = if (flat) null else art?.banner       // flat: the key pad has a chip on top, so an icon and a name, never a banner
        faceColor(if (banner != null) PK_BANNER_BACK else if (color != 0) color else shade(hue))
        if (banner != null) {
            fill(ImageView(c).apply { setImageDrawable(banner.constantState?.newDrawable(c.resources) ?: banner); scaleType = ImageView.ScaleType.CENTER_CROP })
            name.text = label
        } else {
            val inner = LinearLayout(c).apply { orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dpx(6), dpx(if (tag.isEmpty()) 4 else if (flat) 28 else 22), dpx(6), dpx(4)) }   // a tag sits on the top edge: the picture starts under it
            val pic = if (flat) art?.banner ?: art?.icon else art?.icon   // a key pad tile: the banner when there is one, it is what people know the app by
            val side = if (flat) h - dpx(28 + 20 + 3) else h * 42 / 100            // under the chip, over the name
            val wide = flat && art?.banner != null && pic === art.banner
            inner.addView(ImageView(c).apply {
                if (pic != null) { setImageDrawable(pic.constantState?.newDrawable(c.resources) ?: pic); scaleType = ImageView.ScaleType.FIT_CENTER }   // a copy: the grid's tile has the same picture
                else { setImageResource(if (plus) R.drawable.ic_plus else icon.takeIf { it != 0 } ?: R.drawable.ic_apps); imageTintList = android.content.res.ColorStateList.valueOf(if (plus) PK_FAINT else Color.WHITE) }
            }, LayoutParams(if (wide) side * 16 / 9 else if (plus) side * 3 / 4 else side, if (plus) side * 3 / 4 else side))
            if (label.isNotEmpty()) inner.addView(Font.on(TextView(c)).apply {
                text = label; textSize = 12f; setTextColor(if (plus) PK_SOFT else Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setPadding(0, dpx(if (flat) 1 else 4), 0, 0)
            }, LayoutParams(-1, -2))
            fill(inner)
            name.text = ""
        }
        face.addView(check, FrameLayout.LayoutParams(dpx(22), dpx(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dpx(6), dpx(6), 0) })
        face.addView(tagView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dpx(6), dpx(6), 0, 0) })
        if (tag.isNotEmpty()) { tagView.text = tag; tagView.visibility = View.VISIBLE }
    }

    fun mark(on: Boolean) { check.visibility = if (on) View.VISIBLE else View.GONE }
    fun tag(t: String) { tagView.text = t; tagView.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE }
    val wide get() = h

    private fun shade(k: String): Int {
        return PK_HUES[(k.hashCode() and Int.MAX_VALUE) % PK_HUES.size]
    }
}

/**
 * "Add to home screen" and the choice of what a key or the power-on opens: one screen listing the apps (the hidden ones too), the
 * launcher shortcuts each app offers ([Pins]) and the live widgets ([LiveWidgets]), made of [PickTile]s with a search over their names. In
 * [Mode.GRID] a press switches the tile on the app grid or off; in [Mode.TARGET] it returns the chosen [Target] (or "" for none).
 */
object Picker {
    enum class Mode { GRID, TARGET, CHOOSE }   // CHOOSE: the apps alone, many ticked at once, applied by Done (the first run's "which apps on Home", reopened from + Add)

    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "home-picker").apply { isDaemon = true } }

    private class Item(val key: String, val label: String, val app: App?, val tag: String, val section: Int, val icon: Int = 0, val link: Links.Item? = null)

    private fun fold(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('đ', 'd').replace('Đ', 'D').lowercase()

    /** [pick]: CHOOSE for a new folder: nothing ticked at first, at most [max], Done hands the apps over instead of changing the grid. */
    /** [only]: an app's ([Links.Item.group]) channels alone, from "+ Add"'s "Pin a VTVgo channel". [paste]: a card on top with a QR to TV Home's own page, where a link is pasted from a phone ([ManageServer.PASTE_HASH]). */
    fun show(a: Activity, mode: Mode, title: String, now: String = "", only: String? = null, paste: Boolean = false, actions: Boolean = false, pick: ((List<String>) -> Unit)? = null, max: Int = Int.MAX_VALUE, onPick: (String) -> Unit = {}): Dialog {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        val dm = a.resources.displayMetrics
        val dialog = Dialog(a, R.style.Menu)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panelW = minOf(dm.widthPixels - dp(64), dp(1180))
        val cols = Store.get(a).optInt("cols", HomeConfig.COLS_DEFAULT)   // the grid's own: a tile here looks like the one it puts there
        val gap = dp(dev.tvremote.home.GRID_GAP)                      // Home's: a focused tile's name shows under it, 26 put it under the next row
        val tileW = (panelW - dp(2 * 34) - (cols - 1) * gap) / cols

        val area = FrameLayout(a).apply { clipChildren = false; clipToPadding = false }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(34), dp(24), dp(34), dp(70)) }   // room for a focused tile to grow past the first and last row
        area.addView(list, FrameLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(area) }
        val search = TvUi.field(a, a.getString(R.string.pk_search), 16f, EditorInfo.IME_ACTION_SEARCH, TvUi.FIELD_REST, TvUi.R_BUTTON, 18, 10, R.drawable.ic_search)
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(34), dp(58), dp(34), dp(10))   // 32 dp above the panel before, now inside it
            addView(Font.on(TextView(a)).apply { text = title; textSize = 26f; setTextColor(Color.WHITE); typeface = Font.bold; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(search, LinearLayout.LayoutParams(dp(300), dp(46)))
        }
        val note = Font.on(TextView(a)).apply { textSize = 13f; setTextColor(PK_DIM); setPadding(dp(34), 0, dp(34), dp(4)); text = a.getString(when (mode) { Mode.GRID -> R.string.pk_grid_hint; Mode.CHOOSE -> if (pick != null) R.string.folder_choose_hint else R.string.pk_choose_hint; else -> R.string.pk_target_hint }) }
        val linkOnly = only == "link"                                    // "+ Add > Add a link": the QR is the page; a search box and the grid's hint said nothing there
        if (linkOnly) { search.visibility = View.GONE; note.visibility = View.INVISIBLE }   // the note keeps its room for the link's answer: the page jumped when it came
        // a link typed here, or pasted: the TV's keyboard, or a phone's (the TV Remote app types into the field in focus); same pin as the phone's page
        val linkBox = TvUi.field(a, a.getString(R.string.link_type_hint), 16f, EditorInfo.IME_ACTION_DONE, TvUi.FIELD_REST, TvUi.R_BUTTON, 18, 10, R.drawable.ic_link)
        fun pinTyped() {
            val raw = linkBox.text.toString().trim(); if (raw.isEmpty()) { linkBox.raise(); return }
            val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)) raw else "https://$raw"   // "youtube.com/watch?v=…" typed without its scheme
            if (!url.contains(':') || (url.startsWith("http", true) && Uri.parse(url).host?.contains('.') != true)) {   // "abc" became https://abc and was pinned
                note.visibility = View.VISIBLE; note.setTextColor(PK_BAD); note.text = a.getString(R.string.pq_web_bad); return
            }
            note.visibility = View.VISIBLE; note.setTextColor(PK_SOFT); note.text = a.getString(R.string.pk_loading)
            bg.execute {
                val code = dev.tvremote.home.manage.HomeApi.pin(a, JSONObject().put("url", url))
                a.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    note.setTextColor(if (code == "ok") PK_OK else PK_BAD)
                    note.text = a.getString(when (code) { "ok" -> R.string.link_added; dev.tvremote.home.apps.YouTube.NO_LINK -> R.string.pq_web_bad; dev.tvremote.home.apps.YouTube.NO_APP -> R.string.link_no_app; else -> R.string.watch_pin_fail })
                    if (code == "ok") { linkBox.setText(""); (a as? HomeActivity)?.let { h -> h.window.decorView.postDelayed({ if (!h.isDestroyed) h.pinsRead() }, 600) } }
                }
            }
        }
        linkBox.done = { if (linkBox.text.isNotBlank()) pinTyped() }
        val chosen = LinkedHashSet<String>()                             // CHOOSE: the apps ticked now (not saved until Done)
        var allKeys = emptyList<String>()
        val doneBtn = TvUi.button(a, "", {}); val allBtn = TvUi.button(a, "", {})
        fun labels() {
            doneBtn.text = a.getString(R.string.pk_choose_done, chosen.size)
            allBtn.text = a.getString(if (allKeys.isNotEmpty() && chosen.containsAll(allKeys)) R.string.pk_choose_none else R.string.pk_choose_all)
        }
        val bar = LinearLayout(a).apply {                                 // buttons that look like buttons, above the tiles
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(34), dp(4), dp(34), dp(8))
            if (mode == Mode.CHOOSE) { addView(doneBtn, LinearLayout.LayoutParams(-2, dp(46))); if (pick == null) addView(allBtn, LinearLayout.LayoutParams(-2, dp(46)).apply { leftMargin = dp(14) }) }
            else if (linkOnly) { addView(linkBox, LinearLayout.LayoutParams(0, dp(46), 1f)); addView(TvUi.button(a, a.getString(R.string.link_add)) { pinTyped() }, LinearLayout.LayoutParams(-2, dp(46)).apply { leftMargin = dp(14) }) }
            else visibility = View.GONE
        }
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(head); addView(note); addView(bar); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        dialog.setContentView(FrameLayout(a).apply { addView(panel, FrameLayout.LayoutParams(panelW, -1, Gravity.CENTER_HORIZONTAL)) })   // centred: a bare content view sat at the left; to the bottom edge: 64 dp short, the list stopped on an empty band
        dialog.window?.let(::solidPage)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)   // the list first; the keyboard only on OK in the search
        search.isFocusable = false                                     // not the first thing focused while the list loads (it took the keyboard up with it)
        list.addView(Font.on(TextView(a)).apply { text = a.getString(R.string.pk_loading); textSize = 15f; setTextColor(PK_DIM); setPadding(0, dp(24), 0, 0) })   // until the first redraw: an empty page read as stuck

        val items = ArrayList<Item>()
        var pasteCard: View? = null
        val tiles = java.util.concurrent.ConcurrentHashMap<String, PickTile>()   // the rest are made off the main thread while the first rows show
        val sections = listOf(R.string.pk_apps, R.string.pk_pins, R.string.pk_links, R.string.pk_live)

        // read once, kept as presses change them: read per tile (~15 ms each on the FPT box) it was ~250 ms before the list showed
        var hiddenNow = Store.list(a, "hidden"); var tilesNow = Store.list(a, "tiles")
        var pinnedNow = if (mode == Mode.GRID) Pins.list(a).map { it.pkg }.toSet() else emptySet()   // pinned by their apps: on the grid without being in "tiles"
        fun on(k: String): Boolean {
            return when {
                mode == Mode.CHOOSE -> k in chosen
                mode == Mode.TARGET -> k == now
                Target.kind(k) == Target.Kind.APP -> k !in hiddenNow
                else -> k in tilesNow || k in pinnedNow
            }
        }
        fun press(it: Item) {
            if (mode == Mode.TARGET) { dialog.dismiss(); onPick(it.key); return }
            if (mode == Mode.CHOOSE) { if (!chosen.remove(it.key)) { if (chosen.size >= max) return; chosen.add(it.key) }; tiles[it.key]?.mark(it.key in chosen); labels(); return }
            val link = it.link
            if (link != null && it.key !in pinnedNow) {                // another app's channel: TV Home's own pin of its link, the logo as its picture
                bg.execute {
                    val made = Pins.pinIntent(a, Links.source(a, link) ?: a.packageName, link.name, Links.intent(a, link), Links.card(Links.logo(a, link), link.name))
                    if (link.any) LinkApps.noteGroup(a, Pins.ownId(Links.intent(a, link)), LinkApps.groupFor(Links.link(link)), Links.link(link))
                    a.runOnUiThread {
                        if (made == Pins.Made.FAIL) { android.widget.Toast.makeText(a, R.string.watch_pin_fail, android.widget.Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                        pinnedNow = pinnedNow + it.key; tiles[it.key]?.mark(true)
                        (a as? HomeActivity)?.let { h -> h.window.decorView.postDelayed({ if (!h.isDestroyed) h.pinsRead() }, 600) }   // the system confirms through PinActivity first
                    }
                }
                return
            }
            if (Target.kind(it.key) == Target.Kind.APP) {          // an app: off the grid (hidden) or back
                val hidden = Store.list(a, "hidden")
                if (it.key in hidden) Store.change(a, JSONObject().put("hidden", JSONArray(hidden - it.key)))
                else Store.change(a, JSONObject().put("hidden", JSONArray(hidden + it.key)).put("dock", JSONArray(Store.list(a, "dock") - it.key)))
            } else if (Target.kind(it.key) == Target.Kind.PIN) {   // a shortcut: pinned = on the grid; press unpins it (like the long-press menu), or pins it again
                if (it.key in pinnedNow || it.key in tilesNow) { Pins.off(a, it.key); pinnedNow = pinnedNow - it.key }
                else if (Pins.add(a, it.key)) pinnedNow = pinnedNow + it.key
                (a as? HomeActivity)?.pinsRead()
            } else {
                val t = Store.list(a, "tiles")
                if (it.key in t) Store.change(a, JSONObject().put("tiles", JSONArray(t - it.key)).put("dock", JSONArray(Store.list(a, "dock") - it.key)).put("order", JSONArray(Store.list(a, "order") - it.key)))
                else if (t.size < HomeConfig.TILES_MAX) Store.change(a, JSONObject().put("tiles", JSONArray(t + it.key)))
            }
            hiddenNow = Store.list(a, "hidden"); tilesNow = Store.list(a, "tiles")
            tiles[it.key]?.mark(on(it.key))
            if (Target.kind(it.key) == Target.Kind.APP) tiles[it.key]?.tag(if (it.key in hiddenNow) a.getString(R.string.pk_hidden) else "")   // "Hidden" stayed on an app shown again
        }

        fun tileFor(it: Item): PickTile = tiles.getOrPut(it.key) {
            PickTile(a, tileW, ::dp, it.key, it.label, it.app, it.key, it.tag, it.icon, plus = it.key.isEmpty()).also { t ->
                t.setOnClickListener { _ -> press(it) }
                t.mark(it.key.isNotEmpty() && on(it.key))
            }
        }

        // 30 dp above, not 18: the focused tile's name hangs 6-24 dp under its row (TvTile) and drew over the next section's heading (smoke, "Live TV" over "LIVE WIDGETS")
        fun heading(t: String) = Font.on(TextView(a)).apply { text = t.uppercase(); textSize = 12f; setTextColor(PK_DIM); letterSpacing = 0.12f; setPadding(0, dp(30), 0, dp(14)) }
        fun redraw(limit: Int = Int.MAX_VALUE) {                     // [limit] tiles at most: the first screen, the rest a frame later
            val q = fold(search.text.toString().trim())
            list.removeAllViews()
            if (q.isEmpty()) pasteCard?.let { list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
            var left = limit
            for (s in sections.indices) {
                if (left <= 0) break
                if (mode == Mode.CHOOSE && pick == null && s != 0) continue
                val here = items.filter { it.section == s && (q.isEmpty() || fold(it.label).contains(q) || fold(it.tag).contains(q)) }
                if (mode == Mode.CHOOSE && s != 0 && here.isEmpty()) continue   // a folder's picker: only the kinds that are on the grid, no "none" lines
                val empty = when {
                    s == 1 && !Pins.can(a) -> a.getString(R.string.pk_pins_need_home)
                    s == 3 && LiveWidgets.available(a).isEmpty() -> a.getString(if (LiveWidgets.status(a) == LiveWidgets.UNTRUSTED) R.string.power_untrusted else R.string.c_live_none)
                    else -> ""
                }
                if (here.isEmpty() && (q.isNotEmpty() || empty.isEmpty())) continue
                list.addView(heading(a.getString(sections[s])))
                if (here.isEmpty()) { list.addView(Font.on(TextView(a)).apply { text = empty; textSize = 14f; setTextColor(PK_DIM) }); continue }
                var row: LinearLayout? = null
                here.take(left).also { left -= it.size }.forEachIndexed { i, it ->
                    if (i % cols == 0) { row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = gap }) }
                    val t = tileFor(it)
                    (t.parent as? ViewGroup)?.removeView(t)
                    row!!.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i % cols > 0) leftMargin = gap })
                }
            }
            if (list.childCount == 0) list.addView(Font.on(TextView(a)).apply { text = a.getString(R.string.pk_none_found); textSize = 15f; setTextColor(PK_DIM); setPadding(0, dp(24), 0, 0) })
        }
        search.done = { redraw() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { redraw() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        search.after = { (0 until list.childCount).firstNotNullOfOrNull { (list.getChildAt(it) as? LinearLayout)?.getChildAt(0) as? PickTile } }   // Done / Enter: onto the first tile left

        if (paste) {                                                  // the QR to this TV Home's own page, started off the main thread
            var mine: ManageServer.Listener? = null
            bg.execute {
                val s = ManageServer.start(a)
                a.runOnUiThread {
                    if (!dialog.isShowing) { if (s != null) ManageServer.release(); return@runOnUiThread }
                    val status = Font.on(TextView(a)).apply { textSize = 13f; setTextColor(PK_FAINT); setPadding(0, dp(6), 0, 0) }
                    val words = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                        addView(Font.on(TextView(a)).apply { text = a.getString(R.string.yt_paste); textSize = 20f; setTextColor(Color.WHITE); typeface = Font.bold })
                        addView(Font.on(TextView(a)).apply { text = a.getString(if (s != null) R.string.yt_paste_step else R.string.pq_none); textSize = 15f; setTextColor(PK_SOFT); setPadding(0, dp(6), 0, 0) })
                        addView(status)
                    }
                    val card = LinearLayout(a).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(24), dp(16))
                        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(TvGlass.ROW_FILL); setStroke(dp(1), TvGlass.ROW_EDGE) }
                        if (s != null) addView(ImageView(a).apply {      // drawn without smoothing: a blurred edge scans worse
                            setImageDrawable(android.graphics.drawable.BitmapDrawable(a.resources, dev.tvremote.core.web.Qr.bitmap(s.url + ManageServer.PASTE_HASH, 8)).apply { isFilterBitmap = false })
                        }, LinearLayout.LayoutParams(dp(if (linkOnly) 260 else 150), dp(if (linkOnly) 260 else 150)))   // alone on its page: big enough to scan from the sofa
                        addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { if (s != null) leftMargin = dp(22) })
                    }
                    mine = object : ManageServer.Listener {
                        override fun connected(phones: Int) { status.text = a.getString(R.string.mg_connected); status.setTextColor(PK_OK) }
                        override fun changed() { status.text = a.getString(R.string.yt_pinned); status.setTextColor(PK_OK)
                            (a as? HomeActivity)?.let { h -> h.window.decorView.postDelayed({ if (!h.isDestroyed) h.pinsRead() }, 600) } }
                        override fun stopped() {}
                    }
                    if (s != null) ManageServer.listener = mine
                    pasteCard = card
                    if (items.isNotEmpty() || list.childCount > 0) { val f = list.findFocus(); redraw(); f?.requestFocus() }
                }
            }
            dialog.setOnDismissListener { if (ManageServer.listener === mine) ManageServer.listener = null; ManageServer.release() }
        }
        // what there is, read off the main thread: every app's picture, then each app's shortcuts (a binder call per app)
        bg.execute {
            // the grid's apps with their pictures when Home has them: loading every banner again was ~230-400 ms on the FPT box
            val apps = (a as? dev.tvremote.home.HomeActivity)?.loaded()?.filter { !it.grid }?.takeIf { it.isNotEmpty() }?.onEach { if (it.banner == null && it.icon == null) it.load(a) }   // Home's list has its live widgets and pins too: listed here as apps, their tile moved to its own section and left a hole
                ?: Apps.list(a).onEach { it.load(a) }
            val hidden = Store.list(a, "hidden").toSet()
            val got = ArrayList<Item>()
            if (only == null) {                                         // one app's channels alone: no apps, shortcuts, widgets
            if (mode == Mode.TARGET) got.add(Item("", a.getString(R.string.pk_none), null, "", 0))
            if (mode == Mode.TARGET && actions) got.add(Item(Target.CONTROL, a.getString(R.string.cc_title), null, a.getString(R.string.pk_over_apps), 0, R.drawable.ic_settings))   // a key's action: the Control Center over any app
            for (p in apps) got.add(Item(p.pkg, p.label, p, if (p.pkg in hidden && mode == Mode.GRID) a.getString(R.string.pk_hidden) else "", 0))
            if (mode == Mode.CHOOSE) { allKeys = apps.map { it.pkg }; chosen.clear(); if (pick == null) chosen.addAll(apps.map { it.pkg }.filter { it !in hidden }); a.runOnUiThread { labels() }
                if (pick != null) {                                    // a folder can hold what is on the grid besides apps: shortcuts and live widgets (their keys stay in "tiles")
                    val inTiles = Store.list(a, "tiles").toSet()
                    val onGrid = (a as? dev.tvremote.home.HomeActivity)?.loaded()?.filter { it.grid && !it.folder && it.pkg in inTiles }.orEmpty()
                    val live = LiveWidgets.available(a).associateBy { it.key }
                    val names = apps.associate { it.pkg to it.label }
                    for (p in onGrid) if (p.live) live[p.pkg]?.let { t -> got.add(Item(t.key, t.title.ifEmpty { p.label }, null, a.getString(R.string.pk_live_tag), 3, LiveWidgets.iconRes(a, t))) }
                        else got.add(Item(p.pkg, p.label, p, names[Target.pkg(p.pkg)] ?: "", 1))
                }
            }
            else {
            Pins.refresh(a, apps)
            val avail = try { JSONArray(a.getSharedPreferences("pins", Context.MODE_PRIVATE).getString("avail", "[]")) } catch (e: Exception) { JSONArray() }
            val names = apps.associate { it.pkg to it.label }
            for (i in 0 until avail.length()) {
                val o = avail.getJSONObject(i)
                val app = Pins.app(a, o.getString("key")) ?: continue
                got.add(Item(o.getString("key"), o.getString("title"), app, names[o.getString("app")] ?: "", 1))
            }
            for (t in LiveWidgets.available(a)) got.add(Item(t.key, t.title, null, a.getString(R.string.pk_live_tag), 3, LiveWidgets.iconRes(a, t)))
            }
            }
            if (mode == Mode.GRID && only != null && Pins.can(a)) for (l in Links.installed(a).filter { it.group == only }) {   // one app's channels, from + Add (~200 of all apps' here were too many); kept logos only, small cards: full-size ones for ~80 were ~70 MB
                val art = App(l.pkg, l.name, null).apply { banner = android.graphics.drawable.BitmapDrawable(a.resources, Links.card(Links.logo(a, l, fetch = false), l.name, tileW.coerceAtMost(320), android.graphics.Bitmap.Config.RGB_565)) }
                got.add(Item(Target.pin(a.packageName, Pins.ownId(Links.intent(a, l))), l.name, art, "", 2, link = l))
            }
            // the tiles made here too, not yet on a window (as AsyncLayoutInflater does): ~10 ms each on the FPT box's UI thread
            for (it in got.take(cols * 3)) tileFor(it)                    // the first screen only: all of them took ~180 ms there, longer than the wait they saved
            a.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                // three rows first: built and drawn whole (~30 tiles) it was ~400 ms on the FPT box before anything showed
                items.addAll(got); redraw(cols * 3)
                search.isFocusable = true
                val inOrder = (0 until list.childCount).mapNotNull { (list.getChildAt(it) as? LinearLayout)?.getChildAt(0) as? PickTile }.firstOrNull { it.isFocusable }   // the first on the screen, not a map's first
                val first = (if (mode == Mode.TARGET) tiles[now]?.takeIf { it.isFocusable && it.parent != null } else null) ?: inOrder
                (first ?: if (linkOnly) linkBox else search).requestFocus()
            }
            // the rest made here too, then put on in one go: made on the main thread a frame later, TV360's 159 channels held it ~1.5 s
            for (it in got.drop(cols * 3)) { if (!dialog.isShowing) break; tileFor(it) }
            a.runOnUiThread { if (dialog.isShowing && search.text.isEmpty()) {
                val f = list.findFocus(); val was = tiles[now]?.parent != null; redraw()
                (tiles[now]?.takeIf { mode == Mode.TARGET && !was && f is PickTile && it.isFocusable } ?: f)?.requestFocus()  // the key's own choice, if it was further down
            } }
        }
        if (mode == Mode.CHOOSE) {
            allBtn.setOnClickListener { if (chosen.containsAll(allKeys)) chosen.clear() else chosen.addAll(allKeys); allKeys.forEach { k -> tiles[k]?.mark(k in chosen) }; labels() }
            if (pick != null) doneBtn.setOnClickListener { dialog.dismiss(); if (chosen.isNotEmpty()) pick(chosen.toList()) }
            else doneBtn.setOnClickListener {                             // what is not ticked leaves the grid (hidden: in + Add, nothing lost); a ticked favourite keeps its dock place
                val off = allKeys.filter { it !in chosen }
                val dock = Store.list(a, "dock").filter { it in chosen || it !in allKeys }
                Store.change(a, JSONObject().put("hidden", JSONArray(off)).put("dock", JSONArray(dock)))
                dialog.dismiss()
            }
            labels()
        }
        dialog.show()
        return dialog
    }
}
