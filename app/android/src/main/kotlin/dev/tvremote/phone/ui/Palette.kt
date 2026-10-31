package dev.tvremote.phone.ui

/** One theme's colours as plain numbers (no Android needed), so a unit test can check their contrast; [applyPalette] copies one into Ui.kt's vars. */
internal class Palette(
    val bg: Int, val card: Int, val btn: Int, val accent: Int, val accentText: Int, val held: Int, val text: Int, val muted: Int,
    val red: Int, val green: Int, val errText: Int, val errBg: Int, val dim: Int, val okText: Int, val ripple: Int, val pill: Int, val soft: Int,
    val sel: Int,                                                     // on / chosen: a tint under accentText words, with an accent edge (Ui.kt setOn)
) {
    companion object {
        val dark = Palette(
            bg = 0xff111418.toInt(), card = 0xff1c2026.toInt(), btn = 0xff2a3038.toInt(),
            accent = 0xff3366e8.toInt(),                               // fills, with white on top
            accentText = 0xff7ba2ff.toInt(),                           // the same blue as words or an icon on the screen
            held = 0xffe0a030.toInt(), text = 0xffe8eaed.toInt(), muted = 0xff9aa0a6.toInt(), red = 0xffff8a80.toInt(),
            green = 0xff2e7d4f.toInt(),                                // a fill, with white on top (the update banner)
            errText = 0xffffb4a8.toInt(), errBg = 0xff3a2020.toInt(), dim = 0xff98a2b3.toInt(), okText = 0xff4ade80.toInt(),
            ripple = 0x40ffffff, pill = 0xd91e2128.toInt(), soft = 0x335b8cff, sel = 0xff212e4d.toInt(),
        )
        val light = Palette(
            bg = 0xfff3f4f7.toInt(), card = 0xffffffff.toInt(), btn = 0xffeceff3.toInt(),
            accent = 0xff2f6df6.toInt(), accentText = 0xff2559d6.toInt(), held = 0xfff0b040.toInt(), text = 0xff1a1d23.toInt(),
            muted = 0xff5b6676.toInt(), red = 0xffc02f2f.toInt(), green = 0xff137a41.toInt(),
            errText = 0xffb3261e.toInt(), errBg = 0xfffbe5e5.toInt(), dim = 0xff5b6676.toInt(), okText = 0xff137a41.toInt(),
            ripple = 0x30000000, pill = 0xe6ffffff.toInt(), soft = 0x262f6df6, sel = 0xffe6edfe.toInt(),
        )
    }
}
