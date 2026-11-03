package dev.tvremote.tvui

import android.content.Context
import android.graphics.Rect
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * The scroll views of the TV apps: the D-pad's focus is brought to the middle, not only just inside the edge. Android's own stops
 * the focused row at the edge, so nothing tells a person more rows wait beyond it (the user, 2026-11-03). Every move goes
 * through [computeScrollDeltaToGetChildRectOnScreen]: a focus change (scrollToChild), requestRectangleOnScreen, an arrow key with
 * the next row off screen, a resize. Near either end it stops at the end, so the first and last rows sit where they always did.
 * A row taller than the view keeps Android's way (its top or bottom edge), or centering would hide both.
 * `tests/tv_scroll.test.js` fails on a bare ScrollView / HorizontalScrollView in the TV apps.
 */
open class TvScroll(c: Context) : ScrollView(c) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        if (childCount == 0) 0 else centered(rect.top, rect.bottom, scrollY, height - paddingTop - paddingBottom, paddingTop,
            getChildAt(0).height + paddingTop + paddingBottom - height) ?: super.computeScrollDeltaToGetChildRectOnScreen(rect)
}

open class TvHScroll(c: Context) : HorizontalScrollView(c) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        if (childCount == 0) 0 else centered(rect.left, rect.right, scrollX, width - paddingLeft - paddingRight, paddingLeft,
            getChildAt(0).width + paddingLeft + paddingRight - width) ?: super.computeScrollDeltaToGetChildRectOnScreen(rect)
}

/**
 * The scroll change that puts [a]..[b] (content coordinates) in the middle of a [view]-long window that starts [pad] in, clamped
 * to 0..[max]; null when it does not fit (the caller's own rule) or the view is not laid out yet.
 */
internal fun centered(a: Int, b: Int, now: Int, view: Int, pad: Int, max: Int): Int? {
    if (view <= 0 || b - a > view) return null
    val to = ((a + b) / 2 - pad - view / 2).coerceIn(0, maxOf(0, max))
    return to - now
}
