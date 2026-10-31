package dev.tvremote.phone.picture

import android.content.Context
import android.widget.FrameLayout

/** Keeps the picture at the TV's aspect ratio: full width in portrait, as large as fits in landscape. Beside the remote (not full
 *  screen) it takes at most half the screen's height, so the keys stay in reach (landscape, a tablet, a tall picture). */
internal class VideoFrame(c: Context) : FrameLayout(c) {
    private var vw = 16
    private var vh = 9

    fun setAspect(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            vw = w
            vh = h
            requestLayout()
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val exact = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY       // full screen: all of it
        val maxH = if (exact) MeasureSpec.getSize(hSpec)
            else minOf(if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(hSpec), resources.displayMetrics.heightPixels / 2)
        val h = minOf((w.toLong() * vh / vw).toInt(), maxH)
        val cw = (h.toLong() * vw / vh).toInt()
        for (i in 0 until childCount)
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, if (exact) maxH else h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = (r - l - c.measuredWidth) / 2
            val y = (b - t - c.measuredHeight) / 2
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }
}
