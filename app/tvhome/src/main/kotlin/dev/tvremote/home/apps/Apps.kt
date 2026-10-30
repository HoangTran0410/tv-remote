package dev.tvremote.home.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.Target

/**
 * An app the TV can open from a home screen, with the picture it gives for one: a TV banner (16:9), else its icon. Or a shortcut
 * another app pinned here ([pin], a ShortcutInfo: [Pins]); its [pkg] is then "pin:<app>/<id>", the key of its tile.
 */
class App(val pkg: String, val orig: String, val info: ActivityInfo?, val pin: Any? = null, val members: List<String> = emptyList()) {
    /** The name shown everywhere: the person's own ([Apps.names], "names" of the settings) else the app's. */
    val label: String get() = Apps.names[pkg] ?: orig
    val renamed: Boolean get() = Apps.names.containsKey(pkg)
    /** A live widget ([LiveWidgets]) rather than an app: its pkg is "tile:<provider>/<id>", it has no pictures, a press runs its action. */
    val live: Boolean get() = HomeConfig.isLive(pkg)
    /** A folder ([Folders]): a tile that holds [members] (installed apps' packages); a press opens a panel of them. Its pkg is "folder:<id>". */
    val folder: Boolean get() = HomeConfig.isFolder(pkg)
    /** On the grid by the settings' "tiles" (a live widget, a folder or a shortcut the picker added), not an installed app. */
    val grid: Boolean get() = live || folder || (pin != null && Target.isPin(pkg))
    var banner: Drawable? = null
    var icon: Drawable? = null

    /** Both pictures let go, for memory; [load] makes them again. */
    fun release() { banner = null; icon = null }

    fun load(c: Context) {
        if (pin != null) { Pins.picture(c, this); return }
        val info = info ?: return
        val pm = c.packageManager
        banner = shrink(c, sharp(c, info) ?: try { info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm) } catch (e: Exception) { null }, BANNER_MAX)
        banner?.let { if (!wide(it.intrinsicWidth, it.intrinsicHeight)) banner = null }   // a square "banner" (Rockchip's Media Center, 64x64) cropped to 16:9 is a blur: the icon and name instead
        if (banner == null) icon = shrink(c, try { info.loadIcon(pm) } catch (e: Exception) { null }, ICON_MAX)
    }
}

/**
 * The banner at xhdpi or more: a 1080p TV that calls itself hdpi or tvdpi got it small, then blurred by the focused tile's 1.12x
 * (cleanyourtv's AppAdapter). Its memory goes with the rest when Home is hidden ([App.release]).
 */
private fun sharp(c: Context, info: ActivityInfo): Drawable? = try {
    val id = info.banner.takeIf { it != 0 } ?: info.applicationInfo.banner
    if (id == 0) null else c.packageManager.getResourcesForApplication(info.applicationInfo)
        .getDrawableForDensity(id, maxOf(android.util.DisplayMetrics.DENSITY_XHIGH, c.resources.displayMetrics.densityDpi), null)
} catch (e: Exception) { null }

private const val BANNER_MAX = 1280                                   // px, the long side: a tile is ~440 px wide at 1080p, x1.12 focused
private const val ICON_MAX = 512

/**
 * [d] no bigger than [max] px on its long side. An app's picture is drawn as it comes, and a huge one (4096x7280, an image put
 * in a low-density folder and scaled up for the screen) is over what the screen may draw at once: Home crashed on every frame
 * ("Canvas: trying to draw too large bitmap", a globe_ATV box on Android 8). Small pictures and vectors come back as they are.
 */
fun shrink(c: Context, d: Drawable?, max: Int): Drawable? {
    if (d !is android.graphics.drawable.BitmapDrawable) return d
    val b = d.bitmap ?: return d
    val long = maxOf(b.width, b.height); if (long <= max) return d
    val k = max.toFloat() / long
    return android.graphics.drawable.BitmapDrawable(c.resources, android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true))
}

/** Wide enough to fill a 16:9 tile; no size of its own (a colour, a shape) fills any tile. cleanyourtv #324. */
fun wide(w: Int, h: Int) = w <= 0 || h <= 0 || w >= h * 1.4f

object Apps {
    /** The person's names for apps (HomeConfig "names"); HomeActivity keeps it in step with the settings. */
    @Volatile var names: Map<String, String> = emptyMap()

    /**
     * TV apps first (LEANBACK_LAUNCHER), then phone apps that have a launcher entry; not this one. A phone-only entry of the
     * firmware's own (never updated) is left out: makers leave such tools behind (a Hisense FPT Play box: SystemNotification,
     * screen zoom, an updater test, Contacts, Camera), the TV's own home does not show them either. Chrome put on by hand stays.
     */
    fun list(c: Context): List<App> {
        val out = LinkedHashMap<String, App>()
        for (cat in CATS) for (ri in found(c, cat, null)) accept(c, cat, ri, out)
        return out.values.sortedBy { it.label.lowercase() }
    }

    /** The one app [pkg], as [list] would have it; null when this screen would not list it (or it is gone). */
    fun one(c: Context, pkg: String): App? {
        val out = LinkedHashMap<String, App>()
        for (cat in CATS) { for (ri in found(c, cat, pkg)) accept(c, cat, ri, out); if (out.isNotEmpty()) break }
        return out[pkg]
    }

    private val CATS = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)

    private fun found(c: Context, cat: String, pkg: String?): List<android.content.pm.ResolveInfo> = try {
        c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat).apply { if (pkg != null) setPackage(pkg) }, 0)
    } catch (e: Exception) { emptyList() }

    private fun accept(c: Context, cat: String, ri: android.content.pm.ResolveInfo, out: MutableMap<String, App>) {
        val p = ri.activityInfo.packageName
        if (p == c.packageName || p in out) return
        if ((cat == Intent.CATEGORY_LAUNCHER || p.startsWith("com.example.")) && firmware(ri.activityInfo.applicationInfo)) return   // Android's samples a maker shipped (SystemUpdaterSample has a TV entry)
        out[p] = App(p, ri.loadLabel(c.packageManager).toString().trim(), ri.activityInfo)
    }

    private fun firmware(a: android.content.pm.ApplicationInfo) =
        a.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 && a.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0

    fun launch(c: Context, pkg: String, o: android.os.Bundle? = null): Boolean {
        val pm = c.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), o); true } catch (e: Exception) { false }
    }
}
