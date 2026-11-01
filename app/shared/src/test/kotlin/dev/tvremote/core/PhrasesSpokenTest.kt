package dev.tvremote.core

import dev.tvremote.core.scenes.Phrases
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Phrases.spoken: what TV Setup Voice listens for, from the packed list. */
class PhrasesSpokenTest {
    private val p = Phrases(File("web/voice/phrases.json").readText())
    private val vi = p.spoken("vi", listOf("SmartTube", "Phim tối"))

    @Test fun accentsAndSpelling() {
        System.getenv("SPOKEN_OUT")?.let { File(it).writeText(vi.joinToString("\n")) }   // a file to try the list against a model by hand
        assertTrue("hẹn giờ" in vi)
        assertTrue("tăng âm lượng" in vi)
        assertTrue("vê tê vê=vtv" in vi)                               // said letter by letter, handed back written
        assertTrue("vtv" in vi)
        assertTrue("hát tê vê phim=htv phim" in vi)                    // "htv (?:the thao|phim|...)" is each name
        assertTrue("du túp" in vi)                                     // the app list's own spellings
        assertTrue("smarttube" in vi && "phim tối" in vi)             // this TV's apps and scenes
        assertTrue("bảy" in vi)
        assertFalse(vi.any { it.contains('\\') || it.contains('?') || it.contains('(') })
        assertTrue(p.spoken("fr").isEmpty())
    }
}
