package dev.tvremote.home.apps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import dev.tvremote.update.SignedList
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Places inside other apps that the picker offers as pins (a channel of VTVgo or TV360): the site's signed list docs/home/links.json
 * (dev/gen/app_links.py, dev/sign/sign_links.py), else GitHub, else the kept copy, else the one packed in the APK, as [dev.tvremote.home.widgets.Sources].
 * Each app gives its own deep link with "{id}" in it, read from the app and tried on a TV; Android lets no launcher list what
 * links an app takes, and those apps publish no shortcuts, so a new app is a new entry there, not code here. Pressed, an item
 * becomes TV Home's own pin ([Pins.pinIntent]) of a VIEW intent held to that package, its picture the item's logo on a card.
 */
object Links {
    /** [pkg] "*": no app is named, whoever handles the link opens it ([LinkApps]; YouTube's entry). */
    class Item(val pkg: String, val app: String, val id: String, val name: String, val logo: String, val uri: String) {
        val any get() = pkg == ANY
        /** What "+ Add" and the picker group an app's items by: its package, or its name for a "*" entry. */
        val group get() = if (any) "*$app" else pkg
    }
    class All(val rev: Long, val items: List<Item>)

    private val BASES = listOf("https://tvremote.pages.dev/home/", "https://raw.githubusercontent.com/HoangTran0410/tv-remote/main/docs/home/")
    private const val NAME = "links.json"
    private const val DAY = 24 * 3600_000L
    private val PKG = Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")
    private val ID = Regex("[A-Za-z0-9_.\\-]{1,40}")
    private val SLUG = Regex("[a-z0-9][a-z0-9\\-]{0,60}")
    private val SCHEME = Regex("([a-z][a-z0-9+.\\-]*):.*")
    private val BANNED = setOf("intent", "file", "javascript", "content", "android-app")   // a link names a place in its app, nothing more (sign_links.py refuses the same)

    const val ANY = "*"
    @Volatile var now: All = All(0, emptyList())

    private fun scheme0(uri: String) = SCHEME.matchEntire(uri)?.groupValues?.get(1)   // a "*" entry (no app named) is an https link only

    /** The list as TV Home uses it: an app whose link is not a plain deep link with {id} is left out whole, a bad item alone. */
    fun parse(json: String): All? = try {
        val o = JSONObject(json)
        if (o.optInt("v") != 1) null
        else {
            val apps = o.optJSONArray("apps")
            val got = ArrayList<Item>()
            for (k in 0 until (apps?.length() ?: 0)) {
                val a = apps!!.optJSONObject(k) ?: continue
                val pkg = a.optString("pkg"); val uri = a.optString("uri")
                val scheme = SCHEME.matchEntire(uri)?.groupValues?.get(1)
                if (!(PKG.matches(pkg) || pkg == ANY && scheme0(uri) == "https") || "{id}" !in uri || scheme == null || scheme in BANNED) continue
                val items = a.optJSONArray("items") ?: continue
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val id = it.optString("id"); val name = it.optString("name").trim(); val logo = it.optString("logo")
                    if (!ID.matches(id) || name.isEmpty()) continue
                    got.add(Item(pkg, a.optString("name", pkg), id, name, if (logo.startsWith("https://") || SLUG.matches(logo)) logo else "", uri))
                }
            }
            All(o.optLong("rev"), got)
        }
    } catch (e: Exception) { null }

    /** [i]'s link: the app's template with the id put in (ids are plain: letters, digits, "_.-"). */
    fun link(i: Item): String = i.uri.replace("{id}", i.id)

    /** The intent a pin of [i] opens: VIEW, held to its own app, no grants; a new task as Home's every start. */
    fun intent(c: Context, i: Item): Intent =
        if (i.any) LinkApps.intent(c, link(i)) else Intent(Intent.ACTION_VIEW, Uri.parse(link(i))).setPackage(i.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app a pin of [i] opens now (its badge and fallback icon): the package, or the first handler of a "*" link. */
    fun source(c: Context, i: Item): String? = if (i.any) LinkApps.best(c, link(i), LinkApps.groupFor(link(i)))?.pkg else i.pkg

    /**
     * The items of the apps installed and turned on here, in the list's order. One app under two packages (FPT Play: the
     * Android TV app and FPT boxes' launcher) is offered once, by the first of them there.
     */
    fun installed(c: Context): List<Item> {
        val pm = c.packageManager
        val anyApps = now.items.filter { it.any }.distinctBy { it.app }.filter { LinkApps.best(c, link(it), LinkApps.groupFor(link(it))) != null }.map { it.app }.toSet()
        val there = now.items.filter { !it.any }.map { it.pkg }.distinct()
            .filter { p -> try { pm.getApplicationInfo(p, 0).enabled } catch (e: Exception) { false } }   // Optimize TV turns an FPT box's own launcher off
            .distinctBy { p -> now.items.first { it.pkg == p }.app }.toSet()
        return now.items.filter { if (it.any) it.app in anyApps else it.pkg in there }
    }

    // ---- the card: the logo on a dark card, as the pin's picture ----

    private const val CARD_BG = 0xFF1C2233.toInt()
    private const val CARD_EDGE = 0xFF2A3350.toInt()

    private fun file(c: Context, i: Item) = File(File(c.filesDir, "linklogos").apply { mkdirs() }, i.logo.hashCode().toUInt().toString(16) + ".png")

    /** [i]'s logo, fetched once ([fetch]) and kept in files/; null when it has none or it cannot be read. Blocking: off the main thread. */
    fun logo(c: Context, i: Item, fetch: Boolean = true): Bitmap? {
        if (i.logo.isEmpty()) return null
        val f = file(c, i)
        if (!f.isFile && fetch) {
            val urls = if (i.logo.startsWith("https://")) listOf(i.logo) else BASES.map { it + "logos/" + i.logo + ".png" }
            for (u in urls) {
                val b = try {
                    (URL(u).openConnection() as HttpURLConnection).run {
                        connectTimeout = 6000; readTimeout = 8000
                        try { if (responseCode == 200 && contentLength < (1 shl 20)) inputStream.use { it.readBytes() } else null } finally { disconnect() }
                    }
                } catch (e: Exception) { null }
                if (b != null && BitmapFactory.decodeByteArray(b, 0, b.size) != null) { f.writeBytes(b); break }
            }
        }
        return if (f.isFile) BitmapFactory.decodeFile(f.path) else null
    }

    /** The logos of the installed apps' items not kept yet, fetched once: the picker draws from the kept ones only (~80 fetches were seconds). */
    fun warm(c: Context) { for (i in installed(c)) if (i.logo.isNotEmpty() && !file(c, i).isFile) logo(c, i) }

    /** A 16:9 card, [w] wide ([dev.tvremote.tvui.PinArt]'s size for a pin, a tile's for the picker), with [logo] whole in the middle, or [name] when there is none. */
    fun card(logo: Bitmap?, name: String, w: Int = dev.tvremote.tvui.PinArt.W, cfg: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val h = w * 9 / 16
        val b = Bitmap.createBitmap(w, h, cfg)
        val cv = Canvas(b)
        cv.drawColor(CARD_BG)
        cv.drawRect(0f, h * .62f, w.toFloat(), h.toFloat(), Paint().apply { shader = android.graphics.LinearGradient(0f, h * .62f, 0f, h.toFloat(), CARD_BG, CARD_EDGE, android.graphics.Shader.TileMode.CLAMP) })
        if (logo == null) {                                            // not fetched (offline, no logo): the name, so the tile still says what it opens
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = h * .2f }
            while (p.measureText(name) > w * .84f && p.textSize > h * .08f) p.textSize *= .9f
            cv.drawText(android.text.TextUtils.ellipsize(name, android.text.TextPaint(p), w * .84f, android.text.TextUtils.TruncateAt.END).toString(), w / 2f, h * .46f - (p.ascent() + p.descent()) / 2, p)
            return b
        }
        val square = logo.width in (logo.height * 4 / 5)..(logo.height * 5 / 4)   // a channel's round avatar (YouTube): at .46 it was a small dot; a square leaves the corners free for the badge
        val s = minOf(w * .62f / logo.width, h * (if (square) .74f else .46f) / logo.height)   // room at the bottom-right for the app's badge (Pins.badged)
        val lw = logo.width * s; val lh = logo.height * s
        cv.drawBitmap(logo, null, RectF((w - lw) / 2, (h - lh) / 2 - h * .04f, (w + lw) / 2, (h + lh) / 2 - h * .04f), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return b
    }

    private fun rev(b: ByteArray) = try { JSONObject(String(b, Charsets.UTF_8)).optLong("rev", 0) } catch (e: Exception) { 0L }

    fun start(c: Context) {
        val app = c.applicationContext
        val f = File(app.filesDir, NAME)
        var bytes = SignedList.kept(f)
        val packed = SignedList.packed({ app.assets.open(it) }, NAME)
        if (packed != null && rev(packed) > (bytes?.let { rev(it) } ?: -1)) {   // an APK newer than the site carries a newer list
            bytes = packed
            try { SignedList.keep(f, packed, app.assets.open("$NAME.sig").use { String(it.readBytes(), Charsets.UTF_8) }); f.setLastModified(1) } catch (e: Exception) { }
        }
        val mine = bytes?.let { rev(it) } ?: -1
        bytes?.let { parse(String(it, Charsets.UTF_8)) }?.let { now = it }
        val old = !dev.tvremote.guard.Age.fresh(f.lastModified(), DAY)
        Thread({
            if (old) try {
                SignedList.fetch(BASES, NAME, 1 shl 18, connectMs = 6000, readMs = 8000) { list, sig, _ ->
                    val p = parse(String(list, Charsets.UTF_8))
                    if (p == null || p.rev < mine) null
                    else { SignedList.keep(f, list, sig); now = p; true }
                }
            } catch (e: Exception) { }
            try { warm(app) } catch (e: Exception) { }
        }, "links").apply { isDaemon = true }.start()
    }
}
