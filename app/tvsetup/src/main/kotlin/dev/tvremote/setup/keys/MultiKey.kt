package dev.tvremote.setup.keys

/**
 * A double press and a hold per remote key (HomeConfig "keydbl" / "keyhold"; off by default). The timing lives here with no Android type
 * so a test can run it; [dev.tvremote.setup.KeysBridge] feeds it every key of its list and does what it answers.
 *
 * A key with neither is [Out.PASS]ed at once: nothing waits for a second press that cannot matter. Only a key with a double action waits
 * [DOUBLE_MS] after its release; only one with a hold acts on release (not on press) so a hold can be told from a press. What a
 * plain press does then is the caller's ([Do.SINGLE]: the key's own rule, else the key given back).
 */
object MultiKey {
    const val DOUBLE_MS = 300L                                        // the second press must start within this after the first one's release
    const val HOLD_MS = 600L                                          // held this long is a hold (the 2 s of HoldKeys is for keys that must not be hit by mistake)

    enum class Do { SINGLE, DOUBLE, HOLD }
    enum class Timer { NONE, DOUBLE, HOLD }

    /** [eat]: swallow this event. [fire]: do this now. [flush]: first do the plain press of this other key (this event ended its window). [timer]: call [Gate.timer] after its time. */
    class Out(val eat: Boolean, val fire: Do? = null, val timer: Timer = Timer.NONE, val flush: Int = -1) {
        companion object { val PASS = Out(false) }
    }

    private const val DOWN = 0

    /** The press in progress, one key at a time (the filter is called on one thread at a time). */
    class Gate {
        private enum class Phase { IDLE, DOWN, WAIT, DONE }
        private var phase = Phase.IDLE
        private var code = -1
        private var hold = false
        private var dbl = false

        /**
         * One event of any key. [dbl] / [hold]: this key has such an action now. [action] 0 down, 1 up (KeyEvent's); [downTime] /
         * [eventTime] let a remote that repeats show the hold without the timer.
         */
        fun onKey(dbl: Boolean, hold: Boolean, keyCode: Int, action: Int, repeat: Int, downTime: Long, eventTime: Long): Out {
            if (phase != Phase.IDLE && keyCode != code) {             // another key meanwhile: a waiting press is a plain one, now; this event starts fresh
                val pending = if (phase == Phase.WAIT) code else -1
                phase = Phase.IDLE; code = -1
                val o = onKey(dbl, hold, keyCode, action, repeat, downTime, eventTime)
                return Out(o.eat, o.fire, o.timer, pending)
            }
            val mine = keyCode == code && phase != Phase.IDLE
            if (action == DOWN) {
                if (mine && repeat > 0) {
                    if (phase == Phase.DOWN && this.hold && eventTime - downTime >= HOLD_MS) { phase = Phase.DONE; return Out(true, Do.HOLD) }
                    return Out(true)
                }
                if (!dbl && !hold) { phase = Phase.IDLE; code = -1; return Out.PASS }
                if (phase == Phase.WAIT && mine) { phase = Phase.DONE; return Out(true, Do.DOUBLE) }   // the second press: acts at once
                code = keyCode; this.dbl = dbl; this.hold = hold      // a down with repeat 0 is always a new press, even if an up was lost
                phase = Phase.DOWN
                return Out(true, null, if (hold) Timer.HOLD else Timer.NONE)
            }
            if (!mine) return Out.PASS
            return if (phase == Phase.DOWN) {
                if (this.dbl) { phase = Phase.WAIT; Out(true, null, Timer.DOUBLE) } else { phase = Phase.IDLE; code = -1; Out(true, Do.SINGLE) }
            } else { phase = Phase.IDLE; code = -1; Out(true) }       // its up after a hold or a double that already acted
        }

        /** The timer an [Out] asked for ran for [keyCode]: what to do now, null when it no longer matters. */
        fun timer(t: Timer, keyCode: Int): Do? {
            if (keyCode != code) return null
            return when {
                t == Timer.HOLD && phase == Phase.DOWN -> { phase = Phase.DONE; Do.HOLD }
                t == Timer.DOUBLE && phase == Phase.WAIT -> { phase = Phase.IDLE; code = -1; Do.SINGLE }
                else -> null
            }
        }
    }
}
