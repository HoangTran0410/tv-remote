package dev.tvremote.games

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A keyboard is two pads; a remote is not a keyboard; the Mega Drive's buttons reach the core's names. */
class KeyPadsTest {
    private fun hit(k: Int) = KeyPads.lookup(k)

    @Test fun leftHalfIsWasd() {
        assertEquals(0 to KeyEvent.KEYCODE_DPAD_UP, hit(KeyEvent.KEYCODE_W)!!.let { it.half to it.code })
        assertEquals(0 to KeyEvent.KEYCODE_DPAD_LEFT, hit(KeyEvent.KEYCODE_A)!!.let { it.half to it.code })
        assertEquals(0 to KeyEvent.KEYCODE_BUTTON_A, hit(KeyEvent.KEYCODE_J)!!.let { it.half to it.code })
        assertEquals(0 to KeyEvent.KEYCODE_BUTTON_B, hit(KeyEvent.KEYCODE_K)!!.let { it.half to it.code })
        assertEquals(0 to KeyEvent.KEYCODE_BUTTON_X, hit(KeyEvent.KEYCODE_U)!!.let { it.half to it.code })
        assertEquals(0 to KeyEvent.KEYCODE_BUTTON_Y, hit(KeyEvent.KEYCODE_I)!!.let { it.half to it.code })
        assertEquals(0 to KeyEvent.KEYCODE_BUTTON_START, hit(KeyEvent.KEYCODE_ENTER)!!.let { it.half to it.code })
        assertEquals(0 to KeyEvent.KEYCODE_BUTTON_SELECT, hit(KeyEvent.KEYCODE_TAB)!!.let { it.half to it.code })
    }

    @Test fun rightHalfIsArrowsAndNumpad() {
        assertEquals(1 to KeyEvent.KEYCODE_DPAD_RIGHT, hit(KeyEvent.KEYCODE_DPAD_RIGHT)!!.let { it.half to it.code })
        assertEquals(1 to KeyEvent.KEYCODE_BUTTON_A, hit(KeyEvent.KEYCODE_NUMPAD_1)!!.let { it.half to it.code })
        assertEquals(1 to KeyEvent.KEYCODE_BUTTON_B, hit(KeyEvent.KEYCODE_NUMPAD_2)!!.let { it.half to it.code })
        assertEquals(1 to KeyEvent.KEYCODE_BUTTON_START, hit(KeyEvent.KEYCODE_NUMPAD_ENTER)!!.let { it.half to it.code })
        assertEquals(1 to KeyEvent.KEYCODE_BUTTON_SELECT, hit(KeyEvent.KEYCODE_NUMPAD_0)!!.let { it.half to it.code })
        assertEquals(1 to KeyEvent.KEYCODE_BUTTON_A, hit(KeyEvent.KEYCODE_COMMA)!!.let { it.half to it.code })   // laptop set
    }

    @Test fun otherKeysStayTheirOwn() {                               // Back, Esc, Home, volume, the remote's colour keys: not pad keys
        for (k in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_SPACE)) assertNull(k.toString(), hit(k))
    }

    @Test fun noKeyIsOnBothHalves() {
        for (k in 0..300) hit(k)?.let { h -> assertTrue(h.half == 0 || h.half == 1) }
        assertEquals(1, hit(KeyEvent.KEYCODE_DPAD_UP)!!.half)           // the arrows are B's, not A's
        assertEquals(0, hit(KeyEvent.KEYCODE_W)!!.half)
    }

    @Test fun eachHalfHasItsOwnSeat() {
        assertNotEquals(KeyPads.seat(7, 0), KeyPads.seat(7, 1))
        assertNotEquals(KeyPads.seat(7, 1), KeyPads.seat(8, 0))
        assertTrue(KeyPads.seat(255, 1) > 1000)                         // far from any real pad's device id, which is a small number
    }

    private val kb = InputDevice.SOURCE_KEYBOARD
    @Test fun aRealKeyboardIsOne() {
        assertTrue(KeyPads.isKeyboard(kb, InputDevice.KEYBOARD_TYPE_ALPHABETIC, false, true))
    }

    @Test fun aRemoteIsNotOne() {
        // the usual remote: keyboard + D-pad source, "non-alphabetic"; a chatty one says alphabetic but has a D-pad and no letters
        assertFalse(KeyPads.isKeyboard(kb or InputDevice.SOURCE_DPAD, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC, false, false))
        assertFalse(KeyPads.isKeyboard(kb or InputDevice.SOURCE_DPAD, InputDevice.KEYBOARD_TYPE_ALPHABETIC, false, true))
        assertFalse(KeyPads.isKeyboard(kb, InputDevice.KEYBOARD_TYPE_ALPHABETIC, false, false))
        assertFalse(KeyPads.isKeyboard(kb, InputDevice.KEYBOARD_TYPE_NONE, false, true))
    }

    @Test fun aPadAndTheOnScreenKeyboardAreNot() {
        assertFalse(KeyPads.isKeyboard(kb or InputDevice.SOURCE_GAMEPAD, InputDevice.KEYBOARD_TYPE_ALPHABETIC, false, true))
        assertFalse(KeyPads.isKeyboard(kb or InputDevice.SOURCE_JOYSTICK, InputDevice.KEYBOARD_TYPE_ALPHABETIC, false, true))
        assertFalse(KeyPads.isKeyboard(kb, InputDevice.KEYBOARD_TYPE_ALPHABETIC, true, true))
        assertFalse(KeyPads.isKeyboard(InputDevice.SOURCE_TOUCHSCREEN, InputDevice.KEYBOARD_TYPE_ALPHABETIC, false, true))
    }

    @Test fun otherSystemsKeepTheirKeys() {
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER))
            for (l in listOf("nes", "snes", "gba", "ps", "pce", "sms", "arcade", "web")) assertEquals(code, PadMap.sys(l, code))
        assertNull(PadMap.phone("nes", "A"))
    }

    @Test fun megaDriveButtonsReachTheCore() {
        // the core reads Retro B = B, Retro Y = A, Retro A = C, L = X, X = Y, R = Z (ClownMDEmu, Genesis Plus GX)
        val retro = mapOf("A" to KeyEvent.KEYCODE_BUTTON_Y, "B" to KeyEvent.KEYCODE_BUTTON_B, "C" to KeyEvent.KEYCODE_BUTTON_A,
            "X" to KeyEvent.KEYCODE_BUTTON_L1, "Y" to KeyEvent.KEYCODE_BUTTON_X, "Z" to KeyEvent.KEYCODE_BUTTON_R1)
        for ((name, code) in retro) assertEquals(name, code, PadMap.phone("md", name))
        // the pad / keyboard / remote path: south = B, east = C, west = A, north = Y
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, PadMap.sys("md", KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, PadMap.sys("md", KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, PadMap.sys("md", KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, PadMap.sys("md", KeyEvent.KEYCODE_BUTTON_START))
        // the phone's names are the same keys as those positions
        assertEquals(PadMap.phone("md", "B"), PadMap.sys("md", KeyEvent.KEYCODE_BUTTON_A))
    }
}
