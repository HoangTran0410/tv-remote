package dev.tvremote.core

import dev.tvremote.core.scenes.VoicePhrases
import dev.tvremote.update.SignedList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The list of spoken phrases: the packed copy is the site's, signed; a tampered or older copy is never used; the order is fetched, kept, packed. */
class VoicePhrasesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val packed = File("web/voice/phrases.json").readBytes()
    private val sig = File("web/voice/phrases.json.sig").readText()
    private fun withRev(rev: Int) = String(packed, Charsets.UTF_8).replace(Regex("\"rev\":\\s*\\d+"), "\"rev\": $rev").toByteArray(Charsets.UTF_8)

    @Test fun packedCopyIsTheSitesAndSigned() {
        assertArrayEquals("run dev/sign/sign_voice.py", File("../../docs/voice/phrases.json").readBytes(), packed)
        assertEquals(File("../../docs/voice/phrases.json.sig").readText(), sig)
        assertTrue(SignedList.verify(packed, sig))
        assertNotNull(SignedList.packed({ File("web/$it").inputStream() }, VoicePhrases.NAME))
    }

    @Test fun aTamperedListIsIgnored() {
        val bad = packed.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(SignedList.verify(bad, sig))
        assertNull(SignedList.packed({ n -> if (n.endsWith(".sig")) sig.byteInputStream() else bad.inputStream() }, VoicePhrases.NAME))
        val f = File(tmp.root, "voice-phrases.json")
        SignedList.keep(f, packed, sig)
        assertNotNull(SignedList.kept(f))
        File(f.path + ".ok").delete()                                  // the quick check is skipped once the file changed
        f.writeBytes(bad)
        assertNull("a kept copy changed after it was signed", SignedList.kept(f))
    }

    @Test fun fetchedThenKeptThenPacked() {
        val newer = withRev(50); val keptOld = withRev(40)    // above the packed rev, whatever it is now
        assertArrayEquals(newer, VoicePhrases.choose(newer, keptOld, packed)!!.first)
        assertArrayEquals(keptOld, VoicePhrases.choose(null, keptOld, packed)!!.first)
        assertArrayEquals(packed, VoicePhrases.choose(null, null, packed)!!.first)
        assertArrayEquals("a fetched list that is not a list falls to the kept one", keptOld, VoicePhrases.choose("<html>".toByteArray(), keptOld, packed)!!.first)
    }

    @Test fun aListOlderThanThePackedOneIsNotUsed() {
        val r = JSONRev.of(packed)
        val older = withRev(r - 1)
        assertArrayEquals(packed, VoicePhrases.choose(older, older, packed)!!.first)
        assertNull(VoicePhrases.choose(null, null, null))
    }

    private object JSONRev { fun of(b: ByteArray) = org.json.JSONObject(String(b, Charsets.UTF_8)).getInt("rev") }
}
