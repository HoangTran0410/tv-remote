package dev.tvremote.phone.remote

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The game controller map of the phone, the same as js/gamepad.js (gamepad.test.js). */
class PadMapTest {
    @Test fun buttons() {
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, PadMap.key(KeyEvent.KEYCODE_BUTTON_A, false))
        assertEquals(KeyEvent.KEYCODE_BACK, PadMap.key(KeyEvent.KEYCODE_BUTTON_B, false))
        assertEquals(KeyEvent.KEYCODE_HOME, PadMap.key(KeyEvent.KEYCODE_BUTTON_START, false))
        assertEquals(KeyEvent.KEYCODE_MENU, PadMap.key(KeyEvent.KEYCODE_BUTTON_SELECT, false))
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, PadMap.key(KeyEvent.KEYCODE_BUTTON_R1, false))
        assertEquals(KeyEvent.KEYCODE_CHANNEL_DOWN, PadMap.key(KeyEvent.KEYCODE_BUTTON_L2, false))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, PadMap.key(KeyEvent.KEYCODE_DPAD_UP, false))
        assertNull(PadMap.key(KeyEvent.KEYCODE_BUTTON_THUMBL, false))
    }

    @Test fun pictureSendsRealPadButtons() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, PadMap.key(KeyEvent.KEYCODE_BUTTON_A, true))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, PadMap.key(KeyEvent.KEYCODE_BUTTON_START, true))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, PadMap.key(KeyEvent.KEYCODE_DPAD_LEFT, true))
    }

    @Test fun stick() {
        assertNull(PadMap.arrow(0.3f, -0.4f))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, PadMap.arrow(0.9f, 0.2f))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, PadMap.arrow(0.1f, -0.8f))
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, PadMap.arrow(0.1f, 0.8f))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, PadMap.arrow(-0.7f, 0.6f))
    }
}
