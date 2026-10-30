package dev.tvremote.home.apps

import android.annotation.TargetApi
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.Store
import dev.tvremote.home.Target
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shortcuts other apps pin to the favourites, through Android's own request (ShortcutManager.requestPinShortcut, Android 8+):
 * a TV channel, a playlist, a page. This home knows none of those apps; the system keeps which shortcuts are pinned here
 * (asked again on every build); [order] is ours, outside HomeConfig: the phone and computer do not set it. They sit in the dock after its apps, under the same limit.
 */
@TargetApi(25)   // everything here starts at can(), which needs 26; ShortcutInfo is 25
object Pins {
    private fun la(c: Context) = c.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

    /** Only the default home may read or start pinned shortcuts (hasShortcutHostPermission); anything else sees none. */
    fun can(c: Context) = Build.VERSION.SDK_INT >= 26 && try { la(c)?.hasShortcutHostPermission() == true } catch (e: Exception) { false }

    /** The app a pinned tile belongs to: the app that pinned it, or for TV Home's own pins the app they open (null for a tile that is no shortcut). */
    fun appOf(c: Context, a: App): String? = (if (a.pin == null) null else a.pin as? ShortcutInfo)?.let { s -> if (s.`package` == c.packageName) sourceOf(c, s.id) ?: s.`package` else s.`package` }   // the null check first: the cast alone fails on Android 5-7

    /** The id of a shortcut TV Home made itself (a pinned link), else null. */
    fun ownIdOf(c: Context, a: App): String? = (if (a.pin == null) null else a.pin as? ShortcutInfo)?.takeIf { it.`package` == c.packageName }?.id

    /** A shortcut's name for a key's label, null when it is gone. */
    fun label(c: Context, key: String): String? = find(c, key)?.let { (it.shortLabel ?: it.longLabel ?: it.id).toString() }

    fun key(s: ShortcutInfo) = "pin:${s.`package`}/${s.id}"

    private fun prefs(c: Context) = c.getSharedPreferences("pins", Context.MODE_PRIVATE)
    fun order(c: Context): List<String> = prefs(c).getString("order", "")!!.split('\n').filter { it.isNotEmpty() }
    fun order(c: Context, l: List<String>) { prefs(c).edit().putString("order", l.joinToString("\n")).apply() }

    /** The pinned shortcuts, as tiles' apps, in the order they were added; a disabled one (its app took it back) is left out. */
    fun list(c: Context): List<App> {
        if (!can(c)) return emptyList()
        val got = try {
            la(c)!!.getShortcuts(LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), Process.myUserHandle())
        } catch (e: Exception) { null }.orEmpty().filter { it.isEnabled }
        val order = order(c)
        return got.sortedBy { order.indexOf(key(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .map { App(key(it), (it.shortLabel ?: it.longLabel ?: it.`package`).toString(), null, it) }
    }

    /** When the app last changed [a] (its icon, its name): a pin given a new icon is drawn again. */
    @TargetApi(26)
    fun stamp(a: App) = (a.pin as? ShortcutInfo)?.lastChangedTimestamp ?: 0L

    /** A focused tile's width in px ([resize]): a pin's picture is drawn at it, not at [PinArt.W]: ~370 px at 5 columns on a 1080p TV, a third of the memory. */
    @Volatile var fit = dev.tvremote.tvui.PinArt.W; private set

    /** The grid's tiles changed size: pins are drawn again at [px] when next asked (true: there were some to redraw). */
    fun resize(px: Int): Boolean {
        val n = px.coerceIn(64, dev.tvremote.tvui.PinArt.W)
        if (n == fit) return false
        fit = n; made.clear(); return true
    }

    @TargetApi(26)
    fun picture(c: Context, a: App) {
        val s = a.pin as ShortcutInfo
        val sharp = if (s.`package` == c.packageName) dev.tvremote.tvui.PinArt.own(c, s.id, fit)
            else if (s.`package` in OURS) dev.tvremote.tvui.PinArt.read(c, s.`package`, s.id, fit) else null   // the system's copy is ~127 px: ours keep a larger one
        val sys = if (sharp != null) null else try { la(c)!!.getShortcutIconDrawable(s, c.resources.displayMetrics.densityDpi) } catch (e: Exception) { null }
        val d = sharp?.let { android.graphics.drawable.BitmapDrawable(c.resources, it) }
            ?: sys
            ?: try { c.packageManager.getApplicationIcon(s.`package`) } catch (e: Exception) { null }   // the system lost its bitmap ("Icon file not found"): the app's own icon, not the hidden apps' eye
        if (d != null && wide(d.intrinsicWidth, d.intrinsicHeight) && d.intrinsicWidth > 0) a.banner = badged(c, d, if (s.`package` != c.packageName) s.`package` else if (marked(c, s.id)) null else sourceOf(c, s.id)) else a.icon = shrink(c, if (d != null && sys != null) squareBadged(c, d, badgeApp(c, s)) else d, 512)
    }

    /** The app whose logo a square pin carries: the shortcut's app; for TV Home's own pins (Play Next) the app they came from, none when the picture has it. */
    private fun badgeApp(c: Context, s: ShortcutInfo): String? = if (s.`package` != c.packageName) s.`package` else if (marked(c, s.id)) null else sourceOf(c, s.id)

    private val OURS = setOf("dev.tvremote.games", "dev.tvremote.channels")

    /**
     * A wide pin with its app's icon in a corner, as on the system's own launchers: a game's or a channel's picture alone did not
     * say which app it opens. Drawn at [fit], the focused tile's size, so the icon stays sharp over a small card too.
     */

    private fun badged(c: Context, card: android.graphics.drawable.Drawable, source: String?): android.graphics.drawable.Drawable {
        val icon = source?.let { try { c.packageManager.getApplicationIcon(it) } catch (e: Exception) { null } } ?: return card
        val w = fit; val h = fit * dev.tvremote.tvui.PinArt.H / dev.tvremote.tvui.PinArt.W
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        card.setBounds(0, 0, w, h); card.draw(cv)
        Watch.corner(cv, icon, w, h)                                   // the same place and size as on the Play Next row
        return android.graphics.drawable.BitmapDrawable(c.resources, b)
    }

    @TargetApi(26)
    fun open(c: Context, a: App, o: android.os.Bundle? = null): Boolean {
        val s = a.pin as ShortcutInfo
        return try { la(c)!!.startShortcut(s.`package`, s.id, null, o, Process.myUserHandle()); true } catch (e: Exception) { false }
    }

    /** Unpins [a]: the system keeps a list per app, so the app's other pins are named again. */
    @TargetApi(26)
    fun remove(c: Context, a: App) {
        val s = a.pin as ShortcutInfo
        val keep = list(c).mapNotNull { it.pin as? ShortcutInfo }.filter { it.`package` == s.`package` && it.id != s.id }.map { it.id }
        try { la(c)!!.pinShortcuts(s.`package`, keep, Process.myUserHandle()) } catch (e: Exception) {}
        order(c, order(c) - key(s))
    }

    /** Pins [key] again (the system keeps a list per app: its other pins are named with it); false when the shortcut is gone. */
    @TargetApi(26)
    fun add(c: Context, key: String): Boolean {
        val s = find(c, key) ?: return false
        val keep = list(c).mapNotNull { it.pin as? ShortcutInfo }.filter { it.`package` == s.`package` }.map { it.id }
        return try { la(c)!!.pinShortcuts(s.`package`, (keep + s.id).distinct(), Process.myUserHandle()); true } catch (e: Exception) { false }
    }

    /**
     * [key] off the home screen for good: its system pin and TV Home's own tile ("tiles", dock, order). A shortcut can be both (put on
     * the grid by the picker, pinned by its app too): taking only the tile off showed the pin again, so "Remove" seemed to do nothing.
     */
    fun off(c: Context, key: String) {
        val s = find(c, key)
        if (s != null && list(c).any { it.pkg == key }) {
            val keep = list(c).mapNotNull { it.pin as? ShortcutInfo }.filter { it.`package` == s.`package` && it.id != s.id }.map { it.id }
            try { la(c)!!.pinShortcuts(s.`package`, keep, Process.myUserHandle()) } catch (e: Exception) {}
        }
        order(c, order(c) - key)
        fun without(k: String) = JSONArray(Store.list(c, k) - key)
        Store.change(c, JSONObject().put("tiles", without("tiles")).put("dock", without("dock")).put("order", without("order")))
    }

    /**
     * Turns an item of another app's Play Next row into a pin of ours: a shortcut TV Home itself owns ("pin:dev.tvremote.home/pn…"),
     * so it is the grid's ordinary pinned tile (moved, renamed, hidden, unpinned like any). [intent] is already the safe one of
     * Watch.intent (no selector, no URI grants). Android asks the default home to confirm ([PinActivity] takes it). [SAME]: pinned
     * already; [FAIL]: the system refused (or no shortcut host rights). No limit: a pin is one more tile of the grid.
     */
    enum class Made { OK, SAME, FAIL }

    @TargetApi(26)
    fun pinIntent(c: Context, source: String, label: String, intent: android.content.Intent, art: android.graphics.Bitmap?, marked: Boolean = false): Made {
        if (!can(c)) return Made.FAIL
        val id = ownId(intent)
        if (list(c).any { it.pkg == Target.pin(c.packageName, id) }) return Made.SAME
        val sm = c.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return Made.FAIL
        val icon = art ?: try {                                      // no poster: the source app's own icon, not this home's
            val d = c.packageManager.getApplicationIcon(source)
            android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 192, 192); d.draw(android.graphics.Canvas(b)) }
        } catch (e: Exception) { null }
        val go = android.content.Intent(intent).apply { if (action == null) action = android.content.Intent.ACTION_VIEW }
        val info = android.content.pm.ShortcutInfo.Builder(c, id).setShortLabel(label.take(40)).setIntent(go)
            .also { b -> icon?.let { b.setIcon(android.graphics.drawable.Icon.createWithBitmap(it)) } }.build()
        c.getSharedPreferences(SRC, Context.MODE_PRIVATE).edit().putString(id, source).putBoolean("$id.m", marked).apply()   // [marked]: [art] has the app's icon already (Watch.art): not a second one   // whose it is: the shortcut itself is this home's
        art?.let { dev.tvremote.tvui.PinArt.save(c, id, it) }       // the system keeps ~127 px of it: the tile reads this one
        return try { if (sm.requestPinShortcut(info, null)) Made.OK else Made.FAIL } catch (e: Exception) { Made.FAIL }
    }

    /** The id [pinIntent] gives [intent]: the same intent is the same pin, so the picker knows it is pinned before it asks. */
    fun ownId(intent: android.content.Intent) = "pn" + java.security.MessageDigest.getInstance("SHA-1").digest(intent.toUri(0).toByteArray()).joinToString("") { "%02x".format(it) }.take(14)

    private const val SRC = "pin_src"
    /** The app a Play Next item pinned by [pinIntent] came from (its menu named TV Home); null for any other shortcut. */
    fun sourceOf(c: Context, id: String): String? = c.getSharedPreferences(SRC, Context.MODE_PRIVATE).getString(id, null)
    private fun marked(c: Context, id: String) = c.getSharedPreferences(SRC, Context.MODE_PRIVATE).getBoolean("$id.m", false)

    // ---- every shortcut an app offers (static, dynamic, pinned), for the picker and for a key / the grid that points at one ----

    private const val ALL = LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

    /** One app's shortcuts. The query names a package: without one the system answers only the pinned ones. */
    @TargetApi(26)
    private fun of(c: Context, pkg: String, ids: List<String>? = null): List<ShortcutInfo> = try {
        la(c)!!.getShortcuts(LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(ALL).also { q -> if (ids != null) q.setShortcutIds(ids) }, Process.myUserHandle())
    } catch (e: Exception) { null }.orEmpty().filter { it.isEnabled }

    /** The shortcut [key] ("pin:pkg/id", a [Target]) points at; null when its app is gone or took it back, or this is not the home screen. */
    fun find(c: Context, key: String): ShortcutInfo? =
        if (Build.VERSION.SDK_INT < 26 || !can(c) || !Target.isPin(key)) null else of(c, Target.pkg(key), listOf(Target.id(key))).firstOrNull()

    fun start(c: Context, key: String, o: Bundle? = null): Boolean {
        val s = find(c, key) ?: return false
        return try { la(c)!!.startShortcut(s.`package`, s.id, null, o, Process.myUserHandle()); true } catch (e: Exception) { false }
    }

    private val made = HashMap<String, App>()                       // a grid tile's app per key: the same object while nothing changed, so its tile is kept

    /** [key] as a tile's app with its picture (shortcut + app label), or null when the shortcut is not there. */
    fun app(c: Context, key: String): App? {
        val s = find(c, key) ?: return null
        val label = (s.shortLabel ?: s.longLabel ?: s.id).toString()
        made[key]?.let { old -> if ((old.pin as ShortcutInfo).let { (it.shortLabel ?: it.longLabel ?: it.id).toString() } == label) return old }
        return App(key, label, null, s).also { it.load(c); made[key] = it }
    }

    /**
     * Every shortcut of the apps TV Home lists, as [{key, title, app}] in app order: what the picker offers. A stored copy ([report]
     * reads it), made when the settings are read or Home comes back: asking each app is a binder call, not for every [Store.get].
     */
    fun refresh(c: Context, apps: List<App> = Apps.list(c)) {
        val out = JSONArray()
        if (can(c)) for (a in apps) for (s in of(c, a.pkg).sortedBy { it.rank })
            out.put(entry(s, a.pkg))
        prefs(c).edit().putString("avail", out.toString()).putBoolean("can", can(c)).apply()
    }

    private fun entry(s: ShortcutInfo, app: String) =
        JSONObject().put("key", key(s)).put("title", (s.shortLabel ?: s.longLabel ?: s.id).toString()).put("app", app).put("t", s.lastChangedTimestamp)

    /**
     * One app's shortcuts changed (LauncherApps.Callback.onShortcutsChanged, registered by HomeActivity while it lives): only its entries of
     * the stored list are replaced, in the place they were, so + Add shows them without asking every app again. Off the main thread.
     */
    fun refreshPkg(c: Context, pkg: String) {
        if (!can(c)) return
        val now = of(c, pkg).sortedBy { it.rank }.map { entry(it, pkg) }
        val old = try { JSONArray(prefs(c).getString("avail", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray(); var at = -1
        for (i in 0 until old.length()) { val o = old.getJSONObject(i); if (o.optString("app") == pkg) { if (at < 0) at = out.length() } else out.put(o) }
        val merged = JSONArray(); val head = if (at < 0) out.length() else at
        for (i in 0 until head) merged.put(out.get(i))
        now.forEach { merged.put(it) }
        for (i in head until out.length()) merged.put(out.get(i))
        prefs(c).edit().putString("avail", merged.toString()).apply()
    }

    /** The stored list exists (a first Home has none until something refreshed it). */
    fun listed(c: Context) = prefs(c).contains("avail")

    /**
     * Keeps the stored list fresh while Home lives: the system tells this home (the default launcher) when an app's shortcuts change
     * (TV Setup's scenes after a save, a channel list), and only that app is asked again, then its pictures drawn once ([rowIcon]) so
     * + Add finds everything ready. TV Home's own callback: no service, nothing runs when no app's shortcuts change. [bg] runs the work off the main thread.
     * Returns what [unwatch] needs, or null (before Android 8, or not the default home).
     */
    @TargetApi(26)
    fun watch(c: Context, bg: (Runnable) -> Unit): Any? {
        if (Build.VERSION.SDK_INT < 26 || !can(c)) return null
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: android.os.UserHandle) {}
            override fun onPackageAdded(p: String, u: android.os.UserHandle) {}
            override fun onPackageChanged(p: String, u: android.os.UserHandle) {}
            override fun onPackagesAvailable(p: Array<out String>?, u: android.os.UserHandle, r: Boolean) {}
            override fun onPackagesUnavailable(p: Array<out String>?, u: android.os.UserHandle, r: Boolean) {}
            override fun onShortcutsChanged(p: String, shortcuts: MutableList<ShortcutInfo>, u: android.os.UserHandle) {
                bg(Runnable {
                    try { refreshPkg(c, p); for (s in of(c, p)) rowIcon(c, key(s), p, s.lastChangedTimestamp) } catch (e: Exception) { }
                })
            }
        }
        return try { la(c)!!.registerCallback(cb, android.os.Handler(android.os.Looper.getMainLooper())); cb } catch (e: Exception) { null }
    }

    @TargetApi(26)
    fun unwatch(c: Context, token: Any?) { try { (token as? LauncherApps.Callback)?.let { la(c)?.unregisterCallback(it) } } catch (e: Exception) { } }

    private val rowIcons = HashMap<String, android.graphics.drawable.Drawable>()   // drawn once per shortcut version: key + lastChangedTimestamp

    /**
     * A shortcut's own picture with its app's logo in a corner (Watch.corner, as on the wide pins), for + Add's rows. Kept by key and
     * [stamp] ([ShortcutInfo.getLastChangedTimestamp]): opening the panel again draws nothing. Null: the system gave no picture (the row then shows the app's icon alone).
     */
    @TargetApi(26)
    fun rowIcon(c: Context, key: String, app: String, stamp: Long): android.graphics.drawable.Drawable? {
        val k = "$key@$stamp"
        synchronized(rowIcons) { rowIcons[k]?.let { return it } }
        val s = find(c, key) ?: return null
        val d = try { la(c)!!.getShortcutIconDrawable(s, c.resources.displayMetrics.densityDpi) } catch (e: Exception) { null } ?: return null
        val out = squareBadged(c, d, app)
        synchronized(rowIcons) { rowIcons.keys.removeAll { it.substringBefore('@') == key }; rowIcons[k] = out }
        return out
    }

    /** Both drawn at 16 px and compared: the same picture (some apps use their icon for a shortcut, with a different drawable object). Only ever called once per drawn icon, from the cache. */
    private fun looksAlike(a: android.graphics.drawable.Drawable, b: android.graphics.drawable.Drawable): Boolean {
        fun px(d: android.graphics.drawable.Drawable): IntArray {
            val bm = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            val old = d.copyBounds(); d.setBounds(0, 0, 16, 16); d.draw(android.graphics.Canvas(bm)); d.bounds = old
            return IntArray(256).also { bm.getPixels(it, 0, 16, 0, 0, 16, 16) }
        }
        val x = px(a); val y = px(b); var diff = 0L
        for (i in x.indices) for (sh in intArrayOf(0, 8, 16, 24)) diff += Math.abs(((x[i] shr sh) and 255) - ((y[i] shr sh) and 255))
        return diff / (256 * 4) < 20
    }

    /** [d] on a square card with [source]'s icon in the corner; [d] alone when the app has no icon. */
    fun squareBadged(c: Context, d: android.graphics.drawable.Drawable, source: String?): android.graphics.drawable.Drawable {
        val icon = source?.let { try { c.packageManager.getApplicationIcon(it) } catch (e: Exception) { null } } ?: return d
        val banner = try { c.packageManager.getApplicationBanner(source) } catch (e: Exception) { null }
        if (looksAlike(d, icon) || (banner != null && looksAlike(d, banner))) return d       // the picture IS the app (TV Channels' Favorites): a badge would show it twice
        val n = 192
        val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        d.setBounds(0, 0, n, n); d.draw(cv)
        Watch.corner(cv, icon, n, n, pct = 40, ring = true)   // 40 %: small on a 26 dp row, still which app
        return android.graphics.drawable.BitmapDrawable(c.resources, b)
    }

    /** The config the clients read gains "pinsOk", "pinsAvail" [{key, title, app}] and "pinsCan" (only the home screen may start a shortcut). */
    fun report(c: Context, conf: JSONObject): JSONObject = conf.put("pinsOk", true).put("pinsCan", can(c))
        .put("pinsAvail", try { JSONArray(prefs(c).getString("avail", "[]")) } catch (e: Exception) { JSONArray() })
}
