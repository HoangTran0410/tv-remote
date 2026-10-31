package dev.tvremote.phone.remote

import android.view.KeyEvent

/**
 * A game controller connected to the phone (Bluetooth / USB) drives the TV, the same map as the page's js/gamepad.js.
 * Pure, so a unit test covers it. The picture view sends real gamepad buttons (a game on the TV sees a pad); elsewhere the
 * face buttons are the remote's keys.
 */
object PadMap {
    const val DEAD = 0.5f                                              // stick deadzone, as the page

    /** The TV key for a pad button, or null when it is not one we use (the d-pad arrives as DPAD_* already). */
    fun key(code: Int, picture: Boolean): Int? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> if (picture) code else KeyEvent.KEYCODE_DPAD_CENTER
        KeyEvent.KEYCODE_BUTTON_B -> if (picture) code else KeyEvent.KEYCODE_BACK
        KeyEvent.KEYCODE_BUTTON_X -> if (picture) code else KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        KeyEvent.KEYCODE_BUTTON_Y -> if (picture) code else KeyEvent.KEYCODE_VOLUME_MUTE
        KeyEvent.KEYCODE_BUTTON_L1 -> if (picture) code else KeyEvent.KEYCODE_VOLUME_DOWN
        KeyEvent.KEYCODE_BUTTON_R1 -> if (picture) code else KeyEvent.KEYCODE_VOLUME_UP
        KeyEvent.KEYCODE_BUTTON_L2 -> KeyEvent.KEYCODE_CHANNEL_DOWN
        KeyEvent.KEYCODE_BUTTON_R2 -> KeyEvent.KEYCODE_CHANNEL_UP
        KeyEvent.KEYCODE_BUTTON_SELECT -> if (picture) code else KeyEvent.KEYCODE_MENU
        KeyEvent.KEYCODE_BUTTON_START -> if (picture) code else KeyEvent.KEYCODE_HOME
        KeyEvent.KEYCODE_BUTTON_MODE -> KeyEvent.KEYCODE_HOME
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> code
        else -> null
    }

    /** The arrow a stick (or hat) position means: its stronger axis past the deadzone, else null. */
    fun arrow(x: Float, y: Float): Int? {
        if (maxOf(Math.abs(x), Math.abs(y)) <= DEAD) return null
        return if (Math.abs(x) > Math.abs(y)) (if (x > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        else if (y > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
    }
}
