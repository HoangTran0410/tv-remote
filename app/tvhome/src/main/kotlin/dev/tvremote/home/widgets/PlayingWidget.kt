package dev.tvremote.home.widgets

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.R
import dev.tvremote.home.apps.Apps
import org.json.JSONObject

/**
 * "Now playing": what plays behind Home (SmartTube's music, a podcast), read by TV Setup ([HomeConfig.USAGE] "playing", its own
 * NowPlaying over ADB: a launcher may not read other apps' media sessions). Never blank, never hidden (the user, 2026-11-11):
 * - playing / paused in front: the app, the title; OK pauses or plays;
 * - an app paused behind Home ("held"): OK plays it again;
 * - nothing: the last one played (kept here), OK opens that app;
 * - never anything: one line saying what will show here; OK its menu.
 * Asked every [EVERY_MS] only while the widget is on the screen and Home is in front (TV Setup's helper stops 20 s after the last ask).
 */
internal class PlayingWidget(private val w: Widgets, private val box: LinearLayout, private val full: Boolean, private val tonal: Boolean) {
    private val a get() = w.a
    private val ink = if (tonal) Tonal.INK else android.graphics.Color.WHITE
    private val dim = if (tonal) Tonal.SUB else Widgets.DIM
    private val ui = Handler(Looper.getMainLooper())
    private var on = false
    private var asking = false
    private var drawn = ""

    private val tick = object : Runnable {
        override fun run() {
            if (!on) return
            if (!w.pausedHome && box.isShown) ask(null)
            ui.postDelayed(this, EVERY_MS)
        }
    }

    init {
        draw(now)
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { on = true; ui.removeCallbacks(tick); ui.post(tick) }
            override fun onViewDetachedFromWindow(v: View) { on = false; ui.removeCallbacks(tick) }
        })
    }

    /** Home came back in front: read at once, not at the next tick. */
    fun resumed() { if (on) ask(null) }

    /** OK on the widget. */
    fun ok() {
        val s = now
        val st = s?.optString("state").orEmpty()
        val held = s?.optJSONObject("held")
        when {
            st == "playing" || st == "buffering" -> ask(JSONObject().put("do", "pause"))
            st == "paused" -> ask(JSONObject().put("do", "play"))
            held != null -> ask(JSONObject().put("do", "resume").put("pkg", held.optString("pkg")))
            last(a) != null -> last(a)?.optString("pkg")?.let { if (!Apps.launch(a, it, null)) android.widget.Toast.makeText(a, a.getString(R.string.no_open), android.widget.Toast.LENGTH_SHORT).show() }
            else -> w.onMenu?.invoke("playing", box)
        }
    }

    private fun ask(op: JSONObject?) {
        if (asking && op == null) return
        asking = true
        Thread({
            val s = try { a.contentResolver.call(Uri.parse(HomeConfig.USAGE), "playing", op?.toString(), null)?.getString("json")?.let { JSONObject(it) } }
                catch (e: Exception) { null }
            val r = s ?: JSONObject().put("state", "none").put("setup", false)   // no TV Setup, or one older than this widget
            ui.post { asking = false; remember(a, r); now = r; if (on) draw(r) }
            if (op != null) ui.postDelayed({ if (on) ask(null) }, 900)  // the app takes a moment to say it paused: the glyph turns then, not at the next tick
        }, "playing").apply { isDaemon = true }.start()
    }

    private fun draw(s: JSONObject?) {
        val key = s?.toString().orEmpty().replace(Regex("\"pos\":\\d+,?"), "") + (last(a)?.toString() ?: "")
        if (key == drawn && box.childCount > 0) return                // the position moves every tick: not drawn, so nothing else is
        drawn = key
        box.removeAllViews()
        val st = s?.optString("state").orEmpty()
        val held = s?.optJSONObject("held")
        val prev = last(a)
        val (what, item, glyph) = when {
            st == "playing" || st == "buffering" -> Triple(R.string.pl_playing, s, R.drawable.ic_pause)
            st == "paused" -> Triple(R.string.pl_paused, s, R.drawable.ic_play)
            held != null -> Triple(R.string.pl_paused, held, R.drawable.ic_play)
            prev != null -> Triple(R.string.pl_last, prev, R.drawable.ic_play)
            else -> Triple(0, null, 0)
        }
        box.gravity = Gravity.CENTER_VERTICAL
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val size = w.dp(if (full) 56 else 40)
        if (item == null) {                                           // nothing played yet: what will show here, and why it may not
            row.addView(w.tile(Widgets.VIOLET, R.drawable.ic_music), LayoutParams(size, size))
            val why = when {
                s?.optBoolean("setup", true) == false -> R.string.w_need_setup
                s?.optBoolean("adb", true) == false -> R.string.pl_need_adb
                else -> R.string.pl_none
            }
            row.addView(line(a.getString(why), 13f, dim, 3), LayoutParams(0, -2, 1f).apply { leftMargin = w.dp(12) })
        } else {
            val pkg = item.optString("pkg")
            row.addView(ImageView(a).apply {
                setImageDrawable(icon(a, pkg)); scaleType = ImageView.ScaleType.FIT_CENTER; clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * .22f) } }
            }, LayoutParams(size, size))
            val app = item.optString("app").ifEmpty { pkg }
            val title = item.optString("title").takeIf { it.isNotEmpty() && it != "null" }
            val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            words.addView(line(a.getString(what) + " · " + app, 12f, dim, 1))
            words.addView(line(title ?: app, if (full) 17f else 14f, ink, if (full) 2 else 1).apply { typeface = Widgets.MEDIUM })
            item.optString("sub").takeIf { full && it.isNotEmpty() && it != "null" }?.let { words.addView(line(it, 12f, dim, 1)) }
            row.addView(words, LayoutParams(0, -2, 1f).apply { leftMargin = w.dp(12); rightMargin = w.dp(8) })
            row.addView(ImageView(a).apply { setImageResource(glyph); imageTintList = android.content.res.ColorStateList.valueOf(ink) }, LayoutParams(w.dp(if (full) 30 else 24), w.dp(if (full) 30 else 24)))
        }
        box.addView(row, LayoutParams(-1, -2))                         // no title over it: "Playing · SmartTube" says what it is
    }

    private fun line(t: String, size: Float, color: Int, lines: Int): TextView =
        (if (tonal) w.ink(t, size, color) else w.text(t, size, color)).apply { maxLines = lines; isSingleLine = lines == 1 }

    companion object {
        const val EVERY_MS = 3000L
        /** The last answer, so the minute's rebuild draws it at once instead of a blank. */
        private var now: JSONObject? = null
        private val icons = HashMap<String, Drawable?>()

        private fun icon(c: Context, pkg: String): Drawable? = icons.getOrPut(pkg) {
            try { dev.tvremote.home.apps.shrink(c, c.packageManager.getApplicationIcon(pkg), 256) } catch (e: Exception) { null }
        }

        private fun prefs(c: Context) = c.getSharedPreferences("playing", Context.MODE_PRIVATE)
        private var lastMemo: JSONObject? = null; private var lastRead = false

        /** The last app that played (pkg, app, title), kept across restarts: the widget's "last played" when nothing plays. */
        fun last(c: Context): JSONObject? {
            if (!lastRead) { lastMemo = try { prefs(c).getString("last", null)?.let { JSONObject(it) } } catch (e: Exception) { null }; lastRead = true }
            return lastMemo
        }

        /** Keeps what plays as "last", written only when the app or the title changes. */
        fun remember(c: Context, s: JSONObject) {
            val st = s.optString("state")
            val item = if (st == "playing" || st == "paused" || st == "buffering") s else s.optJSONObject("held") ?: return
            val pkg = item.optString("pkg").takeIf { it.isNotEmpty() } ?: return
            val o = JSONObject().put("pkg", pkg).put("app", item.optString("app")).put("title", item.optString("title").takeIf { it != "null" } ?: "")
            if (o.toString() == last(c)?.toString()) return
            lastMemo = o
            prefs(c).edit().putString("last", o.toString()).apply()
        }
    }
}
