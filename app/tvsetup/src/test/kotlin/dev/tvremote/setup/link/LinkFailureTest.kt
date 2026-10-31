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

    @Test fun refusedAndClosedKeepTheirStates() {
        assertEquals("refused", LinkFailure.of("error", "failed to connect: ECONNREFUSED (Connection refused)", 5000, raw).state)
        assertEquals("connecting", LinkFailure.of("closed", "", 5000, raw).state)
    }

    @Test fun anUnansweredQuestionHasPlainWordsEverywhere() {
        val w = Errors.say(IOException(Adb.UNANSWERED), "en")
        assertTrue(w, w.contains("Allow USB debugging") && !w.contains(Adb.UNANSWERED))
    }
}
