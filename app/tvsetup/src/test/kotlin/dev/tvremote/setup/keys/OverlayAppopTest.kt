package dev.tvremote.setup.keys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayAppopTest {
    @Test fun allowedIsRead() = assertTrue(KeyFix.overlayAllowed("SYSTEM_ALERT_WINDOW: allow\n"))
    @Test fun defaultIsNotEnough() = assertFalse(KeyFix.overlayAllowed("SYSTEM_ALERT_WINDOW: default"))
    @Test fun anErrorKeepsItUnprepared() {
        assertFalse(KeyFix.overlayAllowed("Error: Unknown operation string: SYSTEM_ALERT_WINDOW"))
        assertFalse(KeyFix.overlayAllowed("cmd: Failure calling service appops: Failed transaction"))
        assertFalse(KeyFix.overlayAllowed(""))
    }
}
