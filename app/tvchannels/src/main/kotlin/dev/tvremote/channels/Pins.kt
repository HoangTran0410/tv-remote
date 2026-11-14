package dev.tvremote.channels

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build

/**
 * The app's public face: a channel is the link tvchannels://play/<iptv-org id>, a group of the person's tvchannels://group/<id>
 * ([Store.FAV] the favourites). A pinned shortcut is that link put on the
 * home screen through Android's own request (ShortcutManager), so any launcher that takes pins shows it, and no launcher has
 * to know this app. Not every launcher takes them (Google TV's does not): [can] says so, and the menu entry is left out.
 */
object Pins {
    fun link(id: String): Uri = Uri.parse("tvchannels://play/" + Uri.encode(id))

    /** The id in a link made by [link] (or typed by a phone: tvchannels://play/HTV7.vn@SD). */
    fun id(u: Uri?): String? = u?.takeIf { it.scheme == "tvchannels" && it.host == "play" }?.encodedPath?.trim('/')?.let { Uri.decode(it) }?.ifEmpty { null }

    /** The text in tvchannels://find/<text>: a channel by the name a person says, for phones and voice. */
    fun findText(u: Uri?): String? = u?.takeIf { it.scheme == "tvchannels" && it.host == "find" }?.encodedPath?.trim('/')?.let { Uri.decode(it) }?.trim()?.ifEmpty { null }

    /** tvchannels://url?u=<percent-encoded http(s) link>: one stream by its address (a phone or computer over ADB); null when absent or not a plain link. */
    fun streamUrl(u: Uri?): String? = u?.takeIf { it.scheme == "tvchannels" && it.host == "url" }?.getQueryParameter("u")?.trim()?.takeIf { Edit.validUrl(it) }
    fun isStreamLink(u: Uri?) = u?.scheme == "tvchannels" && u.host == "url"

    fun groupLink(id: String): Uri = Uri.parse("tvchannels://group/" + Uri.encode(id))
    fun groupId(u: Uri?): String? = u?.takeIf { it.scheme == "tvchannels" && it.host == "group" }?.encodedPath?.trim('/')?.let { Uri.decode(it) }?.ifEmpty { null }

    fun can(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && (c.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager)?.isRequestPinShortcutSupported == true

    /**
     * When the home screen in use takes no pins but TV Home is on the TV: TV Setup's launcher list, swapping to TV Home at once (the TV
     * still asks Keep / Go back). The Home key can reach TV Home through TV Setup while Android's home screen is another (a Chromecast's
     * setup "recovery" home, 11948009), and only that one takes pins: the menu entry stays, and says why. Null: nothing to offer.
     */
    fun setHome(c: Context): Intent? {
        if (Build.VERSION.SDK_INT < 26 || !has(c, HomeInfo.HOME) || !has(c, SETUP)) return null
        return Intent().setClassName(SETUP, "$SETUP.MainActivity").putExtra("open", "launchers").putExtra("auto", HomeInfo.HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    private const val SETUP = "dev.tvremote.setup"
    private fun has(c: Context, pkg: String) = try { c.packageManager.getApplicationInfo(pkg, 0).enabled } catch (e: Exception) { false }

    @android.annotation.TargetApi(26)   // [can] is checked first
    fun pin(c: Context, ch: Channel, logo: Bitmap?): Boolean {
        if (!can(c)) return false
        val sm = c.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return false
        val icon = if (logo != null) Icon.createWithBitmap(tile(logo, size(sm))) else Icon.createWithResource(c, R.drawable.icon)
        val s = ShortcutInfo.Builder(c, "ch:" + ch.id)
            .setShortLabel(ch.name.take(24)).setLongLabel(ch.name).setIcon(icon)
            .setIntent(Intent(Intent.ACTION_VIEW, link(ch.id)).setClass(c, PlayActivity::class.java))
            .build()
        prefs(c).edit().putString("logo:" + s.id, ch.logo).apply()
        if (logo != null) dev.tvremote.tvui.PinArt.save(c, s.id, tile(logo, dev.tvremote.tvui.PinArt.W to dev.tvremote.tvui.PinArt.H))   // TV Home's sharp copy: the icon above is shrunk to ~127 px
        noLogo(c, s.id, logo == null)
        return try { sm.updateShortcuts(listOf(s)); sm.requestPinShortcut(s, null) } catch (e: Exception) { false }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("pins", Context.MODE_PRIVATE)
    private fun noLogo(c: Context, id: String, on: Boolean) {
        val l = prefs(c).getStringSet("nologo", emptySet())!!.toMutableSet()
        if (if (on) l.add(id) else l.remove(id)) prefs(c).edit().putStringSet("nologo", l).apply()
    }

    /**
     * Channel pins made while the logo could not be fetched get it now (the launcher redraws the tile). Pins from before this was
     * kept (0.26.3 and older) are all looked at once, their logo found in the world index. Blocking: off the main thread.
     */
    fun mend(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val sm = c.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return
        val pinned = try { sm.pinnedShortcuts.map { it.id }.filter { it.startsWith("ch:") } } catch (e: Exception) { return }
        val p = prefs(c)
        if (!p.getBoolean("mended", false)) p.edit().putStringSet("nologo", p.getStringSet("nologo", emptySet())!! + pinned).putBoolean("mended", true).apply()
        val want = p.getStringSet("nologo", emptySet())!!.filter { it in pinned }
        if (want.isEmpty()) { p.edit().remove("nologo").apply(); return }
        val unknown = want.filter { p.getString("logo:$it", null) == null }
        val found = if (unknown.isEmpty()) emptyMap() else try {
            GlobalIndex.get(c)?.byIds(unknown.map { it.removePrefix("ch:").substringBefore('@') }, 1)?.associate { it.id to it.logo }
        } catch (e: Exception) { null }.orEmpty()
        val left = HashSet<String>()
        for (id in want) {
            val url = p.getString("logo:$id", null) ?: found[id.removePrefix("ch:").substringBefore('@')].orEmpty().also { p.edit().putString("logo:$id", it).apply() }   // looked up once
            if (url.isEmpty()) continue                               // the channel has no logo: the app's icon is right
            val logo = Logos.forPin(c, url, 640)
            val ok = logo != null && try { sm.updateShortcuts(listOf(ShortcutInfo.Builder(c, id).setIcon(Icon.createWithBitmap(tile(logo, size(sm)))).build())) } catch (e: Exception) { false }
            if (!ok) left.add(id) else dev.tvremote.tvui.PinArt.save(c, id, tile(logo!!, dev.tvremote.tvui.PinArt.W to dev.tvremote.tvui.PinArt.H))
        }
        p.edit().putStringSet("nologo", left).apply()
    }

    /** A group on the home screen: its name, and up to four of its logos on the card. Opens the list on that group. */
    @android.annotation.TargetApi(26)   // [can] is checked first
    fun pinGroup(c: Context, id: String, name: String, logos: List<Bitmap>): Boolean {
        if (!can(c)) return false
        val sm = c.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return false
        val icon = if (logos.isNotEmpty()) Icon.createWithBitmap(mosaic(logos, size(sm))) else Icon.createWithResource(c, R.drawable.banner)
        val s = ShortcutInfo.Builder(c, "grp:$id")
            .setShortLabel(name.take(24)).setLongLabel(name).setIcon(icon)
            .setIntent(Intent(Intent.ACTION_VIEW, groupLink(id)).setClass(c, BrowseActivity::class.java))
            .build()
        if (logos.isNotEmpty()) dev.tvremote.tvui.PinArt.save(c, s.id, mosaic(logos, dev.tvremote.tvui.PinArt.W to dev.tvremote.tvui.PinArt.H))
        return try { sm.updateShortcuts(listOf(s)); sm.requestPinShortcut(s, null) } catch (e: Exception) { false }
    }

    /** A group renamed: its pin (if any) takes the new name. Deleted: its pin is turned off, the home screen drops it. */
    fun renamed(c: Context, id: String, name: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val sm = c.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return
        val old = try { sm.pinnedShortcuts.firstOrNull { it.id == "grp:$id" } } catch (e: Exception) { null } ?: return
        try { sm.updateShortcuts(listOf(ShortcutInfo.Builder(c, old.id).setShortLabel(name.take(24)).setLongLabel(name).build())) } catch (e: Exception) {}
    }
    fun deleted(c: Context, id: String) {
        if (Build.VERSION.SDK_INT < 26) return
        try { (c.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager)?.disableShortcuts(listOf("grp:$id")) } catch (e: Exception) {}
        dev.tvremote.tvui.PinArt.drop(c, "grp:$id")
    }

    /** Up to four logos, two by two, on the light card. */
    private fun mosaic(logos: List<Bitmap>, size: Pair<Int, Int>): Bitmap {
        if (logos.size == 1) return tile(logos[0], size)
        val (w, h) = size
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(cardColour(logos))                                // square: the launcher rounds it to its own corners, ours showed its backing
        val cw = w / 2f; val chh = h / 2f
        logos.take(4).forEachIndexed { i, logo ->
            val x = (i % 2) * cw; val y = (i / 2) * chh + if (logos.size == 2) chh / 2 else 0f
            val k = minOf((cw * 0.88f) / logo.width, (chh * 0.8f) / logo.height)
            val lw = logo.width * k; val lh = logo.height * k
            cv.drawBitmap(logo, null, RectF(x + (cw - lw) / 2, y + (chh - lh) / 2, x + (cw + lw) / 2, y + (chh + lh) / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG))
        }
        return b
    }

    /**
     * The card's pixels: the biggest 16:9 the system keeps. ShortcutManager shrinks any icon to getIconMaxWidth / Height (about
     * 96 dp), so a bigger card was scaled down there and then stretched to the tile by the launcher: the blur of the first version (a fixed 320x180).
     */
    @android.annotation.TargetApi(25)   // only with a ShortcutManager, which [can] allowed
    private fun size(sm: ShortcutManager): Pair<Int, Int> {
        val mw = try { sm.iconMaxWidth } catch (e: Exception) { 0 }; val mh = try { sm.iconMaxHeight } catch (e: Exception) { 0 }
        val w = (if (mw > 0) mw else 320).coerceIn(128, 640)
        val h = w * 9 / 16
        return w to (if (mh in 1 until h) mh else h)
    }

    /** Light card for the usual logo; a dark one when the logos are white (pluto's), which vanish on light. */
    internal fun cardColour(logos: List<Bitmap>): Int {
        var sum = 0.0; var n = 0
        for (b in logos) {
            val t = Bitmap.createScaledBitmap(b, 24, 24, true)
            for (x in 0 until 24) for (y in 0 until 24) {
                val p = t.getPixel(x, y); val a = Color.alpha(p)
                if (a < 128) continue
                sum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0; n++
            }
        }
        return if (n > 0 && sum / n > 0.85) Color.rgb(30, 36, 48) else Color.rgb(242, 244, 247)
    }

    /** The logo on a light 16:9 card, as the list shows it: logos are made for light backgrounds. */
    private fun tile(logo: Bitmap, size: Pair<Int, Int>): Bitmap {
        val (w, h) = size
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(cardColour(listOf(logo)))                         // square: the launcher rounds it to its own corners, ours showed its backing
        val k = minOf((w * 0.85f) / logo.width, (h * 0.78f) / logo.height)            // fit-centre with a margin; scales with the card, not a fixed 48 px
        val lw = logo.width * k; val lh = logo.height * k
        cv.drawBitmap(logo, null, RectF((w - lw) / 2, (h - lh) / 2, (w + lw) / 2, (h + lh) / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG))
        return b
    }
}
