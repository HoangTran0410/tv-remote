package dev.tvremote.setup.keys

import android.content.Context
import dev.tvremote.core.T
import dev.tvremote.setup.rules.Rules

/**
 * "Hold to use Input / Settings" (opt-in, the rule "holdkeys" in [Rules]): an elderly person who presses Source or Settings by mistake
 * lands on an empty HDMI and thinks the TV broke. With it on, a short press of those physical keys does nothing but say how to do
 * it; holding [HOLD_MS] does the normal thing. [KeysBridge] asks [Gate] about every key the add-on sends (the filter sees only physical keys: what
 * a phone or computer injects with `input keyevent` skips it, so the remote stays the way round this).
 *
 * No Android type in [Gate] so a test can run it.
 */
object HoldKeys {
    const val HOLD_MS = 2000L

    /** TV, TV_INPUT, SETTINGS, and the direct input keys a remote may carry (TV_INPUT_HDMI_1..4, COMPOSITE_1..2, COMPONENT_1..2, VGA_1). */
    val KEYS: Set<Int> = setOf(170, 178, 176) + (243..251)

    /** What the short press says: Settings has its own words, every other key is about the input. */
    fun hint(c: Int): String = if (c == 176) T("hold.settings") else T("hold.input")

    fun on(c: Context): Boolean = try { Rules.load(c).optBoolean("holdkeys") } catch (e: Exception) { false }

    /** What the filter does with one key event. */
    enum class Step {
        PASS,     // not ours (switch off, other key): the system gets it as always
        EAT,      // swallowed, nothing else to do (its up, its repeats, a hold already done)
        ARM,      // a new press: swallow, say "hold", and call [Gate.timer] after [HOLD_MS] if it is still down
        FIRE      // a repeat that shows the hold is long enough: swallow and do the action
    }

    private const val DOWN = 0
    private const val UP = 1

    /** The press in progress, one key at a time (the filter is called on one thread). */
    class Gate {
        private var code = -1
        private var fired = false

        /** [action]: 0 down, 1 up (KeyEvent's); [repeat] KeyEvent.repeatCount; [downTime] / [eventTime] as the event has them. */
        fun onKey(on: Boolean, keyCode: Int, action: Int, repeat: Int, downTime: Long, eventTime: Long): Step {
            if (action == UP && keyCode == code) { code = -1; return Step.EAT }   // the up of a press we ate, even when the switch went off meanwhile
            if (!on || keyCode !in KEYS || action != DOWN) return Step.PASS
            if (keyCode == code && repeat > 0) {                      // a repeat of the held key (remotes that repeat): long enough counts without the timer
                if (!fired && eventTime - downTime >= HOLD_MS) { fired = true; return Step.FIRE }
                return Step.EAT
            }
            code = keyCode; fired = false                              // a down with repeat 0 is always a new press, even if an up was lost
            return Step.ARM
        }

        /** [HOLD_MS] after an ARM: true once, only if that key is still held and not done yet. */
        fun timer(keyCode: Int): Boolean {
            if (keyCode != code || fired) return false
            fired = true
            return true
        }

        /** Was the press that ended just now a hold that already acted? Only for the caller's toast: asked before the up. */
        fun done(keyCode: Int): Boolean = keyCode == code && fired
    }
}
