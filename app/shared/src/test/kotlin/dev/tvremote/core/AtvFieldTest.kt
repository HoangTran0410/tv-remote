package dev.tvremote.core

import dev.tvremote.core.remote.Atv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV's text field over the Android TV Remote protocol (Atv.ime / type): messages are fed in as the TV would send them and what
 * would go on the wire is read back. Field numbers: tronikos/androidtvremote2's remotemessage.proto.
 */
class AtvFieldTest {
    private val sent = ArrayList<ByteArray>()
    private var now = 1_000_000L
    private val atv = Atv(java.nio.file.Files.createTempDirectory("atv").toFile(), "10.0.0.2").also { a ->
        a.tap = { sent.add(it) }
        a.clock = { now }
    }

    private fun feed(b: ByteArray) = atv.ime(Atv.Pb.read(b))
    private fun status(counter: Long, text: String, label: String = "") =
        Atv.Pb().int(1, counter).str(2, text).int(3, text.length.toLong()).int(4, text.length.toLong()).str(6, label).bytes()
    private fun show(counter: Long, text: String, label: String = "") = feed(Atv.Pb().msg(22, Atv.Pb().msg(2, status(counter, text, label)).bytes()).bytes())
    private fun inject(pkg: String, ime: Long, st: ByteArray? = null) =
        feed(Atv.Pb().msg(20, Atv.Pb().msg(1, Atv.Pb().int(1, ime).str(12, pkg).bytes()).also { if (st != null) it.msg(2, st) }.bytes()).bytes())
    private fun batch(ime: Long, field: Long) = feed(Atv.Pb().msg(21, Atv.Pb().int(1, ime).int(2, field).bytes()).bytes())

    /** (ime counter, field counter, value, caret) of a text message that was sent, or null for anything else. */
    private fun edit(b: ByteArray): List<Any>? {
        val m = Atv.Pb.read(b)
        val e = m[21]?.firstOrNull() as? ByteArray ?: return null
        val p = Atv.Pb.read(e)
        val info = Atv.Pb.read(Atv.Pb.read(p[3]!![0] as ByteArray)[2]!![0] as ByteArray)
        return listOf(p[1]?.firstOrNull() ?: 0L, p[2]?.firstOrNull() ?: 0L, ((info[3]?.firstOrNull() as? ByteArray) ?: ByteArray(0)).toString(Charsets.UTF_8), info[1]?.firstOrNull() ?: 0L)
    }
    private fun keyOf(b: ByteArray): Long? = (Atv.Pb.read(b)[10]?.firstOrNull() as? ByteArray)?.let { Atv.Pb.read(it)[1]?.firstOrNull() as? Long }
    private fun texts() = sent.mapNotNull { edit(it) }.map { it[2] as String }
    private fun keys() = sent.mapNotNull { keyOf(it) }

    @Test fun aFieldShownWithItsTextAndLabel() {
        assertFalse(atv.hasField)
        show(7, "hello", "Search")
        assertTrue(atv.hasField)
        assertEquals("hello", atv.currentField!!.text)
        assertEquals("Search", atv.currentField!!.label)
    }

    @Test fun anEditIsBackspacesThenAnInsertWithTheTvsCounters() {
        inject("com.app", 5, status(9, "ab"))
        show(9, "ab")
        atv.type(1, "xyz", false)                                   // "ab" minus one, plus "xyz"
        val e = sent.mapNotNull { edit(it) }.single()
        assertEquals(listOf<Any>(5L, 9L, "xyz", 3L), e)             // the TV inserts at its caret (a BRAVIA, measured)
        assertEquals(listOf(67L), keys())
        assertTrue(sent.indexOfFirst { keyOf(it) != null } < sent.indexOfFirst { edit(it) != null })
        assertEquals("axyz", atv.currentField!!.text)
    }

    @Test fun accentsAndLettersGoTheSameWay() {
        show(1, "")
        atv.type(0, "phở", false)
        atv.type(0, " bò", false)
        assertEquals(listOf("phở", " bò"), texts())
        assertEquals("phở bò", atv.currentField!!.text)
        assertTrue(keys().isEmpty())
    }

    @Test fun deletingIsBackspaceKeys() {
        show(1, "a")
        atv.type(1, "", false)
        assertTrue(texts().isEmpty())
        assertEquals(listOf(67L), keys())
        assertEquals("", atv.currentField!!.text)
    }

    @Test fun deletingMoreThanThereIsStopsAtEmpty() {
        show(1, "ab")
        atv.type(9, "", false)
        assertEquals(9, keys().size)                                // all sent: the TV stops at empty, and may hold more than we know
        assertEquals("", atv.currentField!!.text)
    }

    @Test fun anEmojiIsOneCharacterToDelete() {
        show(1, "a😀")
        atv.type(1, "", false)
        assertEquals(listOf(67L), keys())
        assertEquals("a", atv.currentField!!.text)
    }

    @Test fun enterSubmitsAndTheFieldIsForgotten() {
        show(1, "tv")
        atv.type(0, "", true)
        assertEquals(listOf(66L), keys())
        assertTrue(texts().isEmpty())                               // nothing typed: nothing inserted
        assertFalse(atv.hasField)
    }

    @Test fun typingThenEnterReplacesFirst() {
        show(1, "")
        atv.type(0, "abc", true)
        assertEquals(listOf("abc"), texts())
        assertEquals(listOf(66L), keys())
        assertTrue(sent.indexOfFirst { edit(it) != null } < sent.indexOfFirst { keyOf(it) != null })
    }

    @Test fun noFieldFallsBackToKeys() {
        atv.type(2, "ab", true)
        assertEquals(listOf(67L, 67L, 29L, 30L, 66L), keys())
        assertTrue(texts().isEmpty())
    }

    @Test fun noFieldAccentsStillGoThroughTheKeyboardService() {
        atv.type(0, "ở", false)
        assertEquals(listOf("ở"), texts())
    }

    @Test fun anAppChangeForgetsTheField() {
        inject("com.a", 1)
        show(1, "x")
        assertTrue(atv.hasField)
        inject("com.a", 1)                                          // the same app again: nothing changes
        assertTrue(atv.hasField)
        inject("com.b", 2)
        assertFalse(atv.hasField)
    }

    @Test fun backAndHomeForgetTheField() {
        show(1, "x"); atv.key(4, Atv.SHORT); assertFalse(atv.hasField)
        show(1, "x"); atv.key(3, Atv.SHORT); assertFalse(atv.hasField)
        show(1, "x"); atv.key(19, Atv.SHORT); assertTrue(atv.hasField)   // an arrow does not
    }

    @Test fun theTvsEchoOfOurEditDoesNotUndoNewerTyping() {
        show(1, "")
        atv.type(0, "a", false)
        atv.type(0, "b", false)                                     // "ab"
        show(1, "a")                                                // the echo of the first edit, late
        assertEquals("ab", atv.currentField!!.text)
        now += 5000
        show(1, "ab!")                                              // the person typed on the TV itself, later
        assertEquals("ab!", atv.currentField!!.text)
    }

    @Test fun theTvChangingItsTextTellsTheScreen() {
        var calls = 0
        atv.onField = { calls++ }
        show(1, "a")
        show(1, "a")                                                // the same: no new call
        now += 5000
        show(1, "b")
        assertEquals(2, calls)
        assertEquals("b", atv.currentField!!.text)
    }

    @Test fun aTvThatOnlySendsBatchEditHasAFieldWithNoText() {
        batch(3, 4)
        assertTrue(atv.hasField)
        assertEquals("", atv.currentField!!.text)
        atv.type(0, "hi", false)
        assertEquals(listOf<Any>(3L, 4L, "hi", 2L), sent.mapNotNull { edit(it) }.single())
    }

    @Test fun theKeyInjectCounterBeatsTheBatchEditOne() {
        inject("com.a", 8)
        batch(3, 4)
        atv.type(0, "x", false)
        assertEquals(listOf<Any>(8L, 4L, "x", 1L), sent.mapNotNull { edit(it) }.single())
    }

    @Test fun aBatchEditsZeroFieldCounterDoesNotBeatTheStatusOne() {
        inject("com.android.vending", 1, status(55, "", "Search"))   // a BRAVIA, measured: then {1:1, 2:0}
        batch(1, 0)
        atv.type(0, "kodi", false)
        assertEquals(listOf<Any>(1L, 55L, "kodi", 4L), sent.mapNotNull { edit(it) }.single())
    }

    @Test fun theFocusLeavingTheFieldForgetsIt() {
        inject("com.android.vending", 1, status(58, "", "Search"))
        batch(1, 0)
        assertTrue(atv.hasField)
        batch(0, 0)                                                 // a BRAVIA, measured, as the Play Store moved to its result
        assertFalse(atv.hasField)
        batch(0, 0)
        assertFalse(atv.hasField)
    }

    @Test fun noEnterOnceTheFocusLeft() {
        show(1, "")
        atv.tap = { b -> if (keyOf(b) == null) { sent.add(b); batch(0, 0) } else sent.add(b) }   // the app leaves the field on the text
        atv.type(0, "vlc", true)
        assertEquals(listOf("vlc"), texts())
        assertTrue(keys().isEmpty())                                // "Install" would have taken it
        assertFalse(atv.hasField)
    }

    @Test fun replaceIsBackspacesForAllThenTheText() {
        show(1, "ab😀")
        atv.replace("x")
        assertEquals(listOf(67L, 67L, 67L), keys())
        assertEquals(listOf("x"), texts())
        assertEquals("x", atv.currentField!!.text)
    }

    @Test fun aMissingFieldCounterIsKeptNotZeroed() {
        show(6, "a")
        show(0, "b")                                                // counter 0 = not said
        now += 5000
        atv.type(0, "c", false)
        assertEquals(6L, sent.mapNotNull { edit(it) }.single()[1])
    }

    @Test fun nothingTypedNothingSent() {
        show(1, "a")
        atv.type(0, "", false)
        atv.text("")
        assertTrue(sent.isEmpty())
        assertNull(atv.currentField?.takeIf { it.text != "a" })
    }
}
