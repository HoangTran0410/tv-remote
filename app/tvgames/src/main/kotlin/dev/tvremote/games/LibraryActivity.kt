package dev.tvremote.games

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.tvremote.tvui.dp
import dev.tvremote.tvui.focusBg
import dev.tvremote.tvui.label
import dev.tvremote.tvui.menuButton
import dev.tvremote.tvui.panel
import dev.tvremote.tvui.pill
import dev.tvremote.tvui.pillText
import dev.tvremote.tvui.scrim
import dev.tvremote.tvui.withIcon
import dev.tvremote.update.Release
import dev.tvremote.update.SelfUpdate
import java.io.File
import dev.tvremote.tvui.TvScroll

/**
 * The launcher screen, Apple TV style: clean pictures in rows (favourites, the person's folders, all games, suggestions), the focused one
 * rises with TV Home's ring and shows its name and a quiet line (players, genre, system) under it. A search field and filter pills sit on
 * top; OK held opens a menu (favourite, folders, pin to TV Home). A "+" tile shows the "scan to add" QR.
 */
class LibraryActivity : ComponentActivity() {
    private lateinit var server: GameServer
    private lateinit var root: FrameLayout
    private var overlay: View? = null
    val bare get() = overlay == null && dl == null                    // nothing open over the library: a pad's Back there would leave TV Games
    private val art = java.util.WeakHashMap<View, Bitmap>()           // a tile's picture, for the backdrop
    private val tints = java.util.WeakHashMap<Bitmap, Int>()           // a picture's room colour, worked out once (a 48 x 27 copy)
    private var tintTo = 0
    private var tintShown = 0
    private var tintAnim: android.animation.ValueAnimator? = null
    private val tintGo = Runnable { tint(tintTo) }
    private val artById = HashMap<String, Bitmap>()                   // the pictures by game id, for the pins

    /** One game as the library sees it: what it is, what it takes, and what OK does. */
    private class Item(
        val id: String, val name: String, val sysLabel: String, val sysKey: String, val color: Int, val src: Thumbs.Src?,
        val max: Int, val min: Int,                                   // players; max 0 = not known
        val genre: String,
        val shelf: Boolean,                                           // can be a favourite / in a folder (a game here to play, not a download)
        val play: (() -> Unit)? = null,                               // null: a suggestion to download
        val fresh: (() -> Unit)? = null,                              // a web game opened past the WebView's cache
        val rom: Pair<org.json.JSONObject, Systems.Sys>? = null,
        val web: String? = null,                                      // a link the person added: the menu can remove it
        val file: java.io.File? = null,                               // a ROM on the TV: the menu can delete it
        val hideId: String? = null,                                   // a suggestion: the menu can hide it
    )

    private var lib: List<Item> = emptyList()
    private var sugs: List<Item> = emptyList()
    private var query = ""
    private var fPlayers: Int? = null
    private var fSystem: String? = null
    private var fGenre: String? = null
    private var sections: LinearLayout? = null
    private var searchBox: EditText? = null
    private var updFilters: (() -> Unit)? = null
    private val tileKeys = HashMap<String, View>()                    // "<section>|<id>" -> its tile
    private val tileCache = HashMap<String, Pair<Item, View>>()        // the same, kept across rebuilds of the rows: typing in the search field rebuilt every tile per key (views, drawables, a trim + edge scan of its picture, ~2.4 ms a tile here, several times that on a TV box)
    private val typed = Runnable { renderSections() }                  // a search edit waits for the typing to pause: one rebuild per word, not per key
    private var focusedKey: String? = null
    private var rowKeys: List<String> = emptyList()                   // the focused tile's row, in order: when it goes (hidden, deleted, removed) the focus goes to its neighbour, not nowhere
    private var lastFocus: View? = null
    private var wantFolder: String? = null                            // a tvgames://folder/<id> link: its row takes the focus once drawn
    private var scroller: ScrollView? = null
    private var scrollAnim: android.animation.ValueAnimator? = null
    private val en get() = Lang.code() != "vi"                      // the app's choice, not only the TV's

    override fun attachBaseContext(b: Context) = super.attachBaseContext(Lang.wrap(b))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(tvColor(R.color.tv_bg)) }
        setContentView(root)
        server = GameServer.acquire(this)
        intent.getStringExtra("cat_base")?.let { Catalog.setDebug(this, it, intent.getStringExtra("cat_key")) }   // debug builds only
        firstRun()
        val prefs = getSharedPreferences("games", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT in 23..32 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
        reload()
        link(intent)
        refreshCatalog()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); link(i) }

    /** A pinned shortcut: tvgames://play/<id> starts that game (through its lobby, as from the library), tvgames://folder/<id> shows that folder. */
    private fun link(i: Intent?) {
        val u = i?.data ?: return
        i.data = null
        Pins.playId(u)?.let { id ->
            val it = (lib + sugs).firstOrNull { x -> x.id == id && x.play != null }
            if (it != null) it.play!!() else android.widget.Toast.makeText(this, getString(R.string.pin_gone), android.widget.Toast.LENGTH_LONG).show()
        }
        Pins.folderId(u)?.let { id -> wantFolder = id; clearFilters(false); renderSections() }
    }

    /** The site's list in the background every time the library opens (11 KB; a changed one is kept and drawn, an unchanged one costs nothing); the library is drawn again when it changed. */
    /** The "Refresh" pill: ask the site now (the day-old rule ignored), say what came of it. */
    private fun refreshNow(b: TextView) {
        if (refreshing) return
        refreshing = true
        val spin = android.animation.ObjectAnimator.ofFloat(b, View.ROTATION, 0f, 360f).apply { duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator(); start() }
        Thread {
            val app = applicationContext
            val changed = Catalog.refresh(app, true); val ok = Catalog.reached
            runOnUiThread {
                refreshing = false; spin.cancel(); b.rotation = 0f
                if (isDestroyed) return@runOnUiThread
                val cat = Catalog.peek(app); val n = (cat.optJSONArray("web")?.length() ?: 0) + (cat.optJSONArray("homebrew")?.length() ?: 0)
                android.widget.Toast.makeText(this, getString(if (ok) R.string.refresh_ok else R.string.refresh_fail, n), android.widget.Toast.LENGTH_SHORT).show()
                if (changed && overlay == null && dl == null) reload()
            }
        }.start()
    }
    @Volatile private var refreshing = false

    private fun refreshCatalog() {
        Thread {
            val changed = Catalog.refresh(applicationContext, true); val off = !Catalog.reached
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val was = catOffline; catOffline = off
                if ((changed || was != off) && overlay == null && dl == null && searchBox?.hasFocus() != true) reload()
            }
        }.start()
    }
    @Volatile private var catOffline = false                           // no site answered: the Suggestions row is missing, say why

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, p: Array<String>, r: IntArray) { super.onRequestPermissionsResult(code, p, r); reload() }

    /** The bundled homebrew goes to files/roms once, so the library is never empty; deleting it sticks. */
    private fun firstRun() {
        val prefs = getSharedPreferences("games", MODE_PRIVATE)
        if (prefs.getBoolean("seeded", false)) return
        try {
            assets.open("homebrew/roborun.nes").use { i -> File(Systems.romsDir(this), "Robo Run (homebrew).nes").outputStream().use { i.copyTo(it) } }
            prefs.edit().putBoolean("seeded", true).apply()
        } catch (e: Exception) { }
    }

    private var tidy = false                                         // a downloaded game opened over the download card: put the library right behind it
    override fun onStop() { super.onStop(); if (tidy) { tidy = false; closeOverlay(); reload() } }

    override fun onResume() {
        super.onResume()
        upd.check({ askUpdate(it) }, { updating(it) })
        server.inGame = false; server.webMode = false; server.clearLayouts(); server.setSystem("nes")
        server.onKey = { _, _, _ -> }
        server.onPlayers = { }
        server.onAdded = { runOnUiThread { if (dl == null && overlay == null) reload() else stale = true } }   // the QR card is open while a phone sends: drawn again when it closes
        if (overlay == null && searchBox?.hasFocus() != true) {
            // back from a game: the same list stays as it was (its scroll, the tile in focus); only a changed list is built again
            val was = shown(); if (scroller != null && was.isNotEmpty()) { buildItems(); if (shown() == was) return }
            reload()
        }
    }

    /** What the list shows, to tell whether it changed while a game was open. */
    private fun shown() = (lib + sugs).joinToString("\n") { it.id + "|" + it.name + "|" + it.genre + "|" + it.max + "|" + it.min + "|" + it.sysKey }

    override fun onDestroy() { GameServer.release(); super.onDestroy() }

    private fun filtering() = query.isNotBlank() || fPlayers != null || fSystem != null || fGenre != null

    /** Back: first a closed card, then a cleared search / filter, then the launcher. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (overlay != null) closeOverlay() else if (filtering()) clearFilters(true) else super.onBackPressed() }

    private fun clearFilters(render: Boolean) {
        query = ""; fPlayers = null; fSystem = null; fGenre = null
        searchBox?.setText(""); updFilters?.invoke()
        if (render) renderSections()
    }

    /** The Add-game card answers Back / Escape / a pad's B itself (down and up both eaten), so none of them reaches the launcher. */
    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        if (dl != null && ev.keyCode == android.view.KeyEvent.KEYCODE_BACK) { if (ev.action == android.view.KeyEvent.ACTION_UP) cancelDl(); return true }
        if (overlay != null && (ev.keyCode == android.view.KeyEvent.KEYCODE_BACK || ev.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE || ev.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B)) {
            if (ev.action == android.view.KeyEvent.ACTION_UP) closeOverlay()
            return true
        }
        if (overlay == null && ev.keyCode == android.view.KeyEvent.KEYCODE_MENU) {          // Menu = hold OK: the focused tile's menu
            if (ev.action == android.view.KeyEvent.ACTION_UP) currentFocus?.takeIf { it.isLongClickable }?.performLongClick()
            return true
        }
        if (overlay == null && ev.action == android.view.KeyEvent.ACTION_DOWN && wrap(ev.keyCode)) return true
        return super.dispatchKeyEvent(ev)
    }

    /** Left on a row's first tile goes to the last of the row above, Right on a row's last to the first of the next, as on TV Home. */
    private fun wrap(code: Int): Boolean {
        val left = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT
        if (!left && code != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return false
        var tile: View = currentFocus ?: return false
        while (tile.parent !is GridLayout) tile = tile.parent as? View ?: return false
        val grid = tile.parent as GridLayout
        val i = grid.indexOfChild(tile); val col = i % grid.columnCount
        if (left && col != 0 || !left && col != grid.columnCount - 1 && i != grid.childCount - 1) return false
        val tiles = ArrayList<View>()                                // every tile of every row, in the page's order
        fun walk(v: View) { if (v is GridLayout) (0 until v.childCount).mapTo(tiles) { v.getChildAt(it) } else if (v is android.view.ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k)) }
        walk(window.decorView)
        val to = tiles.getOrNull(tiles.indexOf(tile) + if (left) -1 else 1)?.takeIf { it.isShown && it.isFocusable } ?: return false
        return to.requestFocus()
    }

    private var onOverlayClose: (() -> Unit)? = null                  // the download card: closing it cancels the download

    private var stale = false                                         // a game came while a card was open
    private fun closeOverlay() {
        overlay?.let { root.removeView(it) }; overlay = null
        onOverlayClose?.let { onOverlayClose = null; it() }
        if (stale && dl == null && overlay == null) {                  // on the game just sent, else the first tile: the card's focus is gone
            stale = false; pendingFocus = server.lastRom?.let { "g:$it" }; reload()
            if (currentFocus?.isShown != true) firstTile()?.requestFocus(); return
        }
        scroller?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        lastFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
    }

    // ---- the data

    private fun genreOf(e: org.json.JSONObject?, fallback: String) = Browse.genre(e?.opt("genre"), en) ?: fallback

    private fun buildItems() {
        val games = Systems.scan(this)
        val webs = Systems.webGames(this)
        val cat = Catalog.peek(this)
        val prefs = getSharedPreferences("games", MODE_PRIVATE)
        val hidden = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        val other = getString(R.string.genre_other)
        val have = games.map { it.name.lowercase() }.toSet()
        val stems = games.map { it.file.nameWithoutExtension.removeSuffix(" (homebrew)").lowercase() }.toSet()
        val shas = games.mapNotNull { shaOf(it.file) }.toSet()      // the same file under any name
        fun installed(h: org.json.JSONObject): Boolean {            // by sha, name, the file name in its address, or the file this app saved for its id
            val nm = h.optString("name").replace(Regex("[^A-Za-z0-9 ._-]"), "").lowercase()
            val fromUrl = h.optString("url").substringAfterLast('/').substringBeforeLast('.').lowercase()
            val saved = prefs.getString("got:" + h.optString("id"), null)
            val sha = h.optString("sha256").lowercase()
            return (sha.isNotEmpty() && sha in shas) || nm in stems || h.optString("name").lowercase() in have || (fromUrl.isNotEmpty() && fromUrl in stems) || (saved != null && File(Systems.romsDir(this), saved).exists())
        }
        val l = ArrayList<Item>()
        for (g in games) {
            val pc = Catalog.players(this, g.file.name, null, Systems.maxPlayers(g.sys.id))
            l += Item("g:" + g.file.name, Catalog.entryOf(this, g.file.name, null)?.optString("name")?.ifEmpty { null } ?: g.name, g.sys.label, g.sys.id, g.sys.color, Thumbs.catalog(this, g.file.name) ?: Thumbs.src(this, g.sys, g.file.name),
                pc.first, pc.second, genreOf(Catalog.entryOf(this, g.file.name, null), other), true,
                play = { try { startActivity(Intent(this, GameActivity::class.java).putExtra("path", g.file.path)) } catch (e: Exception) { } }, file = g.file)
        }
        for (w in webs) {
            val e = if (w.url.startsWith("builtin:")) null else Catalog.entryOf(this, null, w.url)
            val pc = if (e != null) Catalog.players(this, null, w.url, 4) else 0 to 1
            l += Item("w:" + w.url, w.name, "Web", "web", 0xFF0891B2.toInt(), webSrc(w.url, null), pc.first, pc.second, genreOf(e, other), true,
                play = { try { startActivity(Intent(this, WebGameActivity::class.java).putExtra("name", w.name).putExtra("url", w.url)) } catch (e: Exception) { } },
                fresh = { try { startActivity(Intent(this, WebGameActivity::class.java).putExtra("name", w.name).putExtra("url", w.url).putExtra("fresh", true)) } catch (e: Exception) { } },
                web = w.url.takeIf { !it.startsWith("builtin:") })
        }
        l.sortBy { it.name.lowercase() }
        lib = l
        val s = ArrayList<Item>()
        val feat = (0 until (cat.optJSONArray("web")?.length() ?: 0)).mapNotNull { cat.optJSONArray("web")?.optJSONObject(it) }
            .filter { it.optBoolean("on", true) && it.optString("url").matches(Regex("https?://.+")) && "w:" + it.optString("id") !in hidden }
        for (w in feat) {
            val nm = w.optJSONObject("name")?.let { it.optString(if (en) "en" else "vi").ifEmpty { it.optString("en") } } ?: w.optString("id")
            val url = w.optString("url")
            s += Item("w:$url", nm, "Web", "web", 0xFF0891B2.toInt(), webSrc(url, w.optString("img")), w.optInt("players", 4).coerceIn(1, 4), w.optInt("min", 1),
                genreOf(w, other), true,
                play = { try { startActivity(Intent(this, WebGameActivity::class.java).putExtra("name", nm).putExtra("url", url).putExtra("pad", w.optString("pad", "web"))) } catch (e: Exception) { } },
                fresh = { try { startActivity(Intent(this, WebGameActivity::class.java).putExtra("name", nm).putExtra("url", url).putExtra("pad", w.optString("pad", "web")).putExtra("fresh", true)) } catch (e: Exception) { } },
                hideId = "w:" + w.optString("id"))
        }
        val brew = (0 until (cat.optJSONArray("homebrew")?.length() ?: 0)).mapNotNull { cat.optJSONArray("homebrew")?.optJSONObject(it) }
            .filter { it.optBoolean("on", true) && "h:" + it.optString("id") !in hidden && !installed(it) && Systems.list(this).any { s -> s.id == it.optString("system") } }
        for (h in brew) {
            val sy = Systems.list(this).first { it.id == h.optString("system") }
            s += Item("h:" + h.optString("id"), h.optString("name"), sy.label, sy.id, sy.color, Thumbs.Src("h:" + h.optString("id"), img = h.optString("img").takeIf { it.isNotEmpty() }),
                h.optInt("players", Systems.maxPlayers(sy.id)).coerceIn(1, 4), h.optInt("min", 1), genreOf(h, other), false, rom = h to sy, hideId = "h:" + h.optString("id"))
        }
        s.sortBy { it.name.lowercase() }
        sugs = s
    }

    // ---- the screen

    private fun reload() {
        if (!::root.isInitialized) return
        overlay = null
        val keepY = scroller?.scrollY ?: 0                            // built again: it opens where it was, not at the top scrolling down
        jump = true
        root.removeAllViews(); tileKeys.clear(); tileCache.clear()
        buildItems()
        if (lib.isEmpty() && sugs.isEmpty()) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(addPanel(server))
                addView(rotateButton(server) { reload() })
                addView(label(getString(R.string.empty_hint), 16f, 0xFFAAB0C0.toInt()).apply { setPadding(dp(80), dp(18), dp(80), 0) })
            }, FrameLayout.LayoutParams(-1, -1))
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        sections = box
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(resources.getDimensionPixelSize(R.dimen.tv_margin_x), resources.getDimensionPixelSize(R.dimen.tv_margin_y), resources.getDimensionPixelSize(R.dimen.tv_margin_x), dp(32)); clipChildren = false; clipToPadding = false
            addView(header())
            addView(filterRow())
            addView(box)
            addView(label(getString(R.string.menu_hint), 14f, 0xFF6B7385.toInt()).apply { gravity = Gravity.START; setPadding(dp(8), dp(2), 0, 0) })
        }
        scroller = object : ScrollView(this) {
            // the default "scroll just enough to show it" would fight centreFocused(), which does the scrolling
            override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean) = false
        }.apply { addView(col); clipChildren = false; clipToPadding = false; isSmoothScrollingEnabled = false }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        scroller?.let { sv -> sv.post { if (jump) sv.scrollTo(0, keepY) }; sv.postDelayed({ jump = false }, 400) }
        renderSections()
    }

    private var jump = false                                          // the next centring jumps instead of gliding (a rebuilt list)

    /** A mouse wheel (the Air Mouse's scroll strip) scrolls the page wherever the pointer rests. Android hands the wheel only to the view
     *  under the pointer, and a tile there takes it (it is clickable) without scrolling: the strip worked only over the gaps between tiles. */
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (super.dispatchGenericMotionEvent(ev)) return true
        if (ev.actionMasked != android.view.MotionEvent.ACTION_SCROLL || !ev.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) return false
        scrollAnim?.cancel()                                           // a wheel step wins over the centring that was running
        return scroller?.onGenericMotionEvent(ev) ?: false
    }

    /** The app's mark and name, the search field on the right. */
    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), dp(12))
        addView(android.widget.ImageView(context).apply {
            setImageResource(R.drawable.icon); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, resources.getDimension(R.dimen.tv_head_radius)) } }
            clipToOutline = true
        }, LinearLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.tv_head_icon), resources.getDimensionPixelSize(R.dimen.tv_head_icon)).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.tv_head_gap) })
        addView(label(getString(R.string.app_name), 24f, tvColor(R.color.tv_ink)).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; typeface = android.graphics.Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        val f = EditText(context).apply {
            hint = getString(R.string.search_hint); setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 16f
            setText(query); setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = android.text.InputType.TYPE_CLASS_TEXT
            id = View.generateViewId(); nextFocusRightId = id                // the last of the header row: Right stays (Android's search took the wide tile of the grid, its far edge being past ours)
            setPadding(dp(18), 0, dp(20), 0); gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(getDrawable(R.drawable.ic_search)!!.mutate().also { d -> d.setBounds(0, 0, dp(22), dp(22)); d.setTint(0x99FFFFFF.toInt()) }, null, null, null); compoundDrawablePadding = dp(12)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = dp(30).toFloat(); setStroke(dp(2), 0xCCFFFFFF.toInt()) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(0x24FFFFFF); cornerRadius = dp(30).toFloat() })
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { val q = e?.toString() ?: ""; if (q != query) { query = q; searchBox?.removeCallbacks(typed); searchBox?.postDelayed(typed, 200) } }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { v, _, _ ->
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                renderSections(); firstTile()?.requestFocus(); true
            }
        }
        searchBox = f
        var gear: TextView? = null                                    // refresh, tiles a row, language, new folder: one button, not four (they are rarely used)
        gear = pill("", 15f, lead = R.drawable.ic_settings, animate = { animOn }, focused = ::centreFocused) { gear?.let { settings(it) } }.apply { contentDescription = getString(R.string.settings); setPadding(dp(11), 0, 0, 0); minWidth = 0; compoundDrawablePadding = 0 }
        addView(gear, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        if (refocusLang) { refocusLang = false; gear.post { gear.requestFocus() } }   // after the rebuild in the new language the focus is back on the gear
        addView(f, LinearLayout.LayoutParams(dp(280), dp(40)))
    }

    /** The gear's sheet: refresh the list (the gear turns meanwhile), tiles a row (4, 5, 6), language, a new folder. */
    private fun settings(gear: TextView, at: Int = 0) {
        popup(getString(R.string.settings), listOf(
            getString(R.string.refresh_pill) to { closeOverlay(); refreshNow(gear) },
            getString(R.string.cols_pill, cols) to {
                cols = if (cols >= 6) 4 else cols + 1
                getSharedPreferences("games", MODE_PRIVATE).edit().putInt("cols", cols).apply()
                tileCache.clear()                                      // kept tiles outside the rows would keep the old size
                resizeTiles()                                          // the same views, a new size: no rebuild, no picture work (it took ~0.7 s on the test box)
                settings(gear, 1)                                      // the sheet stays, showing the new number
            },
            getString(R.string.lang_pill) to { closeOverlay(); languages() },
            getString(R.string.grp_new_item) to { closeOverlay(); newGroup() },
        ), replace = overlay != null, focusIndex = at, icons = listOf(R.drawable.ic_refresh, R.drawable.ic_grid, R.drawable.ic_globe, R.drawable.ic_folder_plus))
    }

    /** The 🌐 sheet: follow the TV / Tiếng Việt / English, the current one ticked; a change builds the screen again in it. */
    private fun languages() {
        val now = Lang.pref(this)
        val names = mapOf("" to getString(R.string.lang_auto)) + dev.tvremote.i18n.Langs.NAMES
        popup(getString(R.string.lang_title), Lang.CHOICES.map { l ->
            names[l]!! to {
                if (l != now) { Lang.set(this, l); refocusLang = true; recreate() } else closeOverlay()
            }
        }, focusIndex = Lang.CHOICES.indexOf(now), icons = Lang.CHOICES.map { if (it == now) R.drawable.ic_check else null })   // the chosen one carries the tick
    }

    private fun firstTile(): View? { val b = sections ?: return null; return findTile(b) }
    private fun findTile(v: View): View? {
        if (itemOf.containsKey(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTile(v.getChildAt(i))?.let { return it }
        return null
    }


    /** Players, system, genre: one dropdown button each, showing the chosen value; a kind with one value only is left out; "Clear" while any is on. */
    private fun filterRow(): View {
        val all = lib + sugs
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(6), dp(8), dp(4)) }
        val allW = getString(R.string.f_all)
        val other = getString(R.string.genre_other)
        class Kind(val icon: Int, val label: String, val values: List<Pair<String, String>>, val get: () -> String?, val set: (String?) -> Unit, val icons: ((String?) -> Int?)? = null)
        val ps = listOf(1 to R.string.f_players_1, 2 to R.string.f_players_2, 3 to R.string.f_players_34).filter { (c, _) -> all.any { Browse.playersOk(c, it.max, it.min) } }.map { it.first.toString() to getString(it.second) }
        val sys = all.map { it.sysKey to it.sysLabel }.distinctBy { it.first }.sortedBy { it.second }
        val genres = all.groupingBy { it.genre }.eachCount().entries.filter { it.key != other || all.any { a -> a.genre != other } }
            .sortedWith(compareBy({ it.key == other }, { -it.value }, { it.key })).map { it.key to it.key }
        val kinds = listOf(
            Kind(R.drawable.ic_users, getString(R.string.f_k_players), ps, { fPlayers?.toString() }, { fPlayers = it?.toInt() }),
            Kind(R.drawable.ic_gamepad, getString(R.string.f_k_system), sys, { fSystem }, { fSystem = it }),
            Kind(R.drawable.ic_tag, getString(R.string.f_k_genre), genres, { fGenre }, { fGenre = it }, { g -> if (g == null) R.drawable.ic_gamepad else genreIcon(g) }),
        ).filter { it.values.size > 1 || (it.values.size == 1 && it.get() != null) }
        val buttons = ArrayList<Pair<Kind, TextView>>()
        var clear: TextView? = null
        fun upd() {
            buttons.forEach { (k, b) -> val v = k.get(); b.pillText(k.values.firstOrNull { it.first == v }?.second ?: k.label); b.isSelected = v != null }
            clear?.visibility = if (fPlayers != null || fSystem != null || fGenre != null) View.VISIBLE else View.GONE
        }
        kinds.forEach { k ->
            var b: TextView? = null
            b = pill(k.label, 15f, lead = k.icon, trail = R.drawable.ic_down, animate = { animOn }, focused = ::centreFocused) { b?.let { anchor -> dropdown(anchor, k.label, listOf<Pair<String?, String>>(null to allW) + k.values, k.get(), k.icons) { v -> k.set(v); upd(); renderSections(); anchor.post { anchor.requestFocus() } } } }
            buttons += k to b; row.addView(b)
        }
        clear = pill(getString(R.string.f_clear), 15f, lead = R.drawable.ic_close, animate = { animOn }, focused = ::centreFocused) { fPlayers = null; fSystem = null; fGenre = null; buttons.firstOrNull()?.second?.requestFocus(); upd(); renderSections() }       // the pill goes away: focus moves to the first dropdown
        row.addView(clear)
        updFilters = { upd() }
        upd()
        return row
    }

    /** A small list under [anchor]: "All" first, the current value ticked; OK picks and closes, Back closes. */
    private fun dropdown(anchor: View, title: String, options: List<Pair<String?, String>>, cur: String?, icons: ((String?) -> Int?)? = null, pick: (String?) -> Unit) {
        lastFocus = anchor
        val loc = IntArray(2); anchor.getLocationInWindow(loc); val rl = IntArray(2); root.getLocationInWindow(rl)
        val x = loc[0] - rl[0]; val y = loc[1] - rl[1] + anchor.height + dp(6)
        var sel: View? = null
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        options.forEach { (v, t) ->
            val on = v == cur
            val r = TextView(this).apply {
                text = if (icons != null) t + (if (on) "  \u2713" else "") else (if (on) "\u2713  " else "     ") + t; textSize = 18f; setTextColor(tvColor(R.color.tv_ink)); gravity = Gravity.CENTER_VERTICAL; maxLines = 1
                setPadding(dp(16), 0, dp(24), 0); isFocusable = true; isClickable = true; minWidth = maxOf(anchor.width, dp(240))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = dp(10).toFloat(); setStroke(dp(2), 0xCCFFFFFF.toInt()) })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(0); cornerRadius = dp(10).toFloat() })
                }
                setOnClickListener { closeOverlay(); pick(v) }
                icons?.invoke(v)?.let { r -> val d = getDrawable(r)!!.mutate(); d.setTintList(textColors); d.setBounds(0, 0, dp(22), dp(22)); setCompoundDrawablesRelative(d, null, null, null); compoundDrawablePadding = dp(12) }
            }
            if (on || (cur == null && v == null)) sel = r
            list.addView(r, LinearLayout.LayoutParams(-2, dp(46)))
        }
        val maxH = root.height - y - dp(16)
        val card = TvScroll(this).apply {
            addView(list); isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply { setColor(tvColor(R.color.tv_card)); cornerRadius = resources.getDimension(R.dimen.tv_radius_card) }
            clipToOutline = true
        }
        val v = FrameLayout(this).apply {
            setBackgroundColor(0x66000000); isClickable = true
            addView(card, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = x; topMargin = y })
        }
        card.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() { card.viewTreeObserver.removeOnGlobalLayoutListener(this); if (card.height > maxH && maxH > dp(120)) card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).also { it.height = maxH } }
        })
        overlay = v
        scroller?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        root.addView(v, ViewGroup.LayoutParams(-1, -1))
        sel?.requestFocus()
    }

    /** A genre's line icon in the dropdown; one the catalog made up gets the tag. */
    private fun genreIcon(words: String): Int = when (Browse.genreId(words)) {
        "action" -> R.drawable.ic_zap; "sports", "sport" -> R.drawable.ic_trophy; "quiz" -> R.drawable.ic_help; "racing" -> R.drawable.ic_flag
        "shooter" -> R.drawable.ic_crosshair; "puzzle" -> R.drawable.ic_puzzle; "platformer" -> R.drawable.ic_mountain; "party" -> R.drawable.ic_users
        "arcade" -> R.drawable.ic_joystick; "adventure" -> R.drawable.ic_compass; "rpg" -> R.drawable.ic_sword; "strategy" -> R.drawable.ic_crown
        "fighting" -> R.drawable.ic_swords; "music" -> R.drawable.ic_music; else -> R.drawable.ic_tag
    }

    private fun newGroup() = askName(getString(R.string.grp_new), Shelf.get(this).freeName(getString(R.string.grp_default))) { n -> val g = Shelf.newGroup(this, n); wantFolder = g.id; renderSections() }

    private fun matches(i: Item) = Browse.nameMatches(i.name, query) && Browse.playersOk(fPlayers, i.max, i.min) && (fSystem == null || i.sysKey == fSystem) && (fGenre == null || i.genre == fGenre)

    /** The rows under the pills: favourites, each folder, all games, suggestions; while searching or filtering one row of what matches. */
    private fun renderSections() {
        searchBox?.removeCallbacks(typed)                              // any other render supersedes a waiting one
        val box = sections ?: return
        val keepKey = focusedKey; val keepId = keepKey?.substringAfter('|')
        val hadFocus = box.hasFocus()
        val elsewhere = currentFocus?.let { c -> generateSequence(c.parent) { it.parent }.none { it === box } } == true       // a filter button has it: leave it there
        val typing = searchBox?.hasFocus() == true                      // the keyboard is open on the search field: it keeps the focus
        box.removeAllViews(); tileKeys.clear()
        if (filtering()) {
            val res = (lib + sugs).filter(::matches)
            if (res.isEmpty()) box.addView(label(getString(R.string.no_match), 20f, 0xFFAAB0C0.toInt()).apply { gravity = Gravity.START; setPadding(dp(8), dp(24), 0, 0) })
            else box.addView(section("r", getString(R.string.sec_results, res.size), res))
        } else {
            val shelf = Shelf.get(this)
            val byId = (lib + sugs).filter { it.shelf }.associateBy { it.id }
            val fav = shelf.fav.mapNotNull { byId[it] }
            if (fav.isNotEmpty()) box.addView(section("fav", getString(R.string.sec_fav), fav))
            for (g in shelf.groups) box.addView(section("g" + g.id, g.name, g.items.mapNotNull { byId[it] }, group = g))
            box.addView(section("all", getString(R.string.sec_all), lib, add = true))
            for ((key, title, items) in sugRows()) box.addView(section(key, title, items))
            if (sugs.isEmpty() && catOffline) box.addView(label(getString(R.string.sug_offline), 16f, tvColor(R.color.tv_dim)).apply { setPadding(dp(8), dp(18), 0, 0) })
        }
        val pend = pendingFocus; pendingFocus = null
        val f = wantFolder; wantFolder = null
        val target = pend?.let { p -> tileKeys.entries.firstOrNull { it.key.endsWith("|$p") }?.value }
            ?: f?.let { id -> tileKeys.entries.firstOrNull { it.key.startsWith("g$id|") }?.value ?: groupHeads["g$id"] }
            ?: if ((hadFocus || keepKey != null) && !elsewhere) (keepKey?.let { tileKeys[it] } ?: keepId?.let { id -> tileKeys.entries.firstOrNull { it.key.endsWith("|$id") }?.value }
            ?: keepKey?.let { k -> neighbour(k) ?: firstTile() }) else null
        if (!typing && !(elsewhere && target == null)) (target ?: if (!hadFocus && lastFocus == null) firstTile() else null)?.requestFocus()
    }

    /** Suggestions in rows a TV is browsed by: our own web games first, then the retro ones one genre a row (the biggest first,
     *  "Other" last). One row mixing them all was hard to scan with a remote, and the genre button above is rarely opened. */
    private fun sugRows(): List<Triple<String, String, List<Item>>> {
        val other = getString(R.string.genre_other)
        val (web, retro) = sugs.partition { it.sysKey == "web" }
        val rows = ArrayList<Triple<String, String, List<Item>>>()
        if (web.isNotEmpty()) rows += Triple("sw", getString(R.string.sug_web), web)
        retro.groupBy { it.genre }.entries.sortedWith(compareBy({ it.key == other }, { -it.value.size }, { it.key.lowercase() }))
            .forEach { (g, l) -> rows += Triple("sg:$g", getString(R.string.sug_retro, g), l) }
        return rows
    }

    private val groupHeads = HashMap<String, View>()

    /** The tile that stood after [key] in its row, else the one before it, that still exists. */
    private fun neighbour(key: String): View? {
        val at = rowKeys.indexOf(key); if (at < 0) return null
        return (rowKeys.drop(at + 1) + rowKeys.take(at).reversed()).firstNotNullOfOrNull { tileKeys[it] }
    }

    /** A row: its title (a folder's with Rename / Delete / Pin beside it) and its tiles. */
    private fun section(key: String, title: String, items: List<Item>, group: Shelf.Group? = null, add: Boolean = false): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(14), 0, dp(8)); clipChildren = false
            addView(label(title, 22f, 0xFFAAB0C0.toInt()).apply { gravity = Gravity.START; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(560) })
            if (group != null) {
                val sp = { View(context).also { v -> v.layoutParams = LinearLayout.LayoutParams(dp(16), 1) } }
                addView(sp())
                val rn = pill(getString(R.string.grp_rename), 15f, lead = R.drawable.ic_pen, animate = { animOn }, focused = ::centreFocused) { askName(getString(R.string.grp_rename), group.name) { n -> Shelf.change(context) { it.rename(group.id, n) }; Pins.renamed(context, group.id, n); renderSections() } }
                addView(rn)
                addView(pill(getString(R.string.grp_delete), 15f, lead = R.drawable.ic_trash, animate = { animOn }, focused = ::centreFocused) { confirm(getString(R.string.grp_delete_q, group.name)) { Shelf.change(context) { it.delete(group.id) }; Pins.deleted(context, group.id); renderSections() } })
                if (Pins.can(context)) addView(pill(getString(R.string.m_pin), 15f, lead = R.drawable.ic_pin, animate = { animOn }, focused = ::centreFocused) { pinGroup(group, items) })
                groupHeads[key] = rn
            }
        })
        if (items.isEmpty() && !add) addView(label(getString(R.string.grp_empty), 16f, 0xFF6B7385.toInt()).apply { gravity = Gravity.START; setPadding(dp(8), 0, 0, dp(18)) })
        else addView(GridLayout(context).apply {
            columnCount = cols; clipChildren = false; clipToPadding = false
            items.forEach { i -> addView(gameTile(i, key)) }
            if (add) addView(addTile())
        })
    }

    private fun webSrc(url: String, img: String?): Thumbs.Src = when {
        url == "builtin:padtest" -> Thumbs.Src("w:$url", asset = "thumbs/padtest.jpg")
        url.startsWith("builtin:") -> Thumbs.Src("w:$url")
        !img.isNullOrEmpty() -> Thumbs.Src("w:$url", img = img)
        else -> Thumbs.Src("w:$url", page = url)
    }

    private fun hide(id: String) {
        val p = getSharedPreferences("games", MODE_PRIVATE)
        p.edit().putStringSet("hidden", HashSet(p.getStringSet("hidden", emptySet()) ?: emptySet()).apply { add(id) }).apply(); reload()
    }

    // ---- the menu on OK held

    private fun menu(i: Item) {
        val e = ArrayList<Pair<String, () -> Unit>>(); val ic = ArrayList<Int>()
        if (i.shelf) {
            val fav = Shelf.get(this).fav.contains(i.id)
            e += getString(if (fav) R.string.m_fav_del else R.string.m_fav_add) to { Shelf.change(this) { it.toggleFav(i.id) }; closeOverlay(); renderSections() }; ic += R.drawable.ic_star
            e += getString(R.string.m_group) to { groupPicker(i) }; ic += R.drawable.ic_folder
            if (Pins.can(this)) { e += getString(R.string.m_pin) to { closeOverlay(); pinGame(i) }; ic += R.drawable.ic_pin }
        }
        i.fresh?.let { f -> e += getString(R.string.m_reload) to { closeOverlay(); f() }; ic += R.drawable.ic_refresh }
        i.web?.let { u -> e += getString(R.string.m_remove) to { closeOverlay(); Systems.removeWeb(this, u); reload() }; ic += R.drawable.ic_trash }
        i.file?.let { f -> e += getString(R.string.m_delete) to { popup(getString(R.string.m_delete_q, i.name), listOf(getString(R.string.yes_delete) to { closeOverlay(); deleteRom(i, f) }), replace = true, focusIndex = 1, icons = listOf(R.drawable.ic_trash)) }; ic += R.drawable.ic_trash }
        i.hideId?.let { h -> e += getString(R.string.m_hide) to { closeOverlay(); hide(h) }; ic += R.drawable.ic_eye_off }
        if (e.isNotEmpty()) popup(i.name, e, icons = ic)
    }

    /** The ROM, its saves (states in saves/, the core's .srm beside the app's files), its place in favourites and folders, its shortcut. */
    private fun deleteRom(i: Item, f: java.io.File) {
        val base = f.nameWithoutExtension + "."
        f.delete()
        for (d in listOf(Systems.savesDir(this), filesDir)) d.listFiles()?.filter { it.isFile && it.name.startsWith(base) && it.name.removePrefix(base) in SAVES }?.forEach { it.delete() }   // only saves: a ROM named "games" took games.json with it
        Shelf.change(this) { it.forget(i.id) }
        Pins.gameDeleted(this, i.id)
        reload()
    }

    private fun groupPicker(i: Item) {
        val sh = Shelf.get(this)
        val e = ArrayList<Pair<String, () -> Unit>>()
        sh.groups.forEach { g -> e += g.name to { Shelf.change(this) { it.toggleIn(g.id, i.id) }; closeOverlay(); renderSections() } }
        e += getString(R.string.grp_new_item) to { closeOverlay(); askName(getString(R.string.grp_new), sh.freeName(getString(R.string.grp_default))) { n -> val g = Shelf.newGroup(this, n); Shelf.change(this) { it.toggleIn(g.id, i.id) }; renderSections() } }
        popup(getString(R.string.m_group), e, replace = true, icons = sh.groups.map { if (sh.inGroup(it.id, i.id)) R.drawable.ic_check else null } + R.drawable.ic_folder_plus)   // a tick on the groups it is in
    }

    private fun pinGame(i: Item) {
        val ok = Pins.pinGame(this, i.id, i.name, artById[i.id], i.color)
        pinSaid(ok) { Pins.gameRefused(this, i.id) }
    }

    private fun pinGroup(g: Shelf.Group, items: List<Item>) {
        val ok = Pins.pinFolder(this, g.id, g.name, items.mapNotNull { artById[it.id] })
        pinSaid(ok) { Pins.folderRefused(this, g.id) }
    }

    /** The launcher cannot pin: say so. It can: look again after a moment, and when it took none (TV Home's dock is full) say that. */
    private fun pinSaid(ok: Boolean, refused: () -> Boolean) {
        if (!ok) { android.widget.Toast.makeText(this, getString(R.string.pin_no), android.widget.Toast.LENGTH_LONG).show(); return }
        root.postDelayed({ if (!isDestroyed && refused()) android.widget.Toast.makeText(this, getString(R.string.pin_full), android.widget.Toast.LENGTH_LONG).show() }, 1800)
    }

    /** A card over the library: a title and buttons; Back / Close leaves it ([dispatchKeyEvent]). */
    private fun popup(title: String, entries: List<Pair<String, () -> Unit>>, replace: Boolean = false, extra: ((LinearLayout) -> Unit)? = null, focusIndex: Int = 0, icons: List<Int?> = emptyList(), close: Boolean = true) {
        if (replace) { overlay?.let { root.removeView(it) }; overlay = null } else lastFocus = currentFocus
        var first: View? = null
        val v = scrim(panel(title).apply {
                extra?.invoke(this)
                entries.forEachIndexed { n, (t, f) -> addView(menuButton(t, 420, 52, 20f) { f() }.also { b -> if (n == focusIndex) first = b; b.withIcon(icons.getOrNull(n)) }) }
                if (close) addView(menuButton(getString(R.string.close), 420, 52, 20f) { closeOverlay() }.also { if (first == null) first = it; it.withIcon(R.drawable.ic_close) })
            })
        overlay = v
        scroller?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        root.addView(v, ViewGroup.LayoutParams(-1, -1))
        first?.requestFocus()
    }

    private val upd by lazy { SelfUpdate(this, "games", "tv-games.apk") }

    /** A newer TV Games in the latest release (SelfUpdate): Update now / Later / Skip this version; Back is a later too. */
    private fun askUpdate(r: Release) {
        if (overlay != null || dl != null) return                     // busy with something else: asked again next time
        popup(getString(R.string.upd_title, r.version), listOf(
            getString(R.string.upd_now) to { updating(r) },
            getString(R.string.upd_later) to { upd.later(); closeOverlay() },
            getString(R.string.upd_skip) to { upd.skip(r); closeOverlay() },
        ), icons = listOf(R.drawable.ic_download, R.drawable.ic_clock, R.drawable.ic_eye_off), close = false,
            extra = { it.addView(label(getString(R.string.upd_note, upd.version()), 18f, tvColor(R.color.tv_dim)).apply { setPadding(0, 0, 0, dp(10)) }) })
    }

    /** Its download, with Cancel; at 100 % Android's installer takes over with its own question. */
    private fun updating(r: Release) {
        lateinit var line: android.widget.TextView
        popup(getString(R.string.upd_title, r.version), listOf(getString(R.string.upd_cancel) to { closeOverlay() }), replace = true, close = false,
            icons = listOf(R.drawable.ic_close), extra = { line = label(getString(R.string.upd_loading, 0), 20f).also { v -> it.addView(v) } })
        onOverlayClose = { upd.cancel() }
        val started = upd.install(r, { pct ->
            if (overlay != null && line.isAttachedToWindow) line.text = getString(R.string.upd_loading, pct)
            if (pct >= 100) { onOverlayClose = null; if (line.isAttachedToWindow) closeOverlay() }
        }, { m ->
            if (line.isAttachedToWindow) closeOverlay()
            popup(getString(R.string.upd_title, r.version), emptyList(), extra = { it.addView(label(getString(R.string.upd_failed, m), 18f, tvColor(R.color.tv_dim))) })
        })
        if (!started) { onOverlayClose = null; closeOverlay() }               // Android's "install unknown apps" first: back from it, onResume goes on
    }

    private fun confirm(q: String, yes: () -> Unit) = popup(q, listOf(getString(R.string.yes_delete) to { closeOverlay(); yes() }), focusIndex = 1, icons = listOf(R.drawable.ic_trash))

    /** A name to type (the TV keyboard opens on OK); OK saves a non-empty one. */
    private fun askName(title: String, initial: String, ok: (String) -> Unit) {
        lateinit var f: EditText
        val save = { val n = f.text.toString().trim(); if (n.isNotEmpty()) { closeOverlay(); ok(n.take(40)) } }
        popup(title, listOf(getString(R.string.ok) to { save() }), icons = listOf(R.drawable.ic_check), extra = { col ->
            f = EditText(this).apply {
                setText(initial); setSelection(initial.length); setSingleLine(true); textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                imeOptions = EditorInfo.IME_ACTION_DONE; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                background = focusBg(10, 0xFF2A3042.toInt(), 0xFF3B4460.toInt())
                setOnEditorActionListener { v, _, _ -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); below(col, v)?.requestFocus(); true }
                // a one-line field keeps Up / Down for its cursor: Down goes on to OK and Close, Up stays
                setOnKeyListener { v, k, e -> if (k == KeyEvent.KEYCODE_DPAD_DOWN) { if (e.action == KeyEvent.ACTION_DOWN) below(col, v)?.requestFocus(); true } else k == KeyEvent.KEYCODE_DPAD_UP }
            }
            col.addView(f, LinearLayout.LayoutParams(dp(420), dp(56)).apply { topMargin = dp(8) })
        })
        f.requestFocus()
    }

    private fun below(col: ViewGroup, v: View): View? = col.getChildAt(col.indexOfChild(v) + 1)

    /** SHA-256 of a ROM (under 4 MB), kept by name + size + date so each file is read once. */
    private fun shaOf(f: File): String? {
        if (f.length() > (4 shl 20)) return null
        val p = getSharedPreferences("shas", MODE_PRIVATE); val k = "${f.name}|${f.length()}|${f.lastModified()}"
        p.getString(k, null)?.let { return it }
        return try {
            val v = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            p.edit().putString(k, v).apply(); v
        } catch (e: Exception) { null }
    }

    private class Dl(val tile: View) { @Volatile var cancelled = false }
    private class Cover(val box: View, val text: TextView, val bar: android.widget.ProgressBar, val hint: TextView)
    private var dl: Dl? = null
    private var pendingFocus: String? = null                          // the file just downloaded: the library focuses its tile once drawn

    /** One OK on a suggestion: the ROM into files/roms, its sha256 checked first (a mismatch is thrown away), then the game opens. A card over
     *  the library shows the percent (bytes of Content-Length; a bar that just runs when the size is unknown); Close or Back cancels; a
     *  failure says so with Retry. */
    private fun getRom(h: org.json.JSONObject, s: Systems.Sys, tile: View) {
        dl?.let { if (it.tile === tile) cancelDl(); return }          // a second OK on the same card cancels; another card waits its turn
        val d = Dl(tile); dl = d
        val cv = dlCard(h.optString("name"))
        onOverlayClose = { if (dl === d) cancelDl() }
        cv.bar.visibility = View.VISIBLE; cv.bar.isIndeterminate = true; cv.text.text = getString(R.string.getting)
        Thread {
            var msg: String? = null; var saved: String? = null
            try {
                val c = java.net.URL(h.getString("url")).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                val total = (c.getHeaderField("content-length")?.toLongOrNull() ?: -1L)
                val out = java.io.ByteArrayOutputStream(if (total in 1..(64L shl 20)) total.toInt() else 1 shl 16)
                var last = -1; var lastAt = 0L
                c.inputStream.use { i ->
                    val buf = ByteArray(16384)
                    while (!d.cancelled) {
                        val n = i.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > (64 shl 20)) throw java.io.IOException("too big")
                        val pc = if (total > 0) (out.size() * 100L / total).toInt() else -1
                        val now = System.currentTimeMillis()
                        if (pc != last && now - lastAt > 120) { last = pc; lastAt = now; runOnUiThread { if (dl === d) progress(cv, pc) } }
                    }
                }
                if (!d.cancelled) {
                    val bytes = out.toByteArray()
                    val sum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    val ext = h.getString("url").substringAfterLast('.').takeIf { it in s.exts } ?: s.exts.first()
                    if (sum != h.optString("sha256").lowercase()) msg = getString(R.string.rom_bad)
                    else { saved = h.optString("file").replace(Regex("[^A-Za-z0-9._-]"), "").takeIf { it.endsWith(".$ext") }   // arcade: the core knows a game by its zip's name
                        ?: (h.optString("name").replace(Regex("[^A-Za-z0-9 ._-]"), "") + ".$ext"); writeWhole(File(Systems.romsDir(this), saved), bytes) }
                }
            } catch (e: java.io.IOException) { android.util.Log.w("TvGames", "rom download", e); msg = getString(if (e.message == "too big") R.string.rom_big else R.string.rom_net) }
            catch (e: Exception) { android.util.Log.w("TvGames", "rom download", e); msg = getString(R.string.rom_net) }
            runOnUiThread {
                if (isDestroyed || dl !== d) return@runOnUiThread
                dl = null
                if (d.cancelled) return@runOnUiThread
                onOverlayClose = null
                if (msg != null) { popup(msg, listOf(getString(R.string.retry) to { getRom(h, s, tile) }), replace = true, icons = listOf(R.drawable.ic_refresh)); return@runOnUiThread }
                getSharedPreferences("games", MODE_PRIVATE).edit().putString("got:" + h.optString("id"), saved).apply()
                pendingFocus = "g:" + saved                                   // back from the game: on its tile in the library
                try { startActivity(Intent(this, GameActivity::class.java).putExtra("path", File(Systems.romsDir(this), saved!!).path)); tidy = true }
                catch (e: Exception) { closeOverlay(); reload() }                // the card closed and the new tile drawn once the game hides them (onStop): no flash
            }
        }.start()
    }

    private fun progress(cv: Cover, pc: Int) {
        if (pc < 0) { cv.bar.isIndeterminate = true; return }
        cv.bar.isIndeterminate = false; cv.bar.progress = pc; cv.text.text = getString(R.string.dl_progress, pc)
    }

    private fun cancelDl() { dl?.let { it.cancelled = true; dl = null; onOverlayClose = null; closeOverlay() } }

    /** The download's card: the game's name, the words and the bar; its Close cancels. */
    private fun dlCard(name: String): Cover {
        val t = label("", 20f, 0xFFD5DAE6.toInt()).apply { gravity = Gravity.CENTER }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(0xFF4ADE80.toInt()); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40FFFFFF)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(t); addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(12); bottomMargin = dp(18) }) }
        popup(name, emptyList(), replace = overlay != null, extra = { it.addView(box, LinearLayout.LayoutParams(dp(420), -2)) })
        return Cover(box, t, bar, t)
    }



    // ---- a tile: TV Home's, one for one (same ring, rise, spring and shine; its code is app/tvhome HomeActivity.Tile)

    private var cols = 0
        get() { if (field == 0) field = getSharedPreferences("games", MODE_PRIVATE).getInt("cols", 4).coerceIn(4, 6); return field }
    private val tileW get() = dp(880 / cols - 24)                     // a row stays 880 dp wide (196 dp tiles at 4) with its 24 dp gaps
    private val tileH get() = tileW * 123 / 196
    private val ringOut get() = dp(3)
    private val animOn: Boolean get() = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    private val calm: Boolean by lazy {                               // little memory (TCL's 1 GB boxes): only the card grows, shorter
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.isLowRamDevice || android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem <= 1536L * 1024 * 1024
    }
    private val spring = GSpring(0.62f)
    private val glide = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private class Ref(val card: FrameLayout, val face: FrameLayout, val ring: View, val shine: View, val content: View, val below: LinearLayout, val nameV: TextView)
    private val refs = java.util.WeakHashMap<View, Ref>()
    private val itemOf = java.util.WeakHashMap<View, Item>()
    private val sizers = java.util.WeakHashMap<View, () -> Unit>()        // a tile -> what re-sizes it to the column count
    private val texts = java.util.WeakHashMap<View, () -> Unit>()         // a tile -> what re-sizes its words

    /** 4 / 5 / 6 a row: every tile and row is kept and only its size changes (one layout pass), the focus stays on its tile and the row stays centred.
     *  It used to rebuild every tile (views, drawables, pictures from disk, a decode each), ~9 ms a tile. */
    private fun resizeTiles() {
        val box = sections ?: return
        fun walk(v: View) {
            if (v is GridLayout) {                                   // 6 a row -> 4: a GridLayout refuses fewer columns while its children hold cells it worked out, so
                val kids = (0 until v.childCount).map { v.getChildAt(it) }        // the same views come out and go back in (focus is on the pill, not on a tile)
                v.removeAllViews(); v.columnCount = cols
                kids.forEach { t ->
                    (t.layoutParams as? GridLayout.LayoutParams)?.let { it.rowSpec = GridLayout.spec(GridLayout.UNDEFINED); it.columnSpec = GridLayout.spec(GridLayout.UNDEFINED) }
                    sizers[t]?.invoke(); texts[t]?.invoke(); v.addView(t)
                }
            }
            else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(box)
        box.requestLayout()
        lastFocus?.let { f -> jump = true; centreFocused(f) }
    }

    /** The ring round a focused tile: TV Home's accent from [ringOut] dp outside the face to 1 dp over it, then a thin white line. */
    private fun ringDrawable(r: Float): Drawable {
        val o = ringOut
        fun band(w: Int, color: Int, radius: Float) = GradientDrawable().apply { cornerRadius = maxOf(0f, radius); setStroke(w, color); setColor(Color.TRANSPARENT) }
        return android.graphics.drawable.LayerDrawable(arrayOf(band(o + dp(1), tvColor(R.color.tv_ring_accent), r + o), band(dp(1), Color.WHITE, r - dp(1)))).apply { setLayerInset(1, o + dp(1), o + dp(1), o + dp(1), o + dp(1)) }
    }

    /** The shell every tile shares: the card (face + ring) that grows, and the lines that show under it while focused. [fill] builds the face; returns its content. */
    private fun shell(color: Int, fill: (FrameLayout) -> View): View {
        val o = ringOut; val w = tileW; val h = tileH; val r = resources.getDimension(R.dimen.tv_radius_tile)
        val outer = FrameLayout(this).apply { isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false }
        val face = FrameLayout(this).apply { background = GradientDrawable().apply { cornerRadius = r; setColor(color) }; clipToOutline = true }
        val content = fill(face)
        val lift = dp(14).toFloat()
        val shine = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x30FFFFFF, 0x0AFFFFFF, 0x00FFFFFF)); alpha = 0f; translationY = -lift }
        face.addView(shine, FrameLayout.LayoutParams(-1, h * 2 / 3))
        val ring = View(this).apply { background = ringDrawable(r); alpha = 0f }
        val card = FrameLayout(this).apply {
            clipChildren = false
            addView(face, FrameLayout.LayoutParams(w, h).apply { setMargins(o, o, o, o) })
            addView(ring, FrameLayout.LayoutParams(-1, -1))
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, out: android.graphics.Outline) { out.setRoundRect(o, o, v.width - o, v.height - o, r) } }   // from the view: a resize needs no new provider
        }
        outer.addView(card, FrameLayout.LayoutParams(w + 2 * o, h + 2 * o).apply { setMargins(-o, -o, -o, -o) })
        val nameV = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt()) }
        val below = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; alpha = 0f; addView(nameV, LinearLayout.LayoutParams(-1, -2)) }
        // under the scaled card, inside the row gap: it adds no height, so rows keep their rhythm
        outer.addView(below, FrameLayout.LayoutParams(w + dp(24), dp(60)).apply { leftMargin = -dp(12); topMargin = h + (h * (GROW - 1f) / 2f).toInt() + o + dp(3) })
        outer.layoutParams = GridLayout.LayoutParams().apply { width = w; height = h; setMargins(dp(12), dp(8), dp(12), dp(58)) }
        refs[outer] = Ref(card, face, ring, shine, content, below, nameV)
        sizers[outer] = {                                              // the tile at the current column count: only layout params change
            val w2 = tileW; val h2 = tileH
            (outer.layoutParams as? GridLayout.LayoutParams)?.let { it.width = w2; it.height = h2 }
            (card.layoutParams as FrameLayout.LayoutParams).let { it.width = w2 + 2 * o; it.height = h2 + 2 * o }
            (face.layoutParams as FrameLayout.LayoutParams).let { it.width = w2; it.height = h2 }
            (shine.layoutParams as FrameLayout.LayoutParams).height = h2 * 2 / 3
            (below.layoutParams as FrameLayout.LayoutParams).let { it.width = w2 + dp(24); it.topMargin = h2 + (h2 * (GROW - 1f) / 2f).toInt() + o + dp(3) }
        }
        outer.setOnFocusChangeListener { v, on -> focusTile(v, on) }
        return outer
    }

    private fun gameTile(i: Item, section: String): View {
        tileCache["$section|${i.id}"]?.let { (was, v) ->
            if (was === i) {                                          // the same game in the same row as before: its view comes back as it was, picture and all
                (v.parent as? ViewGroup)?.removeView(v)
                (v.layoutParams as? GridLayout.LayoutParams)?.let { it.rowSpec = GridLayout.spec(GridLayout.UNDEFINED); it.columnSpec = GridLayout.spec(GridLayout.UNDEFINED) }   // the old grid's cell
                tileKeys.getOrPut("$section|${i.id}") { v }
                return v
            }
        }
        return newTile(i, section).also { tileCache["$section|${i.id}"] = i to it }
    }

    private fun newTile(i: Item, section: String): View {
        val dl = i.rom != null
        var iniTexts: () -> Unit = {}
        val outer = shell(0xFF1E2230.toInt()) { face ->
            val dark = Color.rgb(Color.red(i.color) * 45 / 100, Color.green(i.color) * 45 / 100, Color.blue(i.color) * 45 / 100)
            val fb = FrameLayout(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(i.color, dark)) }
            val ini = TextView(this)
            fb.addView(ini.apply {                                // the designed picture: initials, and the name, until (or without) the real one
                text = ThumbNames.initials(i.name); textSize = 144f / cols; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xE6FFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16))
            }, FrameLayout.LayoutParams(-1, -1))
            val nm = TextView(this)
            fb.addView(nm.apply {
                text = if (dl) getString(R.string.get_rom, i.name) else i.name; textSize = if (cols > 5) 12f else 14f; setTextColor(Color.WHITE); maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), dp(8)); setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            face.addView(fb, FrameLayout.LayoutParams(-1, -1))
            iniTexts = { ini.textSize = 144f / cols; nm.textSize = if (cols > 5) 12f else 14f }
            fb
        }
        texts[outer] = iniTexts
        itemOf[outer] = i
        val ref = refs[outer]!!
        ref.below.addView(TextView(this).apply { text = meta(i); textSize = 14f; addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> val t = v as TextView; if ((t.layout?.getEllipsisCount(0) ?: 0) > 0 && t.text.length > meta(i, false).length) t.post { t.text = meta(i, false) } }; setTextColor(0xFFC5CCDB.toInt()); maxLines = 1; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END; setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt()) }, LinearLayout.LayoutParams(-1, -2))
        ref.nameV.text = if (dl) getString(R.string.get_rom, i.name) else i.name
        ref.nameV.visibility = View.GONE                                     // shown once a picture covers the face: the face names itself until then
        i.src?.let { src ->
            Thumbs.load(this, src) { bmp, _ ->
                if (isDestroyed) return@load
                (0 until ref.face.childCount).map { ref.face.getChildAt(it) }.filter { it.tag == ART }.forEach { ref.face.removeView(it) }   // a snapshot first, the cover after: one picture, not two stacked
                val inner = trimSides(bmp)                                       // a 4:3 picture comes with side bars: its own edge colour fills them
                if (inner != null) {
                    ref.face.addView(View(this).apply { tag = ART; setBackgroundColor(edgeColor(inner)) }, 1, FrameLayout.LayoutParams(-1, -1))
                    ref.face.addView(android.widget.ImageView(this).apply { tag = ART; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setImageBitmap(inner) }, 2, FrameLayout.LayoutParams(-1, -1))
                } else ref.face.addView(android.widget.ImageView(this).apply { tag = ART; scaleType = android.widget.ImageView.ScaleType.FIT_XY; setImageBitmap(bmp) }, 1, FrameLayout.LayoutParams(-1, -1))     // over the designed face, under the shine
                (ref.content as View).visibility = View.GONE
                ref.nameV.visibility = View.VISIBLE
                art[outer] = bmp; artById[i.id] = bmp
                if (outer.hasFocus()) tintFor(outer)                             // the picture came after the focus
            }
        }
        val key = "$section|${i.id}"
        tileKeys.getOrPut(key) { outer }
        outer.setTag(R.id.tile_key, key)
        if (i.rom != null) outer.setOnClickListener { getRom(i.rom.first, i.rom.second, outer) }
        else outer.setOnClickListener { i.play?.invoke() }
        outer.setOnLongClickListener { menu(i); true }
        return outer
    }

    /** The average of the picture's left and right columns: white art gets white sides, dark art dark ones. */
    private fun edgeColor(b: Bitmap): Int {
        var r = 0; var g = 0; var bl = 0; var n = 0
        for (x in intArrayOf(4, b.width - 5)) for (k in 1..15) { val p = b.getPixel(x, b.height * k / 16); r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++ }
        return Color.rgb(r / n, g / n, bl / n)
    }

    /** The picture without its dark side bars (Thumbs' backdrop colour), or null when it has none. */
    private fun trimSides(b: Bitmap): Bitmap? {
        fun bar(x: Int): Boolean = (1..15).all { val p = b.getPixel(x, b.height * it / 16); Math.abs(Color.red(p) - 0x10) < 14 && Math.abs(Color.green(p) - 0x13) < 14 && Math.abs(Color.blue(p) - 0x1A) < 14 }
        var l = 0; while (l < b.width / 3 && bar(l + 1)) l++      // from the 2nd column: the first can be a JPEG edge
        var r = 0; while (r < b.width / 3 && bar(b.width - 2 - r)) r++
        if (l < b.width / 25 && r < b.width / 25) return null
        val m = minOf(l, r)
        return Bitmap.createBitmap(b, m, 0, b.width - 2 * m, b.height)
    }

    /** "2 players · Sports · NES"; without [sys] where the tile is too narrow for it (a cut "Party · W…" said less than "Party"). */
    private fun meta(i: Item, sys: Boolean = true): CharSequence {
        val n = when { i.max <= 0 -> null; i.max == 1 -> "1"; i.min >= i.max -> "${i.max}"; else -> "${i.min}\u2013${i.max}" }
        val rest = listOfNotNull(i.genre.takeIf { it != getString(R.string.genre_other) || n == null }, i.sysLabel.takeIf { sys }).joinToString(" \u00B7 ")
        val sb = android.text.SpannableStringBuilder()
        if (n != null) {
            sb.append(" ")
            sb.setSpan(android.text.style.ImageSpan(getDrawable(R.drawable.ic_user)!!.mutate().also { d -> d.setBounds(0, 0, dp(15), dp(15)); d.setTint(0xFFC5CCDB.toInt()) }, android.text.style.ImageSpan.ALIGN_BASELINE), 0, 1, 0)
            sb.append(" ").append(n)
            if (rest.isNotEmpty()) sb.append(" \u00B7 ")
        }
        return sb.append(rest)
    }

    private fun addTile(): View {
        var plus: TextView? = null; var add: TextView? = null
        val outer = shell(0xFF1A1F2B.toInt()) { face ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(TextView(context).also { plus = it }.apply { text = "+"; textSize = 208f / cols; setTextColor(Color.WHITE); includeFontPadding = false; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL) })
                addView(label(getString(R.string.add_game), if (cols > 5) 13f else 15f).also { add = it }.apply { setTextColor(0xFFC9CFDD.toInt()) })
            }
            face.addView(c, FrameLayout.LayoutParams(-1, -1)); c
        }
        texts[outer] = { plus?.textSize = 208f / cols; add?.textSize = if (cols > 5) 13f else 15f }
        outer.setOnClickListener { showOverlay() }
        return outer
    }

    /** TV Home's focus, copied: a spring rise (1.12x, a soft shadow, the picture a touch larger inside), the ring fading in, the lines under it, a shine settling. */
    /**
     * The room takes the focused game's colour, the way a console's home does: a dark shade of the picture's own hue
     * ([LobbyTheme.accent]) at the top left, into the plain room colour. Only once the focus rests: a D-pad run does not flash
     * through every game. A colour, not the picture blurred (tried and dropped, fe6b177e: busy behind the tiles). A grey picture
     * or none: the plain room.
     */
    private fun tintFor(v: View) {
        val bg = tvColor(R.color.tv_bg)
        val c = art[v]?.let { b -> tints.getOrPut(b) { LobbyTheme.accent(b).let { a -> if (a == LobbyTheme.DEFAULT) bg else room(a) } } } ?: bg
        root.removeCallbacks(tintGo); tintTo = c; root.postDelayed(tintGo, 250)
    }

    private fun room(a: Int): Int { val hsv = FloatArray(3); android.graphics.Color.colorToHSV(a, hsv); hsv[1] = 0.6f; hsv[2] = 0.3f; return android.graphics.Color.HSVToColor(hsv) }

    private fun tint(c: Int) {
        val bg = tvColor(R.color.tv_bg)
        val from = if (tintShown == 0) bg else tintShown
        tintAnim?.cancel()
        if (c == from) return
        val d = (root.background as? GradientDrawable) ?: GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(bg, bg)).also { root.background = it }
        fun paint(x: Int) { tintShown = x; d.colors = intArrayOf(x, bg) }
        if (!animOn) { paint(c); return }
        tintAnim = android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, c).apply {
            duration = ms(420); addUpdateListener { paint(it.animatedValue as Int) }; start()
        }
    }

    private fun focusTile(v: View, on: Boolean) {
        val r = refs[v] ?: return
        if (on) tintFor(v)
        val lift = dp(14).toFloat()
        r.card.animate().cancel(); r.ring.animate().cancel(); r.below.animate().cancel(); r.content.animate().cancel(); r.shine.animate().cancel()
        if (on) {
            lastFocus = v; focusedKey = v.getTag(R.id.tile_key) as? String; centreFocused(v)
            rowKeys = (v.parent as? ViewGroup)?.let { g -> (0 until g.childCount).mapNotNull { g.getChildAt(it).getTag(R.id.tile_key) as? String } } ?: emptyList()
        }
        if (!animOn) {                                                // the end state at once, grown too (TV Home's Tile.focus): at 1x only the ring told which was chosen
            val g = if (on) GROW else 1f
            r.card.translationX = 0f; r.card.translationY = 0f
            r.card.scaleX = g; r.card.scaleY = g; r.card.translationZ = if (on) lift else 0f; r.content.scaleX = if (on) 1.05f else 1f; r.content.scaleY = r.content.scaleX
            r.ring.alpha = if (on) 1f else 0f; r.below.alpha = if (on) 1f else 0f; r.shine.alpha = 0f
            return
        }
        val s = if (on) GROW else 1f
        val t = ms(if (on) 520L else 300L)
        val ip = if (on) spring else glide
        r.card.translationX = 0f; r.card.translationY = 0f
        if (calm) {
            r.card.translationZ = 0f; r.content.scaleX = 1f; r.content.scaleY = 1f; r.shine.alpha = 0f; r.below.translationY = 0f
            r.ring.alpha = if (on) 1f else 0f; r.below.alpha = if (on) 1f else 0f
            r.card.animate().scaleX(s).scaleY(s).setDuration(t).setInterpolator(ip).withLayer().start()
            return
        }
        r.card.animate().scaleX(s).scaleY(s).translationZ(if (on) lift else 0f).setDuration(t).setInterpolator(ip).withLayer().start()
        r.content.animate().scaleX(if (on) 1.05f else 1f).scaleY(if (on) 1.05f else 1f).setDuration(t).setInterpolator(ip).start()
        r.ring.animate().alpha(if (on) 1f else 0f).setDuration(if (on) 200L else 160L).setInterpolator(glide).start()
        r.below.animate().alpha(if (on) 1f else 0f).translationY(if (on) 0f else -lift / 3).setDuration(if (on) 360L else 200L).setInterpolator(glide).start()
        r.shine.animate().alpha(if (on) 1f else 0f).translationY(if (on) 0f else -lift).setDuration(if (on) 420L else 200L).setInterpolator(glide).start()
    }

    private fun ms(t: Long) = if (calm) t * 11 / 20 else t

    /** Scrolls so the focused tile's row sits in the middle of the screen (clamped: the header stays on top for the first row, the
     *  last row stops at the content's end, so no empty band is added below it). A new key retargets the running animation. */
    private fun centreFocused(v: View) {
        val sv = scroller ?: return
        val content = sv.getChildAt(0) ?: return
        sv.post {
            val r = android.graphics.Rect(0, 0, v.width, v.height)
            try { sv.offsetDescendantRectToMyCoords(v, r) } catch (e: Exception) { return@post }
            val inContent = r.centerY()      // already in the content's coordinates; its zoom is ignored
            val max = (content.height - sv.height).coerceAtLeast(0)
            val head = (content as? ViewGroup)?.let { c -> (0..1).sumOf { c.getChildAt(it)?.height ?: 0 } } ?: 0     // the header and the pills stay in view for the first row
            val want = (inContent - sv.height / 2).coerceIn(0, max)
            val to = if (want < head) 0 else want                       // never half-hide the header: the first rows stay as they are
            scrollAnim?.cancel()
            val from = sv.scrollY
            val jumped = jump; jump = false
            if (from == to) return@post
            if (!animOn || jumped) { sv.scrollTo(0, to); return@post }
            scrollAnim = android.animation.ValueAnimator.ofInt(from, to).apply {
                duration = 200; interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { sv.scrollTo(0, it.animatedValue as Int) }
                start()
            }
        }
    }

    private fun showOverlay() {
        var close: View? = null
        lastFocus = currentFocus
        val v = FrameLayout(this).apply {
            setBackgroundColor(0xFA0B0D12.toInt()); isClickable = true
            addView(LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(addPanel(server)); addView(rotateButton(server) { closeOverlay(); showOverlay() })
                addView(menuButton(getString(R.string.close), 360, 52, 20f) { closeOverlay() }.also { close = it })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        overlay = v
        scroller?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        root.addView(v, ViewGroup.LayoutParams(-1, -1))
        close?.requestFocus()                                          // the D-pad starts on Close, not on the tile behind
    }

    /** A download kept only once whole: a full disk leaves no half ROM listed as downloaded. */
    private fun writeWhole(f: File, bytes: ByteArray) {
        val tmp = File(f.path + ".tmp")
        try { tmp.writeBytes(bytes); if (!tmp.renameTo(f)) throw java.io.IOException("rename") } catch (e: java.io.IOException) { tmp.delete(); throw e }
    }

    companion object {
        private var refocusLang = false                             // set just before recreate() for a language change: the globe takes the focus back
        const val GROW = 1.12f                                       // a focused card's size (TV Home's)
        private val SAVES = setOf("srm", "state", "state.tmp", "rtc")
        private const val ART = "art"                                // the views a cover adds to a card's face, taken off before the next
    }
}

/** A real damped spring: rises fast, passes ~6 %, settles (TV Home's `Spring`, Apple TV's focus). */
class GSpring(private val damping: Float) : android.animation.TimeInterpolator {
    private val w = 6.2f / damping
    private val wd = w * Math.sqrt(1.0 - damping * damping).toFloat()
    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        val e = Math.exp((-damping * w * t).toDouble()).toFloat()
        return 1f - e * (Math.cos((wd * t).toDouble()).toFloat() + damping * w / wd * Math.sin((wd * t).toDouble()).toFloat())
    }
}
