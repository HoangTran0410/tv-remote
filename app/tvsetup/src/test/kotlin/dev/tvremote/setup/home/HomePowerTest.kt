package dev.tvremote.setup.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePowerTest {
    @Test fun onlyTheNamedActionsHaveACommand() {
        assertEquals("input keyevent 223", HomePower.command("standby"))
        assertEquals("reboot -p || svc power shutdown", HomePower.command("power_off"))
        assertNull(HomePower.command("screen_off"))                  // the helper, not a shell line
        assertEquals("reboot", HomePower.command("reboot"))
        assertNull(HomePower.command("reboot; id"))
        assertNull(HomePower.command("standby; rm -rf /sdcard"))     // a caller's own words never reach the shell
        assertNull(HomePower.command(""))
    }
}
