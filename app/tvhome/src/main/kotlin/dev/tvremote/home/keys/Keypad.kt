package dev.tvremote.home.keys

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.apps.App

/**
 * The remote as a keypad of flat [PickTile]s, the one layout of the assign-a-key page (HomeActivity.drawKeypad) and Quick keys
 * ([QuickKeys]): 1-2-3 / 4-5-6 / 7-8-9, 0 centred under, the four colour keys in a row, then [extra] (the power-on slot and the keys the
 * stock launcher answered) in a last row. What each key shows is the caller's ([Slot]); a press goes to [press].
 */
object Keypad {
    /** What one tile shows: [label] under the picture, [pic] the app on it, [tag] the key's name on the top edge, [icon] when no [pic]. */
    class Slot(val label: String, val pic: App?, val tag: String, val plus: Boolean, val color: Int, val icon: Int = 0, val dead: Boolean = false, val mark: Boolean = false)

    fun build(c: Context, dp: (Number) -> Int, extra: List<Int>, slot: (Int) -> Slot, press: (Int) -> Unit, tiles: MutableMap<Int, PickTile>): LinearLayout {
        val gap = dp(6)
        fun tile(code: Int): View {
            val s = slot(code)
            val wide = code in HomeConfig.KEY_COLOURS || code in extra
            val t = PickTile(c, dp(if (wide) 130 else 112), dp, "k$code", s.label, s.pic, "k$code", tag = s.tag, icon = s.icon, plus = s.plus, color = s.color, flat = true)
            t.deadAlpha = 0.65f
            t.dead = s.dead
            t.mark(s.mark)
            t.setOnClickListener { press(code) }
            tiles[code] = t
            return t
        }
        fun row(codes: List<Int>) = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false
            codes.forEachIndexed { i, k -> addView(tile(k), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = gap }) }
        }
        val n = HomeConfig.KEY_NUMBERS
        val digits = n.drop(1) + n.first()                            // KEYCODE_1..9, then 0
        val pad = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        for (r in listOf(digits.subList(0, 3), digits.subList(3, 6), digits.subList(6, 9), digits.subList(9, 10)))
            pad.addView(row(r), LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        pad.addView(row(HomeConfig.KEY_COLOURS), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        extra.chunked(5).forEachIndexed { i, r -> pad.addView(row(r), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0) 12 else 6) }) }   // 5 wide tiles a row: more ran off the panel
        return pad
    }

    /** The first key to focus: 1. */
    val first get() = HomeConfig.KEY_NUMBERS.getOrElse(1) { HomeConfig.KEY_NUMBERS.first() }
}
