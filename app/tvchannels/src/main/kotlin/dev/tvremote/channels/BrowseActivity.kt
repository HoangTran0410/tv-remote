package dev.tvremote.channels

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.tvremote.tvui.TvField
import dev.tvremote.tvui.TvState
import dev.tvremote.tvui.TvUi
import java.text.Normalizer
import java.util.concurrent.Executors
import dev.tvremote.update.Release
import dev.tvremote.update.SelfUpdate

/**
 * The list: a side column (favourites and the person's own groups, the country, its categories) and a grid of channel cards.
 * OK plays; holding OK or Menu opens what can be done with a channel (favourite, a group, pin to the Home screen) or a group
 * (pin, rename, delete). Everything is read from iptv-org in the background.
 */
class BrowseActivity : Activity() {
    override fun attachBaseContext(b: Context) = super.attachBaseContext(Lang.wrap(b))
    private val io = Executors.newSingleThreadExecutor(Providers.screenThread)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var rail: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var title: TextView
    private lateinit var note: TextView
    private lateinit var content: View                                 // sidebar + grid: hidden while the one full-screen state (loading / error) shows
    private val last = HashMap<String, Int>()                          // per group: the card that had focus, for OK on the group
    private var loading = true
    private lateinit var state: TvState                                // the one full-screen state: loading, offline, nothing here
    private lateinit var find: TvField
    private val cards = Cards()
    private lateinit var shelves: Shelves                              // the home of a country that has ready-made categories (Categories)
    private var catRes: Categories.Result? = null                     // this country's shelves; null: it has none, the plain list is the home
    private var homeMode = true                                        // shelves on show; off while the person is in the list view (all channels, search, a group)
    private var pendingRow: String? = null                             // a link to a group: its shelf gets the focus once the shelves are drawn

    private var cc = ""
    private var all: List<List<Channel>> = emptyList()
    private var shown: List<List<Channel>> = emptyList()
    private var group = ALL
    private var watching: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null   // favourites / groups edited from a phone or computer
    private var failed = false
    private val plData = HashMap<String, Playlists.Result?>()         // playlist id -> its channels (null: being read), kept while the screen lives
    private var countryName = ""
    private var others: List<List<Channel>> = emptyList()           // search: matches in other countries (GlobalIndex)
    private var otherState = IDLE
    private var gseq = 0
    private var names: Map<String, String> = emptyMap()            // country code -> "🇩🇪 Germany", for the cards of other countries
    private val searcher = Executors.newSingleThreadExecutor(Providers.screenThread)
    private val globalRun = Runnable { searchOthers() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b == null) HomeRows.favourites(this)                       // the row Android's launchers show, from favourites saved before it existed
        if (b == null) Thread { Pins.mend(applicationContext) }.start()   // pins made with no logo get it (not on io: the world index may download)
        val d = { v: Number -> TvUi.dp(this, v).toInt() }
        val mx = resources.getDimension(R.dimen.tv_margin_x).toInt(); val my = resources.getDimension(R.dimen.tv_margin_y).toInt()   // app/DESIGN-TV.md
        val root = LinearLayout(this).apply { @Suppress("DEPRECATION") setBackgroundColor(resources.getColor(R.color.tv_bg)) }

        rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(mx, my, d(12), my) }
        root.addView(ScrollView(this).apply { addView(rail); isVerticalScrollBarEnabled = false; clipToPadding = false },
            LinearLayout.LayoutParams(mx + d(234), -1))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(8), my, mx, 0) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        title = TvUi.text(this, "", 22f, bold = true)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        find = TvUi.field(this, getString(R.string.search_hint), 15f, EditorInfo.IME_ACTION_SEARCH, TvUi.CARD, TvUi.R_BUTTON, 18, 8, R.drawable.ic_search).apply {
            first = { k, e ->                                         // Back with the keyboard up: it closes, and the focus goes on to the results
                if (k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP && text.isNotEmpty() && group == SEARCH) { post { firstCard() } }
                false
            }
            visibility = View.GONE                                    // only in search mode (the first row of the side list)
            setOnEditorActionListener { v, _, _ -> hideKeys(v); firstCard(); true }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { if (group == SEARCH) scheduleOthers(); filter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        head.addView(find, LinearLayout.LayoutParams(d(380), d(44)))
        right.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(12) })

        val body = FrameLayout(this)
        grid = RecyclerView(this).apply {
            adapter = cards; clipToPadding = false; setPadding(d(6), d(10), d(6), d(40)); itemAnimator = null
            setHasFixedSize(true); setItemViewCacheSize(10); recycledViewPool.setMaxRecycledViews(0, 24)   // size does not depend on the cards; scrolling back finds them bound
            layoutManager = GridLayoutManager(this@BrowseActivity, columns()).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(p: Int) = if (shown.getOrNull(p)?.first()?.id == HDR) spanCount else 1
                }
            }
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        note = TvUi.text(this, "", 17f, TvUi.DIM).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }   // an empty group / no match, inside the grid's area
        empty.addView(note, LinearLayout.LayoutParams(d(520), -2))
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        right.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        content = root
        shelves = Shelves(this, object : Shelves.Host {
            override fun play(ch: List<Channel>, lineup: List<List<Channel>>, title: String) = this@BrowseActivity.play(ch, lineup, title)
            override fun actions(ch: List<Channel>) = this@BrowseActivity.actions(ch)
            override fun search() = enterSearch()
            override fun menu() = homeMenu()
        })

        // the whole screen's own state, centred on the screen (not on the grid): loading, or no list and why
        state = TvState(this)
        state.add(TvUi.button(this, getString(R.string.retry)) { load(true) })
        state.add(TvUi.button(this, getString(R.string.country)) { countries() })
        setContentView(FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(-1, -1)); addView(shelves.view, FrameLayout.LayoutParams(-1, -1)); addView(state, FrameLayout.LayoutParams(-1, -1)) })
        cc = countryOf(intent) ?: Store.country(this)
        groupOf(intent)?.let { group = it; pendingRow = rowOf(it) }
        keepGroup?.let { group = it; keepGroup = null }                // the language changed: the same group, not the first
        intent.getStringExtra(QUERY)?.let { group = SEARCH; homeMode = false; find.setText(it) }
        load(false)
        watching = Store.watch(this) { runOnUiThread {
            val g = mine(group)
            if (g != null && g != Store.FAV && Store.group(this, g) == null) group = ALL      // the open group was deleted
            if (isList(group) && Store.playlist(this, group.removePrefix(PL)) == null) group = ALL
            val known = Store.playlists(this).map { it.id }.toSet()
            plData.keys.retainAll(known)                               // an edited list (same id) keeps its channels until read again
            if (homeShown()) refreshHome() else { buildRail(); filter() }
        } }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        countryOf(i)?.takeIf { it != cc }?.let { cc = it; group = ALL; homeMode = true; load(false) }
        groupOf(i)?.let {
            find.setText("")
            if (catRes != null) { homeMode = true; group = ALL; filter(); refreshHome(); if (!shelves.focusRow(rowOf(it))) shelves.focusFirst() } else pick(it)
        }
        i.getStringExtra(QUERY)?.let { group = SEARCH; homeMode = false; others = emptyList(); otherState = IDLE; find.setText(it); buildRail(); filter() }
    }

    override fun onResume() {
        super.onResume(); cards.notifyDataSetChanged(); if (homeShown()) shelves.refreshCards() else if (mine(group) != null) { buildRail(); filter() }
        upd.check({ askUpdate(it) }, { updating(it) })
    }

    /** A group link (a pin on the home screen): the favourites or one of the groups, when it still exists. */
    private fun groupOf(i: Intent?) = Pins.groupId(i?.data)?.let { if (it == Store.FAV) FAVS else if (Store.group(this, it) != null) MINE + it else null }

    private fun rowOf(g: String) = if (g == FAVS) "fav" else "g:" + g.removePrefix(MINE)
    private fun homeShown() = homeMode && catRes != null

    /** Favourites, the person's groups, the country's categories, the rest: one shelf each, none empty. */
    private fun homeRows(): List<Shelves.Row> {
        val res = catRes ?: return emptyList()
        val out = ArrayList<Shelves.Row>()
        live(Store.favourites(this).byChannel()).takeIf { it.isNotEmpty() }?.let { out.add(Shelves.Row("fav", getString(R.string.favourites), it, R.drawable.ic_star)) }
        for (g in Store.groups(this)) live(g.channels.byChannel()).takeIf { it.isNotEmpty() }?.let { out.add(Shelves.Row("g:${g.id}", g.name, it, R.drawable.ic_folder)) }
        val lang = Lang.code()
        res.sections.forEach { out.add(Shelves.Row("c:${it.cat.id}", it.cat.title(lang), it.items)) }
        if (res.others.isNotEmpty()) out.add(Shelves.Row("other", getString(R.string.others), res.others))
        return out
    }
    /** Saved channels keep the streams they had when saved; the country's list now may have more (a provider added later): those first. */
    private fun live(saved: List<List<Channel>>): List<List<Channel>> {
        val now = all.associateBy { it.first().id }
        return saved.map { s -> now[s.first().id]?.let { n -> n + s.filter { x -> n.none { it.url == x.url } } } ?: s }
    }
    private fun refreshHome() { if (homeShown()) shelves.set(homeRows()) }

    /** Back from the list view to the shelves. */
    private fun goHome() { homeMode = true; group = ALL; filter(); refreshHome(); shelves.focusFirst() }

    /** The group of the person's that [g] shows ([Store.FAV] for the favourites), or null for a category of the country. */
    private fun mine(g: String) = if (g == FAVS) Store.FAV else if (g.startsWith(MINE)) g.removePrefix(MINE) else null
    private fun isList(g: String) = g.startsWith(PL)

    /** A playlist's channels, read off the main thread the first time it is opened (or [fresh]); the grid and the rail follow when it lands. */
    private fun loadList(id: String, fresh: Boolean) {
        val p = Store.playlist(this, id) ?: return
        if (!fresh && plData.containsKey(id)) return
        plData[id] = null
        io.execute {
            val r = Playlists.channels(this, p, fresh)
            main.post {
                if (isDestroyed) return@post
                plData[id] = r
                buildRail(); if (group == PL + id) filter()
            }
        }
    }
    private fun mineName(gid: String) = if (gid == Store.FAV) getString(R.string.favourites) else Store.group(this, gid)?.name ?: ""

    private fun countryOf(i: Intent?) = i?.data?.takeIf { it.scheme == "tvchannels" && it.host == "country" }
        ?.pathSegments?.firstOrNull()?.lowercase()?.takeIf { it.length == 2 }

    private fun columns() = (resources.displayMetrics.widthPixels / resources.displayMetrics.density - 280).let { (it / 130).toInt().coerceIn(2, 7) }

    private fun load(fresh: Boolean) {
        Store.setCountry(this, cc)
        if (fresh && isList(group)) loadList(group.removePrefix(PL), true)   // Refresh on a playlist reads that link again too
        all = emptyList(); failed = false; loading = true; catRes = null
        chrome()
        val country = cc
        io.execute {
            var last = -1
            val iptv = Catalog.iptv(this, country, fresh) { got, total ->      // a bar while the list comes in (total -1: not told: the spinner stays)
                val pct = if (total > 0) (got * 100 / total).toInt().coerceAtMost(100) else -1
                if (pct != last) { last = pct; main.post { if (loading && country == cc) state.loading(progressWords(pct), pct) } }
            }
            // the first screen is iptv-org's list alone (one request); the providers' channels and the categories need more requests
            // (a slow TV measured them at most of the old 5 s wait), so they come after, unless all kept: then nothing to wait for
            val quick = !fresh && Providers.warm(this, country) && Categories.current(this, country) != null && !Categories.stale(this, country)
            var list = if (quick) Providers.merge(this, country, iptv, false) else iptv
            var doc = Categories.current(this, country)               // the kept copy at once; the site is asked only when there is none (or Refresh)
            val known = Catalog.young(this, "countries.json")
            fun nameOf() = Catalog.countries(this).firstOrNull { it.code == country }?.let { "${it.flag} ${it.name}" } ?: country.uppercase()
            fun full(l: List<Channel>, d: Categories.Doc?) = if (d == null || d.also.isEmpty()) l else l + Categories.alsoKept(this, country, d)   // + the other countries' channels the file asks for (kept copy)
            fun show(l: List<Channel>, name: String, res: Categories.Result?, first: Boolean) = main.post {
                if (isDestroyed || country != cc) return@post          // gone, or another country was chosen meanwhile
                all = l.byChannel(); failed = l.isEmpty() && !java.io.File(filesDir, "m3u-$country.m3u").exists(); countryName = name; loading = false; catRes = res
                if (homeShown() && group != SEARCH) group = ALL
                if (mine(group) == null && !isList(group) && group != ALL && group != SEARCH && all.none { it.first().group == group }) group = ALL
                buildRail(); filter()
                if (!first) { refreshHome(); return@post }              // the person may be on a card already: it keeps the focus
                if (homeShown()) {
                    refreshHome()
                    val p = pendingRow; pendingRow = null
                    if (p == null || !shelves.focusRow(p)) shelves.focusFirst()
                } else if (!find.hasFocus()) grid.post { (grid.getChildAt(0) ?: rail.getChildAt(0))?.requestFocus() }
            }
            if (quick) {
                val l2 = full(list, doc)
                val res = doc?.let { Categories.categorize(it, l2.byChannel()) }
                show(l2, nameOf(), res, true)
            } else {
                main.post { if (loading && country == cc) state.loading(getString(R.string.loading_read), 100) }
                if (iptv.isNotEmpty()) { show(list, if (known) nameOf() else country.uppercase(), doc?.let { Categories.categorize(it, list.byChannel()) }, true) }
                val cats = if (doc == null || fresh) Providers.async { Categories.refresh(this, country, fresh) } else null   // beside the providers
                list = Providers.merge(this, country, iptv, fresh)
                if (cats != null) { Providers.join(cats); doc = Categories.current(this, country) }
                val l2 = full(list, doc)
                val res = doc?.let { Categories.categorize(it, l2.byChannel()) }
                show(l2, nameOf(), res, false)
            }
            // the other countries' channels the file asks for: kept ones were on the screen already; the index (3 MB, once a day) comes last, so it never delays the first screen
            Categories.current(this, country)?.takeIf { Categories.alsoStale(this, country, it) }?.let { d ->
                if (Categories.alsoFetch(this, country, d).isNotEmpty()) {
                    val l2 = full(list, d); val res = Categories.categorize(d, l2.byChannel())
                    show(l2, nameOf(), res, false)
                }
            }
            if (Categories.stale(this, country)) {                    // a day old: the site's newer file is used next time, or now if it changed what is shown
                Categories.refresh(this, country)
                val now = Categories.current(this, country)
                if (now != null && now !== doc) { val r2 = Categories.categorize(now, full(list, now).byChannel()); main.post { if (!isDestroyed && country == cc) { catRes = r2; refreshHome() } } }
            }
        }
    }

    /** "Downloading the channel list… 42%" for the bar; no number before the size is told. */
    private fun progressWords(pct: Int) = if (pct < 0) getString(R.string.loading) else getString(R.string.loading_pct, pct)

    private fun buildRail() {
        val hadFocus = rail.hasFocus()
        val at = (0 until rail.childCount).firstOrNull { rail.getChildAt(it).hasFocus() } ?: -1
        rail.removeAllViews()
        val dp = { n: Int -> TvUi.dp(this, n).toInt() }
        rail.addView(LinearLayout(this).apply {                       // the app's own tile beside its name
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(18))
            val ic = resources.getDimension(R.dimen.tv_head_icon).toInt(); val rad = resources.getDimension(R.dimen.tv_head_radius)
            addView(ImageView(this@BrowseActivity).apply {
                setImageResource(R.drawable.icon)
                outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, rad) } }
                clipToOutline = true
            }, LinearLayout.LayoutParams(ic, ic).apply { marginEnd = resources.getDimension(R.dimen.tv_head_gap).toInt() })
            addView(TvUi.text(this@BrowseActivity, getString(R.string.app_name), 0f, TvUi.TEXT, true).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tv_head_name)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        })
        if (catRes != null) add(TvUi.row(this, R.drawable.ic_home, getString(R.string.home)) { goHome() }, false)   // back to the shelves
        add(TvUi.row(this, R.drawable.ic_search, getString(R.string.search)) { enterSearch() }, group == SEARCH)   // an action: OK only, moving onto it opens nothing
        // sections, each with its title: what the person keeps (its "+" last), their m3u links (the same), settings, then the country's kinds
        fun section(title: String, line: Boolean = true) {
            if (line) rail.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(10); bottomMargin = dp(2) })
            rail.addView(TvUi.text(this, title, 13f, TvUi.DIM).apply { setPadding(dp(12), dp(8), 0, dp(4)) })
        }
        section(getString(R.string.my_groups))
        val favs = Store.favourites(this).byChannel().size
        add(TvUi.row(this, R.drawable.ic_star, getString(R.string.favourites), if (favs > 0) "$favs" else "") { pick(FAVS) }.held(Store.FAV), group == FAVS, FAVS)
        val rem = Reminders.all(this).size
        if (rem > 0) add(TvUi.row(this, R.drawable.ic_bell, getString(R.string.remind_list), "$rem") { remindList() }, false)   // only while there are reminders
        for (g in Store.groups(this)) {
            val n = g.channels.byChannel().size
            add(TvUi.row(this, R.drawable.ic_folder, g.name, if (n > 0) "$n" else "") { pick(MINE + g.id) }.held(g.id), group == MINE + g.id, MINE + g.id)
        }
        add(newGroup().let { t -> TvUi.row(this, t.icon, t.label) { t.go() } }, false)
        section(getString(R.string.your_lists))                       // the person's own m3u links (typed here, or set from a phone or computer)
        for (l in Store.playlists(this)) {
            val n = plData[l.id]?.channels?.byChannel()?.size
            add(TvUi.row(this, R.drawable.ic_files, l.name, if (n != null && n > 0) "$n" else "") { pick(PL + l.id) }.heldList(l.id), group == PL + l.id, PL + l.id)
        }
        addLink()?.let { t -> add(TvUi.row(this, t.icon, t.label) { t.go() }, false) }
        section(getString(R.string.settings))
        for (t in settings()) add(TvUi.row(this, t.icon, t.label) { t.go() }, false)
        if (all.isNotEmpty()) section(countryName.ifEmpty { getString(R.string.all) })
        if (all.isNotEmpty()) {
        add(TvUi.row(this, R.drawable.ic_tv, getString(R.string.all), "${all.size}") { pick(ALL) }, group == ALL, ALL)
        all.groupingBy { it.first().group }.eachCount().entries.sortedByDescending { it.value }.forEach { (g, n) ->
            add(TvUi.row(this, groupIcon(g), groupName(this, g), "$n") { pick(g) }, group == g, g)
        }
        }
        if (hadFocus && at in 0 until rail.childCount) rail.getChildAt(at).requestFocus()   // a rebuilt list keeps the person's place
    }

    /** A row of one of the person's groups: Menu or a long press opens [groupMenu]. */
    private fun View.held(gid: String) = apply { tag = "grp:$gid"; isLongClickable = true; setOnLongClickListener { groupMenu(gid); true } }
    private fun View.heldList(id: String) = apply { tag = "pl:$id"; isLongClickable = true; setOnLongClickListener { listMenu(id); true } }

    /**
     * [g] set: a group or category, opened on OK or Right (it is then the selected row, its channels on the right); moving
     * through the list only moves the focus. Opening on the focus redrew the grid on every row passed: lag on a fast scroll.
     */
    private fun add(v: View, on: Boolean, g: String? = null) {
        v.isActivated = on
        if (g != null) v.setTag(TAG_GROUP, g)
        rail.addView(v, LinearLayout.LayoutParams(-1, -2))
    }

    /** Opens [g] with the list's focus where it is: only the grid and the marked row change, nothing is rebuilt. */
    private fun select(g: String) {
        group = g
        for (i in 0 until rail.childCount) rail.getChildAt(i).let { v -> (v.getTag(TAG_GROUP) as? String)?.let { v.isActivated = it == g } }
        filter(); grid.scrollToPosition(0)
    }

    private fun pick(g: String) {
        if (isList(g) && plData[g.removePrefix(PL)]?.ok == false) plData.remove(g.removePrefix(PL))   // failed (offline?): opening it again asks again
        homeMode = false; group = g; buildRail(); filter()
        intoGrid()
    }

    /** Focus to the card this group had last, else the first. */
    private fun intoGrid() {
        val n = (last[group]?.takeIf { it in shown.indices } ?: 0).let { if (shown.getOrNull(it)?.first()?.id == HDR) it + 1 else it }
        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(n, grid.height / 3)
        grid.post { (grid.findViewHolderForAdapterPosition(n)?.itemView ?: grid.getChildAt(0))?.requestFocus() }
    }

    private fun filter() {
        val gid = mine(group)
        val pl = if (isList(group)) group.removePrefix(PL) else null
        if (pl != null) loadList(pl, false)
        val searching = group == SEARCH
        TvUi.show(find, searching)
        if (!searching) {
            GlobalIndex.release(); main.removeCallbacks(globalRun); gseq++
            if (find.text.isNotEmpty()) { find.setText(""); return }  // its watcher comes back here
        }
        val q = if (searching) Find.fold(find.text.toString()) else ""
        shown = when {
            searching -> {
                val local = if (q.isEmpty()) emptyList() else all.mapNotNull { ch -> GlobalIndex.rank(Find.fold(ch.first().name), q)?.let { it to ch } }.sortedBy { it.first }.map { it.second }
                local + when {
                    q.isEmpty() -> emptyList()
                    otherState == LOADING -> listOf(header(getString(R.string.searching_all)))
                    otherState == OFFLINE -> listOf(header(getString(R.string.other_offline)))
                    otherState == DONE && others.isNotEmpty() -> listOf(header(getString(R.string.other_countries))) + others
                    else -> emptyList()
                }
            }
            gid != null -> live(Store.channels(this, gid).byChannel())
            pl != null -> plData[pl]?.channels?.byChannel().orEmpty()
            group == ALL -> all
            else -> all.filter { it.first().group == group }
        }
        cards.notifyDataSetChanged()
        title.text = when (group) {
            SEARCH -> getString(R.string.search)
            ALL -> countryName
            else -> if (gid != null) mineName(gid) else if (pl != null) Store.playlist(this, pl)?.name.orEmpty() else "$countryName · ${groupName(this, group)}"
        }
        chrome()
        when {
            searching && q.isEmpty() -> say(getString(R.string.search_hint), false)
            searching && otherState == DONE && shown.isEmpty() -> say(getString(R.string.no_match), false)
            pl != null && shown.isEmpty() -> say(getString(when {
                !plData.containsKey(pl) || plData[pl] == null -> R.string.loading
                plData[pl]?.ok != true -> R.string.list_failed
                else -> R.string.list_empty }), false)
            shown.isNotEmpty() || all.isEmpty() && gid == null -> say("", false)
            q.isNotEmpty() -> say(getString(R.string.no_match), false)
            gid == Store.FAV -> say(getString(R.string.no_favs), false)
            gid != null -> say(getString(R.string.no_in_group), false)
            else -> say(getString(R.string.none_here), true)
        }
    }

    private fun header(text: String) = listOf(Channel(HDR, text, "", "", ""))

    private fun hideKeys(v: View) { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }

    /** Focus to the first result card (not the section header); stays in the field when there is none. */
    private fun firstCard() {
        val at = shown.indexOfFirst { it.first().id != HDR }
        if (at < 0) return
        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(if (at > 0) 0 else at, 0)   // from the top: the section line stays in view
        grid.post { grid.findViewHolderForAdapterPosition(at)?.itemView?.requestFocus() }
    }

    /** The first row of the side list: the field gets the focus and the keyboard, and results come from every country. */
    private fun enterSearch() {
        homeMode = false; group = SEARCH; others = emptyList(); otherState = IDLE
        find.setText("")
        buildRail(); filter()
        find.requestFocus()
        find.post { find.raise() }
    }

    /** The text changed: ask the global index after a pause (the first time it is downloaded, and the card says so). */
    private fun scheduleOthers() {
        main.removeCallbacks(globalRun)
        val q = Find.fold(find.text.toString())
        if (q.length < 2) { gseq++; others = emptyList(); otherState = IDLE; return }
        if (!GlobalIndex.held()) { others = emptyList(); otherState = LOADING }
        main.postDelayed(globalRun, 120)
    }

    private fun searchOthers() {
        val q = Find.fold(find.text.toString())
        if (group != SEARCH || q.length < 2) return
        val n = ++gseq
        searcher.execute {
            val idx = GlobalIndex.get(this)
            val res = idx?.search(q, cc).orEmpty()
            if (idx != null && names.isEmpty()) names = Catalog.countries(this).associate { it.code to "${it.flag} ${it.name}" }
            main.post {
                if (isDestroyed || n != gseq || group != SEARCH) return@post
                val here = all.mapTo(HashSet()) { it.first().id.substringBefore('@') }   // the country's list carries some (its "also" ones): not twice
                others = res.filter { it.first().id.substringBefore('@') !in here }; otherState = if (idx == null) OFFLINE else DONE
                filter()
            }
        }
    }

    private fun say(s: String, again: Boolean) { note.text = s; TvUi.show(note, s.isNotEmpty()) }

    /** One state for the whole screen while there is no list to show: loading (a spinner), offline or empty (the words, Retry, Country). */
    private fun chrome() {
        val on = loading || all.isEmpty() && mine(group) == null && !isList(group) && group != SEARCH
        val home = !on && homeShown()
        TvUi.show(state, on); TvUi.show(content, !on && !home); TvUi.show(shelves.view, home)
        if (!on) return
        if (loading) state.loading(getString(R.string.loading)) else state.message(getString(if (failed) R.string.offline else R.string.none_here))
    }


    fun play(ch: List<Channel>) = play(ch, shown.filter { it.first().id != HDR }, title.text.toString())

    /** [ch] with ▲▼ in the player stepping through [lineup] (the shelf or list it was chosen from). */
    fun play(ch: List<Channel>, lineup: List<List<Channel>>, name: String) {
        Lineup.set(lineup, name)
        startActivity(Intent(Intent.ACTION_VIEW, Pins.link(ch.first().id)).setClass(this, PlayActivity::class.java))
    }

    /** What can be done with one channel: on a TV the Menu key or a long OK, on a phone a long press. */
    private var actionsDlg: android.app.Dialog? = null
    fun actions(ch: List<Channel>) {
        if (actionsDlg?.isShowing == true) return                     // one press can reach here twice (long-click and Menu key): a second sheet would stack
        val c = ch.first()
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog)
        actionsDlg = dlg
        TvUi.sheetTitle(box, c.name)
        box.addView(TvUi.row(this, R.drawable.ic_play, getString(R.string.play)) { dlg.dismiss(); play(ch) })
        val fav = Store.isFavourite(this, c.id)
        box.addView(TvUi.row(this, R.drawable.ic_star, getString(if (fav) R.string.remove_fav else R.string.add_fav)) {
            dlg.dismiss(); Store.toggleFavourite(this, ch); buildRail(); filter()
        })
        box.addView(TvUi.row(this, R.drawable.ic_folder, getString(R.string.to_group)) { dlg.dismiss(); groupsFor(ch) })
        box.addView(TvUi.row(this, R.drawable.ic_guide, getString(R.string.guide)) { dlg.dismiss(); guideOf(ch) })
        if (Pins.can(this)) box.addView(TvUi.row(this, R.drawable.ic_home, getString(R.string.pin)) {
            dlg.dismiss()
            io.execute {
                val logo = Logos.forPin(this, c.logo, 640)
                val ok = Pins.pin(this, c, logo)
                main.post { if (!ok) Toast.makeText(this, R.string.pin_refused, Toast.LENGTH_LONG).show() }
            }
        })
        val why = listOfNotNull(c.quality.takeIf { it.isNotEmpty() }, groupName(this, c.group),
            getString(R.string.geo).takeIf { c.geo }, getString(R.string.not_always).takeIf { c.notAlways },
            getString(R.string.down).takeIf { Store.alive(this, c.url) == false && ch.size == 1 })
        box.addView(TvUi.text(this, why.joinToString(" · "), 13f, TvUi.DIM).apply {
            setPadding(TvUi.dp(this@BrowseActivity, 12).toInt(), TvUi.dp(this@BrowseActivity, 10).toInt(), 0, 0) })
        dlg.show()
    }

    /** A channel's programme guide, the show on now focused. */
    private fun guideOf(ch: List<Channel>) {
        io.execute {
            val shows = Guide.of(this, ch)
            main.post {
                if (isDestroyed) return@post
                if (shows.isEmpty()) { Toast.makeText(this, R.string.guide_none, Toast.LENGTH_SHORT).show(); return@post }
                val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 480)
                TvUi.sheetTitle(box, "${getString(R.string.guide)} · ${ch.first().name}")
                val list = GuideList.view(this, shows)
                box.addView(list, LinearLayout.LayoutParams(-1, TvUi.dp(this, 440).toInt()))
                dlg.show()
                cards.notifyDataSetChanged()                          // the cards' "on now" lines, now known
            }
        }
    }

    private class Tool(val icon: Int, val label: String, val go: () -> Unit)

    /** The actions the rail and the "⋯" menu share, made once so the two never offer different things. */
    private fun newGroup() = Tool(R.drawable.ic_plus, getString(R.string.new_group)) { ask(getString(R.string.new_group), "") { pick(MINE + Store.addGroup(this, it)) } }
    private fun addLink() = if (Store.playlists(this).size < Edit.MAX_LISTS) Tool(R.drawable.ic_link, getString(R.string.list_add)) { addList() } else null
    private fun settings() = listOf(
        Tool(R.drawable.ic_globe, countryName.ifEmpty { getString(R.string.country) }) { countries() },
        Tool(R.drawable.ic_subtitle, getString(R.string.lang, langName(Lang.pref(this)))) { languages() },
        Tool(R.drawable.ic_refresh, getString(R.string.refresh)) { load(true) })

    /** Everything that is not "watch a channel", out of the way of the shelves: opened by "⋯" or the Menu key. */
    private fun homeMenu() {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 380)
        TvUi.sheetTitle(box, getString(R.string.more))
        fun item(icon: Int, label: String, count: String = "", go: () -> Unit) = box.addView(TvUi.row(this, icon, label, count) { dlg.dismiss(); go() })
        item(R.drawable.ic_tv, getString(R.string.all_channels), "${all.size}") { pick(ALL) }
        item(R.drawable.ic_folder, getString(R.string.my_groups), Store.groups(this).size.takeIf { it > 0 }?.toString() ?: "") { myGroups() }
        val lists = Store.playlists(this)
        if (lists.isNotEmpty()) item(R.drawable.ic_files, getString(R.string.your_lists), "${lists.size}") { playlists() }
        val rem = Reminders.all(this).size
        if (rem > 0) item(R.drawable.ic_bell, getString(R.string.remind_list), "$rem") { remindList() }
        for (t in listOfNotNull(newGroup(), addLink())) item(t.icon, t.label, go = t.go)
        val dp = { n: Int -> TvUi.dp(this, n).toInt() }
        box.addView(TvUi.text(this, getString(R.string.settings), 13f, TvUi.DIM).apply { setPadding(dp(12), dp(10), 0, dp(4)) })
        for (t in settings()) item(t.icon, t.label, go = t.go)
        dlg.show()
        box.post { box.getChildAt(1)?.requestFocus() }              // the first row is where the D-pad starts
    }

    /** The favourites and the person's groups: OK on one opens its menu (open, pin, rename, delete). */
    private fun myGroups() {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 380)
        TvUi.sheetTitle(box, getString(R.string.my_groups))
        val favs = Store.favourites(this).byChannel().size
        box.addView(TvUi.row(this, R.drawable.ic_star, getString(R.string.favourites), if (favs > 0) "$favs" else "") { dlg.dismiss(); groupMenu(Store.FAV) })
        for (g in Store.groups(this)) {
            val n = g.channels.byChannel().size
            box.addView(TvUi.row(this, R.drawable.ic_folder, g.name, if (n > 0) "$n" else "") { dlg.dismiss(); groupMenu(g.id) })
        }
        box.addView(TvUi.row(this, R.drawable.ic_plus, getString(R.string.new_group)) {
            dlg.dismiss(); ask(getString(R.string.new_group), "") { Store.addGroup(this, it); Toast.makeText(this, R.string.group_made, Toast.LENGTH_LONG).show(); myGroups() }
        })
        dlg.show()
        box.post { box.getChildAt(1)?.requestFocus() }              // the first row is where the D-pad starts
    }

    /** The person's own m3u links: OK on one opens its menu (open, rename, delete), and a new link is added here. */
    private fun playlists() {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 380)
        TvUi.sheetTitle(box, getString(R.string.your_lists))
        val lists = Store.playlists(this)
        for (l in lists) box.addView(TvUi.row(this, R.drawable.ic_files, l.name) { dlg.dismiss(); listMenu(l.id) })
        if (lists.size < Edit.MAX_LISTS) box.addView(TvUi.row(this, R.drawable.ic_link, getString(R.string.list_add)) { dlg.dismiss(); addList() })
        dlg.show()
        box.post { box.getChildAt(1)?.requestFocus() }              // the first row is where the D-pad starts
    }

    /** An m3u link typed on the TV (the phone app's typing box opens with the TV's keyboard, so a pasted link is quick); named by its host. */
    private fun addList() {
        ask(getString(R.string.list_add), "", getString(R.string.list_url_hint), url = true, note = getString(R.string.list_add_note),
            check = { u ->
                val now = Store.playlists(this)
                when {
                    !Edit.validUrl(u) -> getString(R.string.list_bad)
                    now.any { it.url == u } -> getString(R.string.list_dup)
                    now.size >= Edit.MAX_LISTS -> getString(R.string.list_max, Edit.MAX_LISTS)
                    else -> null
                }
            }) { u ->
            val p = Store.Playlist(Edit.listId(u), java.net.URI(u).host.removePrefix("www.").take(Edit.MAX_NAME), u)
            Store.setPlaylists(this, Store.playlists(this) + p)
            pick(PL + p.id)
        }
    }

    /** One of the person's links: open it, rename it, or delete it (asked again, Keep first, as a group is). */
    private fun listMenu(id: String) {
        val l = Store.playlist(this, id) ?: return
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog)
        TvUi.sheetTitle(box, l.name)
        val host = java.net.URI(l.url).host ?: ""
        if (host != l.name) box.addView(TvUi.text(this, host, 14f, TvUi.DIM).apply { setPadding(TvUi.dp(this@BrowseActivity, 12).toInt(), 0, 0, TvUi.dp(this@BrowseActivity, 8).toInt()) })   // the host only: the link may carry a token
        box.addView(TvUi.row(this, R.drawable.ic_play, getString(R.string.open)) { dlg.dismiss(); pick(PL + id) })
        box.addView(TvUi.row(this, R.drawable.ic_pen, getString(R.string.rename)) {
            dlg.dismiss()
            ask(getString(R.string.rename), l.name, getString(R.string.list_name)) { n -> Store.setPlaylists(this, Store.playlists(this).map { if (it.id == id) Store.Playlist(id, n, it.url) else it }); buildRail(); filter() }
        })
        box.addView(TvUi.row(this, R.drawable.ic_trash, getString(R.string.list_delete)) {
            dlg.dismiss()
            val (sure, b) = TvUi.sheet(this, R.style.Dialog)
            TvUi.sheetTitle(b, getString(R.string.list_delete_ask, l.name))
            b.addView(TvUi.row(this, R.drawable.ic_close, getString(R.string.keep)) { sure.dismiss() })
            b.addView(TvUi.row(this, R.drawable.ic_trash, getString(R.string.list_delete)) {
                sure.dismiss()
                Store.setPlaylists(this, Store.playlists(this).filter { it.id != id }); plData.remove(id)
                if (group == PL + id) group = ALL
                buildRail(); filter()
            })
            sure.show()
        })
        dlg.show()
        box.post { (0 until box.childCount).map { box.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus() }   // Open
    }

    private fun langName(l: String) = dev.tvremote.i18n.Langs.NAMES[l] ?: getString(R.string.lang_auto)

    /** Three choices, the one in use marked; a new one is saved and the screen made again in it, on the same group. */
    private fun languages() {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 320)
        TvUi.sheetTitle(box, getString(R.string.lang_title))
        val now = Lang.pref(this)
        for (l in Lang.CHOICES) box.addView(TvUi.row(this, R.drawable.ic_check, langName(l)) {
            dlg.dismiss()
            if (l == now) return@row
            Lang.set(this, l); keepGroup = if (group == SEARCH) ALL else group
            recreate()
        }.also { if (l != now) (it.getChildAt(0) as ImageView).alpha = 0f })   // the tick's room stays, so the words line up
        dlg.show()
        box.post { box.getChildAt(1 + Lang.CHOICES.indexOf(now))?.requestFocus() }
    }

    private val upd by lazy { SelfUpdate(this, "channels", "tv-channels.apk") }

    /** A newer TV Channels in the latest release (SelfUpdate): Update now / Later / Skip this version; Back is a later too. */
    private fun askUpdate(r: Release) {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 420)
        TvUi.sheetTitle(box, getString(R.string.upd_title, r.version))
        TvUi.sheetNote(box, getString(R.string.upd_note, upd.version()))
        box.addView(TvUi.row(this, R.drawable.ic_download, getString(R.string.upd_now)) { dlg.dismiss(); updating(r) })
        box.addView(TvUi.row(this, R.drawable.ic_clock, getString(R.string.upd_later)) { upd.later(); dlg.dismiss() })
        box.addView(TvUi.row(this, R.drawable.ic_eye_off, getString(R.string.upd_skip)) { upd.skip(r); dlg.dismiss() })
        dlg.show()
        box.post { box.getChildAt(2)?.requestFocus() }
    }

    /** Its download, with Cancel (and Back); at 100 % Android's installer takes over with its own question. */
    private fun updating(r: Release) {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 420)
        TvUi.sheetTitle(box, getString(R.string.upd_title, r.version))
        val line = TvUi.sheetNote(box, getString(R.string.upd_loading, 0))
        box.addView(TvUi.row(this, R.drawable.ic_close, getString(R.string.upd_cancel)) { dlg.dismiss() })
        dlg.setOnDismissListener { upd.cancel() }
        dlg.show()
        box.post { box.getChildAt(2)?.requestFocus() }
        val started = upd.install(r, { pct ->
            line.text = getString(R.string.upd_loading, pct)
            if (pct >= 100) { dlg.setOnDismissListener(null); dlg.dismiss() }
        }, { m ->
            dlg.setOnDismissListener(null); dlg.dismiss()
            val (d2, b2) = TvUi.sheet(this, R.style.Dialog, 420)
            TvUi.sheetTitle(b2, getString(R.string.upd_title, r.version))
            TvUi.sheetNote(b2, getString(R.string.upd_failed, m))
            b2.addView(TvUi.row(this, R.drawable.ic_check, getString(R.string.ok)) { d2.dismiss() })
            d2.show(); b2.post { b2.getChildAt(2)?.requestFocus() }
        })
        if (!started) { dlg.setOnDismissListener(null); dlg.dismiss() }  // Android's "install unknown apps" first: back from it, onResume goes on
    }

    /** Which of the person's groups [ch] is in: a tick on each, OK puts it in or takes it out; and a new group with it in. */
    private fun groupsFor(ch: List<Channel>) {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog)
        val id = ch.first().id
        TvUi.sheetTitle(box, ch.first().name)
        for (g in Store.groups(this)) {
            val row = TvUi.row(this, if (Store.has(this, g.id, id)) R.drawable.ic_check else R.drawable.ic_folder, g.name) {}
            row.setOnClickListener {
                val on = Store.toggle(this, g.id, ch)
                (row.getChildAt(0) as ImageView).setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_folder)
                buildRail(); if (group == MINE + g.id) filter()
            }
            box.addView(row)
        }
        box.addView(TvUi.row(this, R.drawable.ic_plus, getString(R.string.new_group)) {
            dlg.dismiss()
            ask(getString(R.string.new_group), "") { name -> Store.toggle(this, Store.addGroup(this, name), ch); buildRail(); filter() }
        })
        dlg.show()
        box.post { box.getChildAt(1)?.requestFocus() }
    }

    /** The reminders set: OK on one removes it. */
    private fun remindList() {
        val items = Reminders.all(this).sortedBy { it.start }
        if (items.isEmpty()) { buildRail(); return }
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 560)
        TvUi.sheetTitle(box, getString(R.string.remind_list))
        var left = items.size
        for (r in items) {
            lateinit var row: View
            row = TvUi.row(this, R.drawable.ic_close, "${Guide.hm(r.start)}  ${r.title} · ${r.name}") {
                Reminders.remove(this, r); buildRail()
                val at = box.indexOfChild(row)                        // only this row goes; the focus moves to its neighbour, not back to the top
                val to = box.getChildAt(at + 1)?.takeIf { it.isFocusable } ?: box.getChildAt(at - 1)?.takeIf { it.isFocusable }
                box.removeView(row)
                if (--left == 0) dlg.dismiss() else to?.requestFocus()
            }
            box.addView(row)
        }
        box.addView(TvUi.text(this, getString(R.string.remind_list_hint), 14f, TvUi.DIM).apply { setPadding(TvUi.dp(this@BrowseActivity, 12).toInt(), TvUi.dp(this@BrowseActivity, 8).toInt(), 0, 0) })
        dlg.show()
        box.post { box.getChildAt(1)?.requestFocus() }
    }

    /** One group's menu: pin it to the Home screen; the person's own groups can also be renamed and deleted. */
    private fun groupMenu(gid: String) {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog)
        val name = mineName(gid)
        TvUi.sheetTitle(box, name)
        box.addView(TvUi.row(this, R.drawable.ic_play, getString(R.string.open)) { dlg.dismiss(); pick(if (gid == Store.FAV) FAVS else MINE + gid) })
        if (Pins.can(this)) box.addView(TvUi.row(this, R.drawable.ic_home, getString(R.string.pin)) {
            dlg.dismiss()
            val logos = Store.channels(this, gid).byChannel().map { it.first().logo }.filter { it.isNotEmpty() }.take(4)
            io.execute {
                val bits = logos.mapNotNull { Logos.forPin(this, it, 320) }
                val ok = Pins.pinGroup(this, gid, name, bits)
                main.post { if (!ok) Toast.makeText(this, R.string.pin_refused, Toast.LENGTH_LONG).show() }
            }
        })
        if (gid != Store.FAV) {
            box.addView(TvUi.row(this, R.drawable.ic_pen, getString(R.string.rename)) {
                dlg.dismiss()
                ask(getString(R.string.rename), name) { Store.renameGroup(this, gid, it); Pins.renamed(this, gid, it); buildRail(); filter() }
            })
            box.addView(TvUi.row(this, R.drawable.ic_trash, getString(R.string.delete_group)) {
                dlg.dismiss()
                val (sure, b) = TvUi.sheet(this, R.style.Dialog)                               // asked again, Keep first: a stray OK loses nothing
                TvUi.sheetTitle(b, getString(R.string.delete_ask, name))
                b.addView(TvUi.row(this, R.drawable.ic_close, getString(R.string.keep)) { sure.dismiss() })
                b.addView(TvUi.row(this, R.drawable.ic_trash, getString(R.string.delete_group)) {
                    sure.dismiss()
                    Store.deleteGroup(this, gid); Pins.deleted(this, gid)
                    if (group == MINE + gid) group = ALL
                    buildRail(); filter()
                })
                sure.show()
            })
        }
        dlg.show()
    }

    /** A name (or, [url], a link), typed: OK keeps it (not when empty; [check] may refuse it in one line), Back leaves it. */
    private fun ask(title: String, now: String, hint: String = getString(R.string.group_name), url: Boolean = false, note: String? = null,
                    check: (String) -> String? = { null }, done: (String) -> Unit) {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, if (url) 560 else 420)
        TvUi.sheetTitle(box, title)
        if (note != null) box.addView(TvUi.text(this, note, 14f, TvUi.DIM).apply { setPadding(0, 0, 0, TvUi.dp(this@BrowseActivity, 8).toInt()) })
        val f = TvUi.field(this, hint, 16f, EditorInfo.IME_ACTION_DONE, TvUi.FIELD_REST, TvUi.R_FIELD, 14, 7).apply {
            if (url) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(now); setSelection(now.length)
        }
        val ok = {
            val t = f.text.toString().trim()
            val bad = if (t.isEmpty()) hint else check(t)                // empty: say what goes there and go back to the field, never a silent OK
            if (bad != null) { Toast.makeText(this, bad, Toast.LENGTH_LONG).show(); if (t.isEmpty()) f.requestFocus() }
            else { dlg.dismiss(); done(if (url) t else t.take(40)) }
        }
        f.setOnEditorActionListener { _, _, _ -> ok(); true }
        box.addView(f, LinearLayout.LayoutParams(-1, TvUi.dp(this, 46).toInt()).apply { bottomMargin = TvUi.dp(this@BrowseActivity, 8).toInt() })
        box.addView(TvUi.row(this, R.drawable.ic_check, getString(R.string.ok)) { ok() })
        dlg.show()
        f.requestFocus(); f.post { f.raise() }
    }

    private fun countries() {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 420)
        var preBack: () -> Boolean = { false }
        var swallowUp = false                                      // the Back that left the field must not close the modal on its key-up
        val q = TvUi.field(this, getString(R.string.country), 15f, EditorInfo.IME_ACTION_SEARCH, TvUi.FIELD_REST, TvUi.R_FIELD, 14, 7).apply {
            first = { k, e -> k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_DOWN && preBack() }   // Back while the keyboard is up goes to the keyboard first: caught here, before it
        }
        box.addView(q, LinearLayout.LayoutParams(-1, TvUi.dp(this, 42).toInt()).apply { bottomMargin = TvUi.dp(this@BrowseActivity, 8).toInt() })
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@BrowseActivity) }
        val none = TvUi.text(this, getString(R.string.country_loading), 17f, TvUi.DIM).apply { gravity = Gravity.CENTER }   // until the list comes
        val lh = (resources.displayMetrics.heightPixels * 0.62).toInt()
        box.addView(FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1)); addView(none, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, lh))
        io.execute {
            val every = Catalog.countries(this)
            main.post {
                if (!dlg.isShowing) return@post
                none.text = getString(if (every.isEmpty()) R.string.offline else R.string.country_none)       // nothing came: the network, not "no country"
                none.visibility = if (every.isEmpty()) View.VISIBLE else View.GONE
                var now = every.sortedBy { it.code != cc }
                val ad = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = now.size
                    override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(
                        TvUi.row(this@BrowseActivity, 0, "") {}.apply { layoutParams = RecyclerView.LayoutParams(-1, -2) }) {}
                    override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                        val k = now[i]; val row = h.itemView as LinearLayout
                        (row.getChildAt(0) as TextView).text = "${k.flag}  ${k.name}"
                        row.isActivated = k.code == cc
                        row.setOnClickListener { dlg.dismiss(); if (k.code != cc) { cc = k.code; group = ALL; homeMode = true; find.setText(""); load(false) } }
                    }
                }
                list.adapter = ad
                // Back / the keyboard's action key leave the field for the results (the selected country, else the first); Down and Up cross between them
                val imm = (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                val toList = fun(): Boolean {
                    if (now.isEmpty()) return false
                    val at = now.indexOfFirst { it.code == cc }.coerceAtLeast(0)
                    list.scrollToPosition(at)
                    list.post { (list.findViewHolderForAdapterPosition(at)?.itemView ?: list.getChildAt(0))?.requestFocus() }
                    return true
                }
                q.setOnEditorActionListener { _, _, _ -> imm.hideSoftInputFromWindow(q.windowToken, 0); toList(); true }
                preBack = { if (q.text.isNotEmpty() && now.isNotEmpty()) { swallowUp = true; imm.hideSoftInputFromWindow(q.windowToken, 0); toList() } else false }
                dlg.setOnKeyListener { _, k, e ->
                    val down = e.action == KeyEvent.ACTION_DOWN
                    if (k == KeyEvent.KEYCODE_BACK && !down && swallowUp) { swallowUp = false; return@setOnKeyListener true }
                    when {
                        k == KeyEvent.KEYCODE_BACK && q.hasFocus() && q.text.isNotEmpty() && now.isNotEmpty() -> {
                            if (down) { imm.hideSoftInputFromWindow(q.windowToken, 0); toList() }; true }
                        k == KeyEvent.KEYCODE_DPAD_DOWN && q.hasFocus() && now.isNotEmpty() -> { if (down) toList(); true }
                        k == KeyEvent.KEYCODE_DPAD_UP && down && list.hasFocus() &&
                            (list.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition() <= 0 &&
                            list.findContainingViewHolder(dlg.currentFocus ?: q)?.bindingAdapterPosition == 0 -> { q.requestFocus(); true }
                        else -> false
                    }
                }
                q.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val f = fold(s.toString())
                        now = if (f.isEmpty()) every.sortedBy { it.code != cc } else every.filter { fold(it.name).contains(f) || it.code == f }
                        ad.notifyDataSetChanged()
                        none.visibility = if (now.isEmpty()) View.VISIBLE else View.GONE
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                list.post { list.getChildAt(0)?.requestFocus() }
            }
        }
        dlg.show()
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        val v0 = currentFocus
        if (homeShown() && shelves.view.visibility == View.VISIBLE) {      // the shelves: their own D-pad; Menu on a card is its menu (hold OK is the card's own long click), Menu elsewhere the "⋯" menu
            val ch = shelves.channelAt(v0)
            when {
                k == KeyEvent.KEYCODE_MENU -> { if (ch != null) actions(ch) else homeMenu(); return true }
                k == KeyEvent.KEYCODE_BACK && e.repeatCount == 0 && shelves.toTop() -> return true
                (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && shelves.key(k) -> return true
            }
        }
        if (k == KeyEvent.KEYCODE_BACK && e.repeatCount == 0 && v0 != null && content.visibility == View.VISIBLE && !rail.hasFocus()) {
            if (group == SEARCH && find.hasFocus() && find.text.isNotEmpty()) { hideKeys(find); firstCard(); return true }   // the field -> its results, then the side list
            (0 until rail.childCount).map { rail.getChildAt(it) }.firstOrNull { it.isActivated }?.let { it.requestFocus(); return true }
        }
        if (k == KeyEvent.KEYCODE_BACK && e.repeatCount == 0 && catRes != null && !homeMode && content.visibility == View.VISIBLE) { goHome(); return true }   // the list view -> the shelves
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT && v0 != null && rail.hasFocus() && shown.isNotEmpty()) {   // into the grid, the open group made sure of
            (v0.getTag(TAG_GROUP) as? String)?.let { if (it != group) select(it) }
            intoGrid(); return true
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) {                        // from the grid's first column: back to the list on show, not the nearest row
            val at = currentFocus?.let { grid.findContainingViewHolder(it) }?.bindingAdapterPosition ?: -1
            if (at >= 0 && at % (grid.layoutManager as GridLayoutManager).spanCount == 0)
                (0 until rail.childCount).map { rail.getChildAt(it) }.firstOrNull { it.isActivated }?.let { it.requestFocus(); return true }
        }
        if (k == KeyEvent.KEYCODE_MENU) {                              // a long OK is the card's own long-click (Shelves, below): handled here too it opened a second sheet
            val v = currentFocus ?: return super.onKeyDown(k, e)
            val at = grid.findContainingViewHolder(v)?.bindingAdapterPosition ?: -1
            if (at in shown.indices) { if (shown[at].first().id != HDR) actions(shown[at]); return true }
            (v.tag as? String)?.takeIf { it.startsWith("grp:") }?.let { groupMenu(it.removePrefix("grp:")); return true }
            (v.tag as? String)?.takeIf { it.startsWith("pl:") }?.let { listMenu(it.removePrefix("pl:")); return true }
        }
        return super.onKeyDown(k, e)
    }

    private val guides = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + 4); r.run() }
    }
    private val asking = HashSet<String>()

    /** The card's "on now" line: asked for the cards drawn, two at a time, then the card is drawn again. */
    private fun guideFor(ch: List<Channel>) {
        val id = ch.first().id
        if (!asking.add(id)) return
        guides.execute {
            val shows = Guide.of(this, ch)
            main.post {
                asking.remove(id)
                if (!isDestroyed && Guide.now(shows) != null) shown.indexOfFirst { it.first().id == id }.takeIf { it >= 0 }?.let { cards.notifyItemChanged(it, cards.LINE) }
            }
        }
    }

    override fun onStop() { GlobalIndex.release(); super.onStop() }

    override fun onDestroy() { main.removeCallbacks(globalRun); searcher.shutdownNow(); GlobalIndex.release(); watching?.let { Store.unwatch(this, it) }; io.shutdownNow(); guides.shutdownNow(); super.onDestroy() }

    /** Stable ids: a list drawn again (a favourite or a group changed) keeps the focus on the same channel, not on whatever card took its place. */
    private inner class Cards : RecyclerView.Adapter<Cards.H>() {
        val LINE = "line"
        private val lightTile by lazy { TvUi.round(LogoFit.LIGHT_TILE, TvUi.dp(this@BrowseActivity, 12)) }   // lazy: the activity has no resources yet while its fields are built
        private val darkTile by lazy { TvUi.round(LogoFit.DARK_TILE, TvUi.dp(this@BrowseActivity, 12)) }   // lighter than the card, so its edge shows
        init { setHasStableIds(true) }
        override fun getItemViewType(i: Int) = if (shown[i].first().id == HDR) 1 else 0
        override fun getItemId(i: Int) = shown[i].first().let { if (it.id == HDR) -1L else it.id.hashCode().toLong() }   // one card per id ([byChannel])
        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val logo = v.findViewWithTag<ImageView>("logo")
            val letters = v.findViewWithTag<TextView>("letters")
            val name = v.findViewWithTag<TextView>("name")
            val sub = v.findViewWithTag<TextView>("sub")
            val star = v.findViewWithTag<ImageView>("star")
            val q = v.findViewWithTag<TextView>("q")
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val c = this@BrowseActivity
            if (t == 1) return H(TvUi.text(c, "", 15f, TvUi.DIM, true).apply {
                tag = "hdr"; layoutParams = RecyclerView.LayoutParams(-1, -2); setPadding(TvUi.dp(c, 8).toInt(), TvUi.dp(c, 14).toInt(), 0, TvUi.dp(c, 4).toInt())
            })
            val d = { v: Number -> TvUi.dp(c, v).toInt() }
            val card = object : LinearLayout(c) {
                override fun hasOverlappingRendering() = false           // a dimmed (dead) card is not drawn into an off-screen layer
            }.apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; isLongClickable = true
                setPadding(d(6), d(6), d(6), d(8))
                background = TvUi.states(c, Color.TRANSPARENT, TvUi.dp(c, 16))
                setOnFocusChangeListener { v, has ->
                    if (has) grid.getChildAdapterPosition(v).let { if (it >= 0) last[group] = it }
                    v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start() }
            }
            val tile = object : FrameLayout(c) {                         // 16:9 from its own width: a card made before the grid is measured must not take its logo's height
                override fun onMeasure(wm: Int, hm: Int) {
                    val w = MeasureSpec.getSize(wm)
                    super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY))
                }
            }.apply { background = TvUi.round(TvUi.TILE, TvUi.dp(c, 12)) }
            tile.addView(TextView(c).apply {
                tag = "letters"; gravity = Gravity.CENTER; textSize = 26f; setTextColor(0xFF5F6368.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, FrameLayout.LayoutParams(-1, -1))
            tile.addView(LogoView(c).apply { tag = "logo" }, FrameLayout.LayoutParams(-1, -1))
            tile.addView(ImageView(c).apply { tag = "star"; setImageResource(R.drawable.ic_star); imageTintList = android.content.res.ColorStateList.valueOf(0xFFF9AB00.toInt()) },
                FrameLayout.LayoutParams(d(18), d(18), Gravity.TOP or Gravity.END).apply { topMargin = d(6); rightMargin = d(6) })
            tile.addView(TvUi.text(c, "", 12f, Color.WHITE, true).apply {                // a corner: logos sit in the middle
                tag = "q"; setPadding(d(4), d(1), d(4), d(1)); background = TvUi.round(0x8C000000.toInt(), TvUi.dp(c, 4))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = d(5); leftMargin = d(5) })
            card.addView(tile, LinearLayout.LayoutParams(-1, -2))
            card.addView(TvUi.text(c, "", 14f).apply { tag = "name"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(d(4), d(8), d(4), 0) })
            card.addView(TvUi.text(c, "", 12f, TvUi.DIM).apply { tag = "sub"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(d(4), d(4), d(4), 0) })
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { val m = d(6); setMargins(m, m, m, m) }
            return H(card)
        }

        override fun onBindViewHolder(h: H, i: Int) {
            val ch = shown[i]; val c = ch.first()
            if (c.id == HDR) { (h.itemView as TextView).text = c.name; return }
            h.name.text = c.name
            h.letters.text = TvUi.initials(c.name)
            Logos.into(h.logo, c.logo, 280) { got ->
                h.letters.visibility = if (got) View.INVISIBLE else View.VISIBLE
                val dark = got && Logos.isLight(c.logo)                // pale lettering would vanish on the light tile
                (h.logo.parent as View).background = if (dark) darkTile else lightTile
            }
            line(h, ch)
            h.q.text = c.quality.removeSuffix("p").let { if (it.isEmpty()) "" else if (it.toIntOrNull()?.let { n -> n >= 720 } == true) if (it == "2160") "4K" else "HD" else "SD" }
            TvUi.show(h.q, c.quality.isNotEmpty())
            TvUi.show(h.star, group != FAVS && Store.isFavourite(this@BrowseActivity, c.id))
            h.itemView.setOnClickListener { play(ch) }
            h.itemView.setOnLongClickListener { actions(ch); true }
            ch.forEach { s -> Probe.check(this@BrowseActivity, s) { val at = shown.indexOf(ch); if (at >= 0) notifyItemChanged(at, LINE) } }
            val ahead = columns()                                     // the next two rows' logos start now, behind the ones on screen
            for (k in i + 1..minOf(i + 2 * ahead, shown.size - 1)) Logos.prefetch(this@BrowseActivity, shown[k].first().logo)
        }

        /** Only the line under the name and the dimming: what a stream check or the guide changes (the logo is not touched). */
        override fun onBindViewHolder(h: H, i: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(h, i) else if (shown[i].first().id != HDR) line(h, shown[i])
        }

        override fun onViewRecycled(h: H) { if (h.logo != null) Logos.cancel(h.logo) }

        private fun line(h: H, ch: List<Channel>) {
            val c = ch.first()
            val dead = ch.all { Store.alive(this@BrowseActivity, it.url) == false }
            val on = Guide.known(c.id)?.let { Guide.now(it) }
            val from = if (group == SEARCH && c.country.isNotEmpty() && c.country != cc) names[c.country] ?: c.group else null   // a flag and the country, for the other countries' cards
            h.sub.text = (on?.title ?: from ?: groupName(h.sub.context, c.group)).let { if (dead) "● $it" else it }
            if (on == null && Guide.known(c.id) == null) guideFor(ch)
            h.sub.setTextColor(if (dead) TvUi.BAD else TvUi.DIM)
            h.itemView.alpha = if (dead) 0.6f else 1f
        }
    }

    companion object {
        private const val TAG_GROUP = 0x7f0f0001                      // a rail row's group (any id: no layout uses it)
        const val QUERY = "query"                                    // text for the search box (a find link that matched nothing)
        const val ALL = ""
        private var keepGroup: String? = null                      // set just before recreate() for a language change
        const val SEARCH = "\u0000search"                           // the search mode: this country first, then the others
        private const val HDR = "\u0000hdr"                        // a section line in the grid (a Channel whose name is its words)
        private const val IDLE = 0; private const val LOADING = 1; private const val OFFLINE = 2; private const val DONE = 3
        const val FAVS = "\u0000favs"
        const val PL = "\u0000p:"                                    // + a playlist's id: one of the person's m3u links on show
        const val MINE = "\u0000g:"                                  // + a group's id: one of the person's groups on show

        fun fold(s: String) = Normalizer.normalize(s.lowercase().replace('đ', 'd'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

        /** iptv-org's group names in the app's language where we have them; its "Undefined" (no category given) is "Other". */
        /** The category in the app's language (cat_<id> in i18n/apps/<lang>/channels.json); one iptv-org adds later shows as it comes. */
        fun groupName(c: Context, g: String) = CATS[g.lowercase()]?.let { c.getString(it) } ?: g

        /** A line icon per iptv-org category: the list is read at a glance (an unknown category gets the plain TV). */
        fun groupIcon(g: String) = when (g.lowercase()) {
            "news" -> R.drawable.ic_news; "sports" -> R.drawable.ic_trophy; "movies", "series" -> R.drawable.ic_film
            "kids", "animation" -> R.drawable.ic_smile; "music" -> R.drawable.ic_music; "documentary" -> R.drawable.ic_camera
            "education" -> R.drawable.ic_book; "religious" -> R.drawable.ic_flame; "lifestyle" -> R.drawable.ic_heart
            "cooking" -> R.drawable.ic_utensils; "travel" -> R.drawable.ic_compass; "business" -> R.drawable.ic_banknote
            "comedy" -> R.drawable.ic_laugh; "family" -> R.drawable.ic_users; "culture", "legislative", "public" -> R.drawable.ic_landmark
            "outdoor" -> R.drawable.ic_tent; "science" -> R.drawable.ic_flask; "shop" -> R.drawable.ic_store; "weather" -> R.drawable.ic_weather
            "auto" -> R.drawable.ic_car; "classic" -> R.drawable.ic_hourglass; "relax" -> R.drawable.ic_moon; "entertainment" -> R.drawable.ic_sparkle
            "undefined" -> R.drawable.ic_more; else -> R.drawable.ic_tv
        }

        private val CATS = mapOf(
            "general" to R.string.cat_general, "undefined" to R.string.cat_undefined, "news" to R.string.cat_news, "animation" to R.string.cat_animation,
            "auto" to R.string.cat_auto, "business" to R.string.cat_business, "classic" to R.string.cat_classic, "comedy" to R.string.cat_comedy,
            "cooking" to R.string.cat_cooking, "culture" to R.string.cat_culture, "documentary" to R.string.cat_documentary, "education" to R.string.cat_education,
            "entertainment" to R.string.cat_entertainment, "family" to R.string.cat_family, "interactive" to R.string.cat_interactive, "kids" to R.string.cat_kids,
            "legislative" to R.string.cat_legislative, "lifestyle" to R.string.cat_lifestyle, "movies" to R.string.cat_movies, "music" to R.string.cat_music,
            "outdoor" to R.string.cat_outdoor, "public" to R.string.cat_public, "relax" to R.string.cat_relax, "religious" to R.string.cat_religious,
            "science" to R.string.cat_science, "series" to R.string.cat_series, "shop" to R.string.cat_shop, "sports" to R.string.cat_sports,
            "travel" to R.string.cat_travel, "weather" to R.string.cat_weather, "xxx" to R.string.cat_xxx,
        )
    }
}
