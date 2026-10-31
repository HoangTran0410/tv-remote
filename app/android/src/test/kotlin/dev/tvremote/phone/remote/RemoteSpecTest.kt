package dev.tvremote.phone.remote

import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.menu
import dev.tvremote.phone.ui.t

import dev.tvremote.core.scenes.RemoteSpec
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every action remote.json names for the phone has a handler in PhoneRemote.kt (tests/remote.test.js does the same for the page), and every condition / list a menu names is one the menus supply. */
class RemoteSpecTest {
    private val spec = RemoteSpec.parse(File("../shared/web/remote.json").readText(), "phone")
    private val entries = spec.rows.values.flatten().flatten() + spec.menus.values.flatten()

    @Test fun everyActionHasAPhoneHandler() {
        val missing = entries.mapNotNull { it.action }.filter { it !in phoneActions }
        assertTrue("remote.json names actions the phone cannot do: $missing", missing.isEmpty())
    }

    @Test fun everyHookIsForARealAction() {
        assertTrue(phoneButtonHooks.keys.all { it in phoneActions })
    }

    @Test fun everyConditionAndListIsOneTheMenusSupply() {          // timerMenu / scenesMenu in Automation.kt, quickOpen in Media.kt
        assertTrue(entries.mapNotNull { it.whenCond }.all { it in setOf("timerOn", "ir", "native") })   // "ir", "native": PhoneRemote.kt "menu"
        assertTrue(entries.mapNotNull { it.expand }.all { it in setOf("lengths", "lengthsMore", "scenes", "tvapps") })
    }

    @Test fun theRowsAndMenusTheScreenAsksFor() {                    // RemoteScreen.kt, Media.kt, Automation.kt
        for (r in listOf("more", "nav", "media")) assertTrue(r, spec.rows.containsKey(r))
        for (m in listOf("open", "timer", "scenes")) assertTrue(m, spec.menus.containsKey(m))
    }

    @Test fun vidaaSettingsKeyReadsMenu() {                          // greyRows applies forKind(kind()) to the drawn entries
        val e = spec.rows["nav"]!!.flatten().first { it.id == "settings" }
        assertTrue(e.forKind("vidaa").t == "rkey.menu" && e.forKind("vidaa").icon == "menu")
        assertTrue(e.forKind("android").t == "rkey.settings" && e.forKind("lg").icon == "settings")
    }
}
