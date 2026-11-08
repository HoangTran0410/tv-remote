package dev.tvremote.setup.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** TV Home's Control Center may only switch kids mode ON by itself: "kids:off" opens the PIN screen and changes no rules. */
class HomeControlTest {
    @Test fun kidsOffOnlyOpensThePinScreen() {
        assertEquals("pin", HomeControl.route("kids:off"))
        assertEquals("manage", HomeControl.route("kids:setup"))
    }

    /** TV Home's layout lock: the word only opens the PIN screen (a right code there opens the layout), it never changes the rules. */
    @Test fun theLayoutLockWordOnlyOpensThePinScreen() {
        assertEquals("homepin", HomeControl.route("home:unlock"))
        assertEquals("bad", HomeControl.route("home:lock"))
        assertEquals("bad", HomeControl.route("home:"))
    }

    @Test fun onlyTheKnownWordsChangeTheRules() {
        assertEquals("rules", HomeControl.route("kids:on"))
        assertEquals("rules", HomeControl.route("sleep:30"))
        assertEquals("bad", HomeControl.route("sleep:7"))
        assertEquals("bad", HomeControl.route("kids:list"))
        assertEquals("ask", HomeControl.route(""))
    }

    /** The mic tile's two-way switch (dev/flows/voice.md): Google's is plain rules, TV Setup Voice may need its screen first. */
    @Test fun theMicTileSwitchesWhoListens() {
        assertEquals("rules", HomeControl.route("mic:google"))
        assertEquals("mictv", HomeControl.route("mic:tv"))
        assertEquals("voice", HomeControl.route("voice"))
        assertEquals("bad", HomeControl.route("mic:vosk"))
    }

    @Test fun theBrightnessBarTakesOffOrAStrengthInRange() {
        assertEquals("dim", HomeControl.route("dim:0"))
        assertEquals("dim", HomeControl.route("dim:30"))
        assertEquals("bad", HomeControl.route("dim:95"))
        assertEquals("bad", HomeControl.route("dim:2"))
        assertEquals("bad", HomeControl.route("dim:x"))
    }

    @Test fun theColourButtonTakesOnlyTheTintsColours() {
        assertEquals("dimc", HomeControl.route("dimc:amber"))
        assertEquals("dimc", HomeControl.route("dimc:dark"))
        assertEquals("bad", HomeControl.route("dimc:green"))
        assertEquals("bad", HomeControl.route("dimc:"))
    }
}
