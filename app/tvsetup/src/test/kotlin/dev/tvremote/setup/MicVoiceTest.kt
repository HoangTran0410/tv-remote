package dev.tvremote.setup

import dev.tvremote.core.ontv.VoiceAddon
import dev.tvremote.setup.MicVoice.Step
import dev.tvremote.setup.rules.Rules
import dev.tvremote.setup.voice.VoiceProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mic button's filter: off or no recognizer = every key passes untouched; on = tap-to-talk, or hold-to-talk when the remote sends a long press. */
class MicVoiceTest {
    private fun s(ready: Boolean, listening: Boolean, code: Int, action: Int, repeat: Int = 0, down: Long = 0, at: Long = 0) = MicVoice.step(ready, listening, code, action, repeat, down, at)

    /** The texts the FPT box gave (2026-11-05) through the real Voice: a garbled grammar text must not hide a free search. */
    @Test fun pickReadsWhatEachTextParsesTo() {
        dev.tvremote.core.scenes.Voice.phrases = dev.tvremote.core.scenes.Phrases(java.io.File("../shared/web/voice/phrases.json").readText())
        val apps = mapOf("com.google.android.youtube.tv" to "YouTube")
        fun pick(g: String, f: String) = VoiceProtocol.pick(listOf(g, f), { dev.tvremote.core.scenes.Voice.kind(it, dev.tvremote.core.scenes.Voice.parse(it, apps, emptyMap())) }) { dev.tvremote.core.scenes.Voice.asSearch(it) }
        assertEquals("sơn tùng trên youtube", pick("sau home tục menu 6", "sơn tùng trên youtube"))
        assertEquals("sơn tùng trên youtube", pick("sơn tùng chia mưu sinh", "sơn tùng trên youtube"))
        assertEquals("sơn tùng chia mưu sinh", pick("sau home tục menu 6", "sơn tùng chia mưu sinh"))
        assertTrue(pick("tìm ngay xuống thanh đổi", "kỷ niệm ngày xưa anh nổi").endsWith(" kỷ niệm ngày xưa anh nổi"))
        assertTrue(dev.tvremote.core.scenes.Voice.looksLikeSearch(pick("tìm ngay xuống thanh đổi", "kỷ niệm ngày xưa anh nổi")))
        assertEquals("tìm ngày xưa anh nói", pick("tìm ngay xuống thanh đổi", "tìm ngày xưa anh nói"))
        assertEquals("hẹn giờ tắt 30 phu", pick("hẹn giờ tắt 30 phu", "hẹn giờ tắt mà ngươi phú"))
        assertEquals("tắt tiếng", pick("tắt tiếng", "tắt tiếng"))
    }

    @Test fun offOrNoRecognizerPassesEverything() {
        for (k in MicVoice.KEYS) for (a in 0..1) assertEquals(Step.PASS, s(false, false, k, a))
        assertEquals(Step.PASS, s(true, false, 24, 0))                  // another key is never ours
    }

    @Test fun aTapStartsAndAnotherTapStops() {
        assertEquals(Step.START, s(true, false, 231, 0))
        assertEquals(Step.EAT, s(true, true, 231, 1, down = 0, at = 120))      // the up of a short press: nothing, it keeps listening
        assertEquals(Step.STOP, s(true, true, 231, 0))
    }

    @Test fun holdingTalksAndLettingGoStops() {
        assertEquals(Step.START, s(true, false, 219, 0))
        assertEquals(Step.EAT, s(true, true, 219, 0, repeat = 3))
        assertEquals(Step.RELEASE, s(true, true, 219, 1, down = 1000, at = 1000 + MicVoice.HOLD_MS))
    }

    @Test fun theKeysAreAskedAbout() {
        assertTrue(dev.tvremote.setup.keys.KeysProtocol.INTEREST.containsAll(MicVoice.KEYS))
        assertTrue(listOf(84, 219, 231).all { it in MicVoice.KEYS })
    }

    @Test fun aFailureSendsTheKeyBackForAWhile() {
        MicVoice.fixed(); assertFalse(MicVoice.broken())
        MicVoice.broke(); assertTrue(MicVoice.broken()); assertFalse(MicVoice.broken(System.currentTimeMillis() + 11 * 60_000L))
        MicVoice.fixed(); assertFalse(MicVoice.broken())
    }

    @Test fun theRuleIsOffUntilTurnedOn() {
        val o = JSONObject()
        assertFalse(o.optBoolean("mic"))
        Rules.apply(o, JSONObject().put("do", "mic").put("on", true), 0L)
        assertTrue(o.optBoolean("mic"))
        Rules.apply(o, JSONObject().put("do", "mic").put("on", false), 0L)
        assertFalse(o.optBoolean("mic"))
    }

    /** :shared installs the add-on by its name and cannot see VoiceProtocol; MicVoice binds it by VoiceProtocol's: one package. */
    @Test fun addonNameIsOne() = assertEquals(VoiceProtocol.ADDON, VoiceAddon.PKG)
    @Test fun addonLangsAreOne() { assertEquals(VoiceProtocol.LANGS, VoiceAddon.LANGS); for (l in VoiceAddon.LANGS) assertEquals(VoiceProtocol.addon(l), VoiceAddon.pkg(l)) }

    /** Google's assistant coming back (switched on again, updated) is taken back; one the person picked is not. */
    @Test fun googleComingBackIsTakenBack() {
        val katniss = "com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService"
        assertTrue(MicVoice.takesBack(katniss, ""))
        assertTrue(MicVoice.takesBack(katniss, katniss))
        assertTrue(MicVoice.takesBack(null, katniss))
        assertTrue(MicVoice.takesBack("com.other.tv/.Assist", "com.other.tv/.Old"))
        assertFalse(MicVoice.takesBack("com.amazon.alexa/.Voice", katniss))
    }

    private fun why(mic: Boolean = true, google: Boolean = false, ble: Boolean = false, ours: Boolean = false, search: String = "") =
        MicVoice.blockReason(mic, google, ble, ours, search, "dev.tvremote.setup")

    @Test fun googleHoldsTheMicOnABleRemote() {                          // Skyworth SWTV-24AE-4K, Android 14: audio goes straight to the Google app
        assertEquals(MicVoice.GOOGLE, why(google = true, ble = true, ours = true))   // even with our assistant set
        assertEquals("", why(google = true, ble = false, ours = true))           // no BLE service: the assistant setting works
        assertEquals("", why(google = false, ble = true))                        // the Google app off: WEB_SEARCH reaches MicSearch
    }

    @Test fun aMicKeyStartSeenMeansItWorks() {                          // FPT Play 650: the same packages as the Skyworth, but the key reaches us
        val seen = MicVoice.blockReason(true, true, true, true, "", "dev.tvremote.setup", keySeen = true)
        assertEquals("", seen)
        assertEquals(MicVoice.GOOGLE, MicVoice.blockReason(true, true, true, true, "", "dev.tvremote.setup", keySeen = false))
        assertEquals(MicVoice.OTHER, MicVoice.blockReason(true, true, true, false, "ai.zalo.kiki.tv", "dev.tvremote.setup", keySeen = true))   // another app's default is not cleared by a press
    }

    @Test fun anotherAppHoldsWebSearch() {
        assertEquals(MicVoice.OTHER, why(search = "ai.zalo.kiki.tv"))
        assertEquals("", why(search = "ai.zalo.kiki.tv", ours = true))        // the assistant setting is ours: WEB_SEARCH is not the path
        assertEquals("", why(search = "dev.tvremote.setup"))
        assertEquals("", why(search = "android"))                           // the system chooser: the first press asks
        assertEquals("", why(search = ""))
    }

    @Test fun ruleOffMeansNoBlock() {
        assertEquals("", why(mic = false, google = true, ble = true, search = "ai.zalo.kiki.tv"))
    }

    /** dev/flows/voice.md "Who listens": rows 2, 3, 6, 10, and 8 (the add-on's model does not load: the TV's own, not 10 min of nothing). */
    @Test fun whoListens() {
        assertFalse(MicVoice.useAddon(offline = false, tvFirst = false, own = true, noModel = false))   // row 2: Google's first
        assertTrue(MicVoice.useAddon(offline = true, tvFirst = false, own = true, noModel = false))     // row 3: after a network error
        assertTrue(MicVoice.useAddon(offline = false, tvFirst = false, own = false, noModel = false))   // row 6: no recognizer of its own
        assertTrue(MicVoice.useAddon(offline = false, tvFirst = true, own = true, noModel = false))     // row 10: picked first
        assertFalse(MicVoice.useAddon(offline = true, tvFirst = true, own = true, noModel = true))      // row 8: Google's after all
        assertTrue(MicVoice.useAddon(offline = true, tvFirst = true, own = false, noModel = true))      // row 9: nothing else to ask
    }
}
