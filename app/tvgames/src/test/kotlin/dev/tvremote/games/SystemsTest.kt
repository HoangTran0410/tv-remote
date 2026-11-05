package dev.tvremote.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The systems table: nothing twice, every button has a key, the thumbnail repos exist by name. */
class SystemsTest {
    private val all = Systems.ALL

    @Test fun idsAndExtensionsAreUnique() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val exts = all.flatMap { it.exts }
        assertEquals(exts.toString(), exts.size, exts.toSet().size)
        assertTrue("bin is too common an extension", "bin" !in exts)
    }

    @Test fun newSystemsAreThere() {
        for (id in listOf("gb", "gbc", "md", "sms", "gg", "pce")) assertTrue(id, all.any { it.id == id })
        assertEquals(listOf("gbc"), all.first { it.id == "gbc" }.exts)
        assertEquals(setOf("gb", "dmg"), all.first { it.id == "gb" }.exts.toSet())
        assertEquals("md", all.first { it.id == "md" }.pad)
    }

    @Test fun everyButtonHasAKey() {
        for (s in all) for (b in s.buttons) assertTrue("${s.id} $b", GameServer.KEYS.containsKey(b) || PadMap.phone(s.pad, b) != null)
    }

    @Test fun megaDriveHasSixFaceButtons() {
        assertEquals(listOf("A", "B", "C", "X", "Y", "Z", "START", "SELECT"), all.first { it.id == "md" }.buttons)
        assertEquals(listOf("A", "B", "START"), all.first { it.id == "sms" }.buttons)
    }

    @Test fun everySystemHasARepoAndLabel() {
        for (s in all) { assertTrue(s.id, s.repo.isNotEmpty() && s.label.isNotEmpty()); assertTrue(s.repo, s.repo.contains("_-_")) }
    }

    @Test fun handheldsAreOnePlayer() {
        for (id in listOf("gba", "gb", "gbc", "gg")) assertEquals(id, 1, Systems.maxPlayers(id))
        for (id in listOf("nes", "md", "sms", "pce")) assertEquals(id, 2, Systems.maxPlayers(id))
    }
}
