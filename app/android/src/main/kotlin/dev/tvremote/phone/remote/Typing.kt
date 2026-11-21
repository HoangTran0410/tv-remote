package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.ui.setOn

import dev.tvremote.core.T
import dev.tvremote.core.remote.Atv
import dev.tvremote.core.remote.Remote

// The text box: type then send, or live (the TV follows every letter); with or without ADB.

internal fun MainActivity.sendText() {
    val s = textBox.text.toString()
    basic?.let {                                                  // keys only: the text goes into the field that has the focus on the TV
        val f = (it as? Atv)?.takeIf { a -> a.hasField }          // the TV's own text field: the box holds all its text
        if (f != null) {
            if (!live && s != f.currentField?.text) f.replace(s)  // edited here: the field gets it all; unchanged (or live): Enter, which submits
            else { f.type(0, "", true); clearBox() }                  // type: right after live letters, Enter waits for the TV to have them
            return
        }
        if (live) { it.key(66, Atv.SHORT); clearBox() }           // everything is on the TV already: this is Enter
        else if (s.isEmpty()) it.key(66, Atv.SHORT)
        else { val a = it as? Atv; if (a == null) it.text(s) else a.type(0, s, false); textBox.setText("") }   // keys, or the keyboard service for accents and capitals
        return
    }
    fieldLink?.takeIf { it.hasField }?.let { f ->                 // ADB on, and the TV reports its field: typed through it (#31)
        if (!live && s != f.currentField?.text) f.replace(s) else { f.type(0, "", true); clearBox() }
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
            if (keep > 0 && Character.isHighSurrogate(onTv[keep - 1])) keep--
            r.type(onTv.length - keep, now.substring(keep), false)
            onTv = now
            return
        }
        if (it.hasField) { fieldEdit(it, now); return }           // with a field: its text, edited
        var keep = 0                                              // without: Backspace keys, then keys (or the keyboard service)
        while (keep < onTv.length && keep < now.length && onTv[keep] == now[keep]) keep++
        if (keep > 0 && Character.isHighSurrogate(onTv[keep - 1])) keep--   // never half an emoji
        it.type(onTv.codePointCount(keep, onTv.length), now.substring(keep), false)
        onTv = now
        return
    }
    fieldLink?.takeIf { it.hasField }?.let { f -> fieldEdit(f, now); return }   // ADB on, and the TV reports its field (#31)
    val tv = tv
    if (tv == null || !tv.ready()) return
    var same = 0
    while (same < onTv.length && same < now.length && onTv[same] == now[same]) same++
    if (same > 0 && Character.isHighSurrogate(onTv[same - 1])) same--
    repeat(onTv.codePointCount(same, onTv.length)) { tv.press(67) }
    if (now.length > same) tv.text(now.substring(same))
    onTv = now
}

/** The TV's field gets the box's change: Backspaces back to where it differs, then the rest typed (#31). */
private fun MainActivity.fieldEdit(f: Atv, now: String) {
    var keep = 0                                                  // the TV's caret stays at the end (its keyboard takes the arrows): from the
    while (keep < onTv.length && keep < now.length && onTv[keep] == now[keep]) keep++   // first change on, deleted and typed again
    if (keep > 0 && Character.isHighSurrogate(onTv[keep - 1])) keep--   // never half an emoji
    f.type(onTv.codePointCount(keep, onTv.length), now.substring(keep), false)
    onTv = now
}

/** The TV's text field appeared, changed or went (Atv.onField): the box starts with what is in it, with its label as the hint. */
internal fun MainActivity.fieldShown(f: Atv.Field?) {
    quiet = true
    if (f == null) { if (live) textBox.setText("") }              // nothing to mirror any more; with Send, the words wait
    else {
        if (f.text != textBox.text.toString()) {                  // the same text: the caret stays where the person put it
            val fromEnd = textBox.text.length - textBox.selectionEnd  // changed on the TV: the caret keeps its place from the end
            textBox.setText(f.text)
            textBox.setSelection((f.text.length - fromEnd).coerceIn(0, f.text.length))
        }
        textBox.hint = f.label.ifEmpty { if (live) T("typing.live_the_tv_gets") else T("typing.type_then_send") }
    }
    quiet = false
    onTv = f?.text ?: ""
}

/** Follows the TV's text field for as long as [a] is the remote in use. */
internal fun MainActivity.watchField(a: Atv) {
    a.onField = { ui.post { if (basic === a || fieldLink === a) fieldShown(a.currentField) } }
}

/**
 * With ADB: the remote link opened beside it, only so typing goes into the TV's own text field (#31): Enter submits the field
 * instead of pressing the on-screen keyboard's key, and the text already in it can be edited. An extra the person turns on
 * (Settings → The TV's keyboard, pref "fieldType"; off: nothing opens); [ask0] / "fieldAsk" is that tap: an unpaired TV then shows its code
 * to type once, otherwise an unpaired link is only refused and the TV shows nothing. ADB keeps everything else.
 */
internal fun MainActivity.openFieldLink(ip: String, ask0: Boolean = false) {
    if (!prefs.getBoolean("fieldType", false)) return
    val ask = ask0 || prefs.getBoolean("fieldAsk", false)          // just turned on (in Settings, from the TV list): the next TV asks once
    if (ask) fieldUnpaired.remove(ip)
    if (fieldLink != null || ip in fieldUnpaired || basic != null || kindOf(entry ?: ip) != Remote.Kind.ANDROID) return
    val a = Atv(filesDir, ip)
    fieldLink = a
    Thread {
        try {
            a.connect { ui.post { if (fieldLink === a) { fieldLink = null; a.close() } } }   // dropped: opened again with the next session
            ui.post { prefs.edit().remove("fieldAsk").apply(); if (fieldLink === a && host == ip) watchField(a) else a.close() }
        } catch (e: Exception) {
            a.close()
            ui.post {
                if (fieldLink === a) fieldLink = null
                if (e is Atv.NotPaired) { fieldUnpaired.add(ip); if (ask && host == ip) { prefs.edit().remove("fieldAsk").apply(); Thread { pair(ip) { prefs.edit().putBoolean("atv:$ip", true).apply(); fieldUnpaired.remove(ip); openFieldLink(ip) } }.start() } }
            }
        }
    }.apply { isDaemon = true }.start()
}

internal fun MainActivity.closeFieldLink() {
    val a = fieldLink ?: return
    fieldLink = null
    a.onField = null
    Thread { a.close() }.start()
}
