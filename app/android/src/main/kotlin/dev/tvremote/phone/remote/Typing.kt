package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.ui.setOn

import dev.tvremote.core.T
import dev.tvremote.core.remote.Atv

// The text box: type then send, or live (the TV follows every letter); with or without ADB.

internal fun MainActivity.sendText() {
    val s = textBox.text.toString()
    basic?.let {                                                  // keys only: the text goes into the field that has the focus on the TV
        if (live) { it.key(66, Atv.SHORT); clearBox() }           // everything is on the TV already: this is Enter
        else if (s.isEmpty()) it.key(66, Atv.SHORT)
        else { val a = it as? Atv; if (a == null) it.text(s) else if (a.hasField && !Atv.typable(s)) a.text(s) else a.keys(s); textBox.setText("") }
        return
    }
    val tv = tv
    if (tv == null || !tv.ready()) return
    if (live) {                                                   // everything is on the TV already: this is Enter
        tv.press(66)
        clearBox()
    } else if (s.isNotEmpty()) {
        tv.text(s)
        textBox.setText("")
    }
}

internal fun MainActivity.setLive(on: Boolean) {
    live = on
    prefs.edit().putBoolean("live", on).apply()
    liveBtn.setOn(on)
    liveBtn.contentDescription = if (on) T("typing.live_typing_is_on")
        else T("typing.type_then_send_is")
    textBox.hint = if (on) T("typing.live_the_tv_gets") else T("typing.type_then_send")
    clearBox()
}

internal fun MainActivity.clearBox() {
    quiet = true
    textBox.setText("")
    quiet = false
    onTv = ""
}

/** Live typing: make the TV's text follow the box by deleting what changed at the end and typing the new end. */
internal fun MainActivity.typedLive(now: String) {
    if (!live || quiet) return
    basic?.let { r ->                                             // keys only: through the TV's keyboard service when the app uses it, as key presses otherwise
        val it = r as? Atv
        if (it == null) {                                             // LG / Samsung: their keyboard link takes the change
            var keep = 0
            while (keep < onTv.length && keep < now.length && onTv[keep] == now[keep]) keep++
            r.type(onTv.length - keep, now.substring(keep), false)
            onTv = now
            return
        }
        if (it.hasField && !Atv.typable(now)) { if (now.isNotEmpty()) it.text(now) }
        else {
            var keep = 0
            while (keep < onTv.length && keep < now.length && onTv[keep] == now[keep]) keep++
            repeat(onTv.length - keep) { _ -> it.key(67, Atv.SHORT) }
            it.keys(now.substring(keep))
        }
        onTv = now
        return
    }
    val tv = tv
    if (tv == null || !tv.ready()) return
    var same = 0
    while (same < onTv.length && same < now.length && onTv[same] == now[same]) same++
    repeat(onTv.length - same) { tv.press(67) }
    if (now.length > same) tv.text(now.substring(same))
    onTv = now
}
