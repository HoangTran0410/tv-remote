package dev.tvremote.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationKeysTest {
    @Test fun theFiveKeysAndTheirCodes() {
        assertEquals(listOf("vol_down" to 25, "play_pause" to 85, "vol_up" to 24, "mute" to 164, "power" to 26),
            NotificationKeys.ALL.map { it.id to it.code })
        assertEquals(NotificationKeys.POWER, NotificationKeys.of("power")!!.code)
        assertNull(NotificationKeys.of("home"))
        assertNull(NotificationKeys.of(null))
    }

    @Test fun androidShowsFiveAtMostAndThreeFolded() {
        assertTrue(NotificationKeys.ALL.size <= 5)                    // MediaStyle draws no more
        assertEquals(3, NotificationKeys.COMPACT.size)
        assertTrue(NotificationKeys.COMPACT.all { it in NotificationKeys.ALL.indices })
        assertEquals(listOf("vol_down", "play_pause", "vol_up"), NotificationKeys.COMPACT.map { NotificationKeys.ALL[it].id })
    }

    @Test fun everyKeyHasWordsInEveryLanguage() {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "app/shared/i18n") }.first { it.isDirectory }
        val langs = Regex("\"(\\w+)\"\\s*:").findAll(java.io.File(dir, "langs.json").readText()).map { it.groupValues[1] }.toList()
        val ids = NotificationKeys.ALL.map { it.words } + listOf("head", "switch", "note", "channel", "allow").map { "notification_remote.$it" }
        for (l in langs) {
            val words = java.io.File(dir, "$l.json").readText()
            for (id in ids) assertTrue("$l: $id", words.contains("\"$id\""))
        }
    }

    @Test fun theLinkClosesSoonTheNotificationAfterAFilm() {
        val t = 1_000_000L
        assertFalse(NotificationKeys.linkIdle(t, t + NotificationKeys.LINK_IDLE_MS - 1))
        assertTrue(NotificationKeys.linkIdle(t, t + NotificationKeys.LINK_IDLE_MS))
        assertFalse(NotificationKeys.stopIdle(t, t + NotificationKeys.STOP_IDLE_MS - 1))
        assertTrue(NotificationKeys.stopIdle(t, t + NotificationKeys.STOP_IDLE_MS))
        assertEquals(2 * 60_000L, NotificationKeys.LINK_IDLE_MS)
        assertEquals(120 * 60_000L, NotificationKeys.STOP_IDLE_MS)
    }

    @Test fun runsOnlyWhenOnWithATv() {
        assertFalse(NotificationKeys.wanted(false, "10.0.0.2"))
        assertFalse(NotificationKeys.wanted(true, ""))
        assertFalse(NotificationKeys.wanted(true, ","))
        assertTrue(NotificationKeys.wanted(true, "10.0.0.2,10.0.0.3"))
    }
}
