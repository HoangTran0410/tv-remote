package dev.tvremote.setup.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VoiceProtocolTest {
    @Test fun numberWordsBecomeDigits() {
        assertEquals("hẹn giờ tắt 30 phút", VoiceProtocol.digits("hẹn giờ tắt ba mươi phút"))
        assertEquals("kênh vtv 3", VoiceProtocol.digits("kênh vtv ba"))
        assertEquals("25", VoiceProtocol.digits("hai mươi lăm"))
        assertEquals("15", VoiceProtocol.digits("mười lăm"))
        assertEquals("105", VoiceProtocol.digits("một trăm linh năm"))
        assertEquals("1 2 3", VoiceProtocol.digits("một hai ba"))
    }

    @Test fun englishNumberWordsBecomeDigits() {
        assertEquals("set timer 30 minutes", VoiceProtocol.digits("set timer thirty minutes", "en"))
        assertEquals("channel 25", VoiceProtocol.digits("channel twenty five", "en"))
        assertEquals("105", VoiceProtocol.digits("one hundred and five", "en"))
        assertEquals("14", VoiceProtocol.digits("fourteen", "en"))
        assertEquals("1 2 3", VoiceProtocol.digits("one two three", "en"))
        assertEquals("open netflix and play", VoiceProtocol.digits("open netflix and play", "en"))
    }

    @Test fun freeSpeechOnlyWhenTheCommandsWereNotClear() {
        assertEquals(true, VoiceProtocol.sure(listOf(1.0, 1.0)))
        assertEquals(true, VoiceProtocol.sure(listOf(0.97, 0.93)))
        assertEquals(false, VoiceProtocol.sure(listOf(1.0, 0.6)))     // one weak word
        assertEquals(false, VoiceProtocol.sure(listOf(0.92, 0.9)))    // all middling
        assertEquals(false, VoiceProtocol.sure(emptyList()))
    }

    @Test fun aCommandWinsElseFreeSpeech() {
        val cmds = setOf("tắt tiếng", "mở youtube")
        fun pick(vararg h: String) = VoiceProtocol.pick(h.toList()) { it in cmds }
        assertEquals("tắt tiếng", pick("tắt tiếng", "tắc kiểu"))      // the commands' list is exact: kept
        assertEquals("tìm ngày xưa anh nổi", pick("tiếng ngay xuống thanh đổi", "tìm ngày xưa anh nổi"))
        assertEquals("mở youtube", pick("tiếng ngay", "mở youtube"))  // free speech heard a command the list missed
        assertEquals("tiếng ngay", pick("tiếng ngay", ""))            // no second pass: as before
        assertEquals("", pick("", ""))
    }

    /** build.gradle.kts folds the model's words with its own copy of fold (a build script cannot call this one): both must agree. */
    @Test fun buildFoldsLikeTheApp() {
        val f = File("build/generated/model-vi/model/graph/fold.txt")
        if (!f.isFile) return                                         // packModel runs before any build of this module
        var n = 0
        for (l in f.readLines()) {
            val key = l.substringBefore('\t')
            for (w in l.substringAfter('\t').split(' ')) { assertEquals(w, key, VoiceProtocol.fold(w)); n++ }
        }
        assert(n > 10_000) { "only $n words" }
    }
}
