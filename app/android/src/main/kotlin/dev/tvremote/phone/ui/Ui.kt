package dev.tvremote.phone.ui

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.R
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.media.say
import dev.tvremote.phone.remote.pill

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

// Colours, words in two languages, and the small view helpers every screen is built from.

// The palette: dark or light, set by [loadLang] before any view is built (Settings -> Appearance, or the phone's own mode).
internal var LIGHT = false
internal var BG = Palette.dark.bg
/** One of our apps the Downloader can put on a TV: [key] is its code in core/Downloader.kt; the rest are word ids. */
internal class DlApp(val key: String, val pkg: String, val title: String, val look: String, val open: String) {
    val code: String get() = dev.tvremote.core.Downloader.code(key).orEmpty()
}
internal val SETUP_APP = DlApp("setup", "dev.tvremote.setup", "setup.install_setup_title", "adb_help.the_app_has_entered", "adb_help.open_tv_setup")
internal val HOME_APP = DlApp("home", "dev.tvremote.home", "setup.install_home_title", "setup.look_body_home", "adb_help.open_tv_home")
internal val DL_CODE: String get() = dev.tvremote.core.Downloader.code("setup").orEmpty()   // AFTVnews short code of tv-setup.apk (docs/data/downloader.json, signed): Downloader takes it as downloader://<code> or in its address field
internal var CARD = Palette.dark.card
internal var BTN = Palette.dark.btn
internal var ACCENT = Palette.dark.accent                      // fills, with white on top (Palette.kt)
internal var ACCENT_TEXT = Palette.dark.accentText             // the same blue as words or an icon
internal var HELD = Palette.dark.held
internal var TEXT = Palette.dark.text
internal var MUTED = Palette.dark.muted
internal var RED = Palette.dark.red                            // the power icon, "Disconnect"
internal var GREEN = Palette.dark.green                        // the update banner (a fill: white on top)
internal var ERR_TEXT = Palette.dark.errText
internal var ERR_BG = Palette.dark.errBg
internal var DIM = Palette.dark.dim                            // an icon or label that is off
internal var OK_TEXT = Palette.dark.okText                     // "up", "done" as words
internal var RIPPLE = Palette.dark.ripple
internal var PILL = Palette.dark.pill                          // the now playing strip
internal var SOFT = Palette.dark.soft                          // a quiet blue button
internal var SEL = Palette.dark.sel                            // on / chosen (setOn)

/** Dark, light, or the phone's own mode ("theme" in the app's prefs: "" / auto, light, dark), and the colours to match. */
internal fun applyPalette(c: Context) {
    val pick = c.getSharedPreferences("tvs", Context.MODE_PRIVATE).getString("theme", "") ?: ""
    LIGHT = when (pick) {
        "light" -> true
        "dark" -> false
        else -> (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    val p = if (LIGHT) Palette.light else Palette.dark
    BG = p.bg; CARD = p.card; BTN = p.btn; ACCENT = p.accent; ACCENT_TEXT = p.accentText; HELD = p.held; TEXT = p.text; MUTED = p.muted
    RED = p.red; GREEN = p.green; ERR_TEXT = p.errText; ERR_BG = p.errBg; DIM = p.dim; OK_TEXT = p.okText
    RIPPLE = p.ripple; PILL = p.pill; SOFT = p.soft; SEL = p.sel
}

/** Dark icons on the status and navigation bars when the screen is light (they are painted in BG). */
internal fun barFlags(): Int =
    if (!LIGHT) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)

/** Once the window exists: bars in the screen's colour, with icons that show on it. */
internal fun Activity.paintBars() {
    window.statusBarColor = BG
    window.navigationBarColor = BG
    window.decorView.systemUiVisibility = barFlags()
}

internal const val INSTALL_RESULT = "dev.tvremote.phone.INSTALL_RESULT"
internal const val PICK_FILE = 77
internal const val SPEAK = 78

internal var VI = "vi" == Locale.getDefault().language             // set again in MainActivity.onCreate from the Settings choice

/** The Vietnamese or the English words, by the language chosen in Settings (or the phone's). */
internal fun t(vi: String, en: String): String = if (VI) vi else en

/** The language chosen in the app's Settings ("" = the phone's), for the parts that run without the main screen too. */
internal fun loadLang(c: Context) {
    val lang = c.getSharedPreferences("tvs", Context.MODE_PRIVATE).getString("lang", "") ?: ""
    VI = "vi" == (if (lang.isEmpty()) Locale.getDefault().language else lang)
    dev.tvremote.core.Words.lang = dev.tvremote.core.Words.pick(if (lang.isEmpty()) Locale.getDefault().language else lang)
    applyPalette(c)
}

internal fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
internal fun Context.dp(v: Int): Int = dp(v.toFloat())

internal fun Context.col(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    return l
}

internal fun Context.gap(top: Int): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(-1, -2)
    p.topMargin = dp(top)
    return p
}

internal fun Context.cell(height: Int, leftGap: Boolean): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(0, dp(height), 1f)
    if (leftGap) p.leftMargin = dp(6)
    return p
}

internal fun Context.box(color: Int, radius: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radius).toFloat()
    return g
}

/** Two blues that never look alike, as on the page (css/kit.css): the full ACCENT fill is a button that does something (OK,
 *  Send); on or chosen (a switch that is on, the choice in use) is this tint with a blue edge, its words and icon blue. */
internal fun Context.onBox(on: Boolean, radius: Int): GradientDrawable =
    if (on) box(SEL, radius).apply { setStroke(dp(1.5f), ACCENT) } else box(BTN, radius)

/** A button that is on or off: [onBox] behind it, its words blue while on, [off] otherwise. An [IconButton] takes [IconButton.chosen] too. */
internal fun TextView.setOn(on: Boolean, radius: Int = 14, off: Int = TEXT) {
    background = context.onBox(on, radius)
    setInk(if (on) ACCENT_TEXT else off)
    (this as? IconButton)?.chosen = on
}

internal fun Context.label(s: String, sp: Int, color: Int): TextView {
    val v = TextView(this)
    v.text = s
    v.textSize = sp.toFloat()
    v.setTextColor(color)
    if (s.any { dev.tvremote.tvui.Marks.SET.indexOf(it) >= 0 }) v.setMarked(s)     // a text mark in the words is drawn as an icon
    return v
}

internal fun Context.head(s: String): TextView {
    val v = label(s, 12, MUTED)
    v.typeface = Typeface.DEFAULT_BOLD
    v.letterSpacing = 0.08f
    return v
}

internal fun Context.note(s: String): TextView {
    val v = label(s, 13, MUTED)
    v.setPadding(0, dp(6), 0, 0)
    v.setLineSpacing(dp(3).toFloat(), 1f)
    return v
}

/** A fill that takes white words and icons in both themes (the blue and the green are dark enough for it; BTN and CARD are not). */
internal fun onFill(color: Int): Boolean = color == ACCENT || color == GREEN

/** [s] with its text marks drawn as line icons (the icon follows the text colour); for words that come with a mark in them. */
internal fun TextView.setMarked(s: CharSequence) { text = dev.tvremote.tvui.Marks.text(context, s) }

/** [s] as a button's or row's words; "@name|Words" puts the line icon ic_name (dev/gen/gen_icons.py, the one js/ui.js ICON gives that
 *  emoji) in front, in the text's colour. Anything else is plain text, and an icon left from before is dropped. */
internal fun TextView.setLabel(s: String) {
    val art = if (s.startsWith("@") && '|' in s) s.substring(1, s.indexOf('|')) else null
    setMarked(if (art != null) s.substringAfter("|") else s)          // a mark inside the words (a trailing ✓) becomes an icon too
    val id = if (art != null) resources.getIdentifier("ic_$art", "drawable", context.packageName) else 0
    if (id == 0) { setCompoundDrawablesRelative(null, null, null, null); return }
    val d = resources.getDrawable(id, null).mutate()
    val z = (textSize * 1.25f).roundToInt()
    d.setBounds(0, 0, z, z)
    d.setTint(currentTextColor)
    setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = (10 * resources.displayMetrics.density).roundToInt()
    removeOnLayoutChangeListener(IconBesideText); addOnLayoutChangeListener(IconBesideText)
    post { IconBesideText.place(this) }                               // new words on a button of the same size: no layout follows
}

/**
 * A switch's row: a ring before the words, a filled accent disc with a white check while on (app/DESIGN-TV.md: a switch is a
 * ring, never "✓" / "✗" in front of the words, which read as plain text).
 */
internal fun TextView.setToggle(on: Boolean, s: String) {
    text = s
    val z = (textSize * 1.25f).roundToInt()
    val d = Toggle(on, ACCENT, DIM)
    d.setBounds(0, 0, z, z)
    setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = (10 * resources.displayMetrics.density).roundToInt()
    removeOnLayoutChangeListener(IconBesideText); addOnLayoutChangeListener(IconBesideText)
    post { IconBesideText.place(this) }
}

private class Toggle(val on: Boolean, val fill: Int, val off: Int) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds; val r = b.width() / 2f; val cx = b.exactCenterX(); val cy = b.exactCenterY(); val w = r / 6f
        if (on) {
            p.style = android.graphics.Paint.Style.FILL; p.color = fill; c.drawCircle(cx, cy, r, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = w * 1.3f; p.color = Color.WHITE
            p.strokeCap = android.graphics.Paint.Cap.ROUND; p.strokeJoin = android.graphics.Paint.Join.ROUND
            c.drawPath(android.graphics.Path().apply { moveTo(cx - r * .45f, cy + r * .02f); lineTo(cx - r * .12f, cy + r * .33f); lineTo(cx + r * .45f, cy - r * .3f) }, p)
        } else {
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = w; p.color = off; c.drawCircle(cx, cy, r - w / 2, p)
        }
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** [setLabel] with a turning ring in the icon's place: a button that is busy (Scan the Wi-Fi) shows it is, not only says so. */
internal fun TextView.setBusy(s: String) {
    text = s.replace(Regex("^[^\\p{L}\\p{N}]+"), "")                     // its ⏳ would be a second icon
    val z = (textSize * 1.25f).roundToInt()
    val d = Spin(currentTextColor, z / 7f)
    d.setBounds(0, 0, z, z)
    setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = (10 * resources.displayMetrics.density).roundToInt()
    removeOnLayoutChangeListener(IconBesideText); addOnLayoutChangeListener(IconBesideText)
    post { IconBesideText.place(this) }
    d.start()
}

/** A three-quarter ring that turns once a second, until the button's next [setLabel] drops it. */
private class Spin(color: Int, width: Float) : android.graphics.drawable.Drawable(), android.graphics.drawable.Animatable {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = width; strokeCap = android.graphics.Paint.Cap.ROUND; this.color = color
    }
    private val turn = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { if (callback == null) cancel() else invalidateSelf() }   // gone from the button: stop turning
    }
    override fun draw(c: android.graphics.Canvas) {
        val r = android.graphics.RectF(bounds); val w = paint.strokeWidth / 2; r.inset(w, w)
        c.drawArc(r, turn.animatedValue as Float, 270f, false, paint)
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { paint.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    override fun start() = turn.start()
    override fun stop() = turn.cancel()
    override fun isRunning() = turn.isRunning
}

/** A compound icon is drawn at the padding's edge while centred words sit in the middle: on a wide button the two drift
 *  apart. Moved (its bounds, not its room) to just before the words, so icon and words are centred together, as on the page. */
private object IconBesideText : View.OnLayoutChangeListener {
    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) = place(v as TextView)
    fun place(tv: TextView) {
        val d = tv.compoundDrawablesRelative[0] ?: return
        val lay = tv.layout ?: return
        val z = d.bounds.height()
        var off = 0
        if (tv.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL) {
            var w = 0f
            for (i in 0 until lay.lineCount) w = maxOf(w, lay.getLineWidth(i))
            val room = tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight
            off = ((room - w) / 2).toInt().coerceAtLeast(0)
        }
        if (d.bounds.left != off) { d.setBounds(off, 0, off + z, z); tv.invalidate() }
    }
}

/** The words' colour, and the icon in front of them (if any) with it. */
internal fun TextView.setInk(c: Int) {
    setTextColor(c)
    for (d in compoundDrawablesRelative) d?.setTint(c)
}

internal fun Context.button(s: String, color: Int): Button {
    val b = Button(this)
    b.setAllCaps(false)
    b.setTextColor(if (onFill(color)) Color.WHITE else TEXT)
    b.textSize = 15f
    b.setLabel(s)
    b.background = box(color, 14)
    b.stateListAnimator = null
    b.setPadding(dp(14), 0, dp(14), 0)
    b.minHeight = 0
    b.minimumHeight = 0
    return b
}

/** A button that shows only an icon; the words go to screen readers. "@name" = res/drawable/ic_name (dev/gen/gen_icons.py), anything else is text. */
/** One line that slides to its end and back when it has no room, instead of ending in "…" (the page's runFit); with
 *  "Remove animations" on, the plain "…". A marquee runs only on a selected view. */
internal fun TextView.runs() {
    isSingleLine = true
    val still = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    if (still) { ellipsize = android.text.TextUtils.TruncateAt.END; return }
    ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
}

/** Sets the words only when they change: setText, even to the same words, starts a running line over from its start. */
internal fun TextView.say(s: CharSequence) { if (text?.toString() != s.toString()) text = s }

/** The remote's top line, "TV name · what is going on": the name above, the rest under it, smaller; one line each, so a long
 *  name never pushes the rest away. Without " · " the words take both lines. [name] is the line above, added by the screen. */
internal class StatusText(c: Context) : TextView(c) {
    val name = TextView(c).apply {
        setTextColor(TEXT); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; runs(); visibility = GONE
    }
    private var inner = false
    init { setTextColor(MUTED); textSize = 12f; ellipsize = android.text.TextUtils.TruncateAt.END; maxLines = 2 }
    override fun setText(text: CharSequence?, type: BufferType?) {
        val s = text?.toString() ?: ""
        val i = s.indexOf(" · ")
        @Suppress("SENSELESS_COMPARISON") val n = name as TextView?          // null while TextView's own constructor sets its first text
        // isSingleLine sets the words it already has again (its transformation): without the name, which hid the name
        if (n == null || inner) { super.setText(text, type); return }
        inner = true
        try { split(n, s, i, type) } finally { inner = false }
    }
    private fun split(n: TextView, s: String, i: Int, type: BufferType?) {
        n.say(if (i < 0) "" else s.substring(0, i))
        n.visibility = if (i < 0) GONE else VISIBLE
        if (i < 0) { isSingleLine = false; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END } else runs()   // two lines of words, or one that runs
        val rest = if (i < 0) s else s.substring(i + 3)
        if (rest != this.text?.toString()) super.setText(rest, type)        // the same words again would restart the marquee
    }
}

/** The app's own icon, rounded, at the head of the list of TVs and of the remote. */
internal fun Context.logo(sizeDp: Int): android.widget.ImageView = android.widget.ImageView(this).apply {
    setImageResource(R.drawable.icon)
    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
    background = GradientDrawable().apply { cornerRadius = sizeDp * resources.displayMetrics.density / 4 }
    clipToOutline = true
    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

internal fun Context.icon(symbol: String, says: String, color: Int): IconButton {
    val b = IconButton(this)
    val ink = if (onFill(color)) Color.WHITE else TEXT
    b.lit = onFill(color)
    b.setIcon(symbol, if (symbol == "@power") RED else ink)
    b.setAllCaps(false)
    b.setTextColor(ink)
    b.textSize = 20f
    b.contentDescription = says
    b.background = box(color, 16)
    b.stateListAnimator = null
    b.setPadding(0, 0, 0, 0)
    b.minHeight = 0
    b.minimumHeight = 0
    b.minWidth = 0
    b.minimumWidth = 0
    return b
}

internal fun Context.row(s: String, onClick: () -> Unit): Button {
    val b = button(s, CARD)
    b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    b.setPadding(dp(14), dp(12), dp(14), dp(12))
    b.setOnClickListener { onClick() }
    b.layoutParams = gap(6)
    return b
}

internal fun Context.input(hint: String): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setHintTextColor(MUTED)
    e.setTextColor(TEXT)
    e.textSize = 15f
    e.setSingleLine(true)
    e.background = box(CARD, 12)
    e.setPadding(dp(14), dp(12), dp(14), dp(12))
    e.minHeight = dp(48)
    return e
}

/** [v] as a dialog's body: room around it, and a field a box lighter than the dialog, which is CARD itself (a column of
 *  fields and words keeps the dialog's own colour: its fields have their boxes). */
internal fun Context.inDialog(v: View): FrameLayout {
    // every field, not only a lone one: a column's fields kept CARD on the CARD dialog, no edge, no fill (Notify the TV)
    fun lift(x: View) {
        if (x is EditText) x.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(BTN, 12).apply { setStroke(dp(1.5f), ACCENT) })
            addState(intArrayOf(), box(BTN, 12).apply { setStroke(dp(1), (MUTED and 0xFFFFFF) or 0x55000000) })
        } else if (x is android.view.ViewGroup) for (i in 0 until x.childCount) lift(x.getChildAt(i))
    }
    lift(v)
    if (v !is android.view.ViewGroup && v !is EditText) v.background = box(BTN, 12)
    val wrap = FrameLayout(this)
    wrap.setPadding(dp(24), dp(12), dp(24), dp(4))
    wrap.addView(v, FrameLayout.LayoutParams(-1, -2))
    return wrap
}

internal fun Activity.hideKeyboard() {
    val f = currentFocus
    if (f != null) (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0)
}

internal fun Context.oval(color: Int): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(color)
    return g
}

/**
 * A menu whose choices look like what they are: buttons. Each has a title, a line saying what it does, and an arrow.
 * [primary] is the one to press first. Each item: title, explanation, action. A title "@name|Title" gets the icon ic_name in
 * front, so the list can be scanned without reading every line (drawn icons, not emoji: those look different on every phone).
 */
internal fun Activity.menu(title: String, primary: Int, items: List<Triple<String, String, () -> Unit>>) {
    // opened from a choice of another menu (now, or once its list came in): Back returns there, no close and open again
    val from = menuFrom?.takeIf { it.first != title && android.os.SystemClock.uptimeMillis() - it.second < 8000 }?.third
    menuFrom = null
    val list = col()
    list.setPadding(dp(16), dp(8), dp(16), dp(4))
    var dialog: Popup? = null
    items.forEachIndexed { i, (label0, what, run) ->                  // not "title": that one is the menu's, for Back to reopen it by
        val art0 = if (label0.startsWith("@")) label0.substringBefore("|").substring(1) else null
        val art = art0?.substringBefore(':')                          // "dot:#e5484d": the icon and its own colour (remote.json "tint")
        val tint = art0?.substringAfter(':', "")?.takeIf { it.startsWith("#") }?.let { try { Color.parseColor(it) } catch (e: Exception) { null } }
        val name = if (art != null) label0.substringAfter("|") else label0
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(10), dp(12))
        val color = if (i == primary) ACCENT else BTN
        val normal = box(color, 14)
        row.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(RIPPLE), normal, null)
        val id = if (art != null) resources.getIdentifier("ic_$art", "drawable", packageName) else 0
        if (id != 0) {
            val img = android.widget.ImageView(this)
            img.setImageResource(id)
            img.setColorFilter(tint ?: if (i == primary) Color.WHITE else ACCENT_TEXT)
            val ilp = LinearLayout.LayoutParams(dp(24), dp(24))
            ilp.rightMargin = dp(14)
            row.addView(img, ilp)
        }
        val texts = col()
        val head = label(name, 16, if (i == primary) Color.WHITE else TEXT)
        head.typeface = Typeface.DEFAULT_BOLD
        texts.addView(head)
        if (what.isNotEmpty()) texts.addView(label(what, 13, if (i == primary) 0xffe3ebff.toInt() else MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("›", 24, if (i == primary) Color.WHITE else MUTED), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = name
        row.setOnClickListener { dialog?.dismiss(); menuFrom = Triple(title, android.os.SystemClock.uptimeMillis()) { menu(title, primary, items) }; run() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(8)
        list.addView(row, lp)
    }
    val sv = ScrollView(this)
    sv.addView(list)
    val b = Popup.Builder(this).setTitle(title).setView(sv).setNegativeButton(dev.tvremote.core.T("common.close"), null)
    if (from != null) b.setNeutralButton(dev.tvremote.core.T("common.back")) { _, _ -> from() }
    val shown = b.show()
    dialog = shown
    if (from != null) shown.setOnKeyListener { d, key, e ->               // the phone's own back: one step up too
        if (key != android.view.KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
        if (e.action == android.view.KeyEvent.ACTION_UP) { d.dismiss(); from() }
        true
    }
}

/** The menu a choice was just picked in (its title, when, how to open it again), for the next menu's Back. */
private var menuFrom: Triple<String, Long, () -> Unit>? = null

/** A row of choices that look like buttons, the picked one on (setOn); [pick] is told the new choice. */
internal fun MainActivity.chips(options: List<Pair<String, String>>, now: String, pick: (String) -> Unit): LinearLayout {
    val row = LinearLayout(this)
    options.forEachIndexed { i, (value, words) ->
        val b = button(words, BTN).apply { setOn(value == now) }
        b.textSize = 13f
        b.isSingleLine = true                                         // "10 giây" on one line: a wrapped chip was cut at the bottom
        b.setPadding(0, b.paddingTop, 0, b.paddingBottom)
        b.setOnClickListener { pick(value) }
        val wide = options.any { it.second.length <= 2 } && words.length > 2   // "Tự động" beside "1", "2": more room for the word
        row.addView(b, cell(40, i > 0).apply { if (wide) weight = 1.6f })
    }
    return row
}
