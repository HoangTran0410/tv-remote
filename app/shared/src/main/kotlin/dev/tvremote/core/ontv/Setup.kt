package dev.tvremote.core.ontv
import java.io.File
import java.io.FileOutputStream
import dev.tvremote.core.adb.Pkg
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import dev.tvremote.core.Words
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.PmInstall

/**
 * "Optimize TV": the steps, run over an ADB connection. The TV app runs them on itself,
 * the phone app on the TV it is connected to. [dir] is where this device may keep a download for a moment.
 */
class Setup internal constructor(private val adb0: Adb?, private val dir: File?, private val lang: String,
                                 private val sh: (String) -> String,   // sh: a test's TV in place of ADB (SetupTest)
                                 private val fresh: (() -> Adb)? = null, private val log: Log) {
    constructor(adb: Adb, dir: File?, lang: String, log: Log) : this(adb, dir, lang, { adb.shell(it) }, null, log)
    /** Through [link]: a link that drops midway (a TV restarting) is dialled again, so Optimize can wait for the TV and say what happened. */
    constructor(link: dev.tvremote.core.adb.AdbLink, dir: File?, lang: String, log: Log) : this(null, dir, lang, { link.get().shell(it) }, { link.get() }, log)
    private val adb: Adb? get() = fresh?.invoke() ?: adb0
    private fun t(id: String, vararg a: Pair<String, Any?>) = Words.get(lang, id, *a)
    private val acts = dev.tvremote.core.adb.Activities(sh)          // asked once per run: the resolver table is read at most once

    companion object {
        /** The home screen Optimize TV gives: TV Home, this project's own (was FLauncher, a 10 MB download from someone else's releases). */
        const val HOME_PKG = dev.tvremote.home.HomeConfig.PKG
        const val TS_PKG = "com.tailscale.ipn"
        val GOOGLE = arrayOf("com.google.android.play.games", "com.google.android.videos", "com.google.android.youtube.tvmusic")
        /** Only present on Sony BRAVIA sets; skipped elsewhere. */
        val SONY = arrayOf(
            "com.sony.dtv.b2b.vendorprotocol", "com.sony.dtv.b2b.rs232csupport", "com.sony.dtv.b2b.hotelmode", "com.sony.dtv.b2b.prosettings",
            "com.sony.dtv.b2b.importexport", "com.sony.dtv.b2b.noderuntime.normal", "com.sony.dtv.b2b.noderuntime.privilege",
            "com.sony.dtv.b2b.webappruntimedrm", "com.sony.dtv.demomode", "com.sony.dtv.demosupport", "com.sony.dtv.multiscreendemo",
            "com.sony.dtv.sonyselect", "tv.samba.ssm")
        /**
         * Disabled last, and only once FLauncher is installed, or Home would have nowhere to go. launcherx: Google TV's; leanbacklauncher:
         * the older Android TVs'; mitv.tvhome: Xiaomi's PatchWall. Any of them on wins Home over the HOME role (dev/decisions/tv-home.md "The stock launcher wins"), so a swap
         * turns off each one that is on (cleanyourtv's list, tried on their TVs).
         */
        val LAUNCHER = arrayOf("com.google.android.tvrecommendations", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher", "com.mitv.tvhome")
        const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
        /**
         * Google TV's setup wizard answers HOME too, above any installed app (RecoveryActivity, priority 1): with launcherx off
         * it took Home and the TV restarted about every minute (cleanyourtv, a Xiaomi on Google TV 14). Off before launcherx,
         * back on with Undo: a factory reset needs it.
         */
        val WIZARD = arrayOf("com.google.android.tungsten.setupwraith")
        private const val RESOLVE_HOME = LauncherSwap.RESOLVE_HOME
        /** Readable names for the suggested apps, where the TV gives none. */
        val NAMES = mapOf(
            "com.google.android.play.games" to "Google Play Games", "com.google.android.videos" to "Google Play Movies & TV",
            "com.google.android.youtube.tvmusic" to "YouTube Music", "com.sony.dtv.b2b.vendorprotocol" to "Sony B2B vendor protocol",
            "com.sony.dtv.b2b.rs232csupport" to "Sony B2B RS-232C", "com.sony.dtv.b2b.hotelmode" to "Sony Hotel mode",
            "com.sony.dtv.b2b.prosettings" to "Sony Pro settings", "com.sony.dtv.b2b.importexport" to "Sony B2B import/export",
            "com.sony.dtv.b2b.noderuntime.normal" to "Sony B2B runtime", "com.sony.dtv.b2b.noderuntime.privilege" to "Sony B2B runtime (privileged)",
            "com.sony.dtv.b2b.webappruntimedrm" to "Sony B2B web app DRM", "com.sony.dtv.demomode" to "Sony Demo mode",
            "com.sony.dtv.demosupport" to "Sony Demo support", "com.sony.dtv.multiscreendemo" to "Sony Multi-screen demo",
            "com.sony.dtv.sonyselect" to "Sony Select", "tv.samba.ssm" to "Samba TV")
        /**
         * Never offered or turned off: the TV would stop working, or this project would. A launcher (FLauncher, TV Home) is not here:
         * the one Home opens now is kept wherever an app is removed ([homeNow], Manage's home check), the others may go.
         * `ext.services` / `ext.shared` / `modulemetadata` / `overlay.modules`: a Mi TV Stick with them off never booted again
         * (cleanyourtv #884; the bare AOSP `android.ext.*` names too, #1711); no brand list turns them off, only a pick in "Turn off other apps" could.
         */
        val PROTECTED = Regex("android|android\\.ext\\.(services|shared)|com\\.android\\.(systemui|settings|tv\\.settings|shell|phone|bluetooth|providers\\..*|packageinstaller|permissioncontroller|inputmethod.*|networkstack.*|vending|ext\\.services|ext\\.shared|modulemetadata" +
            "|provision|tv\\.provision|managedprovisioning|inputdevices|location\\.fused|externalstorage|keychain|se)" +   // setup, keyboards' layouts, storage: tv-detox's never list
            "|com\\.dolby\\.android\\.audio\\.service" +                       // no sound without it on some TVs (tv-detox)
            "|com\\.google\\.android\\.(gms|gsf|packageinstaller|permissioncontroller|tvlauncher|tvrecommendations|leanbacklauncher.*|inputmethod.*|tv\\.remote\\.service|katniss|apps\\.tv\\.launcherx|tungsten\\.setupwraith|leanback\\.ime|onetimeinitializer|partnersetup|ext\\.services|ext\\.shared|modulemetadata|overlay\\.modules.*)" +
            "|dev\\.tvremote\\.setup(\\.keys)?|com\\.tailscale\\.ipn")   // TV Home only while it is the home screen (callers check); TV Channels, TV Games and the Phone app are ordinary apps
        /**
         * The apps whose permissions stay as they are: the system's own core and this project's. Another app the TV needs (Google's
         * assistant, the keyboard, the launcher) may lose one, one at a time from its details: given back the same way (the
         * assistant kept the microphone open on an FPT box).
         */
        val PERMS_FIXED = Regex("android|com\\.android\\.(?!inputmethod).*|com\\.google\\.android\\.(gms|gsf|packageinstaller|permissioncontroller|ext\\..*|modulemetadata)|dev\\.tvremote\\..*|com\\.tailscale\\.ipn")
        /** What the Apps tab marks "must keep" and refuses to stop, clear, turn off or remove: [PROTECTED] and the brands' own [Bloat.KEEP]. */
        fun mustKeep(p: String) = PROTECTED.matches(p) || Bloat.keep(p)
        /**
         * An app that asks for the TV's own input hardware (HDMI, AV, the tuner), whatever its name: turned off, a OnePlus showed "No
         * Signal" on every input, even once it was back on (cleanyourtv #321). Or one that owns a saved Wi-Fi network: Android forgets
         * an app's networks when it goes, and the TV loses the network and this ADB with it (a TCL's belonged to setupwraith, the
         * emulator's to com.android.tv.provision). Or a PERSISTENT one: Android starts it again, it never stays off (tv-detox).
         * One shell call, made only before turning one off, clearing or removing it.
         */
        fun inUse(sh: (String) -> String, p: String): Boolean = inUse(sh("dumpsys package $p 2>/dev/null | grep -cE 'android.permission.TV_INPUT_HARDWARE| pkgFlags=.*PERSISTENT'; dumpsys wifi 2>/dev/null | grep -cE 'cname=$p([ ,]|$)'" +
                // the TV's Settings and inputs, whatever the brand calls them: a Sony whose Settings another tool removed hung at its logo
                "; cmd package query-activities --brief -a android.settings.SETTINGS 2>/dev/null | grep -c '^ *$p/'" +
                "; cmd package query-activities --brief -a android.intent.action.VIEW -t vnd.android.cursor.item/channel 2>/dev/null | grep -c '^ *$p/'" +
                // q0: this TV has no query-activities (Android 8 and older), so the two counts above mean nothing
                "; cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | grep -q / || echo q0").let { out ->
            if (!out.lines().any { it.trim() == "q0" }) out
            else {                                                    // asked of the resolver table instead; unknown = in use (fail closed)
                val a = dev.tvremote.core.adb.Activities(sh)
                val mine = listOf(a.packages("android.settings.SETTINGS"), a.packages("android.intent.action.VIEW", mime = "vnd.android.cursor.item/channel"))
                if (mine.any { it == null }) "1" else out + "\n" + mine.count { p in it!! }
            }
        })
        fun inUse(out: String): Boolean = out.lines().any { (it.trim().toIntOrNull() ?: 0) > 0 }
        /** What this project turned off beyond its own lists, so Undo can turn exactly that back on. Kept on the TV. */
        const val OFF_LIST = "/data/local/tmp/tvr-disabled.txt"
        private const val TMP = "/data/local/tmp/tvr-setup.apk"
        const val ANIM = "window_animation_scale transition_animation_scale animator_duration_scale"

        /** A package, or one screen of it ("pkg/.Home"): the only shapes that may land in a shell script. */
        private val SAFE = Regex("[A-Za-z][\\w]*(\\.[\\w]+)+(/[\\w.$]+)?")

        /**
         * The same steps as [undo], for a computer with nothing but adb (our apps broken or gone): each app back on, the home screen
         * before chosen again, the records and animations as Undo leaves them. A name that is not a package is dropped, not quoted.
         */
        /** The script's file name: the same in the download and in the script's own "how to run" line. */
        fun restoreName(model: String) = "tv-restore-" + model.replace(Regex("[^A-Za-z0-9_.-]+"), "-").trim('-').ifEmpty { "tv" } + ".sh"

        fun restoreScript(model: String, date: String, host: String, apps: List<String>, home: String, port: Int = dev.tvremote.core.adb.AdbPort.CLASSIC): String {
            val pkgs = apps.filter { SAFE.matches(it) }.distinct()
            val b = StringBuilder("#!/bin/sh\n")
            b.append("# Restore for the TV ").append(model.replace(Regex("[^\\w .-]"), "").trim().ifEmpty { "?" }).append(", saved ").append(date.replace(Regex("[^\\w :+-]"), "")).append(" by TV Remote (Optimize TV, Undo).\n")
            b.append("# Needs only adb on this computer. With the TV on the network:  adb connect ").append(host.replace(Regex("[^\\w.:-]"), "")).append(":$port\n")
            val f = restoreName(model)
            b.append("#   then  sh ").append(f).append("      (several devices: ADB_SERIAL=<ip>:$port sh ").append(f).append(")\n")
            b.append("A=\"adb\"; [ -n \"\$ADB_SERIAL\" ] && A=\"adb -s \$ADB_SERIAL\"\n")
            for (p in pkgs) b.append("\$A shell '").append(TvUser.enable(p)).append("'\n")
            if (home.isNotEmpty() && SAFE.matches(home) && '/' in home)
                b.append("\$A shell 'cmd package set-home-activity ").append(home).append("'\n")
            b.append("\$A shell 'rm -f ").append(OFF_LIST).append(' ').append(LauncherSwap.REC).append("'\n")
            b.append("\$A shell 'for k in ").append(ANIM).append("; do settings put global \$k 1.0; done'\n")
            b.append("echo \"Done: ").append(pkgs.size).append(" apps on again.\"\n")
            return b.toString()
        }

        internal const val ABOUT = "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.product.device; getprop ro.build.version.release"
        /** The package service gone: the TV is shutting down or restarting (a pm that answers "Failure calling service package: Broken pipe"). */
        private val DEAD = Regex("Failure calling service|Can't find service|DeadObjectException|DeadSystemException")

        /**
         * What went wrong on which TV, to the census with the crashes (the same stats switch, off: nothing): which app a TV restarts
         * over, will not turn off, or keeps Home with, is how the lists learn (vgs-cleantv sends the same). [about]: the [ABOUT]
         * lines, read while the link was up. [kind]: lost, restarted, failed, home:<why>, launcher:<why>.
         */
        fun report(kind: String, about: String, pkg: String, apps: Map<String, String> = emptyMap()) {
            if (!dev.tvremote.core.Census.enabled) return
            val tv = about.split("\n").map { it.trim() }
            val facts = "tv: " + tv.take(3).joinToString(" / ") + ", Android " + tv.getOrElse(3) { "?" } + "\n" +
                (if (pkg.isNotEmpty()) "pkg: $pkg\n" else "") + apps.entries.joinToString("\n") { "  " + it.key + ": " + it.value.take(160) }
            dev.tvremote.core.Census.crash("kotlin", "Optimize $kind " + tv.getOrElse(1) { "" }, "Optimize TV $kind" + (if (pkg.isNotEmpty()) ": $pkg" else ""), facts)
        }

        private fun last(out: String): String = out.split("\n").dropLastWhile { it.isEmpty() }.lastOrNull() ?: ""
    }

    fun interface Log { fun line(s: String) }

    /** Whether Optimize TV left something to undo: an app of its lists, or of its own record, turned off. */
    @Throws(IOException::class)
    fun optimized(): Boolean {
        val off = off()
        return undoable().any { it in off }
    }

    private fun off(): Set<String> =
        Pkg.list(sh, "-d " + TvUser.USER, empty = true)

    private fun record(): List<String> = sh("cat $OFF_LIST 2>/dev/null").split("\n").map { it.trim() }.filter { it.isNotEmpty() }   // "" is in no list

    /**
     * What Undo turns back on: what the record names, and the suggested lists (older versions kept no record of them). Older
     * versions also turned the old home screen off without a record: that one comes back too, except on Google TV, where it
     * was off from the factory and turning it on would give Home a second owner. Home screens first, so the wizard ([WIZARD])
     * never finds Home empty.
     */
    private fun undoable(): List<String> {
        val mine = record()
        // the two that older versions turned off unrecorded: never a PatchWall the person turned off themselves
        val legacy = if (mine.none { it in LAUNCHER } && !has(LAUNCHERX)) LAUNCHER.take(2) else emptyList()
        return (LAUNCHER.filter { it in mine } + legacy + GOOGLE + SONY + mine).distinct()
    }

    @Throws(IOException::class)
    fun has(pkg: String): Boolean = Pkg.installed(sh, pkg)

    @Throws(IOException::class)
    fun describe(): String =
        sh("getprop ro.product.manufacturer") + " " + sh("getprop ro.product.model") + ", Android " + sh("getprop ro.build.version.release")

    /** "100.x.y.z" once Tailscale is signed in, else "". */
    @Throws(IOException::class)
    fun tailscaleIp(): String =
        Regex("inet (100\\.[0-9.]+)/32").find(sh("ip -4 addr show"))?.groupValues?.get(1) ?: ""

    @Throws(IOException::class)
    fun lanIp(): String =
        Regex("inet ((?:192\\.168|10|172\\.(?:1[6-9]|2[0-9]|3[01]))\\.[0-9.]+)/").find(sh("ip -4 addr show"))?.groupValues?.get(1) ?: ""

    /**
     * Optimize swaps the home screen and turns system apps off, which suits a TV only: a phone or tablet running TV Setup is
     * refused. Only a plain "false" counts, so an old TV whose pm has no has-feature is not turned away.
     */
    private fun tvOnly() {
        if (sh("pm has-feature android.software.leanback").trim() == "false")
            throw IOException(t("optimize.device_not_tv_optimize"))
    }

    /** What "Optimize TV" would do, for the person to choose: [label] gives an app's name where known. */
    @Throws(IOException::class)
    fun plan(label: (String) -> String?): org.json.JSONObject {
        tvOnly()
        Bloat.fresh()
        val enabled = Pkg.list(sh, "-e " + TvUser.USER)
        val guard = (handlers() ?: throw IOException(t("optimize.cant_check_apps"))) + persistent()
        val suggest = suggested().filter { it in enabled && it !in guard }
        val home = homeNow()
        val shown = HashSet<String>()
        var unknown = false
        for (cat in arrayOf("LEANBACK_LAUNCHER", "LAUNCHER"))              // the apps a person sees on the TV
            acts.packages("android.intent.action.MAIN", cat)?.let { shown.addAll(it) } ?: run { unknown = true }
        val others = shown.filter { it in enabled && it !in suggest && it != home && !kept(it) && it !in guard }.sortedBy { (label(it) ?: it).lowercase() }
        fun item(p: String) = org.json.JSONObject().put("pkg", p).put("label", Bloat.suggest()[p] ?: label(p) ?: NAMES[p] ?: p.split('.').let { s -> if (s.last().length <= 3 && s.size > 1) s.takeLast(2).joinToString(".") else s.last() })
        return org.json.JSONObject()
            .put("anim", sh("settings get global window_animation_scale").trim() != "0.0")
            .put("tvhome", has(HOME_PKG)).put("home", home)
            .put("suggest", org.json.JSONArray(suggest.map { item(it) }))
            .put("apps", org.json.JSONArray(others.map { item(it) })).put("apps_unknown", unknown)
    }

    /** What to do: animations off, FLauncher as the home screen, and which apps to turn off (null: the suggested ones). */
    class Choice(val anim: Boolean = true, val home: Boolean = true, val disable: List<String>? = null)

    @Throws(Exception::class)
    fun apply(c: Choice = Choice()) {
        tvOnly()
        readAbout()
        sh(RulesClient.KEEP_ALIVE)                                    // TV Setup and TV Home stay alive on a low-RAM TV
        if (has(TS_PKG)) {                                            // the Tailscale button does this too; here for a Tailscale installed by hand
            Tailscale(adb!!, dir).keepAlive()
            log.line(t("optimize.tailscale_starts_again_by"))
        }
        if (c.anim) {
            // the screen-to-screen transitions only; the apps' own motion stays at its pace (1, also undoing the 0.5 older versions
            // set): at 0.5 TV Home's scroll and focus ran twice as fast and looked like a jerk, at 0 spinners would freeze
            sh("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 1")
            log.line(t("optimize.transition_animations_off"))
        }

        Bloat.fresh()
        val pick = c.disable ?: suggested()
        val keep = setOf(homeNow(), keyboardNow().substringBefore('/'), if (c.home) HOME_PKG else "") + (handlers() ?: throw IOException(t("optimize.cant_check_apps"))) + homes()   // whatever the list says, the TV keeps a home, a keyboard, its inputs and Settings
        val said = LinkedHashMap<String, String>()                    // each app tried, and pm's last line about it
        val sys = systemPid()
        for (p in pick.distinct()) {
            if (!p.matches(Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")) || kept(p) || p in keep || !has(p) || inUse(sh, p)) continue
            val out = try { turnOff(p) } catch (e: IOException) { null }
            if (out != null && !DEAD.containsMatchIn(out)) { said[p] = last(out.trim()); continue }
            // the link dropped, or the TV is going down, right after p: it may be what the TV cannot live without (a Sony KD-55X8500D
            // restarted at one, vgs-cleantv 2026-10-22). Wait for the TV, then tell a restart (system_server's pid) from a lost link
            if (!back(p)) { report("lost", p, said); throw Stopped(t("errors.optimize_lost", "pkg" to p, "n" to said.keys.count { it != p }), p) }
            val isOff = p in off()
            if (isOff) said[p] = "" else said[p] = try { last(turnOff(p).trim()) } catch (e: IOException) { "" }
            val now = systemPid()
            if (sys != null && now != null && now != sys) { report("restarted", p, said); throw Stopped(t("errors.optimize_restarted", "pkg" to p, "n" to said.keys.count { it != p }), p) }
        }
        // pm's words differ by version ("new state: disabled-user", nothing at all): the TV's own list says what is off
        val nowOff = if (said.isEmpty()) emptySet() else off()
        val failed = said.filterKeys { it !in nowOff }
        if (said.size > failed.size) log.line(t("optimize.turned_off_apps", "n" to said.size - failed.size))
        if (failed.isNotEmpty()) {
            log.line(t("optimize.not_off", "apps" to failed.keys.joinToString(", ")))
            report("failed", "", failed)
        }
        keyboard()

        if (!c.home) { log.line(t("optimize.done")); return }
        // none on the TV, or an older one than the latest release's (its settings stay): put in first, so Home lands on today's
        val had = if (has(HOME_PKG)) Regex("versionName=(\\S+)").find(sh("dumpsys package $HOME_PKG | grep -m1 versionName"))?.groupValues?.get(1) ?: "" else null
        val newer = if (had == "") null                                // a version not read: the one there is kept
            else try { dev.tvremote.update.Release.newer(had ?: "0", "home", Launchers.SELF_APK) } catch (e: Exception) { null }
        if (had == null || newer != null) {
            log.line(t("optimize.downloading_tvhome"))
            val out = try {
                val a = newer?.let { Launchers.Apk("any", it.url, it.sha256) } ?: Launchers.self()   // pinned by the release's signed SHA256SUMS
                val apk = download(a.url, Launchers.SELF_APK, a.sha256)
                log.line(t("optimize.installing_tvhome"))
                install(apk).also { apk.delete() }
            } catch (e: IOException) { e.message ?: "" }
            if (!out.contains("Success") && had == null) {
                log.line(t("optimize.tvhome_could_not_be", "why" to last(out)))
                log.line(t("optimize.done"))
                return
            }                                                         // an update that failed: the one on the TV still works
            // as the gallery does: its weather may ask where the TV is, and compiled now a cold start is ~4x faster (LauncherInstall)
            try { adb?.shell(dev.tvremote.home.HomeConfig.GRANT_WIFI + "cmd package compile -m speed -f $HOME_PKG >/dev/null 2>&1", 120000) } catch (e: IOException) {}
        }
        if (takeHome()) {
            log.line(t("optimize.home_screen", "home" to LauncherSwap(sh).homeOpens()))
            if (via == "wifi") log.line(t("launcher.key_wifi"))       // the key filter has Home: a full swap after the Wi-Fi is the system's (flow row 6)
            if (via in setOf("home", "wifi_unknown")) report("home:key:$via", opened, emptyMap())   // TVs the swap does not know yet
            log.line(t("optimize.done_press_home_see"))
        } else {
            log.line(t("optimize.home_kept", "home" to homeNow()))
            if (refused.isNotEmpty()) log.line(t(LauncherSwap.whyId(refused)))   // the reason, when it is one the person can know
            if (refused == "home" && opened.isNotEmpty()) log.line(t("launcher.home_opens", "pkg" to opened))
            // the known refusals (a Chromecast, a MOKA board) say nothing new; these are the TVs the swap does not know yet
            if (refused in setOf("home", "interrupted", "wifi_unknown", LauncherSwap.KEYS, "")) report("home:" + refused.ifEmpty { "?" }, opened, emptyMap())
            log.line(t("optimize.done"))
        }
    }

    private fun suggested(): List<String> = (GOOGLE + SONY).filterNot { Bloat.dropped(it) } + Bloat.suggest().keys

    private fun kept(p: String) = mustKeep(p)

    /**
     * The apps that open an input (VIEW of a channel: TV Home's input tiles, InputOpen) and Settings, asked of the TV rather than by
     * name: one turned off left the input tiles dead on a Sony W800G and Settings unreachable on a W800C (cleanyourtv 6fa18972).
     * Android 8 and older have no query-activities: [Activities] reads the resolver table; null (neither works) refuses the step.
     */
    private fun handlers(): Set<String>? {
        val ch = acts.packages("android.intent.action.VIEW", mime = "vnd.android.cursor.item/channel")
        val st = acts.packages("android.settings.SETTINGS")
        return if (ch == null || st == null) null else ch + st       // null: the TV could not say, so nothing may be turned off on a guess
    }

    /** Every launcher the TV has: on a TV whose default home cannot be asked (Android 8 and older) none of them is turned off. */
    private fun homes(): Set<String> = acts.packages("android.intent.action.MAIN", "HOME") ?: emptySet()

    /** The PERSISTENT apps, never offered: filtered on the TV, ~150 B and ~35 ms where the whole dump is ~600 KB (emulator). */
    private fun persistent(): Set<String> = sh("dumpsys package packages 2>/dev/null | awk '/^  Package \\[/{p=\$2} / pkgFlags=\\[.*PERSISTENT/{print substr(p,2,length(p)-2)}'")
        .split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** The app the Home button opens now ("android" when Android asks which one). */
    private fun homeNow(): String = last(sh(RESOLVE_HOME)).substringBefore('/')

    /**
     * Turned off one command per shell call: chained, some TVs' pm stopped after the first (cleanyourtv). Kept for Undo before pm is
     * asked, so a TV that goes down with it still has it on the record. Gives what pm said.
     */
    private fun turnOff(p: String): String {
        sh("grep -qx '$p' $OFF_LIST 2>/dev/null || echo '$p' >> $OFF_LIST")
        val out = sh(TvUser.disable(p))
        if (!DEAD.containsMatchIn(out)) sh("am force-stop $p")
        return out
    }

    /** system_server's pid: another one after a pause = the TV restarted, even the quick kind where only Android's runtime does. */
    private fun systemPid(): String? = try { sh("pidof system_server").trim().takeIf { it.matches(Regex("\\d+")) } } catch (e: IOException) { null }

    /** Waits up to ~1.5 min (a TV's restart) for the TV to answer with its package service up again. */
    private fun back(p: String): Boolean {
        log.line(t("optimize.waiting_tv", "pkg" to p))
        repeat(12) {
            pause(8000)
            try { if (Pkg.installed(sh, "android")) return true } catch (e: IOException) { }
        }
        return false
    }
    internal var pause: (Long) -> Unit = { Thread.sleep(it) }        // SetupTest: no real wait

    /** Optimize stopped midway at [pkg] (the TV restarted or was lost right after it); the message is the person's. */
    class Stopped(msg: String, val pkg: String) : IOException(msg)

    private fun report(kind: String, pkg: String, apps: Map<String, String>) = report(kind, about, pkg, apps)
    private var about = ""                                            // the TV's model, read before anything happens: a report may come after the link is gone
    private fun readAbout() { if (dev.tvremote.core.Census.enabled) about = try { sh(ABOUT) } catch (e: IOException) { "" } }

    /**
     * TV Home takes Home, by the same steps as any launcher of the gallery ([LauncherSwap]: the TV's own home screens off with the
     * wizard first, the new one made the home last, then Home is asked where it goes). Anywhere else (a brand's own home screen we do
     * not know, Fire OS, a TV that ignores the choice) and the home screens come back on, and TV Setup's key filter sends Home to TV Home
     * instead (dev/flows/home-screen.md). Recorded on the TV, so the watchdog of TV Setup guards it too.
     */
    private fun takeHome(): Boolean {
        val s = LauncherSwap(sh)
        return s.take(HOME_PKG).also { ok -> refused = if (ok) "" else s.refused.ifEmpty { s.state()?.takeIf { it.state == "rolledback" }?.why ?: "" }; opened = s.opened; via = s.via }
    }
    private var refused = ""
    private var via = ""                                              // why TV Setup's key filter has Home, not the swap (LauncherSwap.via)
    private var opened = ""                                           // what the Home button opened instead of TV Home

    private fun keyboardNow(): String = sh("settings get secure default_input_method").trim()

    /**
     * A TV whose keyboard is gone has none at all (a Xiaomi set to a Baidu keyboard not installed, cleanyourtv): the chosen one
     * is checked against those that work, and Gboard (else the first) is chosen in its place.
     */
    private fun keyboard() {
        fun whole(c: String) = c.substringBefore('/') + "/" + c.substringAfter('/').let { if (it.startsWith(".")) c.substringBefore('/') + it else it }
        val now = whole(keyboardNow())
        val ok = sh("ime list -s").split("\n").map { it.trim() }.filter { '/' in it }.map { whole(it) }
        if (ok.isEmpty() || now in ok) return
        val pick = ok.firstOrNull { it.startsWith("com.google.android.inputmethod.latin/") } ?: ok[0]
        sh("ime set $pick")
        log.line(t("optimize.keyboard_set", "ime" to pick.substringBefore('/')))
    }

    /** [restoreScript] for this TV as it is now: what [undo] would turn on, and the home screen the swap replaced. */
    @Throws(IOException::class)
    fun restore(model: String, date: String, host: String): String {
        val home = LauncherSwap(sh).state()?.let { it.prev.takeIf { p -> p.substringBefore('/') != it.pkg } } ?: ""
        return restoreScript(model, date, host, undoable().filter { has(it.substringBefore('/')) }, home, dev.tvremote.core.adb.AdbPort.known(host))   // a BeeCube: 5877
    }

    @Throws(Exception::class)
    fun undo() {
        var on = 0
        for (p in undoable())
            if (has(p.substringBefore('/'))) { sh(TvUser.enable(p)); on++ }   // p may be one screen of an app (the wizard's Home)
        sh("rm -f $OFF_LIST")
        LauncherSwap(sh).let { s -> if (s.state()?.why == LauncherSwap.KEY) s.rollback("user") else s.dropKey(); s.forget() }   // the key filter lets Home pass
        sh("for k in $ANIM; do settings put global \$k 1.0; done")
        log.line(t("optimize.re_enabled_apps_google", "n" to on))
        log.line(t("optimize.tvhome_tailscale_settings_left"))
    }

    /** Copies the APK to the TV through ADB and has the shell user install it there (an app may not install on its own). */
    @Throws(IOException::class)
    fun install(apk: File): String {
        // On the TV itself the file sits in this app's folder on shared storage, which the shell user can read: no copy at all
        val q = "'" + apk.absolutePath.replace("'", "'\\''") + "'"
        if (sh("stat -c %s $q 2>/dev/null").trim() == apk.length().toString())
            return pmInstall(q, apk.length())
        return try { copyAndInstall(apk) } catch (e: IOException) { copyAndInstall(apk) }   // a copy that stalled: once more
    }

    @Throws(IOException::class)
    private fun copyAndInstall(apk: File): String {
        var whole = false
        try {
            adb!!.put(TMP, apk.length()) { s ->
                apk.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        s.write(buf, 0, n)
                    }
                }
            }
            whole = true
        } finally {
            if (!whole) try { adb!!.shell("rm -f $TMP", 10000) } catch (ignored: IOException) { }   // no half file left behind
        }
        // cat is still writing for a while after the stream closes (seconds for a big file on a slow TV): install only a whole file
        var size = ""
        var still = 0                                                 // checks in a row without the file growing
        while (still < 30) {                                          // 15 s without growing: it will not finish
            val now = sh("stat -c %s $TMP 2>/dev/null").trim()
            if (now == apk.length().toString()) { size = now; break }
            still = if (now == size) still + 1 else 0
            size = now
            Thread.sleep(500)
        }
        if (size != apk.length().toString()) { sh("rm -f $TMP"); throw IOException("copy to the TV incomplete ($size of ${apk.length()} bytes)") }
        return try { pmInstall(TMP, apk.length()) } finally { sh("rm -f $TMP") }
    }

    /** `pm install`; on firmware that refuses it, a PackageInstaller session instead ([PmInstall]). */
    @Throws(IOException::class)
    private fun pmInstall(quoted: String, size: Long): String {
        val out = adb!!.shell("cat $quoted | pm install -r -S $size 2>&1", 600000)
        return PmInstall.fallback(out, { adb!!.shell(it, 600000) }, quoted, size)
    }

    @Throws(Exception::class)
    fun download(url: String, name: String, sha256: String?, progress: ((Int) -> Unit)? = null): File {
        val f = File(dir ?: File(System.getProperty("java.io.tmpdir")), name)   // no dir (SetupTest): File(null, …) wrote it into the working directory, and app/shared/tv-home.apk got committed
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val total = c.contentLength.toLong()                      // -1 when the server does not say
            var done = 0L
            c.inputStream.use { input ->
                FileOutputStream(f).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n)
                        done += n
                        if (progress != null && total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val hex = md.digest().joinToString("") { String.format("%02x", it) }
        if (sha256 != null && hex != sha256) { f.delete(); throw IOException(t("optimize.checksum_mismatch_not_installing")) }
        return f
    }
}
