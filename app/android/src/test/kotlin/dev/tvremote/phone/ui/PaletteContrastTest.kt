package dev.tvremote.phone.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG contrast of the colours the screens really pair, in both themes: words 4.5:1, icons (non-text) 3:1. */
class PaletteContrastTest {
    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
    }
    private fun ratio(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    private val white = 0xffffffff.toInt()

    @Test fun bothPalettesReadable() {
        val bad = ArrayList<String>()
        for ((name, p) in listOf("dark" to Palette.dark, "light" to Palette.light)) {
            fun need(what: String, fg: Int, bg: Int, min: Double) {
                val r = ratio(fg, bg)
                if (r < min) bad.add("$name $what: %.2f < %.1f".format(r, min))
            }
            for ((bn, bg) in listOf("bg" to p.bg, "card" to p.card, "btn" to p.btn)) {
                need("text on $bn", p.text, bg, 4.5)
                need("muted on $bn", p.muted, bg, 4.5)                 // MUTED is used for 12-13sp notes: full text contrast
                need("dim on $bn", p.dim, bg, 3.0)                     // an icon or label that is off
            }
            need("white on accent", white, p.accent, 4.5)
            need("white on green", white, p.green, 4.5)
            for ((bn, bg) in listOf("bg" to p.bg, "card" to p.card)) need("accentText on $bn", p.accentText, bg, 4.5)
            need("accentText on btn", p.accentText, p.btn, 4.5)
            need("accentText on sel", p.accentText, p.sel, 4.5)            // an on / chosen button's words (setOn)
            need("accent edge on card", p.accent, p.card, 3.0)              // what marks it on, beside an off one
            need("okText on card", p.okText, p.card, 4.5)
            need("errText on card", p.errText, p.card, 4.5)
            need("red on card", p.red, p.card, 4.5)
            need("red on btn", p.red, p.btn, 4.5)                      // the power icon sits on a BTN
            need("errText on errBg", p.errText, p.errBg, 4.5)
            need("text on errBg", p.text, p.errBg, 4.5)
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
