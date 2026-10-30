package dev.tvremote.home

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tvremote.home.widgets.Widgets
import dev.tvremote.home.widgets.prayerFacts
import org.json.JSONObject

/**
 * A widget's settings, one dialog for every widget, drawn from [HomeConfig.options] (no widget has a screen of its own): a row per option
 * (a choice shows its values as chips, Left / Right pick one, OK goes to the next; a toggle flips on OK), and the real widget beside
 * them, drawn by the same [Widgets] code at the chosen values, so what is seen here is what the home screen shows. Each press is
 * saved at once (the home screen behind changes with it); Back or Done closes, and the focus stays on the row last changed.
 */
object WidgetSettings {
    private const val ACCENT = 0xFF8AB4F8.toInt()
    private const val DIM = 0xA6FFFFFF.toInt()
    private const val PANEL = 0xFF161A24.toInt()                       // solid: the Customize panel behind is hidden while it is open, nothing shows through
    private const val ROW_FOCUS = 0x2EFFFFFF; private const val ROW_REST = 0x12FFFFFF; private const val ROW_EDGE = 0x1FFFFFFF
    private const val CHIP_ON = 0x408AB4F8; private const val CHIP_OFF = 0x14FFFFFF

    private fun word(a: Context, id: String): String =
        a.resources.getIdentifier(id.replace('.', '_'), "string", a.packageName).let { if (it != 0) a.getString(it) else id.substringAfterLast('_') }

    /** The words of every option's value for widget [k] in [conf], in the schema's order, e.g. "Big", "Minimal", "Light" (the state line of the widget's row). */
    fun summary(a: Context, conf: JSONObject, k: String): String =
        HomeConfig.options(k).joinToString(" · ") { o -> word(a, "home.ov_" + HomeConfig.optValue(conf, k, o.id)) }

    /** The words of the options a cleaned theme [conf] sets besides the size (a theme keeps only what is not the default). */
    fun looks(a: Context, conf: JSONObject, k: String): List<String> =
        HomeConfig.options(k).filter { it.store == "wopt" }.mapNotNull { o -> HomeConfig.optValue(conf, k, o.id).takeIf { it != o.default }?.let { word(a, "home.ov_$it") } }

    fun show(a: Activity, k: String, title: String, done: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        fun glass(radius: Int, fill: Int, stroke: Int) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(fill); setStroke(dp(1), stroke) }
        fun text(t: String, size: Float, color: Int) = Font.on(TextView(a)).apply { text = t; textSize = size; setTextColor(color); maxLines = 1 }
        val opts = HomeConfig.options(k)
        val now = Store.get(a)
        val cur = HashMap<String, String>().also { m -> for (o in opts) m[o.id] = HomeConfig.optValue(now, k, o.id) }
        val dialog = Dialog(a, R.style.Menu)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // the widget itself, beside the options: the same Widgets code at the chosen values (read once off the main thread, drawn again on each press)
        val stage = FrameLayout(a).apply { clipChildren = false; clipToPadding = false }
        val widget = Widgets(a, ::dp).apply { clipChildren = false }
        var facts: Widgets.Facts? = null
        fun redraw() {
            val f = facts ?: return
            val big = cur["size"] == "big"
            widget.options = JSONObject().put("wopt", JSONObject().put(k, JSONObject().also { e -> for ((i, v) in cur) e.put(i, v) }))
            if (k == "prayer") f.pray = widget.prayerFacts()          // the method, Asr and Hijri change the numbers, not only the look (offline, quick)
            val sizes = JSONObject().put(k, cur["size"] ?: "small")
            // a lone widget is a column of its weight in a row of at least FLOOR: the stage is as wide as that row so the column is the widget's own width
            val widgetW = dp(if (big) 330 else 230)
            val weight = HomeConfig.weight(k, big)
            val row = widgetW * maxOf(HomeConfig.FLOOR, weight) / weight
            stage.layoutParams = stage.layoutParams.apply { width = widgetW }
            widget.layoutParams = FrameLayout.LayoutParams(row, dp(Widgets.BIG_H))
            widget.post {
                widget.show(listOf(k), f, "", { it.substringAfterLast('.') }, sizes, now.optJSONObject("qrs") ?: JSONObject())
            }
        }
        stage.addView(widget, FrameLayout.LayoutParams(dp(330) * 2, dp(Widgets.BIG_H)))
        Thread {
            val f = widget.read(listOf(k))
            a.runOnUiThread { if (dialog.isShowing) { facts = f; redraw() } }
        }.start()

        // the Widget page's layout strip, every widget on in its place, this one framed; Store has each press already, so it only repaints
        val strip = Customize.PackGlyph(a).apply { mark = k; named = true }
        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val changed = HashMap<String, View>()
        fun save(o: HomeConfig.Opt, v: String) {
            cur[o.id] = v
            if (o.store == "wsize") {
                val sizes = JSONObject((Store.get(a).optJSONObject("wsize") ?: JSONObject()).toString())
                Store.change(a, JSONObject().put("wsize", sizes.put(k, v)))
            } else Store.change(a, JSONObject().put("wopt", JSONObject().put(k, JSONObject().put(o.id, v))))
            strip.invalidate(); redraw()
        }
        fun chips(o: HomeConfig.Opt, room: Int): Pair<LinearLayout, () -> Unit> {
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val values = if (o.type == "toggle") listOf("off", "on") else o.values
            val views = values.map { v ->
                text(word(a, "home.ov_$v"), 14f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(5), dp(12), dp(5)) }
            }
            // as many chips on a line as fit, the rest on the next: one line never scrolled, so a clock's 5 faces were cut after the 4th
            var line: LinearLayout? = null; var used = 0
            for (tv in views) {
                val w = tv.paint.measureText(tv.text.toString()).toInt() + dp(24)
                if (line == null || used + dp(6) + w > room) {
                    line = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
                    box.addView(line, LinearLayout.LayoutParams(-2, -2).apply { if (box.childCount > 0) topMargin = dp(6) })
                    used = 0
                }
                line.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { if (used > 0) leftMargin = dp(6) })
                used += (if (used > 0) dp(6) else 0) + w
            }
            fun mark() { views.forEachIndexed { i, tv -> val on = values[i] == cur[o.id]; tv.background = glass(14, if (on) CHIP_ON else CHIP_OFF, if (on) ACCENT else ROW_EDGE) } }
            mark()
            return box to ::mark
        }
        var first: View? = null
        for (o in opts) {
            val values = if (o.type == "toggle") listOf("off", "on") else o.values
            val under = values.size >= 3                            // three or more chips go under the label so neither is cut
            val (box, mark) = chips(o, dp(330 - 28) - if (under) 0 else dp(120))   // the row's width less its padding (and the label beside them)
            fun go(step: Int) {
                val at = values.indexOf(cur[o.id])
                val v = values[Math.floorMod(at + step, values.size)]
                save(o, v); mark(); changed[o.id]?.requestFocus()
            }
            box.gravity = if (under) Gravity.START else Gravity.END
            val row = LinearLayout(a).apply {
                orientation = if (under) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (under) Gravity.START else Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), glass(16, ROW_FOCUS, Color.WHITE))
                    addState(intArrayOf(), glass(16, ROW_REST, ROW_EDGE))
                }
                addView(text(word(a, "home.o_${o.id}"), 15f, Color.WHITE), if (under) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) } else LinearLayout.LayoutParams(0, -2, 1f))
                addView(box, LinearLayout.LayoutParams(-2, -2))
                setOnClickListener { go(1) }
                setOnKeyListener { _, code, e ->
                    if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> go(-1); KeyEvent.KEYCODE_DPAD_RIGHT -> go(1); else -> return@setOnKeyListener false }
                    true
                }
            }
            changed[o.id] = row
            if (first == null) first = row
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (rows.childCount > 0) topMargin = dp(8) })
        }
        if (k == "adblock") rows.addView(text(a.getString(R.string.adb_note), 12f, DIM).apply { maxLines = 3 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })   // DNS-level only: said where the widget is set
        val close = text(a.getString(R.string.ok), 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true; setPadding(dp(14), dp(9), dp(14), dp(9))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), glass(16, ROW_FOCUS, Color.WHITE))
                addState(intArrayOf(), glass(16, ROW_REST, ROW_EDGE))
            }
            setOnClickListener { dialog.dismiss() }
        }

        val left = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(rows, LinearLayout.LayoutParams(-1, -2))
            addView(close, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        // the options scroll when they are taller than the screen leaves them (the prayer widget's methods: its Hijri row and OK were cut off);
        // a ScrollView brings the focused row into view by itself
        val room = a.resources.displayMetrics.heightPixels - dp(16 + 16 + 32 + 26 + 72 + 22 + 16)   // the window's gap, the panel's padding, title, strip
        val scroll = object : android.widget.ScrollView(a) {
            override fun onMeasure(w: Int, h: Int) {                 // what the panel leaves it, never more than the screen does
                val most = if (View.MeasureSpec.getMode(h) == View.MeasureSpec.UNSPECIFIED) room else minOf(room, View.MeasureSpec.getSize(h))
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(most, View.MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; isFillViewport = false; addView(left) }
        val body = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL; clipChildren = false
            addView(FrameLayout(a).apply { addView(scroll) }, LinearLayout.LayoutParams(dp(330), -2))   // a clipping frame: body draws its children unclipped (the stage), a ScrollView then drew over the title
            addView(stage, LinearLayout.LayoutParams(dp(330), dp(Widgets.BIG_H)).apply { leftMargin = dp(28) })
        }
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(PANEL); setStroke(dp(1), ACCENT) }
            clipChildren = false
            addView(text(title, 18f, Color.WHITE).apply { typeface = Font.medium })
            addView(strip, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(10); bottomMargin = dp(12) })
            addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        val w = minOf(a.resources.displayMetrics.widthPixels - dp(48), dp(780))
        dialog.setContentView(root, ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(16) }
            setDimAmount(0f)
        }
        Customize.hidePanel(true)                       // the panel behind would show through the corners and the gaps
        dialog.setOnShowListener { first?.requestFocus() }
        dialog.setOnDismissListener { Customize.hidePanel(false); done() }
        dialog.show()
    }
}
