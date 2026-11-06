package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.media.say
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.cell
import dev.tvremote.phone.ui.col
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.gap

import dev.tvremote.core.T
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

// The Simple remote, for a parent: only the keys of remote.json "simple" (the same rows the web page draws, js/remote.js), big and
// with their words under them; the picture, the top bar's tools and every menu hidden. Turned on from Tools (phoneActions "simple"),
// left only by holding the small button on top for 2 s, so a stray tap never brings the full remote back. Kept in the app's prefs.

internal const val SIMPLE_TAG = "simple-remote"

/** The lists a "simple" row may "expand" into, and the "when" conditions it may name (RemoteSpecTest checks remote.json against them). */
internal val simpleLists = setOf("pad")
internal val simpleWhen = setOf("voice")

private const val HOLD_MS = 2000L

internal fun MainActivity.simpleOn(): Boolean = prefs.getBoolean("simple", false)

internal fun MainActivity.setSimple(on: Boolean) {
    if (on == simpleOn()) return
    prefs.edit().putBoolean("simple", on).apply()
    currentFocus?.let { (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
    if (on && video) toggleVideo()                                    // no picture behind the big keys: nothing for the TV to encode
    layoutRemote()
    if (on) say(T("simple.on"), false)
}

/** The Simple remote's view: its leave button, then one row per "simple" row of remote.json. */
@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.simpleRemote(): View {
    val box = col()
    box.setPadding(dp(16), dp(8), dp(16), dp(24))
    val exit = button(T("simple.exit"), BTN)
    exit.textSize = 13f; exit.setTextColor(MUTED); exit.isAllCaps = false
    exit.setPadding(dp(14), 0, dp(14), 0); exit.minHeight = 0; exit.minimumHeight = 0
    exit.contentDescription = T("simple.exit") + ". " + T("simple.hold")
    val leave = Runnable { exit.text = T("simple.exit"); buzz(); setSimple(false) }
    var down = 0L
    exit.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = e.eventTime; exit.text = T("simple.keep"); v.postDelayed(leave, HOLD_MS) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(leave); exit.text = T("simple.exit")
                if (e.actionMasked == MotionEvent.ACTION_UP && e.eventTime - down < HOLD_MS) say(T("simple.hold"), false)   // a tap: say how
            }
        }
        true
    }
    box.addView(exit, LinearLayout.LayoutParams(-2, dp(36)).apply { gravity = Gravity.END })
    val voice = true                                                  // the phone hears by itself (Quick talk or its recogniser): always
    for (row in remoteSpec().simple) {
        val line = LinearLayout(this)
        line.gravity = Gravity.CENTER
        var n = 0
        for (e in row) {
            if (e.whenCond == "voice" && !voice) continue
            if (e.expand == "pad") { line.addView(dpad(1.3f), LinearLayout.LayoutParams(-1, -2)); continue }
            val b = remoteKey(e, 22)
            b.background = box(BTN, 22)
            b.size = 36f; b.captionSize = 15f; b.caption = e.words()
            val first = n++ == 0
            line.addView(b, cell(92, !first).apply { if (!first) leftMargin = dp(12) })
        }
        box.addView(line, gap(14))
    }
    val sv = ScrollView(this)
    sv.tag = SIMPLE_TAG
    sv.visibility = View.GONE
    sv.addView(box)
    return sv
}
