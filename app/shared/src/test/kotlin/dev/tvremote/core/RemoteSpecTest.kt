package dev.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.scenes.RemoteSpec
import dev.tvremote.core.scenes.Scenes

/**
 * web/remote.json is what both remotes draw from. Same checks as tests/remote.test.js for the file itself (the phone's handler map is
 * checked by the Phone app's RemoteSpecTest, the page's by remote.test.js).
 */
class RemoteSpecTest {
    private val text = File("web/remote.json").readText()
    private val phone = RemoteSpec.parse(text, "phone")
    private val web = RemoteSpec.parse(text, "web")
    private fun all(s: RemoteSpec.Spec) = s.rows.values.flatten().flatten() + s.menus.values.flatten() + s.simple.flatten()

    @Test fun idsAreUniqueAndEveryEntryDoesOneThing() {
        val ids = all(phone).map { it.id } + all(web).map { it.id }.filter { id -> all(phone).none { it.id == id } }   // an entry both sides show counts once
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        for (e in all(phone) + all(web)) assertEquals(e.id, 1, listOfNotNull(e.key, e.action, e.expand).size)
    }

    @Test fun everyEntryHasWordsInBothLanguages() {                   // "t" / "sub": ids in i18n/<lang>.json
        for (e in all(phone) + all(web)) if (e.expand == null) {
            assertTrue(e.id, e.icon.isNotBlank() && e.t.isNotBlank())
            for (id in listOf(e.t, e.sub).filter { it.isNotEmpty() }) for (l in listOf("vi", "en"))
                assertTrue("${e.id}: $id has no $l words", Words.get(l, id) != id)
        }
    }

    @Test fun everyKeyIsAKnownKey() {
        for (e in all(phone) + all(web)) (e.key ?: e.arg.takeIf { e.action == "key" })?.let { assertTrue("$it is not in Scenes.KEYS", it in Scenes.KEYS) }
    }

    @Test fun theKeysKeepTheirCodes() {                                  // what the phone sent before the keys moved into the file
        val codes = mapOf("SETTINGS" to 176, "INFO" to 165, "GUIDE" to 172, "LIVE" to 170, "SUBTITLE" to 175, "STOP" to 86, "BACK" to 4, "HOME" to 3,
            "MENU" to 82, "MUTE" to 164, "PLAY_PAUSE" to 85, "PREV" to 88, "NEXT" to 87, "REWIND" to 89, "FFWD" to 90,
            "RED" to 183, "GREEN" to 184, "YELLOW" to 185, "BLUE" to 186,       // Tools > TV keys: the codes TV Setup's key filter knows
            "YOUTUBE" to 190, "NETFLIX" to 191, "PRIME" to 195, "ALL_APPS" to 284,
            "POWER" to 26, "VOL_UP" to 24, "VOL_DOWN" to 25, "CH_UP" to 166, "CH_DOWN" to 167)   // the Simple remote's, the hand-laid keys' codes
        assertEquals(codes, all(phone).mapNotNull { e -> (e.key ?: e.arg.takeIf { e.action == "key" })?.let { it to Scenes.KEYS[it] } }.toMap())
    }

    @Test fun anLgOrSamsungTvGetsOnlyWhatItCanDo() {
        val all = Remote.Kind.ALL.flatMap { Remote.Kind.caps(it) }.toSet()
        for (e in all(phone) + all(web)) for (n in e.needs) assertTrue("${e.id} needs $n, which no kind of TV has", n in all)
        val lg = Remote.Kind.caps(Remote.Kind.LG)
        val open = phone.menus["open"]!!.filter { it.can(lg) }.map { it.id }
        assertTrue("open-apps" in open && "open-link" in open)
        assertFalse(open.any { it == "open-get" || it == "open-send" })   // the Play Store and files need ADB
        assertTrue(phone.menus["open"]!!.single { it.id == "open-apps" }.can(Remote.Kind.caps(Remote.Kind.ANDROID)))   // over ADB there   // Android TVs list their apps on the Apps tab
    }

    @Test fun eachSideShowsItsOwnWordsAndItsOwnEntries() {
        fun find(s: RemoteSpec.Spec, id: String) = all(s).single { it.id == id }
        assertEquals("Live TV", Words.get("en", find(phone, "tools-tv").t))
        assertTrue(all(phone).any { it.id == "air" } && !all(web).any { it.id == "air" })   // Air mouse is the phone's; the web has TV Home in its slot
        assertTrue(all(web).any { it.id == "home-web" } && !all(phone).any { it.id == "home-web" })
        assertTrue(all(phone).any { it.id == "open-cast" })
        assertFalse(all(web).any { it.id == "open-cast" })
    }

    @Test fun theSimpleRemoteIsTheBigKeysOnlyOnBothSides() {           // remote.json "simple": the same rows for the phone and the page
        for (s in listOf(phone, web)) {
            val keys = s.simple.flatten().mapNotNull { it.key }
            assertEquals(listOf("POWER", "BACK", "HOME", "VOL_DOWN", "MUTE", "VOL_UP", "CH_DOWN", "CH_UP"), keys)
            assertEquals(listOf("pad"), s.simple.flatten().mapNotNull { it.expand })   // the arrows and OK: the side's own D-pad
            assertEquals(listOf("voice"), s.simple.flatten().mapNotNull { it.action })
        }
        assertEquals(phone.simple.map { r -> r.map { it.id } }, web.simple.map { r -> r.map { it.id } })
        assertTrue(phone.menus["tools"]!!.any { it.action == "simple" } && web.menus["tools"]!!.any { it.action == "simple" })   // how it is turned on
    }

    @Test fun vidaaCallsSettingsMenuOthersUnchanged() {                // issue #8: VIDAA's KEY_MENU is its settings screen
        val s = all(phone).first { it.id == "settings" }
        assertEquals("rkey.menu", s.forKind(Remote.Kind.VIDAA).t)
        assertEquals("menu", s.forKind(Remote.Kind.VIDAA).icon)
        assertEquals("SETTINGS", s.forKind(Remote.Kind.VIDAA).key)
        for (k in listOf(Remote.Kind.ANDROID, Remote.Kind.LG, Remote.Kind.SAMSUNG, Remote.Kind.ROKU)) {
            assertEquals("rkey.settings", s.forKind(k).t); assertEquals("settings", s.forKind(k).icon)
        }
        for (e in all(phone) + all(web)) for (k in e.kinds.keys) assertTrue(e.id + ": unknown kind " + k, k in Remote.Kind.ALL)
    }
}
