package dev.tvremote.tvui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * THE look of the TV apps (app/DESIGN-TV.md): the tokens and the components, ONE copy. Compiled into all four TV apps
 * (TV Setup, TV Home, TV Channels, TV Games) through `srcDirs("../shared/src/tvui/kotlin")`: an app that needs a row, a sheet, a
 * panel, a pill or a field calls it here and never builds a look-alike (a hand-built copy is how they drifted, dev/decisions/apps.md "One shared UI set").
 * Plain Views: the apps must stay small and quick on a 1 GB TV box. Components take words and drawable ids, never string ids:
 * each app keeps its own strings and its own generated icons.
 *
 * The tokens are constants, not `R.color.tv_*`: this file is compiled into apps whose `R` it cannot name. Their values are
 * `tv_style.xml`'s (beside it in `res/`); `tv_design.test.js` fails when the two disagree.
 */
object TvUi {
    // ---- colours (§2.1): dark only ----
    const val BG = 0xFF0E1116.toInt()                                  // = tv_bg
    const val CARD = 0xFF1B2028.toInt()                                // = tv_card: panels, cards, fields
    const val TEXT = 0xFFE8EAED.toInt()                                // = tv_ink
    const val DIM = 0xFF9AA0A6.toInt()                                 // = tv_dim
    const val RING_ACCENT = 0xFF2F7DF6.toInt()                         // = tv_ring_accent: the focus ring only
    const val DANGER = 0xFFF28B82.toInt()                              // the words of a button that removes or revokes
    const val ACCENT = 0xFF5EEAD4.toInt()                              // the default app accent (Channels'): a chosen item, a spinner
    const val ACTIVE_TINT = 0x2E14B8A6                                 // the selected row's fill, under the focus's
    const val BAD = 0xFFF28B82.toInt()
    const val TILE = 0xFFF2F4F7.toInt()                                // logos are drawn for light backgrounds
    const val FOCUS_FILL = 0x33FFFFFF                                  // a focused row; also the rail's hairline family
    const val FIELD_REST = 0x14FFFFFF                                  // a field or a card in a sheet, at rest
    const val SHEET_FILL = 0xF21B2028.toInt()                          // a dialog sheet: tv_card, a little see-through
    const val SHEET_EDGE = 0x40FFFFFF                                  // its hairline
    const val SCRIM = 0xD90E1116.toInt()                               // the dimmed screen under a popup: the game still seen behind
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BTN = 0xFF2A3042.toInt()                                 // a menu button at rest
    const val BTN_INK_ON = 0xFF0B0D12.toInt()                          // words on a white fill (a focused button, a selected pill)
    const val PILL_REST = 0x24FFFFFF
    const val PILL_FOCUS = 0x73FFFFFF
    const val PILL_ON = 0xF2FFFFFF.toInt()
    const val PILL_INK = 0xFFE5E9F2.toInt()

    // ---- radii (§2.2), dp ----
    const val R_CARD = 16f                                             // = tv_radius_card: a panel, a card, a popup
    const val R_SHEET = 20f
    const val R_ROW = 12f
    const val R_BUTTON = 22f                                           // a button or a search field (a field in a sheet: R_FIELD)
    const val R_FIELD = 18f
    const val R_TILE = 10f                                             // = tv_radius_tile: an app / game tile, a poster
    const val R_MENU = 10f                                             // a menu button
    const val R_PILL = 30f
    const val RING_DP = 3f                                             // = tv_ring

    // ---- text (§2.4), sp ----
    const val SP_PANEL_HEAD = 28f
    const val SP_SHEET_TITLE = 18f
    const val SP_STATE = 17f
    const val SP_ROW = 15f
    const val SP_COUNT = 13f
    const val SP_BUTTON = 16f

    // ---- motion (§2.6), ms ----
    const val MS_PILL = 200L
    const val PILL_GROW = 1.05f

    fun dp(c: Context, v: Number) = (v.toFloat() * c.resources.displayMetrics.density)

    fun round(color: Int, r: Float, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply { setColor(color); cornerRadius = r; if (stroke > 0) setStroke(stroke, strokeColor) }

    /** TV Home's focus ring (app/DESIGN-TV.md): [RING_DP] of accent blue, then 1 dp of white inside it. */
    fun ring(c: Context, fill: Int, r: Float): Drawable {
        val o = dp(c, RING_DP).toInt(); val w = dp(c, 1).toInt()
        return LayerDrawable(arrayOf(round(fill, r, o + w, RING_ACCENT), round(Color.TRANSPARENT, maxOf(0f, r - w), w, WHITE))).apply {
            setLayerInset(1, o, o, o, o)
        }
    }

    /** A background that is [normal] at rest and a light fill when focused or pressed (D-pad and touch); [edge] adds the ring. */
    fun states(c: Context, normal: Int, r: Float, on: Int = FOCUS_FILL, edge: Boolean = true) = StateListDrawable().apply {
        val sel = if (edge) ring(c, on, r) else round(on, r)
        addState(intArrayOf(android.R.attr.state_focused), sel)
        addState(intArrayOf(android.R.attr.state_pressed), sel)
        addState(intArrayOf(android.R.attr.state_activated), round(ACTIVE_TINT, r))   // the selected row: a tint, under the focus's fill
        addState(intArrayOf(), round(normal, r))
    }

    fun text(c: Context, s: CharSequence, sp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
    }

    fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

    /** The first letters of a channel with no logo: "VTV3 HD" -> "VT". */
    fun initials(name: String) = name.filter { it.isLetterOrDigit() }.take(2).uppercase()

    private fun onOff(on: Int, off: Int) = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()), intArrayOf(on, off))

    /**
     * A row of the side list or a dialog: icon ([icon] a drawable id, 0 for none), words, optional count; focusable, with [go] on
     * OK / tap. Two states that read apart: focused (where the D-pad is: the light fill) and selected (the open group,
     * `isActivated`: words and icon in [accent], a tint), both at once on the open row with the focus. A group activates its
     * children, so they follow.
     */
    fun row(c: Context, icon: Int, label: CharSequence, count: String = "", accent: Int = ACCENT, go: () -> Unit) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        val p = dp(c, 12).toInt()
        setPadding(p, dp(c, 10).toInt(), p, dp(c, 10).toInt())
        background = states(c, Color.TRANSPARENT, dp(c, R_ROW), edge = false)
        isFocusable = true; isClickable = true
        setOnClickListener { go() }
        if (icon != 0) addView(ImageView(c).apply {
            setImageResource(icon); imageTintList = onOff(accent, TEXT)
        }, LinearLayout.LayoutParams(dp(c, 20).toInt(), dp(c, 20).toInt()).apply { rightMargin = dp(c, 12).toInt() })
        addView(text(c, label, SP_ROW).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTextColor(onOff(accent, TEXT)) },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (count.isNotEmpty()) addView(text(c, count, SP_COUNT, DIM).apply { setTextColor(onOff(accent, DIM)) })
    }

    /** A button that does something: a verb, bold, on a card with the ring. Not for a choice (that is [pill]). */
    fun button(c: Context, label: CharSequence, go: () -> Unit) = text(c, label, SP_BUTTON, bold = true).apply {
        setPadding(dp(c, 24).toInt(), dp(c, 10).toInt(), dp(c, 24).toInt(), dp(c, 10).toInt()); isFocusable = true; isClickable = true
        gravity = Gravity.CENTER                                      // a caller that sets the height (the picker's 46 dp Done) left the words at the top
        background = states(c, CARD, dp(c, R_BUTTON))
        setOnClickListener { go() }
    }

    /**
     * A text field the D-pad can always leave ([TvField]), the look all of them share: [rest] fill with the ring, a [radius] in dp,
     * [sp] words. [ime] is the keyboard's action (`IME_ACTION_SEARCH` / `DONE`). [icon] a drawable id drawn at its start, 0 for none.
     */
    fun field(c: Context, hint: String, sp: Float, ime: Int, rest: Int, radius: Float, padH: Int, padV: Int, icon: Int = 0): TvField =
        TvField(c).apply {
            this.hint = hint; setHintTextColor(DIM); setTextColor(TEXT); textSize = sp; isSingleLine = true
            imeOptions = ime or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = states(c, rest, dp(c, radius))
            setPadding(dp(c, padH).toInt(), dp(c, padV).toInt(), dp(c, padH).toInt(), dp(c, padV).toInt())
            if (icon != 0) { setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0); compoundDrawablePadding = dp(c, 8).toInt() }
        }

    /**
     * A dialog box, the look all of them share: [theme] the app's transparent dialog style, [w] dp wide. Back closes it.
     * The rows scroll once they are taller than the screen: Android caps the window there, and without the scroll the last
     * rows (the buttons) were squashed until their words vanished (a group list of a big playlist, a 540 dp tall TV).
     */
    fun sheet(c: Context, theme: Int, w: Int = 380): Pair<Dialog, LinearLayout> {
        val dlg = Dialog(c, theme)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(c, 14).toInt(); setPadding(p, p, p, p)
        }
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            background = round(SHEET_FILL, dp(c, R_SHEET), 1, SHEET_EDGE); clipToOutline = true   // the corners stay put while the rows scroll
            addView(box, ViewGroup.LayoutParams(-1, -2))
        }
        dlg.setContentView(scroll, ViewGroup.LayoutParams(dp(c, w).toInt(), -2))
        // a sheet whose caller sets no focus (a notice with one button) still starts on its first button: one OK press closes it.
        dlg.setOnShowListener {
            if (scroll.findFocus() == null) {
                val first = (0 until box.childCount).map { box.getChildAt(it) }.firstOrNull { it.isFocusable }
                first?.requestFocus()
            }
        }
        return dlg to box
    }

    /** A sheet's title, in line with its rows. */
    fun sheetTitle(box: LinearLayout, s: String) =
        box.addView(text(box.context, s, SP_SHEET_TITLE, bold = true).apply { setPadding(dp(box.context, 12).toInt(), 0, 0, dp(box.context, 10).toInt()) })

    /** A line under a sheet's title, in line with it. */
    fun sheetNote(box: LinearLayout, s: String) =
        text(box.context, s, SP_ROW, DIM).apply { setPadding(dp(box.context, 12).toInt(), 0, 0, dp(box.context, 10).toInt()) }.also { box.addView(it) }

    /**
     * One choice of a [menu]: [icon] a drawable id, [sub] a dim line under the words. A switch has [on] (its state, read again
     * after each press) and stays open when pressed; an action closes the menu first.
     */
    class Item(val icon: Int, val label: String, val sub: () -> String = { "" }, val on: (() -> Boolean)? = null, val go: () -> Unit) {
        constructor(icon: Int, label: String, sub: String, go: () -> Unit) : this(icon, label, { sub }, null, go)
        constructor(icon: Int, label: String, go: () -> Unit) : this(icon, label, { "" }, null, go)
    }

    /**
     * A list of choices in a [sheet], each with its icon and an optional second line. A switch ends in a ring, with [check] (the
     * app's own check icon) inside it while on, instead of "✓" / ": off" in its words; pressing it flips it in place.
     * [accent] is the app's own colour (icons, ring, check), as in [row]; [note] a dim line under the title.
     */
    fun menu(c: Context, theme: Int, title: String, items: List<Item>, check: Int, accent: Int = ACCENT, w: Int = 460, note: String = ""): Dialog {
        val (dlg, box) = sheet(c, theme, w)
        sheetTitle(box, title)
        if (note.isNotEmpty()) sheetNote(box, note)
        val first = box.childCount                                     // the first choice: after the title, and the note when there is one
        items.forEach { item ->
            val sub = text(c, "", SP_COUNT, DIM).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
            val ring = FrameLayout(c).apply { val p = dp(c, 4).toInt(); setPadding(p, p, p, p) }
            val tick = ImageView(c).apply { setImageResource(check); imageTintList = ColorStateList.valueOf(accent) }
            ring.addView(tick, FrameLayout.LayoutParams(-1, -1))
            fun show() {
                val s = item.sub(); sub.text = s; show(sub, s.isNotEmpty())
                val on = item.on?.invoke() ?: return
                ring.background = round(Color.TRANSPARENT, dp(c, 12), dp(c, 2).toInt(), if (on) accent else DIM)
                show(tick, on)
            }
            box.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                val p = dp(c, 12).toInt()
                setPadding(p, dp(c, 10).toInt(), p, dp(c, 10).toInt())
                background = states(c, Color.TRANSPARENT, dp(c, R_ROW), edge = false)
                isFocusable = true; isClickable = true
                setOnClickListener { if (item.on == null) { dlg.dismiss(); item.go() } else { item.go(); show() } }
                addView(ImageView(c).apply { setImageResource(item.icon); imageTintList = ColorStateList.valueOf(accent) },
                    LinearLayout.LayoutParams(dp(c, 22).toInt(), dp(c, 22).toInt()).apply { rightMargin = dp(c, 14).toInt() })
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(c, item.label, SP_ROW + 1))
                    addView(sub)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (item.on != null) addView(ring, LinearLayout.LayoutParams(dp(c, 24).toInt(), dp(c, 24).toInt()).apply { leftMargin = dp(c, 12).toInt() })
            }, LinearLayout.LayoutParams(-1, -2))
            show()
        }
        dlg.show()
        box.getChildAt(first)?.requestFocus()
        return dlg
    }

    /**
     * A yes/no question in a [sheet]: [title] (empty for none), [message], then [cancelLabel] and [okLabel] side by side. [danger]
     * (it removes or revokes something): Cancel comes first and has the focus, so a stray OK press does nothing, and the OK words
     * are red; otherwise OK is first and focused. Either button closes it; only OK runs [ok]. [accent] is the app's colour.
     */
    fun confirm(c: Context, theme: Int, title: String, message: String, okLabel: String, cancelLabel: String, danger: Boolean = false,
                accent: Int = ACCENT, ok: () -> Unit): Dialog {
        val (dlg, box) = sheet(c, theme, 460)
        if (title.isNotEmpty()) sheetTitle(box, title)
        sheetNote(box, message)
        fun btn(label: String, color: Int, go: () -> Unit) = button(c, label) { dlg.dismiss(); go() }.also { it.setTextColor(color) }
        val yes = btn(okLabel, if (danger) DANGER else accent, ok)
        val no = btn(cancelLabel, TEXT) { }
        val bar = LinearLayout(c).apply { gravity = Gravity.END; setPadding(0, dp(c, 6).toInt(), 0, 0) }
        val gap = dp(c, 12).toInt()
        val order = if (danger) listOf(no, yes) else listOf(yes, no)
        order.forEachIndexed { i, b -> bar.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = gap }) }
        box.addView(bar)
        dlg.show()
        order[0].requestFocus()
        return dlg
    }
}

/**
 * The whole screen's own state while there is nothing to list: a spinner while it loads, then the words and the buttons
 * ([buttons]: add the app's, the first is focused), or a bar when the size of the wait is known. Over [TvUi.BG], centred on the screen. Words: [loading] / [message].
 */
class TvState(c: Context, accent: Int = TvUi.ACCENT) : FrameLayout(c) {
    private val spin = ProgressBar(c).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(accent) }
    private val words = TvUi.text(c, "", TvUi.SP_STATE, TvUi.DIM).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
    private val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {   // a wait with a known size: how far it is
        max = 100; progressTintList = ColorStateList.valueOf(accent); progressBackgroundTintList = ColorStateList.valueOf(TvUi.FIELD_REST); visibility = View.GONE
    }
    val buttons = LinearLayout(c).apply { gravity = Gravity.CENTER }

    init {
        setBackgroundColor(TvUi.BG)
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(spin, LinearLayout.LayoutParams(TvUi.dp(c, 44).toInt(), TvUi.dp(c, 44).toInt()).apply { bottomMargin = TvUi.dp(c, 16).toInt() })
            addView(words, LinearLayout.LayoutParams(TvUi.dp(c, 520).toInt(), -2))
            addView(bar, LinearLayout.LayoutParams(TvUi.dp(c, 320).toInt(), TvUi.dp(c, 6).toInt()).apply { topMargin = TvUi.dp(c, 14).toInt() })
            addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = TvUi.dp(c, 16).toInt() })
        }, LayoutParams(-1, -1))
    }

    /** Add a button after the others, 12 dp from them. */
    fun add(b: View) = buttons.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (buttons.childCount > 0) marginStart = TvUi.dp(context, 12).toInt() })

    /** Loading: the spinner and [words], no buttons; with [percent] (0-100) a bar under the words shows how far it is. */
    fun loading(s: String, percent: Int = -1) {
        words.text = s; TvUi.show(spin, true); TvUi.show(buttons, false)
        TvUi.show(bar, percent >= 0); if (percent >= 0) bar.progress = percent.coerceAtMost(100)
    }

    /** Nothing to show and why: the words, the buttons; the first button gets the focus unless the screen has it already. */
    fun message(s: String) {
        words.text = s; TvUi.show(spin, false); TvUi.show(bar, false); TvUi.show(buttons, true)
        if (!hasFocus()) buttons.getChildAt(0)?.let { b -> b.post { b.requestFocus() } }
    }
}

// ---- the full-screen popup and the buttons in it (TV Games' look): a panel on a scrim ----

fun Context.dp(n: Int) = (n * resources.displayMetrics.density).toInt()

/** A rounded fill that gets a thick white edge while the D-pad focus is on it (readable from a sofa). */
fun Context.focusBg(radius: Int, fill: Int, focusFill: Int = fill): StateListDrawable {
    fun d(f: Int, stroke: Boolean) = GradientDrawable().apply {
        setColor(f); cornerRadius = dp(radius).toFloat(); if (stroke) setStroke(dp(4), TvUi.WHITE)
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), d(focusFill, true))
        addState(intArrayOf(), d(fill, false))
    }
}

fun Context.label(text: String, sp: Float, color: Int = TvUi.WHITE) = TextView(this).apply {
    this.text = text; textSize = sp; setTextColor(color); gravity = Gravity.CENTER
    // wrap, never the vertical LinearLayout's default match_parent: inside a wrap_content panel that made every label as wide as the
    // QR beside it (150 dp), so "Scan to add games" and the link wrapped and were clipped
    layoutParams = LinearLayout.LayoutParams(-2, -2)
}

/** The TV apps' panel (app/DESIGN-TV.md): `tv_card`, radius 16 dp; every card, popup and menu of TV Games is one. */
fun Context.panelBg() = GradientDrawable().apply { setColor(TvUi.CARD); cornerRadius = TvUi.dp(this@panelBg, TvUi.R_CARD) }

/** A panel's padding and column; [title] on top when given. */
fun Context.panel(title: String? = null) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = panelBg()
    setPadding(dp(36), dp(26), dp(36), dp(28))
    if (title != null) addView(heading(title))
}

fun Context.heading(text: String) = label(text, TvUi.SP_PANEL_HEAD, TvUi.TEXT).apply {
    typeface = Typeface.DEFAULT_BOLD; maxLines = 2; setPadding(dp(20), 0, dp(20), dp(10))
}

/** The screen under a panel: dimmed, the game or the library still seen behind (a dark game hid a dark card on no scrim). */
fun Context.scrim(child: View) = FrameLayout(this).apply {
    setBackgroundColor(TvUi.SCRIM); isClickable = true
    addView(child, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
}

/** A big focusable menu button: dark, white edge + light fill under focus. */
fun Context.menuButton(text: String, w: Int, h: Int, sp: Float, onClick: () -> Unit) = TextView(this).apply {
    this.text = text; textSize = sp; gravity = Gravity.CENTER
    isFocusable = true; isClickable = true
    background = focusBg(TvUi.R_MENU.toInt(), TvUi.BTN, TvUi.WHITE)
    setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(TvUi.BTN_INK_ON, TvUi.WHITE)))
    layoutParams = LinearLayout.LayoutParams(dp(w), dp(h)).apply { topMargin = dp(8) }
    setOnClickListener { onClick() }
}

/** A menu row: its line icon and words together on the left, the icon in the words' colour (dark under focus); a blank slot
 *  keeps rows without an icon in line. */
fun TextView.withIcon(r: Int?): TextView = apply {
    val d = if (r != null) context.getDrawable(r)!!.mutate().also { it.setTintList(textColors) } else ColorDrawable(0)
    val s = context.dp(22); d.setBounds(0, 0, s, s); setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = context.dp(14); gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(context.dp(24), 0, context.dp(20), 0)
}

/**
 * A pill: a choice you can switch on and off; selected (`isSelected`, the caller's) = white, focused = lighter, like the segmented
 * controls on Apple TV. [lead] / [trail] are drawable ids. [animate] says whether motion is on, [focused] runs when the D-pad
 * lands on it (the app scrolls it into view). 40 dp high, 12 apart: one height and gap for every pill, the header's too.
 */
fun Context.pill(text: String, sp: Float = 16f, lead: Int? = null, trail: Int? = null, animate: () -> Boolean = { true },
                 focused: (View) -> Unit = {}, onClick: () -> Unit): TextView = TextView(this).apply {
    this.text = text; textSize = sp; gravity = Gravity.CENTER; isFocusable = true; isClickable = true; maxLines = 1
    setPadding(dp(16), 0, dp(16), 0)
    fun d(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(TvUi.R_PILL.toInt()).toFloat() }
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_selected), d(TvUi.PILL_ON))
        addState(intArrayOf(android.R.attr.state_focused), d(TvUi.PILL_FOCUS))
        addState(intArrayOf(), d(TvUi.PILL_REST))
    }
    val ink = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(TvUi.BTN_INK_ON, TvUi.PILL_INK))
    setTextColor(ink)
    if (lead != null || trail != null) {
        fun ic(r: Int?) = r?.let { getDrawable(it)!!.mutate().also { d -> d.setBounds(0, 0, dp((sp + 3).toInt()), dp((sp + 3).toInt())); d.setTintList(ink) } }
        setCompoundDrawablesRelative(ic(lead), null, ic(trail), null); compoundDrawablePadding = dp(8)
        setPadding(dp(if (lead != null) 14 else 16), 0, dp(if (trail != null) 12 else 16), 0)
    }
    minWidth = Math.ceil(paint.measureText(text).toDouble()).toInt() + dp(36)   // the words alone: icons are not counted at first (as before the move), pillText() counts them when the words change
    setOnFocusChangeListener { v, on ->
        // colour only (the state list above): a scale needs a hardware layer built on every focus, ~7x the CPU of a colour change on the target box
        if (on) focused(v)
    }
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(12) }
}

/** New words for a pill: it wraps them (the measure alone came a pixel short in a scroller), its icons and padding added. */
fun TextView.pillText(t: String) {
    text = t
    minWidth = Math.ceil(paint.measureText(t).toDouble()).toInt() + context.dp(36) +
        compoundDrawablesRelative.sumOf { (it?.bounds?.width() ?: 0) + (if (it != null) context.dp(8) else 0) }
}
