package dev.tvremote.tvui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The focus as one pane of glass that glides from tile to tile (the idea of cleanyourtv's launcher, drawn our own way): a bright
 * rim, a faint glow round it and a sheen on the top part only, no fill, so a banner keeps its colours under it. It is drawn ONCE
 * into a texture the size of the largest tile, and a move only changes its translation and scale: no redraw while it glides (a
 * pane redrawn every frame crawled on a MediaTek MT5895). All tiles are 16:9, so a uniform scale fits it to any of them; another
 * shape (a widget card, the header, a round button) is drawn once for that shape (its glow kept, [HALOS]) and reached by two
 * panes crossing ([to]). In a FrameLayout over the tiles inside the scrolled content, so a scroll carries
 * it along with its tile.
 */
class Glass(c: Context, private val dp: (Number) -> Int) : View(c) {
    // the pane is white light only; what changes is how much of it shows
    private var tint = 0xFFFFFF                                      // white, or the accent ([accent]): the pane is one colour of light
    private fun white(a: Int) = (a shl 24) or tint
    private fun whites(vararg a: Int) = IntArray(a.size) { white(a[it]) }
    private val pad = dp(14)                                         // room for the glow round the pane
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = white(0x38)
        maskFilter = BlurMaskFilter(dp(6).toFloat(), BlurMaskFilter.Blur.NORMAL)
    }
    private val pic = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var halo: Bitmap? = null                                 // the glow, blurred once in software for this size
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val at = Rect()
    private var baseW = 0                                            // the pane's size at scale 1 (the largest of this shape seen)
    private var baseH = 0
    private var radius = 0f
    private var shown = false
    private var atX = 0f; private var atY = 0f                      // where it rests, for [nudge]

    init {
        pivotX = 0f; pivotY = 0f; alpha = 0f
        isFocusable = false; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** The pane's light in the accent [rgb] (0: white again). The size's cached glow is made again in the new colour. */
    fun accent(rgb: Int) {
        val t = if (rgb == 0) 0xFFFFFF else rgb and 0xFFFFFF
        if (t == tint) return
        twin?.accent(rgb); tint = t; glow.color = white(0x38); halo = null; halos.clear()
        if (width > 0) { shade(width, height); invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shade(w, h)
    }

    private fun shade(w: Int, h: Int) {
        // the rim lit from the top left as iOS's glass is: bright where the light meets the corners (top left, bottom right),
        // dim along the sides; the hairline inside it, the other way round, is the far edge of the glass seen through it
        val cx = w / 2f; val cy = h / 2f
        val a = Math.toDegrees(Math.atan2((h - 2.0 * pad), (w - 2.0 * pad))).toFloat() / 360f   // a corner's angle on the sweep
        rim.shader = SweepGradient(cx, cy, whites(0x8C, 0xFF, 0x59, 0x59, 0xFF, 0xA6, 0x8C),
            floatArrayOf(0f, a, 0.25f + a / 2, 0.5f, 0.5f + a, 0.75f, 1f))
        inner.shader = SweepGradient(cx, cy, whites(0x14, 0x0A, 0x40, 0x14, 0x0A, 0x40, 0x14),
            floatArrayOf(0f, a, 0.25f, 0.5f, 0.5f + a, 0.75f, 1f))
        sheen.shader = LinearGradient(0f, pad.toFloat(), 0f, pad + (h - 2 * pad) * 0.5f, white(0x47), white(0), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return                         // drawn once before its first layout: no bitmap of 0 px
        box.set(pad.toFloat(), pad.toFloat(), (width - pad).toFloat(), (height - pad).toFloat())
        // the glow: one ring blurred (a real Gaussian), not three fading rings, whose steps showed as separate bands. Blurred
        // on a software canvas once per size (BlurMaskFilter is not drawn by the GPU before Android 9), then only pasted
        val key = (width.toLong() shl 32) or (height.toLong() shl 12) or radius.toLong().coerceIn(0, 4095)
        val h = halo?.takeIf { it.width == width && it.height == height } ?: (halos[key] ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawRoundRect(box, radius, radius, glow); drawRoundRect(box, radius, radius, CUT) }   // outward only: inside, it hazed the banner's edge
            halos[key] = it
            if (halos.size > HALOS) halos.remove(halos.keys.first())  // not recycled: the twin may still be drawing it
        }).also { halo = it }
        canvas.drawBitmap(h, 0f, 0f, pic)
        canvas.save()
        canvas.clipRect(box.left, box.top, box.right, box.top + box.height() * 0.5f)   // clipRect stays on the GPU; clipPath did not
        canvas.drawRoundRect(box, radius, radius, sheen)
        canvas.restore()
        canvas.drawRoundRect(box, radius, radius, rim)
        val i = dp(2.5f).toFloat()
        canvas.drawRoundRect(box.left + i, box.top + i, box.right - i, box.bottom - i, radius - i, radius - i, inner)
    }

    /**
     * Onto [face] (a tile's picture) grown by [grow] round its centre, as the tile is when focused; [r] its corner radius.
     * [glide] false: there at once (the first showing, motion off, a layout that moved the tiles). To another shape it glides
     * too: the pane it was flies there and fades, a second pane drawn in the new shape flies from here and comes in ([twin]);
     * either one stretched on the way is half seen, so neither rim reads as bent. Still no redraw while they move.
     */
    fun to(content: ViewGroup, face: View, grow: Float, r: Float, glide: Boolean) {
        waiting?.let { (v, l) -> v.removeOnLayoutChangeListener(l) }; waiting = null
        // not laid out yet: once it has a size. A post here spun the main thread for hours on a face that stayed 0 wide (FPT, 2026-11-01)
        if (face.width == 0) {
            val l = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r2: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    if (v.width == 0) return
                    v.removeOnLayoutChangeListener(this); waiting = null; to(content, face, grow, r, false)
                }
            }
            face.addOnLayoutChangeListener(l); waiting = face to l; return
        }
        val a = cur
        if (!glide || !a.shown || a.fits(face, r)) { (if (a === this) twin else this)?.drop(); a.go(content, face, grow, r, glide); return }
        val b = (if (a === this) twin ?: Glass(context, dp).also { twin = it; it.tint = tint; it.glow.color = white(0x38); content.addView(it, content.indexOfChild(this) + 1, FrameLayout.LayoutParams(0, 0)) } else this)
        val fx = a.translationX + a.pad * a.scaleX; val fy = a.translationY + a.pad * a.scaleY   // where a's face is now, mid-glide or not
        val fw = a.baseW * a.scaleX; val fh = a.baseH * a.scaleY
        b.shape(face, r)
        a.target(content, face, grow)
        val tx = a.at.exactCenterX() - face.width * grow / 2f; val ty = a.at.exactCenterY() - face.height * grow / 2f
        b.animate().cancel(); b.cover(fx, fy, fw, fh); b.alpha = 0f; b.shown = true
        b.slide(tx, ty, face.width * grow, face.height * grow, 1f)
        a.shown = false; a.slide(tx, ty, face.width * grow, face.height * grow, 0f)
        cur = b
    }

    private var cur: Glass = this                                    // the pane in front: this, or the twin after a change of shape
    private var twin: Glass? = null                                  // the second pane, made at the first change of shape, next to this one
    private var waiting: Pair<View, View.OnLayoutChangeListener>? = null   // a face not laid out yet: one at a time, the latest focus wins

    private fun fits(face: View, r: Float) = baseH > 0 && r == radius && Math.abs(face.width.toFloat() / face.height - baseW.toFloat() / baseH) < 0.02f

    /** [face]'s place in content, in [at]. */
    private fun target(content: ViewGroup, face: View, grow: Float) { at.set(0, 0, face.width, face.height); content.offsetDescendantRectToMyCoords(face, at) }

    /** A texture for [face]'s shape, drawn once: sized on its own, as a requestLayout laid out the whole page again on every new shape. */
    private fun shape(face: View, r: Float) {
        if (fits(face, r) && face.width <= baseW) return
        baseW = face.width; baseH = face.height; radius = r; halo = null
        val w = baseW + 2 * pad; val h = baseH + 2 * pad
        val lp = layoutParams as? FrameLayout.LayoutParams
        if (lp == null) layoutParams = FrameLayout.LayoutParams(w, h)
        else {
            lp.width = w; lp.height = h
            measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            layout(left, top, left + w, top + h)
        }
        invalidate()
    }

    /** Scaled (unevenly, for a moment) so the face part covers [w] x [h] at [x], [y]. */
    private fun cover(x: Float, y: Float, w: Float, h: Float) {
        scaleX = w / baseW; scaleY = h / baseH
        translationX = x - pad * scaleX; translationY = y - pad * scaleY
        atX = translationX; atY = translationY
    }

    private fun slide(x: Float, y: Float, w: Float, h: Float, to: Float) {
        val sx = w / baseW; val sy = h / baseH
        atX = x - pad * sx; atY = y - pad * sy
        animate().cancel()
        animate().translationX(atX).translationY(atY).scaleX(sx).scaleY(sy).alpha(to).setDuration(Motion.ms(460)).setInterpolator(Motion.spring).start()
    }

    /** The other pane, gone at once: the focus left the change of shape mid-way, or a re-place. */
    private fun drop() { if (shown || alpha > 0f) { shown = false; animate().cancel(); alpha = 0f } }

    private fun go(content: ViewGroup, face: View, grow: Float, r: Float, glide: Boolean) {
        target(content, face, grow)
        shape(face, r)
        val k = face.width * grow / baseW
        val x = at.exactCenterX() - (baseW + 2 * pad) * k / 2f
        val y = at.exactCenterY() - (baseH + 2 * pad) * k / 2f
        atX = x; atY = y
        animate().cancel()
        if (!glide || !shown) {
            translationX = x; translationY = y; scaleX = k; scaleY = k
            if (!shown) { shown = true; if (glide) animate().alpha(1f).setDuration(180).start() else alpha = 1f }
            else alpha = 1f                                           // a re-place (a layout, a scroll) cancelled the fade in: it stayed see-through
            return
        }
        alpha = 1f
        animate().translationX(x).translationY(y).scaleX(k).scaleY(k).setDuration(Motion.ms(460)).setInterpolator(Motion.spring).start()
    }

    /** With its tile, leaning [px], [py] at an edge and wobbling back ([Tile.nudge]). */
    fun nudge(px: Float, py: Float) {
        val g = cur
        if (!g.shown) return
        g.animate().cancel()
        g.animate().translationX(g.atX + px).translationY(g.atY + py).setDuration(80).setInterpolator(Motion.glide).withEndAction {
            g.animate().translationX(g.atX).translationY(g.atY).setDuration(460).setInterpolator(Motion.bounce).start()
        }.start()
    }

    /** Focus left the tiles (the buttons, a panel): the pane fades and the next tile it lands on gets it at once. */
    fun hide() {
        for (g in listOfNotNull(this, twin)) {
            if (!g.shown) continue
            g.shown = false
            g.animate().cancel()
            g.animate().alpha(0f).setDuration(140).start()
        }
    }

    val placed get() = cur.shown

    private companion object {
        const val HALOS = 6
        val halos = LinkedHashMap<Long, Bitmap>()                    // the last few shapes' glows, both panes': tiles, two card widths, the header, a button and back cost no blur
        val CUT = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) } }
}
