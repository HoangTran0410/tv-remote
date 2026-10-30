package dev.tvremote.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFixTest {
    private val stock = "com.google.android.tv/com.google.android.tv.receiver.GlobalKeyReceiver"
    // what Google TV's global_keys.xml and the FPT box's mKeyMapping say
    private val mapped = KeyFix.byCode(listOf("KEYCODE_TV" to stock, "KEYCODE_TV_INPUT" to stock, "KEYCODE_GUIDE" to stock, "KEYCODE_BUTTON_3" to stock,
        "KEYCODE_BUTTON_2" to "com.example.facservicetest/.MyReceiver", "KEYCODE_0" to "com.hisense.bigfac/.Boot", "KEYCODE_PAIRING" to "com.android.tv.settings/.G"))

    @Test fun onlyKeysWeHaveSomethingForAreRead() {
        assertEquals(setOf(170, 178, 190), mapped.keys)               // GUIDE, 0, BUTTON_2 and PAIRING are not ours
    }

    @Test fun aKeyWhoseHandlerIsStillThereIsNeverTaken() {
        assertTrue(KeyFix.plan(mapped, { true }, { true }).isEmpty())
    }

    @Test fun deadHandlerGivesTheKeyBack() {
        val other = KeyFix.byCode(listOf("KEYCODE_TV_INPUT" to "x/y", "KEYCODE_TV" to stock))
        assertEquals(setOf(170), KeyFix.plan(other, { it == "x/y" }, { true }).keys)   // only the one whose receiver is gone
        val dead = KeyFix.plan(mapped, { false }, { it == "com.google.android.youtube.tv" })
        assertEquals(KeyFix.INPUT, dead[178]?.act); assertEquals(KeyFix.INPUT, dead[170]?.act)
        assertEquals(listOf("com.google.android.youtube.tv"), dead[190]?.pkgs)
    }

    @Test fun anAppKeyWithoutItsAppKeepsDoingNothingAsBefore() {
        val dead = KeyFix.plan(mapped, { false }, { false })
        assertNull(dead[190]); assertEquals(KeyFix.INPUT, dead[178]?.act)
    }

    @Test fun aKeyThatIsNotMappedIsNotTaken() {
        // Netflix 191 here goes to the focused app: nothing is dead about it
        assertFalse(191 in KeyFix.plan(mapped, { false }, { true }))
        assertTrue(KeyFix.plan(emptyMap(), { false }, { true }).isEmpty())
    }

    @Test fun numbersWorkAsWellAsNames() {
        assertEquals(mapOf(284 to "a/b"), KeyFix.byCode(listOf("284" to "a/b", null to "x/y", "KEYCODE_TV" to null)))
    }

    @Test fun theListKeepsTheOthers() {
        val s = KeyFix.SERVICE
        assertEquals(s, KeyFix.list(null, true)); assertEquals(s, KeyFix.list("null", true)); assertEquals("", KeyFix.list(s, false))
        assertEquals("a/b:c/d:$s", KeyFix.list("a/b:c/d", true))
        assertEquals("a/b:c/d:$s", KeyFix.list("a/b:$s:c/d", true))   // once, never twice
        assertEquals("a/b:c/d", KeyFix.list("a/b:$s:c/d", false))
        assertTrue(KeyFix.has("a/b:dev.tvremote.setup.keys/.KeyFixService")); assertFalse(KeyFix.has("a/b:dev.tvremote.setup.keys/.KeyCatch"))   // the system's short form
        assertEquals("a/b:$s", KeyFix.list("a/b:dev.tvremote.setup.keys/.KeyFixService", true))
        val catch = "dev.tvremote.setup.keys/dev.tvremote.setup.keys.KeyCatch"   // the add-on's, while the screen is off: never ours to drop
        assertEquals(catch, KeyFix.list("$catch:$s", false))
    }

    @Test fun anEntryOfTheVersionsBeforeTheMoveIsReplaced() {          // a TV updated from a TV Setup that kept the services inside it
        val old = "dev.tvremote.setup/dev.tvremote.setup.KeyFixService"
        assertTrue(KeyFix.hasStale("a/b:$old")); assertFalse(KeyFix.has("a/b:$old"))
        assertTrue(KeyFix.hasStale("dev.tvremote.setup/.KeyCatch")); assertFalse(KeyFix.hasStale("a/b:${KeyFix.SERVICE}"))
        assertEquals("a/b:${KeyFix.SERVICE}", KeyFix.list("a/b:$old", true))   // moved, not doubled
        assertEquals("a/b", KeyFix.list("a/b:$old:dev.tvremote.setup/dev.tvremote.setup.KeyCatch", false))   // and gone when no longer needed
    }

    @Test fun theAddOnIsAskedAboutEveryKeyTheAppMayTake() {
        val asked = dev.tvremote.setup.keys.KeysProtocol.INTEREST
        assertTrue(asked.containsAll(KeyFix.RULES.keys)); assertTrue(asked.containsAll(KeyFix.COLOUR)); assertTrue(asked.containsAll(HoldKeys.KEYS)); assertTrue(asked.containsAll(MicVoice.KEYS)); assertTrue(asked.containsAll(KeyFix.DEVICE_KEYS.map { it.code }))
        assertTrue(HomeKey.CODE in asked); assertTrue(asked.containsAll(HomeKey.DIGITS.toList()))
    }

    @Test fun homeGoesToThePickedLauncherOnlyWhenOneIsPicked() {
        assertNull(KeyFix.plan(emptyMap(), { true }, { true })[HomeKey.CODE])   // every other TV: Home is never touched
        val p = KeyFix.plan(emptyMap(), { true }, { true }, home = "dev.tvremote.home")
        assertEquals(KeyFix.LAUNCHER, p[HomeKey.CODE]?.act); assertEquals(listOf("dev.tvremote.home"), p[HomeKey.CODE]?.pkgs)
        assertFalse(p[HomeKey.CODE]!!.mine)                           // no toast: Home opening the home screen is no surprise
    }

    @Test fun onlyThePersonsOwnKeysSayWhatTheyOpened() {
        val p = KeyFix.plan(mapped, { false }, { true }, user = mapOf(183 to "com.google.android.youtube.tv"))
        assertTrue(p[183]!!.mine); assertFalse(p[190]!!.mine)          // red given an app: said; the dead YouTube button: not
        assertFalse(HomeKey.say(HomeKey.TV_HOME)); assertTrue(HomeKey.say("vn.vtv.vtvgo")); assertTrue(HomeKey.say(""))
    }

    @Test fun xiaomiMooq0InputButtonIsMediaRecordAndOpensInputsOnlyThere() {
        assertEquals(KeyFix.INPUT, KeyFix.forDevice("MiTV-MOOQ0", "croods")[130]?.act)
        assertEquals(KeyFix.INPUT, KeyFix.forDevice("mitv-mooq0", null)[130]?.act)
        assertEquals(KeyFix.INPUT, KeyFix.forDevice(null, "croods")[130]?.act)
        assertTrue(KeyFix.forDevice("BRAVIA 4K", "BRAVIA_ATV3")[130] == null); assertTrue(KeyFix.forDevice(null, null).isEmpty())
        val p = KeyFix.plan(emptyMap(), { true }, { true }, device = KeyFix.forDevice("MiTV-MOOQ0", "croods"))
        assertEquals(setOf(130), p.keys); assertEquals(KeyFix.INPUT, KeyFix.decide(p, 130)?.act)
        assertTrue(KeyFix.plan(emptyMap(), { true }, { true }, device = KeyFix.forDevice("Other", "x")).isEmpty())
    }

    @Test fun noAccessibilityServiceInThisApp() {                      // Play Protect blocks one from a browser or Downloader: they live in app/tvkeys
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("BIND_ACCESSIBILITY_SERVICE")); assertFalse(manifest.contains("android.accessibilityservice"))
    }

    @Test fun theAddOnIsPackedInTheAppAndSignedAlike() {
        val dir = System.getProperty("tvkeys.dir")
        val apk = java.io.File(dir, "debug/tvkeys.apk")
        assertTrue("packKeysDebug did not put the add-on in assets: $apk", apk.length() > 2000)
        java.util.zip.ZipFile(apk).use { z ->
            assertTrue(z.getEntry("classes.dex") != null && z.getEntry("AndroidManifest.xml") != null)
            assertTrue("signed (a signature permission only trusts the same key)", z.entries().asSequence().any { it.name.startsWith("META-INF/") && it.name.endsWith(".RSA") })
        }
        val gradle = java.io.File("build.gradle.kts").readText()
        assertTrue(gradle.contains("../tvkeys/src/protocol/kotlin") && gradle.contains("packKeys"))
    }
    @Test fun theAppsKeyOutsideTheTableIsDeadOnlyWhenNobodyAnswersIt() {   // a BRAVIA on Android 9 with tvlauncher off
        val sony = KeyFix.withAllApps(emptyMap(), answered = false)
        assertEquals(KeyFix.HOME, KeyFix.plan(sony, { true }, { true })[284]?.act)
        assertTrue(284 in KeyFix.free(sony) { true })
        assertTrue(KeyFix.plan(KeyFix.withAllApps(emptyMap(), answered = true), { true }, { true }).isEmpty())   // tvlauncher on: its apps view
        assertEquals(mapOf(284 to "a/b"), KeyFix.withAllApps(mapOf(284 to "a/b"), answered = false))   // in the table: the table decides
    }

    // ---- TV Home's own key → app map -------------------------------------------------------------------------------

    @Test fun colourKeysAreAlwaysFreeAndDeadOnesOnlyWhileDead() {
        assertEquals(setOf(183, 184, 185, 186), KeyFix.allowed(emptyMap(), { true }))
        assertEquals(setOf(183, 184, 185, 186, 170, 178, 190), KeyFix.allowed(mapped, { false }))   // the three dead ones join
        assertEquals(KeyFix.COLOUR + 190, KeyFix.allowed(mapped, { true }))                            // a key that works is never offered, but for an app button
        assertFalse((7..16).any { it in KeyFix.allowed(mapped, { false }) })                           // numbers are TV Home's alone
    }

    @Test fun anAppOnAColourKeyIsTakenWithNoDeadKey() {
        val p = KeyFix.plan(emptyMap(), { true }, { it == "a.b" }, mapOf(183 to "a.b", 184 to "gone.app"))
        assertEquals(setOf(183), p.keys); assertEquals(listOf("a.b"), p[183]?.pkgs)   // 184's app is not installed: the key keeps its way
    }

    @Test fun theControlCenterOnAKeyIsAnActionNotAnApp() {
        val p = KeyFix.plan(emptyMap(), { true }, { it == dev.tvremote.home.Target.CONTROL }, mapOf(183 to dev.tvremote.home.Target.CONTROL))
        assertEquals(KeyFix.CONTROL, p[183]?.act); assertTrue(p[183]!!.mine)
        val none = KeyFix.plan(emptyMap(), { true }, { false }, mapOf(183 to dev.tvremote.home.Target.CONTROL))   // TV Home not installed: the key keeps its way
        assertTrue(none.isEmpty())
    }

    @Test fun aWorkingKeyIsNeverTakenForAnApp() {
        // 170 still has its handler, 7 is a number: both named by the person, neither taken
        val p = KeyFix.plan(mapped, { true }, { true }, mapOf(170 to "a.b", 7 to "a.b", 999 to "a.b"))
        assertTrue(p.isEmpty())
    }

    @Test fun anAppButtonThatWorksGoesToThePersonsApp() {             // a Chromecast: YouTube still answers its button, the person wants VLC there
        val p = KeyFix.plan(mapped, { true }, { true }, mapOf(190 to "org.videolan.vlc", 170 to "a.b"))
        assertEquals(setOf(190), p.keys); assertEquals(listOf("org.videolan.vlc"), p[190]?.pkgs)   // TV (170) still works: not taken
        assertTrue(KeyFix.plan(mapped, { true }, { true }).isEmpty())                              // nothing given: YouTube keeps it
        assertFalse(191 in KeyFix.offered(mapped, { true }))                                       // Netflix is no global key here: not offered
    }

    @Test fun aDeadKeyGivesWayToThePersonsApp() {
        val p = KeyFix.plan(mapped, { false }, { true }, mapOf(190 to "my.app"))
        assertEquals(listOf("my.app"), p[190]?.pkgs); assertEquals(KeyFix.INPUT, p[178]?.act)         // 190 was YouTube; the others stay as before
    }

    @Test fun homeConfigKnowsTheSameDeadKeys() {
        assertEquals(KeyFix.RULES.keys, dev.tvremote.home.HomeConfig.KEY_DEAD.toSet())
        assertEquals(KeyFix.COLOUR, dev.tvremote.home.HomeConfig.KEY_COLOURS.toSet())
    }

    // ---- a key a client sends: the same decision as a real press ---------------------------------------------------

    @Test fun aSentKeyGetsTheDecisionOfARealPress() {
        val plan = KeyFix.plan(mapped, { false }, { true }, mapOf(183 to "dev.tvremote.channels", 190 to "org.videolan.vlc"))
        assertEquals(listOf("dev.tvremote.channels"), KeyFix.decide(plan, 183)?.pkgs)      // a colour key TV Home gave an app
        assertEquals(listOf("org.videolan.vlc"), KeyFix.decide(plan, 190)?.pkgs)           // the person's choice over the dead key's old job
        assertEquals(KeyFix.INPUT, KeyFix.decide(plan, 178)?.act)                          // a dead key: its rule
        assertNull(KeyFix.decide(plan, 184))                                               // green, nothing given: the plain key
        assertNull(KeyFix.decide(emptyMap(), 190))
    }
}
