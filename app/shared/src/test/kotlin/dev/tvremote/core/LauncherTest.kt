package dev.tvremote.core

import dev.tvremote.core.ontv.LauncherSwap
import dev.tvremote.core.ontv.LauncherWatch
import dev.tvremote.core.ontv.Launchers
import dev.tvremote.core.ontv.RulesClient
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.ontv.SetupNeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launcher gallery's safety: the swap and its way back (a fake TV that keeps the record), the watchdog's decisions, the list's fit. */
class LauncherTest {
    companion object { const val WIZ = "com.google.android.tungsten.setupwraith" }
    private class Tv(val pick: Boolean = true) {
        val installed = mutableSetOf("com.google.android.tungsten.setupwraith", Setup.LAUNCHERX, "com.new.home", "com.google.android.tvlauncher")
        val disabled = mutableSetOf("com.google.android.tvlauncher")
        val homes = linkedMapOf("com.new.home" to 0, Setup.LAUNCHERX to 2, WIZ to 1, "com.google.android.tvlauncher" to 2)
        var forgot = false                                             // Android 9 after a new launcher went in: no choice, its picker
        var wizardLocked = false                                       // a TV that refuses to turn one screen of a system app off
        var wifi = "WifiConfigManager\nID: 0 SSID\ncname=android.uid.system:1000"   // WIFI_OWNERS' answer: the network re-joined in Settings
        var device = "sabrina_not"                                     // ro.product.device
        var setup = "0.26.3"                                           // TV Setup's versionName
        var homekey: String? = null                                    // its rule "homekey", set by RulesReceiver
        var keys: Boolean? = null                                      // its key filter is up (null: a TV Setup that does not say, 0.34.1 and older)
        var keysAfter = 0                                              // looks before it comes up (the add-on being put on the TV)
        val enabledOrder = mutableListOf<String>()
        var chosen: String? = null
        var focus: String? = null                                      // what the Home key really opens, when not the home (a Google TV Streamer)
        var maker = "Google\nGoogle"
        var users = "Users:\n\tUserInfo{0:Owner:c13} running"           // pm list users
        var bootAware: String? = null                                  // dumpsys's count of DIRECT_BOOT_AWARE; null: the TV says nothing
        var rec = ""
        var crash = ""                                                 // logcat -b crash -v epoch
        var clock = 1000L
        val said = mutableListOf<String>()
        fun home(): String {
            val on = homes.filterKeys { it in installed && it !in disabled && "$it/.Home" !in disabled }
            if (!pick && Setup.LAUNCHERX in disabled) return "android/.Resolver"   // a TV that ignores the choice
            chosen?.let { c -> if (c.substringBefore('/') in on && pick) return c }
            if (forgot) return "android/com.android.internal.app.ResolverActivity"
            return on.maxByOrNull { it.value }?.key?.let { "$it/.Home" } ?: "android/.Resolver"
        }
        fun sh(raw: String): String {
            val cmd = raw.replace(dev.tvremote.core.ontv.TvUser.ID, "0")
            said += cmd
            Regex("^pm disable-user --user 0 (\\S+)$").find(cmd)?.let { disabled += it.groupValues[1]; chosen = null; return "" }
            Regex("^pm disable --user 0 (\\S+) 2>&1$").find(cmd)?.let { if (!wizardLocked) disabled += it.groupValues[1]; chosen = null; return "" }
            Regex("^pm enable --user 0 (\\S+)$").find(cmd)?.let { disabled -= it.groupValues[1]; enabledOrder += it.groupValues[1]; chosen = null; return "" }
            Regex("^cmd package set-home-activity (\\S+)").find(cmd)?.let { chosen = it.groupValues[1]; return "" }
            Regex("^echo '(.*)' > ${LauncherSwap.REC}$").find(cmd)?.let { rec = it.groupValues[1]; return "" }
            return when {
                cmd == "cat ${LauncherSwap.REC} 2>/dev/null" -> rec
                cmd == "rm -f ${LauncherSwap.REC}" -> { rec = ""; "" }
                cmd.startsWith("getprop ro.product.manufacturer") -> maker
                cmd.contains("DIRECT_BOOT_AWARE") -> bootAware ?: ""
                cmd.startsWith("input keyevent 3") -> "  mFocusedApp=ActivityRecord{4f1 u0 ${focus ?: home()} t7}"
                cmd == "date +%s" -> (clock).toString()
                cmd.startsWith("logcat -b crash") -> crash
                cmd == LauncherSwap.WIFI_OWNERS -> wifi
                cmd == "getprop ro.product.device" -> device
                cmd == "pm list users" -> users
                cmd.startsWith("pm path ") -> cmd.removePrefix("pm path ").substringBefore(' ').let { if (it in installed) "package:/system_ext/app/$it.apk" else "" }
                cmd.startsWith("dumpsys package ${RulesClient.SETUP}") -> "    versionName=$setup"
                cmd.startsWith("am broadcast") && cmd.contains("RulesReceiver") -> {
                    val d = if ("--es set " !in cmd) org.json.JSONObject() else org.json.JSONObject(String(java.util.Base64.getDecoder().decode(cmd.substringAfter("--es set ").substringBefore(' '))))   // none: a read
                    if (d.optString("do") == "homekey") homekey = d.optString("pkg")
                    val st = org.json.JSONObject().put("homekey", homekey ?: "")
                    keys?.let { k -> st.put("keys", k && keysAfter-- <= 0) }
                    "Broadcast completed: result=0, data=\"" + java.util.Base64.getEncoder().encodeToString(st.toString().toByteArray()) + "\""
                }
                cmd.startsWith("pm list packages -U ") -> "package:${cmd.substringAfterLast(' ')} uid:10039"
                cmd.startsWith("pm list packages -e") -> (installed - disabled).joinToString("\n") { "package:$it" }
                cmd.startsWith("pm list packages -d") -> disabled.joinToString("\n") { "package:$it" }
                cmd.startsWith("cmd package query-activities") ->
                    homes.keys.filter { it in installed && it !in disabled && "$it/.Home" !in disabled }.joinToString("\n") { "priority=${homes[it]}\n$it/.Home" }
                cmd.contains("android.app.role.HOME") -> "priority=0\n" + home()
                else -> ""
            }
        }
    }

    /** The key way (dev/flows/home-screen.md): TV Setup's rule has Home, nothing of the TV's is off, the record says why. */
    private fun keyWay(tv: Tv, s: LauncherSwap, via: String, saidBy: LauncherSwap = s) {   // saidBy: the instance that took it (the watchdog's own)
        assertEquals(via, "com.new.home", tv.homekey)
        assertEquals(via, setOf("com.google.android.tvlauncher"), tv.disabled)   // as before the swap
        val r = s.state()!!
        assertEquals(via, LauncherSwap.KEY, r.why); assertEquals(via, "kept", r.state); assertTrue(via, r.off.isEmpty())
        if (saidBy === s) assertEquals(via, via, s.via)
        assertEquals(via, "com.new.home/.Home", s.homeOpens())
    }

    @Test fun swapMakesItHomeAndRecordsWhatWentOff() {
        val tv = Tv(); val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        assertEquals("com.new.home", tv.home().substringBefore('/'))
        val r = s.state()!!
        assertEquals("pending", r.state)
        assertEquals(setOf(Setup.LAUNCHERX, "$WIZ/.Home"), r.off.toSet())
        assertFalse(WIZ in tv.disabled)                                // its Home only: the whole app off cost an FPT box its saved Wi-Fi
        assertFalse("com.google.android.tvlauncher" in r.off)       // off from the factory: not ours to turn on
        s.keep(); assertEquals("kept", s.state()!!.state)
    }

    @Test fun theHomeBeforeIsTheOneReadBeforeTheApkWentIn() {       // a BRAVIA (Android 9) forgot FLauncher when TV Home was installed
        val tv = Tv(); val s = LauncherSwap({ tv.sh(it) })
        tv.installed += "com.old.home"; tv.homes["com.old.home"] = 0; tv.chosen = "com.old.home/.Home"
        val before = s.homeNow()
        tv.chosen = null; tv.forgot = true
        assertTrue(s.take("com.new.home", 30, before))
        assertEquals("com.old.home/.Home", s.state()!!.prev)
        s.rollback("user")
        assertEquals("com.old.home/.Home", tv.home())
    }

    @Test fun goingBackWithTheOneBeforeUnknownNeverStaysOnTheOneTried() {
        val tv = Tv(); val s = LauncherSwap({ tv.sh(it) })
        tv.installed -= setOf(Setup.LAUNCHERX, WIZ); tv.installed += "com.old.home"; tv.homes["com.old.home"] = 0
        tv.forgot = true
        assertTrue(s.take("com.new.home", 30))
        assertTrue(LauncherSwap.badHome(s.state()!!.prev))
        s.rollback("timeout")
        assertEquals("com.old.home/.Home", tv.home())
    }

    @Test fun rollbackPutsBackExactlyWhatWasOff() {
        val tv = Tv(); val s = LauncherSwap({ tv.sh(it) })
        s.take("com.new.home", 30)
        s.rollback("timeout")
        assertEquals(setOf("com.google.android.tvlauncher"), tv.disabled)
        assertEquals(Setup.LAUNCHERX, tv.home().substringBefore('/'))
        assertEquals("rolledback", s.state()!!.state)
        assertEquals("timeout", s.state()!!.why)
    }

    @Test fun aMokaBoardGetsHomeByTheKeyFilterAndNothingIsTouched() {   // Kalley / PRISM+: the wizard returns at every boot, a loop at the logo
        for (maker in listOf("MOKA\nKalley", "Google\nPRISM+", "MOKA\nKALLEY")) {
            val tv = Tv(); tv.maker = maker; val s = LauncherSwap({ tv.sh(it) })
            assertTrue(s.take("com.new.home", 30))
            assertTrue(tv.said.none { it.startsWith("pm disable") })
            keyWay(tv, s, "board")
        }
    }

    @Test fun aHomeThatDoesNotLandIsPutBackAndTheKeyFilterHasIt() {   // a TV that ignores the choice
        val tv = Tv(pick = false); val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        keyWay(tv, s, "home")
        assertEquals(Setup.LAUNCHERX + "/.Home", s.state()!!.prev)     // the way back: the home before the swap, not the one tried
    }

    @Test fun aWizardWhoseHomeCannotGoOffGoesWholeWhenTheWifiIsNotItsOwn() {   // every Google TV: the shell may not turn one screen off
        val tv = Tv().apply { wizardLocked = true }; val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        assertTrue(WIZ in tv.disabled && Setup.LAUNCHERX in tv.disabled)
        assertTrue(tv.said.indexOf("pm disable-user --user 0 $WIZ") < tv.said.indexOf("pm disable-user --user 0 ${Setup.LAUNCHERX}"))   // the wizard first
        assertEquals(WIZ, s.state()!!.off.first()); assertTrue(s.state()!!.off.none { it.startsWith("$WIZ/") })
        s.rollback("user")
        assertEquals(setOf("com.google.android.tvlauncher"), tv.disabled)
        assertTrue(tv.enabledOrder.indexOf(Setup.LAUNCHERX) < tv.enabledOrder.indexOf(WIZ))   // launcherx back before the wizard can take Home
        assertEquals(Setup.LAUNCHERX, tv.home().substringBefore('/'))
    }

    @Test fun aWizardHoldingTheWifiLeavesHomeToTheKeyFilter() {     // a TCL Google TV (issue #9): off, the TV would lose its Wi-Fi
        for ((wifi, why) in listOf("WifiConfigManager\nID: 0 SSID\ncname=$WIZ:10039" to "wifi",
                "WifiConfigManager\nID: 0 SSID\ncname=com.google.uid.shared:10039" to "wifi",   // its uid under a shared name
                "" to "wifi_unknown", "WifiConfigManager\nID: 0 SSID" to "wifi_unknown")) {
            val tv = Tv().apply { wizardLocked = true; this.wifi = wifi }; val s = LauncherSwap({ tv.sh(it) })
            assertTrue(s.take("com.new.home", 30))
            assertFalse(tv.said.any { it == "pm disable-user --user 0 $WIZ" })
            keyWay(tv, s, why)
        }
    }

    @Test fun aKeyFilterThatNeverStartsIsNotCalledHome() {          // issue #9, TCLs: the rule set, the filter off, Home still on the TCL launcher
        val tv = Tv().apply { wizardLocked = true; wifi = "WifiConfigManager\nID: 0 SSID\ncname=$WIZ:10039"; keys = false }
        val s = LauncherSwap({ tv.sh(it) })
        assertFalse(s.take("com.new.home", 30))
        assertEquals(LauncherSwap.KEYS, s.refused)                    // "The TV kept its own home screen", with what to do
        assertEquals("", tv.homekey)                                  // no rule left behind that says otherwise
        assertTrue(s.state()?.active != true)                         // nothing for the watchdog, no "TV Home" in Optimize's log
        assertEquals(Setup.LAUNCHERX, s.homeOpens().substringBefore('/'))
        assertEquals(setOf("com.google.android.tvlauncher"), tv.disabled)
        assertEquals(LauncherSwap.KEYS_WAIT, tv.said.count { it == "sleep 1" })   // it was given the time to come up
    }

    @Test fun aKeyFilterThatComesUpWhileTheAddOnGoesInKeepsHome() {
        val tv = Tv().apply { wizardLocked = true; wifi = "WifiConfigManager\nID: 0 SSID\ncname=$WIZ:10039"; keys = true; keysAfter = 3 }
        val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        keyWay(tv, s, "wifi")
    }

    @Test fun theWatchdogSaysSoWhenTheKeyFilterCannotTakeHomeBack() {   // a TCL turns its wizard on again after a full swap
        val tv = Tv().apply { wizardLocked = true }; val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 0))
        tv.disabled -= WIZ; tv.keys = false
        assertEquals(LauncherSwap.KEYS, LauncherWatch({ tv.sh(it) }).check())
        assertEquals("", tv.homekey)
    }

    @Test fun aChromecastKeepsItsWizardForCastingAndTvSetupTakesHome() {
        val tv = Tv().apply { wizardLocked = true; device = "sabrina" }; val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))                        // HomeKey in TV Setup sends Home there
        assertTrue(tv.said.none { it.startsWith("pm disable") })      // nothing off: casting keeps working
        keyWay(tv, s, "cast")
        s.dropKey(); assertEquals("", tv.homekey)
    }

    @Test fun aZteBoxWithItsResetWatchdogKeepsItsLauncherOn() {     // B866F: no system launcher a minute after a boot = factory reset
        val tv = Tv().apply { installed += LauncherSwap.ZTE_WATCHDOG }; val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        assertTrue(tv.said.none { it.startsWith("pm disable") })
        keyWay(tv, s, "zte")
    }

    @Test fun aSkyworthRemoteServiceWithASecondUserKeepsLauncherxOn() {   // its hotkeys ask launcherx with 2+ users: off = NPE loop, RescueParty
        val one = Tv().apply { installed += LauncherSwap.SKW_KEYS }
        assertTrue(LauncherSwap({ one.sh(it) }).take("com.new.home"))
        assertTrue(Setup.LAUNCHERX in one.disabled)                    // one user: nothing asks launcherx, the swap is fine
        val two = Tv().apply { installed += LauncherSwap.SKW_KEYS; users += "\n\tUserInfo{10:new_user:410}" }; val s = LauncherSwap({ two.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        assertTrue(two.said.none { it.startsWith("pm disable") })
        keyWay(two, s, "users")
        assertEquals(2, LauncherSwap.users("Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:new_user:410}"))
    }

    @Test fun aChromecastSentByAnOlderVersionWithNoRecordStillLetsHomePass() {   // 0.32.1 and older kept no record of the key way
        val tv = Tv().apply { device = "sabrina"; homekey = "com.new.home" }
        LauncherSwap({ tv.sh(it) }).dropKey()
        assertEquals("", tv.homekey)
    }

    @Test fun aTvSetupTooOldForTheKeyFilterIsAskedForAndNothingStaysOff() {
        val cases = listOf<Pair<String, Tv.() -> Unit>>("cast" to { device = "sabrina" }, "board" to { maker = "MOKA\nKalley" },
            "wifi" to { wizardLocked = true; wifi = "WifiConfigManager\nID: 0 SSID\ncname=$WIZ:10039" }, "home" to { focus = "$WIZ/.Recovery" })
        for ((what, set) in cases) for (have in listOf("0.26.2", "")) {
            val tv = Tv().apply { setup = have; set() }; val s = LauncherSwap({ tv.sh(it) })
            val e = try { s.take("com.new.home"); null } catch (x: SetupNeed.Missing) { x }
            assertEquals(what, "homekey", e?.feature)                 // every client's one Install / Update TV Setup question
            assertEquals(what, setOf("com.google.android.tvlauncher"), tv.disabled)
            assertNull(what, tv.homekey)
        }
    }

    @Test fun wifiOwners() {
        assertEquals(true, LauncherSwap.wifiSafe("WifiConfigManager", "10039"))                    // Ethernet: nothing saved, nothing to lose
        assertEquals(true, LauncherSwap.wifiSafe("WifiConfigManager\nID: 0 SSID\nID: 1 SSID\ncname=android.uid.system:1000", "10039"))   // the BRAVIA
        assertEquals(false, LauncherSwap.wifiSafe("WifiConfigManager\nID: 0 SSID\ncname=android.uid.system:1000\ncname=$WIZ:10039", "10039"))
        assertEquals(null, LauncherSwap.wifiSafe("", "10039"))
    }

    @Test fun theWizardWholeBackOnAfterABootGoesToTheKeyFilter() {   // an Acer's firmware turns it on at every cold boot (cleanyourtv), a TCL within seconds
        val tv = Tv().apply { wizardLocked = true }; val s = LauncherSwap({ tv.sh(it) }); val w = LauncherWatch { tv.sh(it) }
        assertTrue(s.take("com.new.home", 0))
        assertNull(w.check())
        tv.disabled -= WIZ
        assertNull(w.check())                                         // put back, and Home by the key filter: nothing to tell
        keyWay(tv, s, "home", saidBy = LauncherSwap({ tv.sh(it) }))
        tv.chosen = "$WIZ/.Home"                                      // wherever Android sends Home now, the filter has it
        assertNull(w.check()); assertEquals("com.new.home", tv.homekey)
    }

    @Test fun everyReasonHasWords() {
        val vi = java.io.File("i18n/vi.json").readText(); val en = java.io.File("i18n/en.json").readText()
        for (w in LauncherSwap.WHYS + "?")
            assertTrue(w, "\"${LauncherSwap.whyId(w)}\"" in vi && "\"${LauncherSwap.whyId(w)}\"" in en)
    }

    @Test fun aSwapThatOutlivedTheWatchdogIsUndoneAgain() {
        val tv = Tv(); val s = LauncherSwap({ c -> tv.sh(c).also { if (c.startsWith("cmd package set-home-activity")) LauncherSwap({ tv.sh(it) }).rollback("interrupted") } })
        assertFalse(s.take("com.new.home", 30))
        assertEquals(setOf("com.google.android.tvlauncher"), tv.disabled)
        assertEquals(Setup.LAUNCHERX, tv.home().substringBefore('/'))
    }

    @Test fun aTvWhoseWizardIsNoHomeKeepsItOn() {                    // the FPT Play box: the wizard is installed, not a home screen
        val tv = Tv().apply { homes.remove(WIZ) }; val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        assertFalse(tv.said.any { WIZ in it && "disable" in it })
    }

    @Test fun aPackageWithNoHomeActivityIsRefusedUntouched() {
        val tv = Tv(); tv.installed += "com.other"
        assertFalse(LauncherSwap({ tv.sh(it) }).take("com.other", 30))
        assertEquals(setOf("com.google.android.tvlauncher"), tv.disabled)
    }

    @Test fun badPackageNamesAreRefused() {
        val tv = Tv()
        try { LauncherSwap({ tv.sh(it) }).take("x; reboot", 30); org.junit.Assert.fail() } catch (e: java.io.IOException) { }
        assertTrue(tv.said.none { "reboot" in it })
    }

    @Test fun watchdogWaitsForPendingThenRollsBackOnTimeout() {
        val tv = Tv(); val s = LauncherSwap({ tv.sh(it) }); val w = LauncherWatch({ tv.sh(it) })
        s.take("com.new.home", 30)
        assertNull(w.check())
        tv.clock += 30 + 20
        assertEquals("timeout", w.check())
        assertEquals(Setup.LAUNCHERX, tv.home().substringBefore('/'))
        assertNull(w.check())                                        // over: nothing left to watch
    }

    @Test fun watchdogRollsBackOnABadHomeAndOnlyAfterKeepToo() {
        val tv = Tv(); val s = LauncherSwap({ tv.sh(it) }); val w = LauncherWatch({ tv.sh(it) })
        s.take("com.new.home", 30); s.keep()
        assertNull(w.check())
        tv.installed -= "com.new.home"                               // it was removed: Home lands on the wizard
        assertEquals("home", w.check())
    }

    @Test fun crashesAreCountedPerPackageWithinFiveMinutes() {
        val log = """
            1760000000.123  1  1 E AndroidRuntime: FATAL EXCEPTION: main
            1760000000.124  1  1 E AndroidRuntime: Process: com.new.home, PID: 123
            1760000100.5  1  1 E AndroidRuntime: Process: com.other, PID: 9
            1760000200.1  1  1 F DEBUG   : pid: 5, tid: 5, name: x  >>> com.new.home <<<
        """.trimIndent()
        val t = LauncherWatch.crashes(log, "com.new.home")
        assertEquals(listOf(1760000000L, 1760000200L), t)
        assertTrue(LauncherWatch.looping(t, 1759999900))
        assertFalse(LauncherWatch.looping(t, 1760000100))                  // the first one is before the swap
        assertFalse(LauncherWatch.looping(listOf(1760000000L, 1760000400L), 0))  // more than five minutes apart
    }

    @Test fun theTvsOwnHomeComingUpByItselfOpensTheLauncherPickedInTheKeyWay() {   // an AOC: Back out of Wi-Fi settings landed on its launcher
        val homes = LauncherWatch.ownHomes(listOf("com.tpv.launcher/com.tpv.launcher.Main", "dev.tvremote.home/dev.tvremote.home.HomeActivity",
            "com.android.tv.settings/com.android.tv.settings.system.FallbackHome", "android/com.android.internal.app.ResolverActivity"), "dev.tvremote.home")
        assertEquals(setOf("com.tpv.launcher/com.tpv.launcher.Main"), homes)
        val wifi = 1000L to "com.android.tv.settings/com.android.tv.settings.connectivity.NetworkActivity"
        val own = 5000L to "com.tpv.launcher/com.tpv.launcher.Main"
        assertTrue(LauncherWatch.cameHome(listOf(wifi, own), homes, 0, 7000))
        assertFalse(LauncherWatch.cameHome(listOf(own, wifi), homes, 0, 7000))               // the settings in front: theirs
        assertFalse(LauncherWatch.cameHome(listOf(own), homes, 0, 5000 + LauncherWatch.FRESH_MS + 1))   // up long ago: the person stays there
        assertFalse(LauncherWatch.cameHome(listOf(own), homes, 4000, 7000))                  // a long Home asked for the TV's own
        assertFalse(LauncherWatch.cameHome(listOf(5000L to "com.android.tv.settings/com.android.tv.settings.system.FallbackHome"), homes, 0, 7000))
        assertFalse(LauncherWatch.cameHome(emptyList(), homes, 0, 7000))
    }

    @Test fun factoryHomes() {
        assertTrue(LauncherSwap.factory("com.mtk.factorytest/.Home"))
        assertTrue(LauncherSwap.badHome("com.oem.FactoryMode/.Main"))
        assertTrue(LauncherSwap.badHome("com.x/.factory_test.Home"))
        assertFalse(LauncherSwap.badHome("com.google.android.tvlauncher/.MainActivity"))
    }

    @Test fun badHomes() {
        assertTrue(LauncherSwap.badHome("android/com.android.internal.app.ResolverActivity"))
        assertTrue(LauncherSwap.badHome("com.google.android.tungsten.setupwraith/.RecoveryActivity"))
        assertTrue(LauncherSwap.badHome("com.android.settings/.FallbackHome"))
        assertFalse(LauncherSwap.badHome("com.new.home/.Home"))
    }

    @Test fun homeIsWhatTheHomeKeyOpensNotOnlyTheRole() {          // the role named the launcher, Home went to RecoveryActivity
        val tv = Tv(); tv.focus = "$WIZ/.RecoveryActivity"
        val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        keyWay(tv, s, "home")
        assertEquals(WIZ, s.opened)
        assertEquals("com.x/.Main", LauncherSwap.focused("  mFocusedApp=ActivityRecord{1a2b u0 com.x/.Main t12}"))
        assertEquals("", LauncherSwap.focused("mFocusedApp=null"))
    }


    @Test fun noFallbackHomeNeedsALauncherThatStartsBeforeTheUnlock() {
        val tv = Tv(); tv.bootAware = "0"; val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        assertTrue(tv.said.none { it.startsWith("pm disable") || it.startsWith("cmd package set-home-activity") })   // Android starts the role before the unlock
        assertEquals("boot", s.via); assertEquals("com.new.home", tv.homekey)
        val ok = Tv(); ok.bootAware = "1"
        assertTrue(LauncherSwap({ ok.sh(it) }).take("com.new.home", 30))
    }

    @Test fun theWizardsHomeBackOnAfterABootGoesToTheKeyFilter() {
        val tv = Tv(); val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 0))
        assertNull(LauncherWatch { tv.sh(it) }.check())
        tv.disabled -= "$WIZ/.Home"                                    // the firmware turned it on again
        assertNull(LauncherWatch { tv.sh(it) }.check())
        assertFalse(Setup.LAUNCHERX in tv.disabled)
        assertEquals("com.new.home", tv.homekey)
    }

    @Test fun theKeyWayIsGuardedForCrashesOnly() {
        val tv = Tv().apply { maker = "MOKA\nKalley" }; val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 0))
        tv.crash = (0..1).joinToString("\n") { "${1_700_000_000 + it}.000  1234  1234 E AndroidRuntime: Process: com.new.home, PID: 1234" }
        assertEquals("crash", LauncherWatch { tv.sh(it) }.check())
        assertEquals("", tv.homekey)                                  // Home passes to the TV's own again
        assertEquals("rolledback", s.state()!!.state)
    }

    @Test fun theWayBackFromTheKeyFilterLetsHomePassAndANewPickReplacesIt() {
        val tv = Tv().apply { maker = "MOKA\nKalley" }; val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 30))
        s.rollback("user")
        assertEquals("", tv.homekey); assertEquals("rolledback", s.state()!!.state)
        assertEquals(Setup.LAUNCHERX + "/.Home", s.homeOpens())
        assertTrue(s.take("com.new.home", 30))                        // row 1: a pick after a key-way one
        tv.maker = "Google\nGoogle"
        assertTrue(s.take("com.new.home", 30))                        // a real swap now: the old rule goes first
        assertEquals("", tv.homekey); assertEquals("pending", s.state()!!.state); assertEquals("", s.via)
    }

    @Test fun aFailedPickAfterAKeyWayOneLeavesNoOldRecord() {       // row 1 then row 13: the old record must not say Home opens the old launcher
        val tv = Tv().apply { maker = "MOKA\nKalley"; keys = true }; val s = LauncherSwap({ tv.sh(it) })
        assertTrue(s.take("com.new.home", 0))
        assertEquals(LauncherSwap.KEY, s.state()!!.why)
        tv.keys = false                                               // the filter does not come up this time
        assertFalse(s.take("com.new.home", 0))
        assertEquals(LauncherSwap.KEYS, s.refused)
        assertEquals("", tv.homekey)
        assertNull(s.state())                                         // no {old, kept, key} left for homeOpens and the watchdog
        assertEquals(s.homeNow(), s.homeOpens())
    }

    @Test fun anAppHoldingTheTvsInputsIsNeverTurnedOff() {
        assertTrue(Setup.inUse({ "1" }, "com.oem.tvservice"))
        assertFalse(Setup.inUse({ "0\n0" }, "com.oem.ads"))
        assertFalse(Setup.inUse({ "" }, "com.oem.ads"))
    }
}
