package dev.tvremote.core

import dev.tvremote.core.ontv.Bloat
import dev.tvremote.core.ontv.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Optimize TV and its Undo (Setup.kt) on a made-up TV that keeps what the shell commands do: an app it must never turn off
 * (the TV stops working), a name that would run a second command, Undo missing one it turned off. Wrong here is a TV with no
 * picture or no home screen in someone's living room.
 */
class SetupTest {
    private class FakeTv(val tv: Boolean = true, vararg apps: String) {
        val installed = apps.toMutableSet()
        val disabled = mutableSetOf<String>()
        /** Who answers HOME and how high (higher wins); a package of a brand's own may stay on top whatever we do. */
        val homes = linkedMapOf(Setup.HOME_PKG to 0, Setup.LAUNCHERX to 2, "com.google.android.tvlauncher" to 2,
            "com.google.android.tungsten.setupwraith" to 1, "com.android.tv.settings" to -1000)
        var keyboard = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
        val keyboards = mutableListOf("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME")
        fun on(p: String) = p in installed && p !in disabled && "$p/.Home" !in disabled
        fun home(): String = homes.filterKeys { on(it) }.maxByOrNull { it.value }?.key ?: "android"
        val offList = mutableListOf<String>()
        val said = mutableListOf<String>()
        var anim = "1.0"
        var pid = "100"                                               // system_server's: another one = the TV restarted
        val refuse = mutableSetOf<String>()                           // pm says no (a protected package of the brand's)
        var downAt = ""                                               // turning this one off takes the TV down (and the link with it)
        var stayDown = false                                          // ...and it never comes back
        var restarts = true                                           // false: only the link dropped
        var lost = false
        val hangs = mutableSetOf<String>()                            // turning this one on holds pm past Setup.SLOW
        fun sh(raw: String): String {
            val cmd = raw.replace(dev.tvremote.core.ontv.TvUser.ID, "0")
            if (lost) throw IOException("adb connection lost")
            said += cmd
            if (cmd == "pm path android") return "package:/system/framework/framework-res.apk"   // the package service is up
            Regex("^pm path (\\S+)$").find(cmd)?.let { return if (it.groupValues[1] in installed) "package:/data/app/x.apk" else "" }
            if (cmd == "pidof system_server") return pid
            Regex("^pm disable-user --user 0 (\\S+)$").find(cmd)?.let {
                val p = it.groupValues[1]
                if (p in refuse) return "Error: java.lang.SecurityException: Cannot disable a protected package: $p"
                disabled += p
                if (p == downAt) { if (restarts) pid = "200"; lost = stayDown; throw IOException("adb connection lost") }
                return "Package $p new state: disabled-user"
            }
            Regex("^pm disable --user 0 (\\S+) 2>&1$").find(cmd)?.let { disabled += it.groupValues[1]; return "" }
            Regex("^ime set (\\S+)$").find(cmd)?.let { keyboard = it.groupValues[1]; return "" }
            Regex("^pm enable --user 0 (\\S+)$").find(cmd)?.let { disabled -= it.groupValues[1]; return "" }
            Regex("^if command -v timeout .* then timeout ${Setup.SLOW} pm enable --user 0 (\\S+) .*; fi$").find(cmd)?.let {
                if (it.groupValues[1] in hangs) return "tvr-slow"; disabled -= it.groupValues[1]; return "" }
            Regex("^printf '%s\\\\n' (.*) > ${Setup.OFF_LIST}$").find(cmd)?.let { offList.clear(); offList += it.groupValues[1].split(" ").map { w -> w.trim('\'') }; return "" }
            Regex("^grep -qx '([^']+)' ${Setup.OFF_LIST} 2>/dev/null \\|\\| echo '\\1' >> ${Setup.OFF_LIST}$").find(cmd)?.let { if (it.groupValues[1] !in offList) offList += it.groupValues[1]; return "" }
            return when {
                cmd == "pm has-feature android.software.leanback" -> tv.toString()
                cmd.startsWith("pm list packages -d") -> disabled.joinToString("\n") { "package:$it" }
                cmd == "pm list packages --user 0" -> installed.joinToString("\n") { "package:$it" }
                cmd.startsWith("pm list packages -e") -> (installed - disabled).joinToString("\n") { "package:$it" }
                cmd.startsWith("cmd package query-activities") && "HOME" in cmd ->
                    homes.keys.filter { on(it) }.joinToString("\n") { "priority=${homes[it]}\n$it/.Home" }.ifEmpty { "dev.fake.stub/.Home" }   // a TV that has query-activities always has a home
                cmd == "settings get secure default_input_method" -> keyboard
                cmd == "ime list -s" -> keyboards.joinToString("\n")
                cmd == "cat ${Setup.OFF_LIST} 2>/dev/null" -> offList.joinToString("\n")
                cmd == "rm -f ${Setup.OFF_LIST}" -> { offList.clear(); "" }
                cmd.startsWith("settings put global window_animation_scale 0") -> { anim = "0"; "" }
                cmd.startsWith("for k in ${Setup.ANIM}; do settings put global \$k 1.0") -> { anim = "1.0"; "" }
                cmd.contains("android.app.role.HOME") -> "priority=0\n${home()}/.Home"
                else -> ""
            }
        }
        fun setup() = Setup(null, null, "en", { sh(it) }) { }
    }

    private val musts = arrayOf("android", "com.android.systemui", "com.android.tv.settings", "com.google.android.gms", "com.google.android.tvlauncher",
        "com.google.android.katniss", "dev.tvremote.setup", "com.tailscale.ipn")

    /** The site's list adds and drops suggestions, and only adds to KEEP / FACTORY; a bad pattern leaves the whole list out. */
    @Test fun theSitesBloatListOnlyAddsToWhatIsKept() {
        val x = Bloat.parse("""{"v":1,"suggest":{"com.foo.ads":"Ads","bad name":"x"},"drop":["com.google.android.feedback"],"keep":["com\\.bar\\..*"],"factory":["com\\.qux\\.test"]}""".toByteArray())
        assertEquals(setOf("com.foo.ads"), x.suggest.keys)
        assertTrue("com.google.android.feedback" in x.drop)
        assertTrue(x.keep.single().matches("com.bar.tv"))
        assertTrue(x.factory.single().matches("com.qux.test"))
        try { Bloat.parse("""{"v":1,"keep":["("]}""".toByteArray()); fail() } catch (e: java.io.IOException) {}
        try { Bloat.parse("""{"v":2}""".toByteArray()); fail() } catch (e: java.io.IOException) {}
    }

    /** `dumpsys package` counts TV_INPUT_HARDWARE, `dumpsys wifi` the networks the app made: either kept (HDMI, the network). */
    @Test fun anAppHoldingTheInputsOrTheWifiIsInUse() {
        assertTrue(Setup.inUse("0\n4\n"))
        assertTrue(Setup.inUse("2\n0\n"))
        assertFalse(Setup.inUse("0\n0\n"))
        assertFalse(Setup.inUse(""))
    }

    @Test fun setupAppsAudioAndKeyboardStayOn() {                       // tv-detox's never list
        for (p in listOf("com.google.android.partnersetup", "com.google.android.onetimeinitializer", "com.google.android.leanback.ime",
                "com.android.tv.provision", "com.android.inputdevices", "com.dolby.android.audio.service", "com.android.se"))
            assertTrue(p, Setup.mustKeep(p))
        assertFalse("com.google.android.partnersetup" in dev.tvremote.core.ontv.Bloat.SUGGEST)
        assertFalse(Setup.mustKeep("com.android.service"))                  // "se" is that one name, not a prefix
    }

    @Test fun factoryTestAppsAreKnownByName() {
        for (p in listOf("com.cvte.tv.hkcfac", "com.mediatek.factorymode", "com.tcl.factory", "com.xm.tvfac.ui", "com.hisense.FactoryTest"))
            assertTrue(p, Bloat.FACTORY.matches(p))
        for (p in listOf("com.facebook.katana", "com.netflix.ninja", "com.android.tv.settings", "com.google.android.surface"))
            assertFalse(p, Bloat.FACTORY.matches(p))
    }

    @Test fun whatKeepsTheTvWorkingIsNeverTurnedOff() {
        val tv = FakeTv(true, *(musts.toList() - Setup.TS_PKG).toTypedArray(), "com.foo.game")   // installed, Tailscale would be kept alive over ADB
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = musts.toList() + "com.foo.game"))
        assertEquals(setOf("com.foo.game"), tv.disabled)
    }

    @Test fun cvtesOwnHomeStaysOn() {                                   // a JVC hung on its boot animation without it (cleanyourtv #741)
        assertTrue(Setup.mustKeep("dev.tvremote.setup")); assertTrue(Setup.mustKeep("dev.tvremote.setup.keys")); assertTrue(Setup.mustKeep("com.tailscale.ipn"))
        assertFalse(Setup.mustKeep("dev.tvremote.channels")); assertFalse(Setup.mustKeep("dev.tvremote.games")); assertFalse(Setup.mustKeep("dev.tvremote.phone"))
        assertTrue(Setup.mustKeep("com.cvte.tv.launcher.space"))
        assertFalse(Setup.mustKeep("com.cvte.tv.pictorial"))                // its screensaver ads are still suggested
    }

    @Test fun aNameThatWouldRunAnotherCommandIsSkipped() {
        val bad = listOf("com.foo; reboot", "com.foo'x", "com.foo && rm -rf /sdcard", "\$(reboot)", "foo")
        val tv = FakeTv(true, *bad.toTypedArray())
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = bad))
        assertTrue(tv.disabled.isEmpty())
        assertFalse(tv.said.any { "reboot" in it || "rm -rf" in it })
    }

    /** A TV that says what went wrong: pm's refusals are not counted as off, a restart or a lost link midway names the app. */
    private fun run(tv: FakeTv, apps: List<String>, lines: MutableList<String> = mutableListOf()) =
        Setup(null, null, "en", { tv.sh(it) }) { lines += it }.also { it.pause = { } }.apply(Setup.Choice(anim = false, home = false, disable = apps))

    @Test fun anAppTheTvRefusesIsNotCountedAsOff() {
        val tv = FakeTv(true, "com.foo.game", "com.brand.locked")
        tv.refuse += "com.brand.locked"
        val lines = mutableListOf<String>()
        run(tv, listOf("com.foo.game", "com.brand.locked"), lines)
        assertEquals(setOf("com.foo.game"), tv.disabled)
        assertTrue(lines.toString(), "✓ Turned off 1 app" in lines)
        assertTrue(lines.toString(), lines.any { "would not let these be turned off" in it && "com.brand.locked" in it })
    }

    @Test fun aTvThatRestartsMidwayStopsAndNamesTheApp() {
        val tv = FakeTv(true, "com.foo.game", "com.brand.core", "com.foo.shop")
        tv.downAt = "com.brand.core"
        try { run(tv, listOf("com.foo.game", "com.brand.core", "com.foo.shop")); fail("went on after a restart") }
        catch (e: Setup.Stopped) { assertEquals("com.brand.core", e.pkg); assertTrue(e.message!!, "restarted" in e.message!! && "all 1 before it" in e.message!!) }
        assertFalse("com.foo.shop" in tv.disabled)                    // nothing more after it
        assertTrue("com.brand.core" in tv.offList)                    // on record before pm was asked: Undo has it
    }

    @Test fun aTvThatNeverComesBackStopsAndSaysSo() {
        val tv = FakeTv(true, "com.foo.game", "com.brand.core")
        tv.downAt = "com.brand.core"; tv.stayDown = true
        val lines = mutableListOf<String>()
        try { run(tv, listOf("com.foo.game", "com.brand.core"), lines); fail() }
        catch (e: Setup.Stopped) { assertTrue(e.message!!, "has not come back in" in e.message!!) }
        assertEquals(1, lines.count { "waiting for it" in it })
        assertEquals(listOf("com.foo.game", "com.brand.core"), tv.offList)
    }

    @Test fun aBriefDropWithoutARestartGoesOn() {
        val tv = FakeTv(true, "com.foo.game", "com.brand.core", "com.foo.shop")
        tv.downAt = "com.brand.core"; tv.restarts = false
        val lines = mutableListOf<String>()
        run(tv, listOf("com.foo.game", "com.brand.core", "com.foo.shop"), lines)
        assertEquals(setOf("com.foo.game", "com.brand.core", "com.foo.shop"), tv.disabled)
        assertTrue(lines.toString(), "✓ Turned off 3 apps" in lines)
    }

    @Test fun notInstalledIsNotCounted() {
        val tv = FakeTv(true, "com.foo.game")
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = listOf("com.foo.game", "com.foo.gone")))
        assertEquals(setOf("com.foo.game"), tv.disabled)
        assertEquals(listOf("com.foo.game"), tv.offList)
    }

    @Test fun theHomeScreenGoesOnlyOnceFLauncherIsThere() {
        val without = FakeTv(true, *Setup.LAUNCHER)
        without.setup().apply(Setup.Choice(anim = false, home = false, disable = emptyList()))
        assertTrue(without.disabled.isEmpty())                        // no home: the launcher stays
        val with = FakeTv(true, *Setup.LAUNCHER, Setup.HOME_PKG)
        with.setup().apply(Setup.Choice(anim = false, home = true, disable = emptyList()))
        assertEquals(Setup.LAUNCHER.toSet(), with.disabled)
        assertEquals(Setup.HOME_PKG, with.home())
    }

    @Test fun googleTvGivesHomeToFLauncherWithoutTheWizard() {         // launcherx alone off: the wizard took Home and the TV kept restarting
        val tv = FakeTv(true, *Setup.LAUNCHER, *Setup.WIZARD, Setup.HOME_PKG, "com.android.tv.settings")
        tv.disabled += "com.google.android.tvlauncher"                // off from the factory on Google TV
        tv.setup().apply(Setup.Choice(anim = false, home = true, disable = emptyList()))
        assertEquals(Setup.HOME_PKG, tv.home())
        assertTrue("${Setup.WIZARD[0]}/.Home" in tv.disabled)
        assertFalse(Setup.WIZARD[0] in tv.disabled)                    // its Home only: the whole app off cost an FPT box its saved Wi-Fi
        val off = tv.said.filter { it.startsWith("pm disable") }
        assertTrue(off.indexOfFirst { Setup.WIZARD[0] in it } < off.indexOfFirst { Setup.LAUNCHERX in it })
        tv.setup().undo()
        assertEquals(setOf("com.google.android.tvlauncher"), tv.disabled)   // what the factory turned off stays off
        assertFalse(tv.setup().optimized())
    }

    @Test fun theRestoreScriptIsUndoForAShell() {
        val out = Setup.restoreScript("BRAVIA 4K", "2026-10-25 09:00:00", "192.168.1.5",
            listOf("com.google.android.tvlauncher", "com.google.android.tungsten.setupwraith/.Home", "x; reboot", "a.b'c", "com.mitv.tvhome"),
            "com.google.android.tvlauncher/.MainActivity")
        val expected = listOf(
            "#!/bin/sh",
            "# Restore for the TV BRAVIA 4K, saved 2026-10-25 09:00:00 by TV Remote (Optimize TV, Undo).",
            "# Needs only adb on this computer. With the TV on the network:  adb connect 192.168.1.5:5555",
            "#   then  sh tv-restore-BRAVIA-4K.sh      (several devices: ADB_SERIAL=<ip>:5555 sh tv-restore-BRAVIA-4K.sh)",
            "A=\"adb\"; [ -n \"\$ADB_SERIAL\" ] && A=\"adb -s \$ADB_SERIAL\"",
            "\$A shell 'pm enable --user ${dev.tvremote.core.ontv.TvUser.ID} com.google.android.tvlauncher'",
            "\$A shell 'pm enable --user ${dev.tvremote.core.ontv.TvUser.ID} com.google.android.tungsten.setupwraith/.Home'",
            "\$A shell 'pm enable --user ${dev.tvremote.core.ontv.TvUser.ID} com.mitv.tvhome'",
            "\$A shell 'cmd package set-home-activity com.google.android.tvlauncher/.MainActivity'",
            "\$A shell 'rm -f ${Setup.OFF_LIST} ${dev.tvremote.core.ontv.LauncherSwap.REC}'",
            "\$A shell 'for k in ${Setup.ANIM}; do settings put global \$k 1.0; done'",
            "echo \"Done: 3 apps on again.\"", "")
        assertEquals(expected.joinToString("\n"), out)
        assertFalse("reboot" in out)                                   // a name that is not a package never reaches the script
    }

    @Test fun theRestoreScriptOfATvNamesWhatUndoWouldTurnOn() {
        val tv = FakeTv(true, *Setup.LAUNCHER, *Setup.WIZARD, Setup.HOME_PKG, "com.android.tv.settings")
        tv.setup().apply(Setup.Choice(anim = false, home = true, disable = emptyList()))
        val script = tv.setup().restore("Model X", "now", "10.0.2.2")
        assertTrue("pm enable --user ${dev.tvremote.core.ontv.TvUser.ID} ${Setup.LAUNCHERX}'" in script)
        assertTrue("setupwraith/.Home'" in script)
        tv.setup().undo()
        assertFalse(tv.setup().optimized())
    }

    @Test fun homeThatWillNotMoveGetsItsHomeScreensBack() {
        val tv = FakeTv(true, *Setup.LAUNCHER, *Setup.WIZARD, Setup.HOME_PKG, "com.brand.home")
        tv.homes["com.brand.home"] = 5                                 // a brand's own home screen, not on our lists
        // the key filter would send Home to TV Home (dev/flows/home-screen.md row 8): no TV Setup here, so its one question
        val e = try { tv.setup().apply(Setup.Choice(anim = false, home = true, disable = emptyList())); null } catch (x: dev.tvremote.core.ontv.SetupNeed.Missing) { x }
        assertEquals("homekey", e?.feature)
        assertTrue(tv.disabled.isEmpty())
        assertEquals("com.brand.home", tv.home())
    }

    @Test fun aGoogleTvFreshFromTheFactoryHasNothingToUndo() {
        val tv = FakeTv(true, *Setup.LAUNCHER, *Setup.WIZARD)
        tv.disabled += "com.google.android.tvlauncher"
        assertFalse(tv.setup().optimized())
    }

    @Test fun anAndroidTvOptimizedByAnOlderVersionGetsItsHomeBack() {   // older versions kept no record of the home screen
        val tv = FakeTv(true, "com.google.android.tvlauncher", "com.google.android.tvrecommendations", Setup.HOME_PKG)
        tv.disabled += listOf("com.google.android.tvlauncher", "com.google.android.tvrecommendations")
        assertTrue(tv.setup().optimized())
        tv.setup().undo()
        assertTrue(tv.disabled.isEmpty())
    }

    @Test fun aXiaomiGivesHomeToFLauncherWithPatchWallOff() {          // PatchWall on took Home back whatever the role said
        val tv = FakeTv(true, "com.mitv.tvhome", Setup.HOME_PKG)
        tv.setup().apply(Setup.Choice(anim = false, home = true, disable = emptyList()))
        assertEquals(Setup.HOME_PKG, tv.home())
        assertEquals(setOf("com.mitv.tvhome"), tv.disabled)
        tv.setup().undo()
        assertTrue(tv.disabled.isEmpty())
    }

    @Test fun thePickerOffersInstalledLaunchersButNotTheTvsOwn() {
        val comps = listOf("com.my.launcher/.Main", "com.my.launcher/.Other", "com.google.android.tvlauncher/.MainActivity", "com.mitv.tvhome/.Home",
            "com.android.tv.settings/.FallbackHome", "${Setup.WIZARD[0]}/.RecoveryActivity", "com.brand/.FactoryTestHome", Setup.HOME_PKG + "/.HomeActivity")
        assertEquals(listOf("com.my.launcher"), dev.tvremote.core.ontv.LauncherSwap.pickable(comps, setOf(Setup.HOME_PKG)))
    }

    @Test fun undoNeverTurnsOnAPatchWallItDidNotTurnOff() {
        val tv = FakeTv(true, "com.mitv.tvhome", "com.google.android.tvlauncher", Setup.HOME_PKG)
        tv.disabled += listOf("com.mitv.tvhome", "com.google.android.tvlauncher")   // an older version's tvlauncher; PatchWall the person's
        tv.setup().undo()
        assertEquals(setOf("com.mitv.tvhome"), tv.disabled)
    }

    @Test fun theHomeAndTheKeyboardInUseAreKept() {
        val tv = FakeTv(true, "com.brand.home", "com.brand.keys", "com.foo.game")
        tv.homes["com.brand.home"] = 5
        tv.keyboard = "com.brand.keys/.Ime"; tv.keyboards.add(0, "com.brand.keys/com.brand.keys.Ime")
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = listOf("com.brand.home", "com.brand.keys", "com.foo.game")))
        assertEquals(setOf("com.foo.game"), tv.disabled)
        assertEquals("com.brand.keys/.Ime", tv.keyboard)                 // set in its short form, listed in full: left alone
    }

    @Test fun aKeyboardThatIsNotThereIsReplaced() {
        val tv = FakeTv(true)
        tv.keyboard = "com.baidu.input/.ImeService"
        tv.keyboards.add("com.other.keys/.Ime")
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = emptyList()))
        assertEquals("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME", tv.keyboard)
    }

    @Test fun undoTurnsBackOnAllItTurnedOff() {
        val tv = FakeTv(true, *Setup.GOOGLE, *Setup.LAUNCHER, Setup.HOME_PKG, "com.foo.game", "com.bar.shop")
        tv.setup().apply(Setup.Choice(anim = true, home = true, disable = Setup.GOOGLE.toList() + "com.foo.game" + "com.bar.shop"))
        assertEquals(Setup.GOOGLE.size + Setup.LAUNCHER.size + 2, tv.disabled.size)
        assertEquals("0", tv.anim)
        assertTrue(tv.setup().optimized())
        tv.disabled += "com.user.own"                                 // turned off by the person, not by us: left as it is
        tv.setup().undo()
        assertEquals(setOf("com.user.own"), tv.disabled)
        assertTrue(tv.offList.isEmpty())
        assertEquals("1.0", tv.anim)
        assertFalse(tv.setup().optimized())
    }

    @Test fun undoSkipsAnAppThatHangsAndNamesIt() {                   // cleanyourtv: Undo sat on one package, the rest stayed off
        val tv = FakeTv(true, *Setup.GOOGLE, "com.foo.game", "com.bar.shop")
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = listOf("com.foo.game", "com.bar.shop")))
        tv.hangs += "com.foo.game"
        val lines = mutableListOf<String>()
        Setup(null, null, "en", { tv.sh(it) }) { lines += it }.undo()
        assertEquals(setOf("com.foo.game"), tv.disabled)
        assertEquals(listOf("com.foo.game"), tv.offList)              // still on the record: Undo again later
        assertTrue(lines.any { "com.foo.game" in it && "too long" in it })
        tv.hangs.clear()
        tv.setup().undo()
        assertTrue(tv.disabled.isEmpty() && tv.offList.isEmpty())
    }

    @Test fun undoStopsAfterThreeHangsInARowAndKeepsTheRest() {       // the package service is gone: not 20 s for each of 40 apps
        val apps = (1..6).map { "com.app.n$it" }
        val tv = FakeTv(true, *apps.toTypedArray())
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = apps))
        tv.hangs += apps
        tv.setup().undo()
        assertEquals(apps.toSet(), tv.disabled)
        assertEquals(apps, tv.offList)
        assertEquals(3, tv.said.count { it.startsWith("if command -v timeout") })
    }

    @Test fun optimizedSeesAnAppOnlyWeTurnedOff() {                   // so the page offers Undo for a TV optimized with a hand-picked list
        val tv = FakeTv(true, "com.foo.game")
        assertFalse(tv.setup().optimized())
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = listOf("com.foo.game")))
        assertTrue(tv.setup().optimized())
    }

    @Test fun aPhoneIsNotOptimized() {
        val tv = FakeTv(false, "com.foo.game")
        try { tv.setup().apply(Setup.Choice(disable = listOf("com.foo.game"))); fail("a phone was optimized") } catch (e: IOException) { }
        assertTrue(tv.disabled.isEmpty())
    }

    @Test fun brandListsNeverMeetWhatATvNeeds() {                       // a suggestion is ticked by default: it may not be a kept one
        for (p in Bloat.SUGGEST.keys) assertFalse(p, Setup.PROTECTED.matches(p) || Bloat.KEEP.matches(p))
        for (p in listOf("com.android.ext.services", "android.ext.services", "android.ext.shared", "com.google.android.ext.shared", "com.google.android.modulemetadata", "com.google.android.overlay.modulesconfig"))
            assertTrue(p, Setup.PROTECTED.matches(p))                  // a Mi TV Stick never booted without them (cleanyourtv #884)
        val tv = FakeTv(true, "com.tcl.suspension", "com.mitv.livetv", "com.amazon.venezia", "com.mediatek.tv.agent", "com.tcl.bi")
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = tv.installed.toList()))
        assertEquals(setOf("com.tcl.bi"), tv.disabled)
    }

    @Test fun whatOwnersFoundLoadBearingStaysOnAndTheRestGoesOff() {   // cleanyourtv, read again October 2026
        val keep = listOf("com.hisense.tv.tiletransition", "org.droidtv.eum", "org.droidtv.aurora", "com.vt.tv.smartsense",
            "com.homwee.dmt.upgrade", "fusion.android.tv.demo", "com.sony.dtv.smartui", "com.xiaomi.android.tvsetup.partnercustomizer",
            "com.amazon.tv.oobe", "com.tcl.sourcemananger", "com.jmgo.autofocus")
        val off = listOf("com.hisense.logupload", "com.amazon.client.metrics.api", "org.droidtv.usagelogger", "tv.anoki.acr.controller",
            "android.autoinstalls.config.JVC", "com.nvidia.stats")
        for (p in keep) assertTrue(p, Bloat.KEEP.matches(p))
        for (p in off) assertTrue(p, Bloat.SUGGEST.containsKey(p))
        val tv = FakeTv(true, *(keep + off).toTypedArray())
        tv.setup().apply(Setup.Choice(anim = false, home = false, disable = tv.installed.toList()))
        assertEquals(off.toSet(), tv.disabled)
    }

    @Test fun brandSuggestionsGoOffByDefaultAndComeBackWithUndo() {
        val tv = FakeTv(true, "com.miui.tv.analytics", "com.foo.game")
        tv.setup().apply(Setup.Choice(anim = false, home = false))
        assertEquals(setOf("com.miui.tv.analytics"), tv.disabled)
        tv.setup().undo()
        assertTrue(tv.disabled.isEmpty())
    }
}
