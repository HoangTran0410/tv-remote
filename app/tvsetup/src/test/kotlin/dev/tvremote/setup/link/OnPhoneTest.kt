package dev.tvremote.setup.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnPhoneTest {
    @Test fun asksOnlyOnAPhoneUntilTheyStay() {
        assertTrue(OnPhone.shouldAsk(isTv = false, stay = false))
        assertFalse(OnPhone.shouldAsk(isTv = false, stay = true))
        assertFalse(OnPhone.shouldAsk(isTv = true, stay = false))
    }
}
