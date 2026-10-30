package dev.tvremote.home.keys

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.tvremote.home.Font
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.R
import dev.tvremote.home.Store
import dev.tvremote.home.Target
import dev.tvremote.home.apps.App
import dev.tvremote.home.apps.LiveWidgets
import dev.tvremote.tvui.TvGlass
import dev.tvremote.tvui.pill
import dev.tvremote.tvui.pillText
import org.json.JSONObject

/**
 * "Quick keys" (Phím nhanh), webOS's number-key screen for this TV: 1-9 and 0, the four colour keys and the power-on slot, each a tile
 * with the logo of what it opens (a [Target]: app, shortcut or live widget). A key this remote does not have ([Keys.have]) is greyed
 * and cannot be chosen, unless something is already on it (so it can still be cleared). A press picks the target in the [Picker];
 * the first one chosen switches the keys on (HomeConfig keyon / keyapps / autoon / autoapp, the same settings the phone's page edits).
 */
object QuickKeys {
    internal val COLOUR = mapOf(183 to 0xFFB3261E.toInt(), 184 to 0xFF2E7D32.toInt(), 185 to 0xFFB58105.toInt(), 186 to 0xFF1D5FBF.toInt())
    private const val POWER = -1                                     // the power-on slot's place among the keys

    fun show(a: Activity): Dialog {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        val dm = a.resources.displayMetrics
        val dialog = Dialog(a, R.style.Menu)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panelW = minOf(dm.widthPixels - dp(64), dp(1180))

        val area = FrameLayout(a).apply { clipChildren = false; clipToPadding = false }
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(34), dp(24), dp(34), dp(70)) }
        area.addView(grid, FrameLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(area) }

        fun line(t: String, size: Float, color: Int) = Font.on(TextView(a)).apply { text = t; textSize = size; setTextColor(color) }
        val hint = line("", 13f, PK_DIM).apply { setPadding(dp(34), 0, dp(34), dp(4)) }
        fun testKeys() {                                              // TV Setup draws the tester; started from here, the app in front (Android 14 blocks it from the background)
            Thread({
                val out = dev.tvremote.home.apps.SetupHand.control(a, "keytest")
                a.runOnUiThread {
                    if (out == null || out.has("error")) Toast.makeText(a, R.string.cc_setup_need, Toast.LENGTH_LONG).show() else dialog.dismiss()   // an old TV Setup answers "bad"
                }
            }, "home-keytest").apply { isDaemon = true }.start()
        }
        // the switch and its state in its words: "Bật phím" alone did not say whether the keys were on, nor what pressing would do
        val keysOn = a.pill(a.getString(R.string.qk_on, a.getString(R.string.qk_no)), 14f, animate = { true }) { Store.change(a, JSONObject().put("keyon", !Store.get(a).optBoolean("keyon"))) }
        // power-on wins over whatever the TV woke into (HomeConfig autoall); shown once TV Setup has said it takes it
        val always = a.pill(a.getString(R.string.qk_always, "TV Home", a.getString(R.string.qk_no)), 14f, animate = { true }) { Store.change(a, JSONObject().put("autoall", !Store.get(a).optBoolean("autoall"))) }
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(34), dp(58), dp(34), dp(12))   // 32 dp above the panel before, now inside it
            clipChildren = false; clipToPadding = false                // a focused pill grows past the row: clipped, its top and bottom were cut
            addView(line(a.getString(R.string.qk_title), 26f, Color.WHITE).apply { typeface = Font.bold; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(a.pill(a.getString(R.string.qk_test), 14f, animate = { true }) { testKeys() }, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(12) })   // TV Setup's key tester
            addView(always, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(12) })
            addView(keysOn)
        }
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            addView(head); addView(hint); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        // a tile lifts out of the scroll's box (its ring), so scrolled keys drew over the title and the switches: they sit above, on the page's colour
        for (v in listOf(head, hint)) { v.setBackgroundColor(TvGlass.PANEL_BOTTOM); v.translationZ = 1f; v.outlineProvider = null }
        dialog.setContentView(FrameLayout(a).apply { addView(panel, FrameLayout.LayoutParams(panelW, -1, Gravity.CENTER_HORIZONTAL)) })   // centred: a bare content view sat at the left; to the bottom edge: 64 dp short, the last row stopped on an empty band
        dialog.window?.let(::solidPage)

        val extra = listOf(POWER) + Keys.dead(a).filter { it in HomeConfig.KEY_DEAD }                      // the last row: power-on, then the keys the stock launcher answered
        val keyOrder = (HomeConfig.KEY_NUMBERS.drop(1) + HomeConfig.KEY_NUMBERS.first()) + HomeConfig.KEY_COLOURS + extra   // 1..9, 0, the colours, power-on, then keys the stock launcher answered
        val tiles = HashMap<Int, PickTile>()
        var seq = 0                                                   // a redraw superseded by a newer one (two changes in a row) is dropped

        fun targetOf(code: Int, c: JSONObject = Store.get(a)): String = if (code == POWER) c.optString("autoapp") else HomeConfig.keyApps(c.optJSONObject("keyapps"))[code].orEmpty()
        fun words(code: Int) = if (code == POWER) a.getString(R.string.qk_power) else Keys.name(a, code)
        fun choose(code: Int) {
            Picker.show(a, Picker.Mode.TARGET, words(code), targetOf(code), actions = code != POWER) { t ->   // the power-on slot opens an app: an action needs a key press
                if (code == POWER) Store.change(a, JSONObject().put("autoapp", t).put("autoon", t.isNotEmpty()))   // the slot alone decides
                else {
                    val m = JSONObject(); for ((k, v) in HomeConfig.keyApps(Store.get(a).optJSONObject("keyapps"))) if (k != code) m.put(k.toString(), v)
                    if (t.isNotEmpty()) m.put(code.toString(), t)
                    Store.change(a, JSONObject().put("keyapps", m).also { if (t.isNotEmpty()) it.put("keyon", true) })   // the first one chosen switches the keys on: nobody asks twice
                }
            }
        }
        fun paintHead() {
            val c = Store.get(a)
            keysOn.isSelected = c.optBoolean("keyon")
            fun said(on: Boolean) = a.getString(if (on) R.string.qk_yes else R.string.qk_no)
            keysOn.pillText(a.getString(R.string.qk_on, said(keysOn.isSelected)))
            always.visibility = if (Keys.all(a)) View.VISIBLE else View.GONE
            always.isSelected = c.optBoolean("autoall")
            // where it goes, in the words: "Luôn về khi bật TV" did not say to what
            val to = c.optString("autoapp").let { if (c.optBoolean("autoon") && it.isNotEmpty()) Targets.label(a, it) else "TV Home" }
            always.pillText(a.getString(R.string.qk_always, to, said(always.isSelected)))
            hint.text = a.getString(if (!Keys.setup(a)) R.string.k_need_setup else if (!Keys.can(a)) R.string.k_need_adb else R.string.k_everywhere)
        }

        fun redraw(keepFocus: Int?) {
            val mine = ++seq
            val have = Keys.have(a)?.let { arr -> List(arr.length()) { arr.getInt(it) } }
            Thread({
                val conf = Store.get(a)                                // once: read per key (twice each, 16 keys) it was ~400 ms on the FPT box
                val pics = HashMap<Int, App?>()
                for (code in keyOrder) targetOf(code, conf).takeIf { it.isNotEmpty() }?.let { pics[code] = Targets.app(a, it) }
                a.runOnUiThread {
                    if (mine != seq || !dialog.isShowing) return@runOnUiThread
                    paintHead()
                    grid.removeAllViews(); tiles.clear()
                    fun slot(code: Int): Keypad.Slot {
                        val target = targetOf(code, conf)
                        val art = pics[code]
                        val missing = target.isNotEmpty() && art == null                    // the app is gone: the slot shows it, to be cleared or changed
                        val off = code != POWER && have != null && code !in have
                        val info = if (Target.kind(target) == Target.Kind.TILE) LiveWidgets.available(a).firstOrNull { it.key == target } else null
                        return Keypad.Slot(if (art != null) art.label else if (missing) Targets.label(a, target) else if (off) a.getString(R.string.qk_no_key) else "", art, words(code), target.isEmpty(),
                            if (art == null) COLOUR[code] ?: PK_EMPTY else 0,
                            icon = if (code == POWER) R.drawable.ic_power else if (Target.kind(target) == Target.Kind.TILE) (info?.let { LiveWidgets.iconRes(a, it) } ?: R.drawable.ic_gauge) else 0,
                            dead = code != POWER && code in HomeConfig.KEY_NUMBERS + HomeConfig.KEY_COLOURS && off && target.isEmpty())
                    }
                    grid.addView(Keypad.build(a, ::dp, extra, ::slot, ::choose, tiles), LinearLayout.LayoutParams(-1, -2))
                    if (keysOn.hasFocus()) return@runOnUiThread   // a pill just switched: the focus stays on it, not on key 1
                    (keepFocus?.let { tiles[it] }?.takeIf { it.isFocusable } ?: keyOrder.firstNotNullOfOrNull { k -> tiles[k]?.takeIf { it.isFocusable } })?.requestFocus()   // key 1 first: a HashMap's order put it on any slot (the power-on one)
                }
            }, "home-quickkeys").apply { isDaemon = true }.start()
        }

        val change = Store.onChange                                   // the screen behind still hears every change; this one redraws too
        var lastFocus: Int? = null
        Store.onChange = { change?.invoke(); redraw(tiles.entries.firstOrNull { it.value.hasFocus() }?.key ?: lastFocus) }
        Keys.onState = { paintHead() }
        dialog.setOnDismissListener { Store.onChange = change; Keys.onState = null; Keys.capture(a, false) }
        dialog.setOnShowListener { Keys.push(a) }                     // asks TV Setup again, so the line above is true when it answers
        dialog.setOnKeyListener { _, code, e ->                       // the real key on the remote: the tile of that key is chosen without walking to it
            if (code in tiles.keys && code in HomeConfig.KEY_NUMBERS + HomeConfig.KEY_COLOURS) { if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) choose(code); true } else false
        }
        Keys.capture(a, true)
        paintHead(); redraw(null)
        dialog.show()
        return dialog
    }
}
