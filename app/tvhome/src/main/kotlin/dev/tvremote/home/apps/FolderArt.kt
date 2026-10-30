package dev.tvremote.home.apps

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A folder's picture (the face of its grid tile, [dev.tvremote.home.Tile] `art`): a soft panel with up to [SHOWN] of its apps' icons
 * small: two by two, or three by two once it holds more than four (the tile is 16:9, a wider panel still fits). Drawn straight from the icon drawables each frame it is asked (nothing is kept as a bitmap), at whatever size the
 * tile is; no intrinsic size, so the tile's picture view stretches it.
 */
class FolderArt(private val icons: List<Drawable>) : Drawable() {
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
    private val box = RectF()
    private val cut = Path(); private val one = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        val h = b.height().toFloat()
        val side = h * .74f                                           // the panel's height, centred under a tile's 16:9
        val cols = if (icons.size > 4) 3 else 2
        val gap = side * .08f; val cell = (side - 3 * gap) / 2
        val wide = cols * cell + (cols + 1) * gap                     // 2 columns: a square
        box.set(b.exactCenterX() - wide / 2, b.exactCenterY() - side / 2, b.exactCenterX() + wide / 2, b.exactCenterY() + side / 2)
        c.drawRoundRect(box, side * .16f, side * .16f, panel)
        icons.take(SHOWN).forEachIndexed { i, d ->
            val x = box.left + gap + (i % cols) * (cell + gap); val y = box.top + gap + (i / cols) * (cell + gap)
            val keep = d.copyBounds()
            // every icon rounded alike: some apps ship a square one (VTV Go), others already round (TV360), side by side they clashed
            one.set(x, y, x + cell, y + cell); cut.reset(); cut.addRoundRect(one, cell * .22f, cell * .22f, Path.Direction.CW)
            val n = c.save(); c.clipPath(cut)
            d.setBounds(x.toInt(), y.toInt(), (x + cell).toInt(), (y + cell).toInt()); d.draw(c); d.bounds = keep
            c.restoreToCount(n)
        }
    }

    override fun setAlpha(a: Int) { panel.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { panel.colorFilter = f }
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val SHOWN = 6
        private const val PANEL = 0x33FFFFFF                            // the soft panel behind the icons

        /** The art of a folder holding [members] (only the first [SHOWN] icons are fetched; an app that is gone is skipped). */
        fun of(c: Context, members: List<String>): FolderArt = FolderArt(members.asSequence().mapNotNull { p ->
            try { c.packageManager.getApplicationIcon(p) } catch (e: Exception) { null }
        }.take(SHOWN).toList())
    }
}
