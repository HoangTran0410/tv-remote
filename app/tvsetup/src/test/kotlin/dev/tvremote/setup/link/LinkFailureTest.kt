package dev.tvremote.setup.link

import dev.tvremote.core.Errors
import dev.tvremote.core.adb.Adb
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkFailureTest {
    private val raw: (String) -> String = { it }

    @Test fun anUnansweredQuestionIsAllowNotAnError() {
        val o = LinkFailure.of("error", Adb.UNANSWERED, 5 * 60_000L, raw)
        assertEquals("allow", o.state)
        assertEquals("", o.detail)                                     // the page says "the TV is asking", not a raw word
        assertTrue(o.backoff)                                          // the question pops over what is playing: no nagging
    }

    @Test fun otherErrorsKeepTheirBackoffAndShowWords() {
        val o = LinkFailure.of("error", "adb tls: this device is not paired", 20_000L, { Errors.say(IOException(it), "en") })
        assertEquals("error", o.state)
        assertTrue(o.detail, !o.detail.contains("adb tls"))
        assertEquals(20_000L, o.retryMs)
        assertTrue(o.backoff)
    }

    @Test fun aTimeoutOnTheOwnLinkIsTheTvsAdbNotTheNetwork() {
        for (d in listOf("java.net.SocketTimeoutException: Read timed out", "timed out")) {
            val o = LinkFailure.of("error", d, 5000, { Errors.say(IOException(it), "en") }, silent = { "tv adb silent" })
            assertEquals("error", o.state)
            assertEquals("tv adb silent", o.detail)                     // not Errors.say's "network is slow"
        }
        assertEquals(3, LinkFailure.ASK_LIMIT)
    }

    @Test fun refusedAndClosedKeepTheirStates() {
        assertEquals("refused", LinkFailure.of("error", "failed to connect: ECONNREFUSED (Connection refused)", 5000, raw).state)
        assertEquals("connecting", LinkFailure.of("closed", "", 5000, raw).state)
    }

    @Test fun anUnansweredQuestionHasPlainWordsEverywhere() {
        val w = Errors.say(IOException(Adb.UNANSWERED), "en")
        assertTrue(w, w.contains("Allow USB debugging") && !w.contains(Adb.UNANSWERED))
    }

    @Test fun androidElevenWithWirelessDebuggingOffIsToldToTurnItOn() {
        assertEquals("tv_main.adb_wifi_off_howto", LinkFailure.noPortHowto(false, 34).first())   // a Chromecast: "unplug it" led nowhere
        assertEquals(3, LinkFailure.noPortHowto(false, 34).size)
        assertEquals(listOf("tv_main.opening_wireless_debugging", "tv_main.adb_wifi_only_pair", "tv_main.adb_wifi_only_again"), LinkFailure.noPortHowto(false, 34, opening = true))   // a fourth line ran out of the box                                // a fourth line pushed the first out of the log box
        assertEquals("tv_main.adb_wifi_only_howto", LinkFailure.noPortHowto(true, 34).first())
        assertEquals(listOf("tv_main.adb_no_port_howto"), LinkFailure.noPortHowto(false, 29))     // no such switch before Android 11
    }

    @Test fun wirelessScreensInOrder() {
        assertEquals(emptyList<LinkFailure.Screen>(), LinkFailure.wirelessScreens(29))
        val s = LinkFailure.wirelessScreens(34)
        assertEquals("com.android.settings.Settings\$WirelessDebuggingActivity", s.first().cls)   // the page itself first
        assertEquals("com.android.settings.development.WirelessDebuggingFragment", s.last().fragment)
        assertEquals(true, LinkFailure.opensWireless(false, 30))
        assertEquals(false, LinkFailure.opensWireless(true, 34))   // already on: the words say pair, nothing to open
        assertEquals(false, LinkFailure.opensWireless(false, 29))
        assertEquals(false, LinkFailure.opensWireless(false, 30, shield = true))   // a SHIELD: its Network debugging switch, not Wireless debugging
        assertEquals(listOf("tv_main.shield_adb"), LinkFailure.noPortHowto(false, 30, shield = true))
        assertEquals(true, LinkFailure.shield("NVIDIA", "SHIELD Android TV"))
        assertEquals(emptyList<String>(), LinkFailure.unansweredHowto(29, false))   // no Wireless debugging before Android 11
        assertEquals(listOf("tv_main.allow_not_shown", "tv_main.adb_wifi_off_howto"), LinkFailure.unansweredHowto(30, false))
        assertEquals(false, LinkFailure.shield("TCL", "Smart TV Pro"))
    }

    @Test fun fireTvsOwnAdbBlockedSaysSo() {
        val o = LinkFailure.of("error", "EOFException", 20_000L, raw, "Amazon", fire = { "blocked" })
        assertEquals("error", o.state)
        assertEquals("blocked", o.detail)
        assertTrue(LinkFailure.fireBlocked("Amazon", "java.io.EOFException: stream closed"))
        assertTrue(!LinkFailure.fireBlocked("Xiaomi", "EOFException"))                // elsewhere an EOF is not Amazon's block
        assertEquals("EOFException", LinkFailure.of("error", "EOFException", 20_000L, raw, "Xiaomi", fire = { "blocked" }).detail)
    }
}
