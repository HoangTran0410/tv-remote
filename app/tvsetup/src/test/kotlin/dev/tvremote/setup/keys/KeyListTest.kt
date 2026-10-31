package dev.tvremote.setup.keys

import dev.tvremote.update.SignedList
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The site's list of one TV's odd buttons: signed, read as KeyFix rules, and never able to take a navigation / volume / power key. */
class KeyListTest {
    private val dir = File("../../docs/setup")

    @Test fun theSitesListIsSignedAndRead() {
        val list = File(dir, "keys.json").readBytes()
        assertTrue(SignedList.verify(list, File(dir, "keys.json.sig").readText()))   // run dev/sign/sign_keys.py after an edit
        val all = KeyList.parse(String(list, Charsets.UTF_8))!!
        assertTrue(all.keys.isNotEmpty())
        assertEquals(KeyFix.INPUT, KeyFix.forDevice("MiTV-MOOQ0", "croods", all.keys)[130]?.act)
    }

    @Test fun anEntryCannotTakeTheRemoteAway() {
        val bad = """{"v":1,"rev":2,"keys":[{"model":"X","code":20,"act":"home"},{"code":131,"act":"input"},{"model":"X","code":132,"act":"run"},
            {"model":"X","code":133,"act":"app"},{"model":"X","code":134,"act":"app","pkgs":["a.b"]}]}"""
        val keys = KeyList.parse(bad)!!.keys
        assertEquals(listOf(134), keys.map { it.code })              // D-pad down, no model, unknown act, app with no package: all dropped
        assertTrue(KeyFix.forDevice("Y", "y", keys).isEmpty())       // another TV never gets it
        assertNull(KeyList.parse("""{"v":2,"keys":[]}"""))
    }

    @Test fun theSignScriptRefusesTheSameKeys() {
        val py = File("../../dev/sign/sign_keys.py").readText()
        val never = Regex("NEVER = \\{([\\d, ]+)\\}").find(py)!!.groupValues[1].split(",").map { it.trim().toInt() }.toSet()
        assertEquals(KeyList.NEVER, never)
    }
}
