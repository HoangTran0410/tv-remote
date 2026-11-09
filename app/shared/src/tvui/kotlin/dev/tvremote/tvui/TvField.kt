package dev.tvremote.tvui

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * A text field the D-pad can always leave. On the FPT box Gboard took the arrows before the field saw them, with its
 * keyboard down too: the person was stuck in the field, and OK did not raise the keyboard either.
 * - the keys are read before the keyboard app gets them ([onKeyPreIme]), while the keyboard is down: left / right move the
 *   caret, and past either end of the words go to the control that way (Use beside a link); up / down leave; OK raises
 *   the keyboard; Enter (a phone's remote typing into the field, no keyboard needed) is Done
 * - while the keyboard is up it has every key; Done / Search / Go closes it, runs [done] once and takes the focus to
 *   [after] or whatever is below; Back only closes it
 * The web page's fields do the same in js/ontv.js.
 */
class TvField(c: Context) : EditText(c) {
    var after: () -> View? = { null }
    var done: () -> Unit = {}
    var first: (Int, KeyEvent) -> Boolean = { _, _ -> false }       // the app's look at a key before anything (Back with the keyboard up); true = taken
    private var typing = false                                       // the keyboard is up: it has the keys

    init {
        showSoftInputOnFocus = false
        setOnEditorActionListener { _, id, e ->
            if (e != null && e.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true   // called on Enter's down and up
            if (e == null && id == EditorInfo.IME_ACTION_NONE) return@setOnEditorActionListener false
            finish(); true
        }
    }

    /** The keyboard up now, as OK does: for a field that opens in front of the person (search, a name), not one the D-pad passes. */
    fun raise() { typing = true; keys().showSoftInput(this, 0) }

    private fun keys() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun finish() {
        typing = false
        keys().hideSoftInputFromWindow(windowToken, 0)
        done()
        (after() ?: focusSearch(View.FOCUS_DOWN))?.requestFocus()
    }

    private fun leave(dir: Int) { focusSearch(dir)?.requestFocus() }

    override fun onKeyPreIme(k: Int, e: KeyEvent): Boolean {
        if (first(k, e)) return true
        if (typing) {
            if (k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) typing = false
            return super.onKeyPreIme(k, e)
        }
        // in touch mode (a tap, a phone's touchpad) only the field can hold the focus: the buttons beside it are not found. The
        // key goes on as Android's own, and its first one leaves touch mode, after the keyboard app's turn, where it is decided
        if (isInTouchMode && k in NAV) return super.onKeyPreIme(k, e)
        val down = e.action == KeyEvent.ACTION_DOWN
        when (k) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                val left = k == KeyEvent.KEYCODE_DPAD_LEFT
                val at = if (left) selectionStart else selectionEnd
                if (if (left) at > 0 else at < length()) setSelection(at + if (left) -1 else 1)
                else leave(if (left) View.FOCUS_LEFT else View.FOCUS_RIGHT)
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) leave(if (k == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
            KeyEvent.KEYCODE_DPAD_CENTER -> if (!down) { typing = true; keys().showSoftInput(this, 0) }   // 0: asked for, not implicit (refused)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!down) finish()
            else -> return super.onKeyPreIme(k, e)
        }
        return true
    }

    /**
     * An arrow the keyboard app sent (Gboard's ◀ ▶ keys) moves the caret and nothing else, never the focus: leaving the field
     * closes the keyboard while OK is still held on that key, Gboard never sees it let go and its key repeat sends the arrow
     * forever, until the TV is unplugged (a user, 2026-11-10: Customize's pages kept going back on their own).
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (soft(e) && e.keyCode in ARROWS) { super.dispatchKeyEvent(e); return true }
        return super.dispatchKeyEvent(e)
    }

    companion object {
        private val NAV = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        private val ARROWS = NAV - KeyEvent.KEYCODE_DPAD_CENTER
        /** Sent by the keyboard app on screen, not pressed on a remote: a parent's own D-pad rules must leave it to the field. */
        fun soft(e: KeyEvent) = e.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0
    }

    override fun onFocusChanged(focused: Boolean, dir: Int, prev: Rect?) {
        super.onFocusChanged(focused, dir, prev)
        if (focused) setSelection(length())                         // at the end: one press right reaches the button beside
        else if (typing) { typing = false; keys().hideSoftInputFromWindow(windowToken, 0) }   // the keyboard goes with the field: left up, it covered what the focus moved to
    }
}
