package dev.tvremote.tvui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The focus of a 16:9 tile (an app, a game, a poster), TV Home's look moved here (app/DESIGN-TV.md §2.5) so every app has the one
 * copy: the card rises to [GROW] on a spring, a soft shadow, the picture a touch larger inside it, a shine settling on it, the name
 * fading in under it, and either the ring (this class) or the pane of glass ([Glass], moved by the app with [pane] / [corner]).
 * The shell has no content: the app puts its picture in with [fill] and may add its own views to [face] after (badges).
 * [dp] is the app's own conversion; the ring lies OUTSIDE the face, so the tile keeps its place and the card is that much larger.
 */
open class TvTile(c: Context, w: Int, dp: (Number) -> Int, hPct: Int = 56) : LinearLayout(c) {   // hPct: the face's height in % of its width (56 = 16:9)
    protected val card = FrameLayout(c)                             // what grows: the face and, round it, the ring
    protected val face = FrameLayout(c)                             // the picture, clipped to its rounded corners
    private val ring = View(c)
    /** The name's alpha while not focused: 0, or 1 for a tile whose name always shows (a renamed app on TV Home). */
    var nameRest = 0f
        set(v) { field = v; if (!hasFocus()) name.alpha = v }
    val name = TextView(c)                                          // under the card, shown while focused; the app sets its font
    private val shine = View(c)
    private var content: View = face                                // the picture, scaled inside the face
    private val lift = dp(14).toFloat()
    /** False: the tile's own ring, never the gliding glass, whatever the setting (a picker over Home: the glass's layer cost a frame there). */
    open val glassy get() = Motion.glass
    /** False: focus is the ring and the name only, the card stays its size (a full-screen page of tiles, where the growth cost most). */
    open val grows get() = true
    private val across = w.toFloat()
    private val dpOf = dp
    private var plain = false
    val pane: View get() = face                                     // where the glass goes
    val corner = dp(TvUi.R_TILE).toFloat()
    var nudged: ((Float, Float) -> Unit)? = null                   // the glass leans with the card

    init {
        orientation = VERTICAL; isFocusable = true; isClickable = true; isLongClickable = true
        clipChildren = false; clipToPadding = false
        val h = w * hPct / 100
        face.clipToOutline = true
        shine.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(SHINE_TOP, SHINE_MID, SHINE_END))
        shine.alpha = 0f; shine.translationY = -lift
        face.addView(shine, FrameLayout.LayoutParams(-1, h * 2 / 3))
        // the ring lies OUTSIDE the face, its solid blue over the face's edge: drawn on the edge, a white banner's soft-cornered
        // pixels showed past it at the corners. The card is that much larger (and the tile that much smaller round it) so the
        // ring is inside the card's own layer while it grows; the tiles stay where they were.
        val o = dp(TvUi.RING_DP)
        card.addView(face, FrameLayout.LayoutParams(w, h).apply { setMargins(o, o, o, o) })
        ring.background = ringDrawable(corner, dp)
        ring.alpha = 0f
        card.addView(ring, FrameLayout.LayoutParams(-1, -1))
        card.clipChildren = false
        card.outlineProvider = object : android.view.ViewOutlineProvider() {   // the shadow under the face only
            override fun getOutline(v: View, out: android.graphics.Outline) { if (plain) out.setEmpty() else out.setRoundRect(o, o, o + w, o + h, corner) }
        }
        addView(card, LayoutParams(w + 2 * o, h + 2 * o).apply { setMargins(-o, -o, -o, -o) })
        // a card at rest is a texture: a page scroll then pastes it, not the picture through its rounded clip again. On the FPT
        // box (Mali-G31, a 25% tint over the screen) TV Home's scroll took 17 ms of GPU a frame (p90 38) and held a frame
        // 5 presses in 8; with this 12 ms (p90 16). The focused card has none: a layer grown by GROW would be soft ([focus])
        card.setLayerType(LAYER_TYPE_HARDWARE, null)
        name.apply {
            textSize = 13f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            alpha = 0f; setPadding(0, 0, 0, 0); setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), NAME_SHADOW)
        }
        // under the scaled card, inside the gap between rows (the gap is sized for it): it adds no height to the row, so rows sit
        // as far apart as tiles are side by side, and it never reaches the picture or the next row
        val below = (h * (GROW - 1f) / 2f).toInt() + o + dp(3)
        addView(name, LayoutParams(w, dp(18)).apply { topMargin = below; bottomMargin = -(below + dp(18)) })   // fixed height: the row stays the picture's
        background = ColorDrawable(Color.TRANSPARENT)
    }

    /** The ring in another colour (TV Home's accent); [RING_ACCENT] when never called. */
    fun accent(color: Int) { ring.background = ringDrawable(corner, dpOf, color) }

    /** A tile with no card of its own (TV Home's "icon" style): no shadow under it and no shine on it, only the ring / glass and the name. */
    fun flat() { plain = true; shine.visibility = GONE; card.invalidateOutline() }

    /** The picture of the tile, under the shine; it is what grows a touch more than the card on focus. */
    fun fill(v: View) { content = v; face.addView(v, 0, FrameLayout.LayoutParams(-1, -1)) }

    /** The colour behind a picture that does not fill the face. */
    fun faceColor(color: Int) { face.background = GradientDrawable().apply { cornerRadius = corner; setColor(color) } }

    /**
     * Being carried (move mode): the card a step larger than a focused one, on top of the others; [focus] puts it back.
     */
    fun carry(on: Boolean) {
        val s = if (on) GROW + 0.10f else GROW
        card.animate().cancel()
        ring.animate().cancel(); ring.alpha = if (on || !glassy) 1f else 0f   // the accent ring even where the focus is the glass; back to the glass after
        if (!Motion.on) { card.scaleX = s; card.scaleY = s; card.translationZ = if (on) lift * 1.5f else 0f; return }
        card.animate().scaleX(s).scaleY(s).translationZ(if (on) lift * 1.5f else 0f).setDuration(150).setInterpolator(Motion.glide).start()
    }

    /**
     * Focus lifts the card (a springy rise, a soft shadow, the picture a touch larger inside it, a shine passing over) and rings it;
     * with motion off only the ring and the name change, at once. A card at rest keeps its layer; the focused one draws itself.
     */
    fun focus(on: Boolean) {
        card.animate().cancel(); ring.animate().cancel(); name.animate().cancel(); content.animate().cancel(); shine.animate().cancel()
        if (grows) card.setLayerType(if (on) LAYER_TYPE_NONE else LAYER_TYPE_HARDWARE, null)   // before withLayer(), which puts this back after
        if (!grows) { ring.alpha = if (on) 1f else 0f; name.alpha = if (on) 1f else nameRest; name.translationY = 0f; return }
        if (!Motion.on) {                                             // the end state at once, grown too: at 1x the glass (sized for GROW) stood round a small tile and nothing told which was chosen
            val s = if (on) GROW else 1f
            card.translationX = 0f; card.translationY = 0f
            card.scaleX = s; card.scaleY = s; card.translationZ = if (on && !plain) lift else 0f; content.scaleX = if (on) 1.05f else 1f; content.scaleY = content.scaleX
            ring.alpha = if (on && !glassy) 1f else 0f; name.alpha = if (on) 1f else nameRest; name.translationY = 0f; shine.alpha = 0f
            return
        }
        // in on a spring, out on a plain glide a little slower: the next tile's rise leads, the last one sinks behind it
        val s = if (on) GROW else 1f
        val t = Motion.ms(if (on) 520L else 300L)
        val ip = if (on) Motion.spring else Motion.glide
        card.translationX = 0f; card.translationY = 0f
        if (Motion.calm) {                                            // two animators, not five: the rest is set at once
            card.translationZ = 0f; content.scaleX = 1f; content.scaleY = 1f; shine.alpha = 0f
            ring.alpha = if (on && !glassy) 1f else 0f
            card.animate().scaleX(s).scaleY(s).setDuration(t).setInterpolator(ip).withLayer().start()
            // the name still fades and slides in (one small text, no layer): popping in read as rough next to CleanTV's Home (the user, 2026-11-01)
            if (on && nameRest == 0f) { name.alpha = 0f; name.translationY = -lift / 3 }
            name.animate().alpha(if (on) 1f else nameRest).translationY(0f).setDuration(if (on) 220L else 120L).setInterpolator(Motion.glide).start()
            return
        }
        // the shadow at once, only the size glides: animating translationZ re-made the shadow every frame, on a BRAVIA (MT5891,
        // Android 9) 41% of the frames over 16.7 ms while focus moved sideways, 12% this way (no shadow at all: the same 12%)
        card.translationZ = if (on && !plain) lift else 0f
        card.animate().scaleX(s).scaleY(s).setDuration(t).setInterpolator(ip).withLayer().start()
        content.animate().scaleX(if (on) 1.05f else 1f).scaleY(if (on) 1.05f else 1f).setDuration(t).setInterpolator(ip).start()
        ring.animate().alpha(if (on && !glassy) 1f else 0f).setDuration(if (on) 200L else 160L).setInterpolator(Motion.glide).start()
        name.animate().alpha(if (on) 1f else nameRest).translationY(if (on || nameRest > 0f) 0f else -lift / 3).setDuration(if (on) 360L else 200L).setInterpolator(Motion.glide).start()
        // the gloss: light from above that settles on the card as it rises, rather than a sweep on every move (busy when stepping fast)
        shine.animate().alpha(if (on) 1f else 0f).translationY(if (on) 0f else -lift).setDuration(if (on) 420L else 200L).setInterpolator(Motion.glide).start()
    }

    /** Nothing that way: the card leans toward it and wobbles back, so the press was felt (Apple TV does the same). */
    fun nudge(dx: Int, dy: Int) {
        if (!Motion.on || Motion.calm) return
        val px = across / 40f
        nudged?.invoke(dx * px * GROW, dy * px * GROW)
        card.animate().cancel()
        card.animate().translationX(dx * px).translationY(dy * px).setDuration(80).setInterpolator(Motion.glide).withEndAction {
            card.animate().translationX(0f).translationY(0f).setDuration(460).setInterpolator(Motion.bounce).start()
        }.start()
    }

    companion object {
        const val GROW = 1.12f                                        // a focused card's size
        private const val SHINE_TOP = 0x30FFFFFF
        private const val SHINE_MID = 0x0AFFFFFF
        private const val SHINE_END = 0x00FFFFFF
        private const val NAME_SHADOW = 0xCC000000.toInt()

        /**
         * The ring round the focused tile: the accent (almost no banner is that blue) from [TvUi.RING_DP] dp outside the face to 1 dp
         * over it, covering the face's soft edge, then a thin white line for a banner that is blue itself. No dark edge: on a TV it
         * read as a black border round the blue.
         */
        fun ringDrawable(r: Float, dp: (Number) -> Int, accent: Int = TvUi.RING_ACCENT): Drawable {
            val o = dp(TvUi.RING_DP)
            fun band(w: Int, color: Int, radius: Float) = GradientDrawable().apply { cornerRadius = maxOf(0f, radius); setStroke(w, color); setColor(Color.TRANSPARENT) }
            return LayerDrawable(arrayOf(band(o + dp(1), accent, r + o), band(dp(1), Color.WHITE, r - dp(1)))).apply {
                setLayerInset(1, o + dp(1), o + dp(1), o + dp(1), o + dp(1))
            }
        }
    }
}
