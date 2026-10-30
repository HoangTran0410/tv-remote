package dev.tvremote.home

import android.content.Context
import dev.tvremote.home.apps.Apps
import dev.tvremote.home.apps.Pins
import dev.tvremote.home.apps.LiveWidgets
import dev.tvremote.home.keys.Keys
import dev.tvremote.home.wall.Online
import dev.tvremote.home.wall.Videos
import dev.tvremote.home.wall.Walls
import org.json.JSONObject

/** The settings ([HomeConfig]) in prefs; the screen is told of every change, whether it came from the phone or from the TV. */
object Store {
    var onChange: (() -> Unit)? = null                                // the screen, while it exists; changes come on the main thread

    private fun prefs(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    /**
     * The settings as they are now: motion starts off on a weak box (little RAM) until someone chooses, and "dream" is what the
     * system says (the screensaver can be changed in its own settings too), neither kept in the saved copy.
     */
    fun get(c: Context): JSONObject {
        val saved = saved(c) ?: return first(c)
        return effective(c, JSONObject(saved.toString()))           // a copy: callers change what they get
    }

    // the saved copy cleaned once, again only when the text in prefs changes: parsed and cleaned on every call it was ~7 ms on the
    // FPT box, and [list] (a dozen calls building one screen) paid that and [effective]'s ~6 ms for one array
    private var raw: String? = null
    private var clean = JSONObject()
    @Synchronized private fun saved(c: Context): JSONObject? {
        val now = prefs(c).getString("conf", null) ?: return null
        if (now != raw) { clean = HomeConfig.clean(try { JSONObject(now) } catch (e: Exception) { JSONObject() }); raw = now }
        return clean
    }

    /** The saved settings' text: changes with every change, cheap to compare (a JSONObject.toString of [get] was ~5% of Customize's CPU). */
    fun stamp(c: Context): String? = prefs(c).getString("conf", null)

    private fun effective(c: Context, conf: JSONObject): JSONObject {
        // on until someone turns it off: older builds saved "off" for 2 GB boxes nobody had asked (it costs no memory, only frames)
        if (!prefs(c).getBoolean("anim_chosen", false)) conf.put("anim", true)
        conf.put("dream", Dream.isOn(c))
        return conf.put("theme", themeNow(conf))
    }

    // which theme is on, again only when the settings or the ready-made list change: canon() of every theme on each read was ~12% of
    // the main thread's CPU while Customize was walked on the FPT box (its pages read the settings a few dozen times)
    private var themeKey: Any? = null
    private var themeId = ""
    @Synchronized private fun themeNow(conf: JSONObject): String {
        val key = listOf(raw, conf.optBoolean("anim"), conf.optBoolean("dream"), HomeConfig.ready)
        if (key != themeKey) { themeId = HomeConfig.themeNow(conf); themeKey = key }
        return themeId
    }

    /**
     * [conf] ([get] or [change]) as the clients read it (the phone's broadcast, the page served by ManageServer): what this TV Home
     * can do and what it offers. Built only for them: TV Home's own screens never read it and paid ~6 ms a read on the FPT box.
     */
    fun forClients(c: Context, conf: JSONObject): JSONObject {
        return conf.put("wsizes", true).put("colsOk", true).put("rowsOk", true).put("rowsMax", rowsFit)   // an older TV Home has none: the page offers it the old layout chips
            .put("wschema", HomeConfig.schema(android.os.Build.VERSION.SDK_INT))                  // what each widget offers in its settings (an older one has none: its looks were "wstyles")
            .put("wweb", true)                                    // the background can be a web page (an older one would clean it away)
            .put("wrotate", true)                                 // the background can turn through a set (an older one has one choice)
            .put("shadeOk", true)                                 // the veil's strength can be chosen (an older one has one)
            .put("fitOk", true)                                   // a photo can be shown whole (HomeConfig "wfit"; an older one crops it)
            .put("greetOk", true)                                 // the greeting's words: with a name, or the person's own (HomeConfig "gstyle")
            .put("headOk", true)                                  // the header's two lines: what each says and its type (HomeConfig "dstyle", HEAD_TYPE)
            .put("ownOk", true)                                   // the own wall: its colours and direction (HomeConfig "wcol", "wdir")
            .put("qrOk", true)                                    // QR widgets (an older one would clean them away)
            .put("foldersOk", true)                               // folders on the grid (HomeConfig "folders", "folder:<id>" in tiles and order)
            .put("infoOk", true)                                  // the market and news widgets, own RSS links ("feeds"), the weather map
            .put("themesOk", true)                                // themes (HomeConfig "theme"): the ready-made ones ([get] has which look is on now)
            .put("themesBuiltin", org.json.JSONArray(HomeConfig.ready.map { r -> JSONObject().put("id", r.id).put("names", JSONObject(r.names)).put("conf", r.conf).put("look", r.look).put("tiles", org.json.JSONArray(r.live)).also { o -> if (r.community) o.put("community", true).put("by", r.by) } }))
            .let { Keys.report(c, it) }                           // keys that open apps: "keysOk", and whether TV Setup took them
            .let { LiveWidgets.report(c, it) }                          // live widgets: "tilesOk", what TV Setup offers, and whether it can be asked
            .let { Pins.report(c, it) }                           // shortcuts: "pinsOk", what the apps offer ([Target] "pin:...")
            .put("keysHave", Keys.have(c))                        // the keys this remote has (the quick keys screen greys out the others)
    }

    /** The first start: a dock of the well-known apps this TV has, so the screen is not empty before anyone sets it up. */
    private fun first(c: Context): JSONObject {
        val have = Apps.list(c).map { it.pkg }.toSet()
        val dock = FIRST_DOCK.filter { it in have }.take(HomeConfig.DOCK_MAX)
        // Everything else goes to "hidden" (= off the grid, still in All apps and + Add; nothing else reads it: no search, key or
        // Play Next rule), so no second "tucked" state is needed. It is a list of packages, so an app installed LATER is not in it
        // and shows on the grid as always. Only when the grid keeps a few apps: a TV that knows none of ours would show an empty one.
        val keep = have.filter { it in FIRST_DOCK || it in FEATURED }
        val tuck = if (keep.size >= TUCK_MIN) (have - keep.toSet()).sorted().take(300) else emptyList()   // 300 = HomeConfig HIDDEN_MAX
        val conf = HomeConfig.clean(JSONObject().put("dock", org.json.JSONArray(dock)).put("hidden", org.json.JSONArray(tuck)))
        prefs(c).edit().putString("conf", conf.toString()).putBoolean("choose_due", have.size > keep.size).apply()   // the choice screen follows once ([HomeActivity]); this tucking is what Back / skipping keeps
        return effective(c, conf)
    }
    /** True once after a fresh install that tucked apps: Home then opens "Choose the apps on your home screen". An update never has it (no flag). */
    fun chooseDue(c: Context): Boolean {
        if (!prefs(c).getBoolean("choose_due", false)) return false
        prefs(c).edit().putBoolean("choose_due", false).apply()
        return true
    }
    /** The project's own TV apps: first in the dock when they come, so a person sees what goes with TV Home (the user, 2026-10-25). */
    val OWN = listOf("dev.tvremote.setup", "dev.tvremote.games", "dev.tvremote.channels", "dev.tvremote.apps")

    /**
     * Each of [OWN] installed, once: put in the dock after the others of [OWN] at its front (the grid's front when the dock is full),
     * unless it is placed already. Once only (prefs "promoted"): moved or hidden later, it stays where the person put it. True when
     * it changed the settings (the screen is redrawn by [onChange]).
     */
    fun promote(c: Context, have: Collection<String>): Boolean {
        val done = prefs(c).getStringSet("promoted", emptySet())!!
        val now = OWN.filter { it in have && it !in done }
        if (now.isEmpty()) return false
        prefs(c).edit().putStringSet("promoted", done + now).apply()
        val dock = list(c, "dock"); val order = list(c, "order"); val hidden = list(c, "hidden")
        var changed = false
        for (p in now) {
            if (p in dock || p in hidden) continue
            if (dock.size < HomeConfig.DOCK_MAX) dock.add(dock.indexOfFirst { it !in OWN }.let { if (it < 0) dock.size else it }, p)
            else { order.remove(p); order.add(0, p) }
            changed = true
        }
        if (changed) change(c, JSONObject().put("dock", org.json.JSONArray(dock)).put("order", org.json.JSONArray(order)))
        return changed
    }

    private val FIRST_DOCK = OWN + listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.google.android.youtube.tvmusic", "com.spotify.tv.android", "com.android.vending", "com.android.tv.settings")

    /** Well-known streaming / media / TV apps that stay on the grid of a first run, after the dock (the rest is tucked into All apps). */
    private val FEATURED = listOf("com.google.android.youtube", "com.google.android.youtube.tvmusic", "com.google.android.youtube.tv.music",
        "com.netflix.mediaclient", "com.amazon.avod", "com.disney.disneyplus", "com.hbo.hbonow", "com.apple.atve.androidtv.appletv",
        "net.fptplay.ottbox", "com.fptplay.launcher", "com.vtvgo.tv", "vn.vtvgo.tv", "com.vtvcab.onbox", "com.viettel.tv360", "com.vieon.tv", "vn.vieon.tv", "com.vieon",
        "com.mytv.tv", "vn.mytv.b2c.androidtv", "com.vnpt.mytv", "org.xbmc.kodi", "com.stremio.one", "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta",
        "com.plexapp.android", "com.google.android.videos", "com.google.android.tv", "com.android.tv",
        "com.google.android.apps.nbu.files", "com.android.documentsui", "com.mediatek.filemanager", "com.android.chrome", "com.google.android.play.games",
        "com.android.vending", "com.android.tv.settings", "com.twitch.android.app", "tv.twitch.android.app")
    private const val TUCK_MIN = 4                                // the grid keeps at least this many apps for a first run to tuck the rest

    /** @throws SecurityException "dream" without WRITE_SECURE_SETTINGS (a client grants it over ADB first; the TV's own switch falls back to the system's screen) */
    fun change(c: Context, change: JSONObject): JSONObject {
        if (change.has("dream")) Dream.set(c, change.optBoolean("dream"))
        val n = HomeConfig.merge(get(c), change, { k -> LiveWidgets.status(c) == LiveWidgets.OK && LiveWidgets.available(c).any { it.key == k } },
            { it == HomeConfig.BING || Walls.usable(c, it) })           // Bing is fetched once it shows, so a theme with it is not turned into Night on a TV that has none yet
        if (change.has("wall")) Videos.forgetPerf(c, change.optString("wall"))   // a video that was too slow gets another try when it is chosen again
        val e = prefs(c).edit().putString("conf", n.toString())
        val applied = change.optJSONObject("theme")?.has("apply") == true      // a theme sets the motion and the background too
        if (change.has("anim") || applied) e.putBoolean("anim_chosen", true)  // from now on the choice is theirs
        if (change.has("wall") || change.has("walls") || change.has("wevery") || applied) e.putLong("wlast", System.currentTimeMillis())   // a new choice gets its whole turn
        e.apply()
        if (listOf("keyon", "keyapps", "autoon", "autoapp", "autoall").any { change.has(it) }) Keys.push(c)   // TV Setup does the keys everywhere and the power-on app
        synchronized(this) { themeKey = null }                    // [raw] is still the text before this change
        val out = effective(c, JSONObject(n.toString()))
        change.optJSONObject("theme")?.let { ThemeShots.after(c, it, n) }   // the previews' pictures: taken when a theme is saved, dropped when it goes
        onChange?.invoke()
        return out
    }

    /** The "hold OK for more" tip: its own flag, outside the settings, so a theme or an import never brings it back. */
    fun suggestOff(c: Context) = prefs(c).getBoolean("suggest_setup_off", false)
    fun suggestOff(c: Context, off: Boolean) = prefs(c).edit().putBoolean("suggest_setup_off", off).apply()
    /** Apps whose Play Next and channels stay off the home screen (the row's menu). */
    fun rowsOff(c: Context): Set<String> = prefs(c).getStringSet("rows_off", null).orEmpty()
    fun rowsOff(c: Context, pkg: String) = prefs(c).edit().putStringSet("rows_off", rowsOff(c) + pkg).apply()
    fun rowsOn(c: Context, pkg: String) = prefs(c).edit().putStringSet("rows_off", rowsOff(c) - pkg).apply()
    fun tipIsDone(c: Context) = prefs(c).getBoolean("tip_menu_done", false)
    fun tipDone(c: Context) { if (!tipIsDone(c)) prefs(c).edit().putBoolean("tip_menu_done", true).apply() }

    /** When the background last changed (ms), from a choice or from the rotation. */
    fun last(c: Context) = prefs(c).getLong("wlast", 0L)

    /**
     * Moves the background on to the next of its set when it is time (or, [show], when Home has just come back on screen and the
     * rotation is "show"); true when it changed. No timer anywhere keeps this running: Home asks when it is shown and sets one
     * timer while it is on screen, so a weak box does no work between changes.
     */
    fun advance(c: Context, show: Boolean): Boolean {
        val conf = get(c)
        val every = conf.optString("wevery")
        val a = conf.getJSONArray("walls")
        val walls = List(a.length()) { a.getString(it) }
        if (every == "off" || !turns(c, walls)) return false
        val now = System.currentTimeMillis()
        if (!(show && every == "show") && !HomeConfig.due(every, last(c), now, java.util.TimeZone.getDefault().getOffset(now).toLong())) return false
        if (conf.optString("wall") == HomeConfig.BING && Online.step(c)) { change(c, JSONObject().put("walls", a)); return true }   // Bing's older days first, then the next of the set
        val next = HomeConfig.next(walls, conf.optString("wall")) { Walls.usable(c, it) } ?: return false
        change(c, JSONObject().put("wall", next).put("walls", a))
        return true
    }

    /** Whether [walls] has anything to turn to: two or more, or Bing alone with several days kept. */
    fun turns(c: Context, walls: List<String>) = walls.size >= 2 || (walls.singleOrNull() == HomeConfig.BING && Online.count(c) > 1)

    /** One of the saved lists (dock, hidden, tiles, order, widgets): from the cleaned copy, none of [effective]'s reports needed. */
    fun list(c: Context, key: String): MutableList<String> = synchronized(this) {
        val a = (saved(c) ?: return get(c).getJSONArray(key).let { a -> MutableList(a.length()) { a.getString(it) } }).getJSONArray(key)
        MutableList(a.length()) { a.getString(it) }
    }

    fun putList(c: Context, key: String, l: List<String>) { change(c, JSONObject().put(key, org.json.JSONArray(l))) }
}
