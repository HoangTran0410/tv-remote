package dev.tvremote.home.apps

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import dev.tvremote.home.Font
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.Target
import dev.tvremote.home.R

/**
 * Live widgets: widgets another app provides (TV Setup's "Free RAM" and "Sleep timer", TV Channels' "Keep watching"), drawn in the app grid at an app tile's size with its own render,
 * pressed like an app. This file is the small API the rest of TV Home (and the "Add to home screen" browser) uses, apart from any view:
 *   [available]  what the provider offers now: [Info] (key, title, kind), from the last [refresh]; [status] says why the list is empty
 *   [fetch]      one live widget's state now (the provider's JSON: mode live|working|done|failed, big, small, progress, accent, result)
 *   [run]        its action (the press); [press] is fetch-and-follow for a screen
 * A live widget's key is "tile:<provider package>/<id>" ([HomeConfig.liveKey]) and sits in the config's "tiles" (both are stored on TVs and sent between apps: kept, they mean live widgets) (which are on the grid) and
 * in dock / hidden / order like an app's package name. A provider answers only TV Home, by its signing key (its HomeInfo), and TV
 * Home asks a provider only when that app is signed by the key pinned here ([PINNED]) or by this app's own.
 * A live widget's "layout" says how [LiveFace] draws it: "gauge" (a ring and a line; the default), "next" (a channel and its programmes)
 * or "toggle" (a value that a press steps through).
 * Calls to it are binder calls to another app: never on the main thread.
 */
object LiveWidgets {
    class Info(val key: String, val title: String, val kind: String, val layout: String = "gauge", val icon: String = "")

    /** The live widget's own drawing for a list of live widgets ("ram" was TV Setup's list name for its "memory"); a gauge when it names none we have. */
    fun iconRes(c: Context, t: Info): Int {
        val n = if (t.icon == "ram") "memory" else t.icon
        val id = if (n.isEmpty()) 0 else c.resources.getIdentifier("ic_$n", "drawable", c.packageName)
        return if (id == 0) R.drawable.ic_gauge else id
    }

    const val OK = "ok"                                                 // the provider answered with its list
    const val MISSING = "missing"                                       // no TV Setup, or one older than the live widgets
    const val UNTRUSTED = "untrusted"                                   // TV Setup is there but signed by another key
    const val EVERY_MS = 5000L                                          // how often a live widget on screen is read again
    const val FOLLOW_MS = 350L                                          // ... while its action runs

    private fun prefs(c: Context) = c.getSharedPreferences("home_tiles", Context.MODE_PRIVATE)

    /** The provider's content address by its package: "<package>.home", as TV Setup's HomeInfo is "dev.tvremote.setup.home" ([HomeConfig.USAGE]). */
    fun uri(provider: String): Uri = Uri.parse("content://$provider.home")

    /** TV Setup and TV Channels; each is asked the same way. */
    val PROVIDERS = listOf("dev.tvremote.setup", "dev.tvremote.channels")

    /** The release certificate (SHA-256) each provider must be signed with; every app has a key of its own (each app's signing.jks). */
    val PINNED = mapOf(
        "dev.tvremote.setup" to "6dbf49b2d455de428edaca6674266f7e3d1222483d59c26f7345f8cc129bd966",       // app/tvsetup/signing.jks
        "dev.tvremote.channels" to "a3e09c97fb750bf09285d6fa7c065d4c63ee6d8247b1caaa555dd279e2534b6a")    // app/tvchannels/signing.jks

    /** Whether [certs] (the provider's signing digests) are the pinned one, or one of [mine] (debug builds of all apps share a key). */
    fun certOk(provider: String, certs: List<String>, mine: List<String>): Boolean = certs.any { it == PINNED[provider] || it in mine }

    private val vouched = HashMap<String, Pair<Long, Boolean?>>()       // provider -> (checked at, signed right; null: not installed), a minute

    @Suppress("DEPRECATION")
    private fun certs(c: Context, pkg: String): List<String> = try {
        val pm = c.packageManager
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            else pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        sigs.orEmpty().map { s -> java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
    } catch (e: Exception) { emptyList() }

    /** true: [provider] is installed and signed right; false: signed by another key; null: not installed. */
    private fun signedRight(c: Context, provider: String): Boolean? = synchronized(vouched) {
        vouched[provider]?.takeIf { System.currentTimeMillis() - it.first < 60_000L }?.let { return it.second }
        val theirs = certs(c, provider)
        val ok = if (theirs.isEmpty()) null else certOk(provider, theirs, certs(c, c.packageName))
        vouched[provider] = System.currentTimeMillis() to ok
        ok
    }

    private fun call(c: Context, provider: String, method: String, arg: String?): android.os.Bundle? {
        when (signedRight(c, provider)) {
            null -> return null
            false -> return android.os.Bundle().apply { putString("result", "untrusted") }
            true -> {}
        }
        return try {
            c.contentResolver.call(uri(provider), method, arg, android.os.Bundle().apply { putString("lang", Locale.getDefault().language) })
        } catch (e: Exception) { null }                                 // no such provider, or one that does not know this word
    }

    /** Asks every provider for its live widgets and keeps the answer; returns whether it changed (the grid and the config are then read again). */
    fun refresh(c: Context): Boolean {
        val all = JSONArray()
        var state = MISSING
        for (p in PROVIDERS) {
            val b = call(c, p, "tiles", null)
            when {
                b == null -> {}
                b.getString("result") == "untrusted" -> if (state == MISSING) state = UNTRUSTED
                else -> try {
                    val l = JSONArray(b.getString("json") ?: "[]")
                    for (i in 0 until l.length()) l.getJSONObject(i).let { all.put(JSONObject().put("key", HomeConfig.liveKey(p, it.getString("id")))
                        .put("title", it.optString("title")).put("kind", it.optString("kind"))
                        .put("layout", it.optString("layout").ifEmpty { "gauge" }).put("icon", it.optString("icon"))) }
                    state = OK
                } catch (e: Exception) { }
            }
        }
        val old = prefs(c).getString("list", "") + prefs(c).getString("state", "")
        val now = all.toString() + state
        prefs(c).edit().putString("list", all.toString()).putString("state", state).apply()
        return old != now
    }

    fun status(c: Context): String = prefs(c).getString("state", MISSING) ?: MISSING

    /** What the providers offered at the last [refresh]; empty when [status] is not [OK]. */
    fun available(c: Context): List<Info> = try {
        val l = JSONArray(prefs(c).getString("list", "[]"))
        List(l.length()) { l.getJSONObject(it).let { o -> Info(o.getString("key"), o.optString("title"), o.optString("kind"), o.optString("layout", "gauge"), o.optString("icon")) } }
    } catch (e: Exception) { emptyList() }

    /** The name to show for [key]: the provider's, else (a live widget set from a phone before the provider was seen) its id. */
    fun title(c: Context, key: String): String = available(c).firstOrNull { it.key == key }?.title?.takeIf { it.isNotEmpty() } ?: HomeConfig.liveId(key)

    /** The live widgets that are on the grid (config "tiles"), as live widgets' apps; they show even while their provider is away, saying so. */
    fun apps(c: Context, conf: JSONObject): List<App> {
        val a = conf.optJSONArray("tiles") ?: return emptyList()
        val folders = conf.optJSONObject("folders")
        return List(a.length()) { a.getString(it) }.mapNotNull {
            if (HomeConfig.isFolder(it)) folders?.optJSONObject(HomeConfig.folderId(it))?.let { f -> App(it, f.optString("name").ifEmpty { c.getString(R.string.folder_default) }, null, null, f.optJSONArray("apps")?.let { m -> List(m.length()) { i -> m.getString(i) } } ?: emptyList()) }
            else if (Target.isPin(it)) Pins.app(c, it) else App(it, title(c, it), null)   // a shortcut that is gone shows nothing
        }
    }

    /** The config the clients read gains: "tilesOk", "tilesAvail" [{key, title, kind, layout}] and "tilesState" ([OK] / [MISSING] / [UNTRUSTED]). */
    fun report(c: Context, conf: JSONObject): JSONObject {
        val l = JSONArray()
        for (t in available(c)) l.put(JSONObject().put("key", t.key).put("title", t.title).put("kind", t.kind).put("layout", t.layout))
        return conf.put("tilesOk", true).put("tilesAvail", l).put("tilesState", status(c))
    }

    private val last = HashMap<String, JSONObject>()                   // the latest state of each live widget, so a rebuilt grid starts with it

    fun cached(key: String): JSONObject? = synchronized(last) { last[key] }

    /** One live widget's state now (a binder call: a thread of its own); a state with mode "missing" / "untrusted" when its provider does not answer. */
    fun fetch(c: Context, key: String): JSONObject {
        val provider = HomeConfig.liveProvider(key)
        val b = call(c, provider, "tile", HomeConfig.liveId(key))
        val s = when {
            b == null -> JSONObject().put("mode", MISSING).also { missingLine(c, provider)?.let { l -> it.put("small", l) } }
            b.getString("result") == "untrusted" -> JSONObject().put("mode", UNTRUSTED).also { missingLine(c, provider)?.let { l -> it.put("small", l) } }
            else -> try { JSONObject(b.getString("json") ?: "") } catch (e: Exception) { JSONObject().put("mode", MISSING) }
        }
        synchronized(last) { last[key] = s }
        return s
    }

    /** The line for a provider that is away, when it is not TV Setup (the face's own words speak of TV Setup). */
    private fun missingLine(c: Context, provider: String): String? =
        if (provider == "dev.tvremote.channels") c.getString(if (signedRight(c, provider) == false) R.string.live_untrusted_channels else R.string.live_need_channels) else null

    /** Runs the live widget's action: true when it started (or was already running), false when the provider is missing, untrusted or refused. */
    fun run(c: Context, key: String): Boolean {
        val b = call(c, HomeConfig.liveProvider(key), "tile_do", HomeConfig.liveId(key)) ?: return false
        if (b.getString("result") == "untrusted") return false
        return try { JSONObject(b.getString("json") ?: return false).let { it.optBoolean("started") || it.optBoolean("busy") } } catch (e: Exception) { false }
    }

    /**
     * A press for a screen: starts the action, then reads the live widget every [FOLLOW_MS] until it is no longer "working" (at most
     * [FOLLOW_TRY] times), each state handed to [onState]. Call it from a thread of its own; false when it could not start.
     */
    fun press(c: Context, key: String, onState: (JSONObject) -> Unit): Boolean {
        val st = cached(key) ?: fetch(c, key)
        val open = st.optString("open")                                 // a live widget that opens its app's screen: TV Home is in front, so it starts the link
        if (open.isNotEmpty()) return launch(c, HomeConfig.liveProvider(key), open).also { onState(fetch(c, key)) }
        val hand = st.optString("hand")                                 // one of TV Setup's own screens (watched today: Health), behind its PIN
        if (hand.isNotEmpty() && c is android.app.Activity && HomeConfig.liveProvider(key) == "dev.tvremote.setup") return SetupHand.control(c, hand)?.has("error") == false
        if (!run(c, key)) { onState(fetch(c, key)); return false }
        Thread.sleep(FOLLOW_MS)
        var n = 0
        while (n++ < FOLLOW_TRY) {
            val s = fetch(c, key); onState(s)
            if (s.optString("mode") != "working") break
            Thread.sleep(FOLLOW_MS)
        }
        return true
    }
    private const val FOLLOW_TRY = 40

    /** Starts [link] in the provider's own app only (setPackage): a provider cannot send TV Home to another app. */
    private fun launch(c: Context, provider: String, link: String): Boolean = try {
        c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(link)).setPackage(provider).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) { false }
}


/**
 * The face of a live widget, a sibling of the app banners next to it: a bright gradient, an icon and the name on the left, a ring
 * gauge with its value in the middle on the right, and one short line under the name. Any provider's state fits it: title, big
 * (the value), small (the line), progress (the ring; none = the value alone), accent (ok / warn / bad: the ring's tint, and the
 * gradient turns amber / red), and optionally icon (a drawable name, "memory"), tone (the gradient family, "clean") and note (a
 * shorter line shown instead of small, e.g. "-37 MB" after a press). A press shows the outcome as the ring running down to the
 * new value (about a second, once per outcome) and, while the provider works, a spinner turning on the ring; with motion off or
 * calm (a weak box) the numbers just change.
 * Two more layouts ("layout" in the state): "next", a channel to keep watching (a logo, its name, what is on with a bar of how far it is: state big,
 * small, progress, logo (line2, the next one, is sent but not drawn); "empty" dims it) and "toggle", a value that a press steps through (a pill with big, a line under it; "on"
 * fills the pill; the pill gives one small pop when the value changes, none with motion off).
 */
class LiveFace(c: Context, private val w: Int, private val h: Int, private val title: String, private val say: (String) -> String) : View(c) {
    private companion object {
        const val INK = 0xFFFFFFFF.toInt(); const val LINE_INK = 0xE6FFFFFF.toInt(); const val TRACK = 0x40FFFFFF       // white at 100 / 90 / 25 %
        const val RING_OK = 0xFFFFFFFF.toInt(); const val RING_WARN = 0xFFFFF1BF.toInt(); const val RING_BAD = 0xFFFFD9D6.toInt()   // light tints: readable on every gradient below
        val CLEAN = intArrayOf(0xFF16A34A.toInt(), 0xFF0D6B73.toInt())                                                  // clean / memory: green into teal
        val BLUE = intArrayOf(0xFF3B73F6.toInt(), 0xFF1E3A8A.toInt())                                                   // TV Setup's family, for a tone nobody named
        val AMBER = intArrayOf(0xFFD97706.toInt(), 0xFF9A3412.toInt())
        val RED = intArrayOf(0xFFDC2626.toInt(), 0xFF9F1239.toInt())
        val IDLE = intArrayOf(0xFF2A3446.toInt(), 0xFF171E2B.toInt())                                                   // no answer from the provider, or a toggle that is off
        val VIOLET = intArrayOf(0xFF7C3AED.toInt(), 0xFF3B1E8A.toInt())                                                 // tone "watch": a channel to keep watching
        val NIGHT = intArrayOf(0xFF4F46E5.toInt(), 0xFF1E1B4B.toInt())                                                  // tone "night": the sleep timer, set
        val logos = object : android.util.LruCache<String, android.graphics.Bitmap>(12) {}                              // channel logos, fetched once
        const val SPIN_MS = 1100L
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var big = ""; private var small = ""; private var mode = "live"; private var accent = "ok"; private var tone = ""
    private var hasRing = true
    private var bar = -1f                                              // a "next" live widget's programme so far, 0..1; -1 = the provider gave none
    private var layout = "gauge"; private var line2 = ""; private var logoUrl = ""; private var empty = false; private var on = false
    private var pop = 1f                                                // the toggle pill's scale during its pop
    private var popAnim: ValueAnimator? = null
    private var icon: Drawable? = null; private var iconName = ""
    private var progress = 0f                                           // what the ring shows now (moves during the animation)
    private var seenAt = 0L                                             // the outcome already animated
    private var anim: ValueAnimator? = null
    private var spin: ValueAnimator? = null
    private var turn = 0f                                               // the spinner's angle
    private var motion = false
    private var gradient: LinearGradient? = null; private var gradientFor: IntArray? = null
    private val u = h / 100f

    /** Shows [s] (a provider's state; none = not answered); [animate]: motion is on and not calm. [say] gives the line for a provider that is missing or untrusted. */
    fun set(s: JSONObject?, animate: Boolean) {
        motion = animate
        val m = s?.optString("mode").orEmpty()
        if (s == null || m == LiveWidgets.MISSING || m == LiveWidgets.UNTRUSTED || m.isEmpty()) {
            anim?.cancel(); spinning(false)
            mode = m.ifEmpty { LiveWidgets.MISSING }; big = "—"; small = s?.optString("small").orEmpty().ifEmpty { say(mode) }; progress = 0f; hasRing = true; tone = "idle"
            layout = "gauge"; line2 = ""; empty = false; invalidate(); return
        }
        accent = s.optString("accent", "ok"); mode = m; tone = s.optString("tone")
        layout = s.optString("layout", "gauge").let { if (it == "next" || it == "toggle") it else "gauge" }
        if (layout != "gauge") { setOther(s); return }
        small = s.optString("note").ifEmpty { s.optString("small") }
        hasRing = s.has("progress")
        icon(s.optString("icon"))
        spinning(m == "working" && animate)
        val p = s.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f)
        val r = s.optJSONObject("result")
        if (m == "done" && r != null && r.optLong("at") != seenAt) {
            seenAt = r.optLong("at")
            if (animate && r.optInt("before") != r.optInt("after")) { drop(r.optInt("before") / 100f, p); return }
        }
        if (anim?.isRunning == true) return                             // the count-down is on: it ends on the same numbers
        progress = p; big = s.optString("big"); invalidate()
    }

    /** The state of a "next" or "toggle" live widget: only text and a picture, nothing that runs. */
    private fun setOther(s: JSONObject) {
        anim?.cancel(); spinning(false)
        small = s.optString("small"); line2 = s.optString("line2"); empty = s.optBoolean("empty"); on = s.optBoolean("on"); hasRing = false; progress = 0f
        bar = if (layout == "next" && s.has("progress")) s.optDouble("progress").toFloat().coerceIn(0f, 1f) else -1f
        icon(s.optString("icon"))
        val v = s.optString("big")
        if (layout == "toggle" && big.isNotEmpty() && v != big && motion) popPill()
        big = v
        logo(s.optString("logo"))
        invalidate()
    }

    private fun popPill() {
        popAnim?.cancel()
        popAnim = ValueAnimator.ofFloat(1f, 1.14f, 1f).apply { duration = 260; addUpdateListener { pop = it.animatedValue as Float; invalidate() }; start() }
    }

    /** The channel's picture, fetched once on a thread of its own (a plain http(s) address, at most 1.5 MB); the live widget draws without it until it is there. */
    private fun logo(url: String) {
        if (url == logoUrl) return
        logoUrl = url
        if (!url.startsWith("http") || logos.get(url) != null) return
        Thread({
            try {
                val k = URL(url).openConnection() as HttpURLConnection
                k.connectTimeout = 6000; k.readTimeout = 8000; k.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                val bytes = try { k.inputStream.use { it.readBytes(1_500_000) } } finally { k.disconnect() }
                val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
                val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = Math.max(1, Math.max(b.outWidth, b.outHeight) / 256) }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.let { bmp -> logos.put(url, bmp); post { invalidate() } }
            } catch (e: Exception) { }
        }, "live-logo").apply { isDaemon = true }.start()
    }

    private fun java.io.InputStream.readBytes(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (out.size() < max) { val n = read(buf); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }

    private fun icon(name: String) {
        if (name == iconName) return
        iconName = name
        val id = if (name.isEmpty()) 0 else resources.getIdentifier("ic_$name", "drawable", context.packageName)
        icon = if (id == 0) null else context.getDrawable(id)?.mutate()?.also { it.setTint(INK) }
    }

    private fun drop(from: Float, to: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = 1100; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; big = "${Math.round(progress * 100f)}%"; invalidate() }
            start()
        }
    }

    /** The spinner turns only while the provider works (and motion is on); one animator, stopped when the live widget goes. */
    private fun spinning(on: Boolean) {
        if (on == (spin?.isRunning == true)) return
        spin?.cancel(); spin = null
        if (!on) { turn = 0f; return }
        spin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = SPIN_MS; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { turn = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun ringTint(): Int = when (accent) { "bad" -> RING_BAD; "warn" -> RING_WARN; else -> RING_OK }

    private fun colours(): IntArray = when {
        tone == "idle" || tone == "off" -> IDLE
        tone == "watch" -> VIOLET
        tone == "night" -> NIGHT
        accent == "bad" -> RED
        accent == "warn" -> AMBER
        tone == "clean" -> CLEAN
        else -> BLUE
    }

    override fun onDetachedFromWindow() { anim?.cancel(); spin?.cancel(); spin = null; popAnim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val r = 10f * u
        val col = colours()
        if (gradientFor !== col) { gradientFor = col; gradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), col[0], col[1], Shader.TileMode.CLAMP) }
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        paint.shader = gradient; paint.style = Paint.Style.FILL
        cv.drawRoundRect(box, r, r, paint); paint.shader = null

        val pad = 12f * u
        if (layout == "next") { drawNext(cv, pad); return }
        if (layout == "toggle") { drawToggle(cv, pad); return }
        val ring = if (hasRing) 29f * u else 0f                         // the ring's outer radius; a state without progress gives the room to the words
        val ringX = w - pad - ring
        val textW = if (hasRing) ringX - ring - pad - 6f * u else w - 2 * pad - 40f * u
        // left block: icon, name, line: centred in the live widget's height as a whole
        val ic = if (icon != null) 24f * u else 0f
        val gap = if (ic > 0f) 7f * u else 0f
        val lineH = 12f * u
        val block = ic + gap + 17f * u + 4f * u + lineH
        var y = (h - block) / 2f
        icon?.let { it.setBounds(pad.toInt(), y.toInt(), (pad + ic).toInt(), (y + ic).toInt()); it.draw(cv) }
        y += ic + gap
        paint.typeface = Font.bold; paint.textSize = 16f * u; paint.color = INK
        cv.drawText(fit(title, textW), pad, y + 14f * u, paint)
        y += 17f * u + 4f * u
        paint.typeface = Font.medium; paint.textSize = 11.5f * u; paint.color = LINE_INK
        cv.drawText(fit(small, textW + (if (hasRing) 0f else 40f * u)), pad, y + 10f * u, paint)

        val cy = h / 2f
        if (!hasRing) {                                                 // no progress: the value alone, big, on the right
            paint.typeface = Font.bold; paint.textSize = 30f * u; paint.color = INK; paint.textAlign = Paint.Align.RIGHT
            cv.drawText(big, w - pad, cy + 10f * u, paint); paint.textAlign = Paint.Align.LEFT
            return
        }
        val sw = 7f * u
        val rr = ring - sw / 2f
        box.set(ringX - rr, cy - rr, ringX + rr, cy + rr)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.strokeCap = Paint.Cap.ROUND
        paint.color = TRACK; cv.drawArc(box, 0f, 360f, false, paint)
        val working = mode == "working"
        paint.color = ringTint()
        if (working) paint.alpha = if (spin != null) 90 else 160         // the value fades back while the spinner (or, calm, a plain dim) says "busy"
        if (progress > 0f) cv.drawArc(box, -90f, 360f * progress, false, paint)
        if (working && spin != null) { paint.alpha = 255; cv.drawArc(box, turn - 90f, 70f, false, paint) }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT; paint.alpha = 255
        paint.typeface = Font.bold; paint.color = INK; paint.textAlign = Paint.Align.CENTER
        paint.textSize = 17f * u
        val room = 2f * (rr - sw / 2f) - 2f * u
        while (paint.measureText(big) > room && paint.textSize > 8f * u) paint.textSize -= u
        cv.drawText(big, ringX, cy + paint.textSize * 0.36f, paint); paint.textAlign = Paint.Align.LEFT
    }

    /** The icon and the live widget's name on top, in one row; returns where the next row starts. */
    private fun drawHead(cv: Canvas, pad: Float, size: Float): Float {
        val ic = if (icon != null) 20f * u else 0f
        icon?.let { it.setBounds(pad.toInt(), (10f * u).toInt(), (pad + ic).toInt(), (10f * u + ic).toInt()); it.draw(cv) }
        paint.typeface = Font.bold; paint.textSize = size; paint.color = LINE_INK
        val x = pad + if (ic > 0f) ic + 6f * u else 0f
        cv.drawText(fit(title, w - x - pad), x, 10f * u + ic / 2f + size * 0.36f, paint)
        return 10f * u + ic
    }

    private fun drawNext(cv: Canvas, pad: Float) {
        drawHead(cv, pad, 13f * u)
        val room = w - 2 * pad
        if (empty) {                                                    // nothing watched yet: say so, and what a press does
            paint.typeface = Font.bold; paint.textSize = 19f * u; paint.color = INK
            cv.drawText(fit(big, room), pad, 56f * u, paint)
            paint.typeface = Font.medium; paint.textSize = 12.5f * u; paint.color = LINE_INK
            cv.drawText(fit(small, room), pad, 77f * u, paint)
            return
        }
        // for a TV at 3 m: the logo and the channel's name big, ONE line for what is on, and a thin bar for how far it is (no time text, no "next")
        val logoH = 30f * u; val logoW = 42f * u; val top = 30f * u
        val bmp = logos.get(logoUrl)
        box.set(pad, top, pad + logoW, top + logoH)
        paint.color = INK; cv.drawRoundRect(box, 7f * u, 7f * u, paint)
        if (bmp != null) {
            val f = Math.min((logoW - 8f * u) / bmp.width, (logoH - 8f * u) / bmp.height)   // fit-centre on the plain plate, never stretched
            val bw = bmp.width * f; val bh = bmp.height * f
            cv.drawBitmap(bmp, null, RectF(pad + (logoW - bw) / 2f, top + (logoH - bh) / 2f, pad + (logoW + bw) / 2f, top + (logoH + bh) / 2f), paint)
        }
        val nx = pad + logoW + 8f * u
        paint.typeface = Font.bold; paint.textSize = 20f * u; paint.color = INK
        while (paint.measureText(big) > w - nx - pad && paint.textSize > 14f * u) paint.textSize -= u   // shrinks before it cuts: a name is read whole
        cv.drawText(fit(big, w - nx - pad), nx, top + logoH / 2f + paint.textSize * 0.36f, paint)
        val hasBar = bar >= 0f
        paint.typeface = Font.medium; paint.textSize = 13.5f * u; paint.color = INK
        cv.drawText(fit(small, room), pad, if (hasBar) 82f * u else 88f * u, paint)
        if (hasBar) {
            val bh = 3.5f * u; val by = 90f * u
            paint.color = TRACK; box.set(pad, by, pad + room, by + bh); cv.drawRoundRect(box, bh / 2f, bh / 2f, paint)
            paint.color = INK; box.set(pad, by, pad + Math.max(bh, room * bar), by + bh); cv.drawRoundRect(box, bh / 2f, bh / 2f, paint)
        }
    }

    private fun drawToggle(cv: Canvas, pad: Float) {
        drawHead(cv, pad, 15f * u)
        paint.typeface = Font.bold; paint.textSize = 17f * u               // 22 sp: "Tắt" outweighed the live widget's name above it
        val ph = 30f * u; val top = 36f * u                              // the pill, then what a press does under it: both get the whole width
        val pw = Math.max(paint.measureText(big) + 22f * u, ph * 1.6f)
        val cx = pad + pw / 2f; val cy = top + ph / 2f
        cv.save(); cv.scale(pop, pop, cx, cy)
        box.set(pad, top, pad + pw, top + ph)
        if (on) { paint.style = Paint.Style.FILL; paint.color = INK }
        else { paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * u; paint.color = LINE_INK; box.inset(1.5f * u, 1.5f * u) }
        cv.drawRoundRect(box, ph / 2f, ph / 2f, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (on) colours()[1] else INK; paint.textAlign = Paint.Align.CENTER
        cv.drawText(big, cx, cy + 6f * u, paint); paint.textAlign = Paint.Align.LEFT
        cv.restore()
        paint.typeface = Font.medium; paint.textSize = 12.5f * u; paint.color = LINE_INK
        cv.drawText(fit(small, w - 2 * pad), pad, 90f * u, paint)
    }

    private fun fit(t: String, room: Float): String {
        if (paint.measureText(t) <= room) return t
        var s = t
        while (s.length > 1 && paint.measureText("$s…") > room) s = s.dropLast(1)
        return "$s…"
    }
}
