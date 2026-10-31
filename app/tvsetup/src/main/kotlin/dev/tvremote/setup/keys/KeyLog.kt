package dev.tvremote.setup.keys

/**
 * The pure parts of the key tester ([KeyTestActivity]): the names of keys, the history of presses and the way out. No Android type
 * here, so a test can run it. Key actions are KeyEvent's: 0 down, 1 up, 2 multiple (counted as a down).
 */
internal object KeyNames {
    /** The common keys by what a person calls them: code → the id of its words (`keytest.k_*`). Others show their KEYCODE_ name made readable. */
    val WORDS: Map<Int, String> = buildMap {
        for (c in listOf(23, 66, 160)) put(c, "ok")                    // DPAD_CENTER, ENTER, NUMPAD_ENTER
        put(19, "up"); put(20, "down"); put(21, "left"); put(22, "right")
        put(4, "back"); put(3, "home"); put(82, "menu"); put(187, "recents")
        put(24, "vol_up"); put(25, "vol_down"); put(164, "mute")
        put(166, "ch_up"); put(167, "ch_down")
        put(183, "red"); put(184, "green"); put(185, "yellow"); put(186, "blue")
        put(190, "youtube"); put(191, "netflix"); put(195, "prime")   // KEYCODE_BUTTON_3 / 4 / 8: the remote's app buttons
        for (c in listOf(192, 193, 194, 196, 197, 198, 199, 200, 201, 202, 203)) put(c, "app")   // the other BUTTON_n: more app buttons
        put(84, "search"); put(219, "mic"); put(231, "mic")             // SEARCH, ASSIST, VOICE_ASSIST
        put(170, "input"); put(178, "input"); put(176, "settings"); put(284, "allapps")
        put(172, "guide"); put(165, "info"); put(26, "power")
        put(85, "playpause"); put(126, "play"); put(127, "pause"); put(86, "stop")
        put(87, "next"); put(88, "prev"); put(89, "rew"); put(90, "ff"); put(130, "record")
    }

    /** The ids of the common keys' words, spelled out (the i18n test finds an id by its quotes). */
    val IDS: Map<String, String> = listOf(
        "keytest.k_ok", "keytest.k_up", "keytest.k_down", "keytest.k_left",
        "keytest.k_right", "keytest.k_back", "keytest.k_home", "keytest.k_menu",
        "keytest.k_recents", "keytest.k_vol_up", "keytest.k_vol_down", "keytest.k_mute",
        "keytest.k_ch_up", "keytest.k_ch_down", "keytest.k_red", "keytest.k_green",
        "keytest.k_yellow", "keytest.k_blue", "keytest.k_youtube", "keytest.k_netflix",
        "keytest.k_prime", "keytest.k_app", "keytest.k_search", "keytest.k_mic",
        "keytest.k_input", "keytest.k_settings", "keytest.k_allapps", "keytest.k_guide",
        "keytest.k_info", "keytest.k_power", "keytest.k_playpause", "keytest.k_play",
        "keytest.k_pause", "keytest.k_stop", "keytest.k_next", "keytest.k_prev",
        "keytest.k_rew", "keytest.k_ff", "keytest.k_record").associateBy { it.removePrefix("keytest.k_") }

    /** A digit key (0-9 on the row or the keypad) as itself, else null. */
    fun digit(code: Int): String? = when (code) { in 7..16 -> (code - 7).toString(); in 144..153 -> (code - 144).toString(); else -> null }

    /**
     * "KEYCODE_MEDIA_PLAY_PAUSE" → "Media play pause", "KEYCODE_BUTTON_3" → "Button 3". [raw] is what KeyEvent.keyCodeToString gives;
     * an unknown code comes back as its number, which becomes "Key 251".
     */
    fun readable(raw: String): String {
        val s = raw.removePrefix("KEYCODE_").lowercase().replace('_', ' ').trim()
        if (s.isEmpty()) return raw
        if (s.all { it.isDigit() }) return "Key $s"
        return s.replaceFirstChar { it.uppercase() }
    }

    /** The big name of a key: a digit as itself, a common key by its [word] (the localised words), else [readable] of [raw]. */
    fun label(code: Int, raw: String, word: (String) -> String): String =
        digit(code) ?: WORDS[code]?.let { IDS[it] }?.let(word) ?: readable(raw)
}

/** One line of the history: a press from its first down to its up. */
internal class KeyPress(val code: Int, val scan: Int, val source: String, val at: Long) {
    enum class Phase { DOWN, HELD, LONG, UP }
    var phase = Phase.DOWN
    var repeats = 0
    var heldMs = 0L
    var long = false
}

/** The last [max] presses, newest first. [feed] takes one event and says which press it belongs to. */
internal class KeyLog(private val max: Int = 12) {
    val items = ArrayList<KeyPress>()

    /** [longFlag]: the system marked the event as a long press; else a key held [longMs] counts as one. */
    fun feed(code: Int, scan: Int, source: String, action: Int, repeat: Int, downTime: Long, eventTime: Long,
             longFlag: Boolean = false, longMs: Long = 500): KeyPress {
        val top = items.firstOrNull()?.takeIf { it.code == code && it.phase != KeyPress.Phase.UP }
        if (action == 1) {                                             // up
            val p = top ?: newPress(code, scan, source, eventTime)
            p.phase = KeyPress.Phase.UP
            p.heldMs = (eventTime - downTime).coerceAtLeast(0)
            if (p.heldMs >= longMs) p.long = true
            return p
        }
        if (repeat == 0 || top == null) return newPress(code, scan, source, eventTime).also { it.heldMs = 0 }
        top.repeats = repeat
        top.heldMs = (eventTime - downTime).coerceAtLeast(0)
        if (longFlag || top.heldMs >= longMs) top.long = true
        top.phase = if (top.long) KeyPress.Phase.LONG else KeyPress.Phase.HELD
        return top
    }

    private fun newPress(code: Int, scan: Int, source: String, at: Long): KeyPress {
        val p = KeyPress(code, scan, source, at)
        items.add(0, p)
        while (items.size > max) items.removeAt(items.size - 1)
        return p
    }
}

/**
 * Back must be testable too, so it does not leave at once: held [holdMs] (the system's repeats keep coming, so the held time is read
 * from them), or two presses within [gapMs]. [onKey] says true when the tester should close.
 */
internal class BackExit(private val holdMs: Long = 2000, private val gapMs: Long = 700) {
    private var lastDown = 0L

    fun onKey(action: Int, repeat: Int, downTime: Long, eventTime: Long): Boolean {
        if (action == 1) return false
        if (repeat == 0) {
            val twice = lastDown != 0L && eventTime - lastDown <= gapMs
            lastDown = eventTime
            return twice
        }
        return eventTime - downTime >= holdMs
    }

    /** A Back came lately enough that one more leaves: the screen says so. */
    fun armed(now: Long): Boolean = lastDown != 0L && now - lastDown <= gapMs
}
