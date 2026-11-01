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

    /** build.gradle.kts folds the model's words with its own copy of fold (a build script cannot call this one): both must agree. */
    @Test fun buildFoldsLikeTheApp() {
        val f = File("build/generated/model/model/graph/fold.txt")
        if (!f.isFile) return                                         // packModel runs before any build of this module
        var n = 0
        for (l in f.readLines()) {
            val key = l.substringBefore('\t')
            for (w in l.substringAfter('\t').split(' ')) { assertEquals(w, key, VoiceProtocol.fold(w)); n++ }
        }
        assert(n > 10_000) { "only $n words" }
    }
}
