package dev.tvremote.games

import android.view.InputDevice
import android.view.KeyEvent

/**
 * A physical keyboard on the TV is two pads, each with its own seat like a game pad (GameServer.padPort):
 *   player A (left hand):  W A S D move, J K U I = A B X Y, Q E = L R, Enter = Start, Tab = Select
 *   player B (right hand): arrows move, Num 1 2 4 5 = A B X Y, Num 7 8 = L R, Num Enter = Start, Num 0 = Select
 * Keyboards without a number pad (laptops) have a second set for B: , . / ; = A B X Y, [ ] = L R, Right Shift = Start, Right Ctrl = Select.
 * Only a real letter keyboard counts: a TV remote often says "keyboard" too, and its D-pad and OK must stay the remote's.
 * No Android here but the key codes, so a unit test can run it.
 */
object KeyPads {
    /** A seat key for the first (0) or second (1) half of keyboard [deviceId]: far above any real device id, so it never meets a pad's. */
    fun seat(deviceId: Int, half: Int): Int = BASE + deviceId * 2 + half
    private const val BASE = 1 shl 20

    class Hit(val half: Int, val code: Int)

    private val LEFT = mapOf(
        KeyEvent.KEYCODE_W to KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_S to KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_A to KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_D to KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_J to KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_K to KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_U to KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_I to KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_Q to KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_E to KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_ENTER to KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_TAB to KeyEvent.KEYCODE_BUTTON_SELECT,
    )
    private val RIGHT = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_NUMPAD_1 to KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_COMMA to KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_NUMPAD_2 to KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_PERIOD to KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_NUMPAD_4 to KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_SLASH to KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_NUMPAD_5 to KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_SEMICOLON to KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_NUMPAD_7 to KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_LEFT_BRACKET to KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_NUMPAD_8 to KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_RIGHT_BRACKET to KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_NUMPAD_ENTER to KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_SHIFT_RIGHT to KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_NUMPAD_0 to KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_CTRL_RIGHT to KeyEvent.KEYCODE_BUTTON_SELECT,
    )

    /** Which half and which pad button a key is, or null (Esc, F-keys, Back ... stay what they were). */
    fun lookup(keyCode: Int): Hit? =
        LEFT[keyCode]?.let { Hit(0, it) } ?: RIGHT[keyCode]?.let { Hit(1, it) }

    /**
     * A real letter keyboard: alphabetic, not the on-screen one, no D-pad / pad / stick of its own (a remote that says "keyboard"
     * has the D-pad) and it has the letters of the map. Pure so a test can feed it a remote's and a keyboard's numbers.
     */
    fun isKeyboard(sources: Int, keyboardType: Int, virtual: Boolean, hasLetters: Boolean): Boolean =
        keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && !virtual && hasLetters &&
            has(sources, InputDevice.SOURCE_KEYBOARD) && !has(sources, InputDevice.SOURCE_DPAD) &&
            !has(sources, InputDevice.SOURCE_GAMEPAD) && !has(sources, InputDevice.SOURCE_JOYSTICK)

    private fun has(sources: Int, s: Int) = sources and s == s            // the source constants share bits (class button), so test them whole

    private val known = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()   // per device id: hasKeys asks the system

    fun isKeyboard(d: InputDevice?): Boolean {
        if (d == null) return false
        return known.getOrPut(d.id) {
            isKeyboard(d.sources, d.keyboardType, d.isVirtual, d.hasKeys(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_Q).all { it })
        }
    }

    /** Is a real keyboard plugged in right now (the pause card shows the key map then). */
    fun present(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { isKeyboard(it.sources, it.keyboardType, it.isVirtual, it.hasKeys(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_Q).all { k -> k }) } == true
    }
}

/**
 * A system's buttons are not always its pad's: the Mega Drive core reads Retro B as its B, Retro Y as its A, Retro A as its C,
 * L / X / R as X / Y / Z. Everything the TV hears (a physical pad, the keyboard, the remote, a phone) is in the usual
 * south = A, east = B, west = X, north = Y; this turns that into what the core reads.
 */
object PadMap {
    /** Key [code] as the pad [layout] (Sys.pad) wants it. */
    fun sys(layout: String, code: Int): Int = if (layout != "md") code else when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B      // south = B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A      // east = C
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y      // west = A
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X      // north = Y
        else -> code
    }

    /** The phone's Mega Drive button names in the usual pad positions: A B C along the bottom, X Y Z above. */
    private val MD = mapOf("A" to KeyEvent.KEYCODE_BUTTON_X, "B" to KeyEvent.KEYCODE_BUTTON_A, "C" to KeyEvent.KEYCODE_BUTTON_B,
        "X" to KeyEvent.KEYCODE_BUTTON_L1, "Y" to KeyEvent.KEYCODE_BUTTON_Y, "Z" to KeyEvent.KEYCODE_BUTTON_R1)

    /** The key a phone's button [name] sends in a game of layout [layout], or null = the usual table (GameServer.KEYS). */
    fun phone(layout: String, name: String): Int? = if (layout == "md") MD[name]?.let { sys(layout, it) } else null
}
