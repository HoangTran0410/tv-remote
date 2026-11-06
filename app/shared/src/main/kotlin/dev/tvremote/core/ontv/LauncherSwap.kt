package dev.tvremote.core.ontv
import org.json.JSONArray
import org.json.JSONObject
import dev.tvremote.core.adb.Pkg
import java.io.IOException

/**
 * Giving a TV another home screen, and taking it back. Any launcher, not FLauncher only (Optimize TV and the launcher gallery
 * use this one code). What it does, in the order that kept TVs working (dev/sign/bloat-sources.md):
 *  1. the new launcher is opened once, so Home is never empty;
 *  2. the TV's own home screens that are on go off, the setup wizard's Home first (with launcherx off alone Home lands on the wizard's
 *     RecoveryActivity and Google TV restarts about every minute). That one screen where the shell may; where it may not (every Google
 *     TV seen: "Shell cannot change component state") the wizard's whole app, as cleanyourtv does, but only when no saved Wi-Fi network
 *     is its own: Android deletes those with it (an FPT Play box, Android 14: no network, no ADB), see [wifiSafe];
 *  3. it is made the home LAST (Android forgets that choice whenever the set of home screens changes);
 *  4. Home is asked where it goes: anywhere else and everything is put back at once.
 * Where the TV will not let the home screen change, TV Setup's key filter sends Home there instead ([byKey]). Every case, in order,
 * with what the person sees: dev/flows/home-screen.md (read it before changing this file; change it with this file).
 * What was turned off, and the home before, are kept ON THE TV in [REC] before anything is touched, so a way back exists even if the
 * phone or computer that started it is gone: [rollback] puts back exactly that. After the swap the record waits for the person's
 * Keep ("pending", [CONFIRM_SEC] seconds, like a display-resolution change), and [LauncherWatch] keeps looking for a launcher that
 * crashes or a Home that lands nowhere.
 */
class LauncherSwap(private val sh: (String) -> String, private val log: (String) -> Unit = { }) {
    companion object {
        const val REC = "/data/local/tmp/tvr-launcher.json"
        const val CONFIRM_SEC = 30
        /**
         * The home screen in use, its component on the last line. Android 10+ keeps it as the HOME role: resolve-activity answered
         * Android's picker on an FPT box (Android 14) right after another launcher was installed, while Home still opened TV Home, and
         * a swap took the picker for "the one before". resolve-activity only where there is no role (Android 9 and older).
         */
        const val RESOLVE_HOME = "p=\$(cmd role get-role-holders android.app.role.HOME 2>/dev/null); " +
            "c=\$([ -n \"\$p\" ] && cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | grep -m1 \"^ *\$p/\"); " +
            "if [ -n \"\$c\" ]; then echo \$c; else cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN; fi"
        private val PKG = Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")

        /** What is on screen: Android 10+ names it mFocusedApp, older ones only the resumed activity. */
        const val FOCUSED = "dumpsys activity activities 2>/dev/null | grep -m4 -E 'mFocusedApp|ResumedActivity'"
        /** The component in [FOCUSED]'s answer ("ActivityRecord{1a2b u0 com.x/.Main t12}"), or "" when it named none. */
        fun focused(out: String): String = Regex("\\bu\\d+ ([\\w.]+/[\\w.$]+)").find(out)?.groupValues?.get(1) ?: ""
        /**
         * Makers whose firmware turns the setup wizard's Home back on early in every boot: with launcherx off, the next boot loops at
         * the Google TV logo (a Kalley 43G200 by MOKA, Android 14: no ADB, a USB reflash; cleanyourtv). Home is never taken there.
         * PRISM+ (Singapore) is the same MOKA board (R10G, cleanyourtv examples/prismplus-q65-ultra-r10g); listed by brand in case it reports its own.
         */
        val NO_SWAP = setOf("moka", "kalley", "prismplus", "prism+")

        /** Every [Rec.why] and [via]: the flow's "Cases" list (dev/flows/check.js keeps them equal). */
        val WHYS = listOf("crash", "home", "timeout", "interrupted", "user", "wifi", "wifi_unknown", "cast", "zte", "users", "board", "boot", "keys", "key")
        /** [Rec.why] of a launcher Home reaches through TV Setup's key filter, nothing turned off ([byKey]). */
        const val KEY = "key"
        /**
         * Why the key way was not kept: TV Setup took the rule but its key filter (the add-on's accessibility service, KeysBridge)
         * never came up, so Home would still reach the TV's own launcher (issue #9, TCLs: the rule set, Optimize said "through TV
         * Setup", Home went to the TCL launcher). [KEYS_WAIT] seconds for the add-on to be put on the TV and bound.
         */
        const val KEYS = "keys"
        const val KEYS_WAIT = 45

        /** The words (their id) for why a swap was put back ([Rec.why]): one table for TV Setup, the page and the computer's settings. */
        /** The whys where the swap was refused before anything stayed changed: the TV kept its launcher, it did not "go back". */
        val REFUSED = setOf("wifi", "wifi_unknown", "cast", "zte", "users", "board", "boot", KEYS)
        /** The notice's words for a swap put back with [why]: "kept its launcher" for a refusal, "went back" for an undone swap. */
        fun backId(why: String): String = if (why in REFUSED) "core.fr_launcher_kept" else "core.fr_launcher_back"

        fun whyId(why: String): String =
            if (why in WHYS && why != "user" && why != KEY) "launcher.why_$why" else "launcher.why_user"

        /** The saved Wi-Fi networks and who saved each (`cname=<name>:<uid>`), one shell call: the same lines on Android 9 and 14. */
        const val WIFI_OWNERS = "dumpsys wifi 2>/dev/null | grep -oE 'cname=[^ ]*|ID: [0-9]+ SSID|WifiConfigManager' | sort -u"
        /** Google's own players, where a phone's cast says "Untrusted device" once the wizard is off (cleanyourtv): Home is not taken there. */
        val CAST_HOSTS = setOf("sabrina", "boreal", "kirkwood")
        const val ZTE_WATCHDOG = "com.control.device"                 // see keyWhy
        const val SKW_KEYS = "com.smartdevice.global.service"         // see keyWhy
        /** How many Android users [out] (`pm list users`) lists. */
        fun users(out: String): Int = Regex("UserInfo\\{").findAll(out).count()

        /**
         * Whether the wizard ([uid] its uid) can go off without the TV going offline: false when a saved network is its own (saved during
         * the first setup: forgetting and joining it again in Settings gives it to the system), null when [WIFI_OWNERS] said too little to tell.
         */
        fun wifiSafe(out: String, uid: String): Boolean? {
            val l = out.split("\n").map { it.trim() }
            if (l.none { it == "WifiConfigManager" }) return null       // no dump: never guessed
            val owners = l.filter { it.startsWith("cname=") }
            if (l.any { it.startsWith("ID: ") } && owners.isEmpty()) return null   // networks with no owner shown
            return owners.none { "setupwraith" in it || (uid.isNotEmpty() && it.endsWith(":$uid")) }
        }

        fun last(out: String): String = out.split("\n").dropLastWhile { it.isBlank() }.lastOrNull()?.trim() ?: ""

        /** Home landing on something that is not a launcher a person can use: Android's picker, the setup wizard, the fallback. */
        fun badHome(comp: String): Boolean {
            val pkg = comp.substringBefore('/')
            return pkg.isEmpty() || pkg == "android" || pkg in Setup.WIZARD || "FallbackHome" in comp || factory(comp)
        }

        /**
         * The packages of [comps] (HOME activities, "pkg/cls") a person could pick as the home screen beside the gallery's [known]: never
         * the TV's own launchers (picking one is the way back), the wizard, the fallbacks or a factory screen.
         */
        fun pickable(comps: List<String>, known: Set<String>): List<String> =
            comps.filter { !badHome(it) }.map { it.substringBefore('/') }.filter { it !in known && it !in Setup.LAUNCHER }.distinct()

        private val FACTORY = Regex("factorytest|factory_test|factorymode", RegexOption.IGNORE_CASE)
        /** A maker's factory-test screen that also answers Home (cleanyourtv skips them): never a home to go back to. */
        fun factory(comp: String): Boolean = FACTORY.containsMatchIn(comp)
    }

    /** What is kept on the TV: [state] swapping / pending (waiting for Keep) / kept / rolledback; times are the TV's own clock (seconds). */
    class Rec(val pkg: String, val comp: String, val prev: String, val off: List<String>, val at: Long, val state: String, val until: Long, val why: String) {
        fun json(): JSONObject = JSONObject().put("pkg", pkg).put("comp", comp).put("prev", prev).put("off", JSONArray(off))
            .put("at", at).put("state", state).put("until", until).put("why", why)
        fun with(state: String, until: Long = this.until, why: String = this.why) = Rec(pkg, comp, prev, off, at, state, until, why)
        val active get() = state == "swapping" || state == "pending" || state == "kept"
    }

    /** Why the last [take] never started ("board": the maker's wizard returns every boot; "boot": no launcher can start before the unlock). No record is saved then. */
    var refused = ""
        private set
    /** What the Home button opened when [take] gave up because Home did not land on the new launcher: the app holding it. */
    var opened = ""
        private set
    /** Why the last [take] used TV Setup's key filter, not the swap (cast / zte / board / boot / wifi / wifi_unknown / home); "" for a swap. */
    var via = ""
        private set

    fun tvNow(): Long = sh("date +%s").trim().toLongOrNull() ?: (System.currentTimeMillis() / 1000)

    fun state(): Rec? {
        val raw = sh("cat $REC 2>/dev/null").trim()
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            Rec(o.getString("pkg"), o.optString("comp"), o.optString("prev"), o.optJSONArray("off")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                o.optLong("at"), o.optString("state"), o.optLong("until"), o.optString("why"))
        } catch (e: org.json.JSONException) { null }
    }

    private fun save(r: Rec) { sh("echo '" + r.json().toString().replace("'", "") + "' > $REC") }

    private fun enabled(): Set<String> =
        Pkg.list(sh, "-e")

    private fun homes(): List<String> = (acts.query("android.intent.action.MAIN", "HOME") ?: emptyList()).filter { !factory(it) }
    private val acts = dev.tvremote.core.adb.Activities(sh)

    /** The home screen in use; where the TV cannot say (Android 8 and older) the only launcher it has, else "". */
    fun homeNow(): String {
        val r = last(sh(RESOLVE_HOME))
        return if (r.matches(Regex("[\\w.]+/[\\w.$]+"))) r else (homes().singleOrNull() ?: "")
    }

    /** What the Home button opens for the person: the launcher of a kept key-way record, else the HOME role ([homeNow]). */
    fun homeOpens(): String = state()?.takeIf { it.why == KEY && it.state == "kept" && it.comp in homes() }?.comp ?: homeNow()

    /** Turned off one command per shell call (chained, some TVs' pm stopped after the first), and kept for Setup's Undo. */
    private fun turnOff(p: String) {
        sh(TvUser.disable(p))
        sh("am force-stop $p")
        sh("grep -qx '$p' ${Setup.OFF_LIST} 2>/dev/null || echo '$p' >> ${Setup.OFF_LIST}")
    }

    /**
     * Makes [pkg] (installed) the home screen. True when Home now lands on it, with the record [state] "pending" for [confirmSec] seconds
     * (0: "kept" at once). False when it did not: everything is back as it was, the record says rolledback.
     */
    @Throws(IOException::class)
    fun take(pkg: String, confirmSec: Int = 0, before: String = ""): Boolean {
        if (!PKG.matches(pkg)) throw IOException("bad package name")
        refused = ""; opened = ""; via = ""
        val all = homes()
        val ours = all.firstOrNull { it.startsWith("$pkg/") } ?: run { log("no home activity"); return false }   // not a launcher
        if (state()?.why == KEY) { dropKey(); forget() }              // flow row 1: the launcher picked before lets go of Home, and its record
        keyWhy(all, pkg)?.let { return byKey(pkg, it) }               // rows 2-5: the swap could break the TV
        // [before]: read before its APK went in. Android 9 forgets the home chosen when a new launcher is installed (a BRAVIA then
        // answered its picker), and only Android 10+ keeps it as a role
        val prev = before.takeIf { it.contains('/') && !badHome(it) } ?: homeNow()
        sh("am start -n $ours")                                      // opened once before the old one goes, so Home is never empty
        val on = enabled()
        val wizard = homes().filter { it.substringBefore('/') in Setup.WIZARD }   // components: pm enable / disable take them like packages
        val gone = wizard + Setup.LAUNCHER.filter { it in on && it != pkg }   // only what is on: Google TV's tvlauncher is off from the factory
        val now = tvNow()
        save(Rec(pkg, ours, prev, gone, now, "swapping", 0, ""))      // before the first thing goes off
        for (c in wizard) {
            sh("pm disable ${TvUser.USER} $c 2>&1")
            if (!homes().contains(c)) sh("grep -qx '$c' ${Setup.OFF_LIST} 2>/dev/null || echo '$c' >> ${Setup.OFF_LIST}")   // for Optimize TV's Undo
        }
        val stuck = homes().filter { it in wizard }.map { it.substringBefore('/') }.distinct()
        if (stuck.isNotEmpty()) {                                     // the shell may not turn one screen off: the whole app, if it is safe (see 2.)
            val no = when {
                sh("getprop ro.product.device").trim().lowercase() in CAST_HOSTS -> "cast"
                else -> when (wifiSafe(sh(WIFI_OWNERS), sh("pm list packages -U ${stuck[0]}").substringAfter("uid:", "").trim().substringBefore(','))) {
                    true -> null; false -> "wifi"; null -> "wifi_unknown"
                }
            }
            if (no != null) { log("wizard stays: $no"); rollback(no, gone); return byKey(pkg, no) }   // rows 6-7
            // first in the record, so a rollback turns it on last: back on before launcherx, its RecoveryActivity takes Home, and a reboot
            // there factory-reset a Xiaomi MiTV-MOSR4 (cleanyourtv)
            val off = stuck + gone.filter { it.substringBefore('/') !in stuck }   // its screens stayed on: nothing of theirs to turn back on
            save(Rec(pkg, ours, prev, off, now, "swapping", 0, ""))
            for (p in stuck) turnOff(p)
            if (homes().any { it.substringBefore('/') in stuck }) { log("wizard home stays"); opened = stuck[0]; rollback("home", off); return byKey(pkg, "home") }
        }
        for (p in gone - wizard) turnOff(p)
        sh("cmd package set-home-activity $ours 2>/dev/null")
        // a step that hung for minutes (a box's pm while it dropped its Wi-Fi) outlives the watchdog's patience: it has put everything back
        // meanwhile, and what came after its rollback is undone the same way
        state()?.takeIf { it.state == "rolledback" }?.let { rollback(it.why); return false }
        // the role can name it while the Home key still lands on the wizard (a Google TV Streamer): what opens is the answer
        val shown = if (homeNow().substringBefore('/') == pkg) focused(sh("input keyevent 3; sleep 2; $FOCUSED")) else "-"
        if (shown != "-" && (shown.isEmpty() || !badHome(shown))) {
            val r = state()?.with(if (confirmSec > 0) "pending" else "kept", if (confirmSec > 0) tvNow() + confirmSec else 0) ?: return true
            save(r)
            return true
        }
        opened = (if (shown == "-") homeNow() else shown).substringBefore('/')
        rollback("home", gone)
        return byKey(pkg, "home")                                     // row 8: a TCL Google TV keeps launcherx on Home (issue #9)
    }

    /**
     * Why the swap must not be tried on this TV (flow rows 2-5), or null. The [all] home screens, the launcher [pkg].
     * cast: a Chromecast's casting needs the wizard. zte: a ZTE box factory-resets with no system launcher on. board: a MOKA board's
     * wizard comes back each boot (a boot loop with launcherx off). boot: no FallbackHome and a launcher that cannot start before
     * the unlock (a Vision, CVTE, Android 13: black screen at every boot, cleanyourtv). users: Skyworth's persistent remote service
     * asks launcherx's provider on every hotkey once the TV has a second user (a Google TV profile); with launcherx off that is an
     * NPE, and its crashes walk RescueParty to a settings reset, then a reboot and a factory-reset prompt (Coocaa Y74 Pro, Google TV 14,
     * cleanyourtv issue 2973).
     */
    private fun keyWhy(all: List<String>, pkg: String): String? = when {
        cast() -> "cast"
        Pkg.installed(sh, ZTE_WATCHDOG) -> "zte"
        Pkg.installed(sh, SKW_KEYS) && users(sh("pm list users")) > 1 -> "users"
        sh("getprop ro.product.manufacturer; getprop ro.product.brand").lowercase().split("\n").any { it.trim() in NO_SWAP } -> "board"
        all.none { "FallbackHome" in it } && sh("dumpsys package $pkg 2>/dev/null | grep -c DIRECT_BOOT_AWARE").trim().toIntOrNull() == 0 -> "boot"
        else -> null
    }

    private fun cast(): Boolean = sh("getprop ro.product.device").trim().lowercase() in CAST_HOSTS

    /**
     * The key way (dev/flows/home-screen.md): nothing of the TV's goes off; the role is set (not for "boot": Android starts the role
     * before the unlock) and TV Setup's key filter sends Home to [pkg] (HomeKey there, rule "homekey"). Kept as a record with why
     * [KEY], so the watchdog guards a launcher that crashes and [rollback] lets Home pass again. [why]: the case, kept as [via].
     * An older or no TV Setup: [SetupNeed.Missing], every client's one Install / Update question. False, with [refused], when TV
     * Setup did not take the rule.
     */
    @Throws(IOException::class)
    fun byKey(pkg: String, why: String): Boolean {
        val ours = homes().firstOrNull { it.startsWith("$pkg/") } ?: return false
        val have = SetupNeed.have(sh)
        if (!SetupNeed.ok(have, "homekey")) throw SetupNeed.Missing("homekey", have)
        val prev = state()?.takeIf { it.why != KEY }?.prev?.takeIf { it.contains('/') } ?: homeNow()   // after a rollback: the one before it
        val st = RulesClient.call(sh, org.json.JSONObject().put("do", "homekey").put("pkg", pkg))
        if (st.optString("homekey") != pkg) { log("TV Setup did not take Home: $st"); refused = why; return false }
        if (!filterUp(st)) {                                          // the rule alone sends Home nowhere: never "Home opens it" then
            RulesClient.call(sh, org.json.JSONObject().put("do", "homekey").put("pkg", ""))
            log("TV Setup's key filter did not start"); refused = KEYS; return false
        }
        if (why != "boot") sh("cmd package set-home-activity $ours 2>/dev/null")
        save(Rec(pkg, ours, prev, emptyList(), tvNow(), "kept", 0, KEY))
        via = why
        sh("am start -n $ours")
        log("Home by TV Setup's key filter: $why")
        return true
    }

    /**
     * Whether TV Setup's key filter is running, after the rule [st] was taken: KeyFix.sync puts the add-on on the TV and turns its
     * service on, which can take a while the first time. A TV Setup that does not say ("keys" missing, 0.34.1 and older) is trusted.
     */
    private fun filterUp(st: JSONObject): Boolean {
        if (!st.has(KEYS)) return true
        var now = st
        for (i in 0 until KEYS_WAIT) {
            if (now.optBoolean(KEYS)) return true
            sh("sleep 1")
            now = RulesClient.call(sh, null)
        }
        return now.optBoolean(KEYS)
    }

    /**
     * The key filter lets Home pass again. A Chromecast or ZTE box sent there by a version before the record kept the key way
     * (0.32.1 and older) has no record: those are told by the TV itself.
     */
    fun dropKey() {
        try {
            val key = state()?.why == KEY || cast() || Pkg.installed(sh, ZTE_WATCHDOG)
            if (key && SetupNeed.ok(SetupNeed.have(sh), "homekey")) RulesClient.call(sh, org.json.JSONObject().put("do", "homekey").put("pkg", ""))
        } catch (e: Exception) { }
    }

    /** The person's Keep: the swap stays, and only the watch is left. */
    fun keep() { state()?.takeIf { it.state == "pending" }?.let { save(it.with("kept")) } }

    /**
     * Back to what the record says: the home screens turned off come back (the launchers before the wizard), the home before is chosen
     * again, Home is asked once more. [why]: crash / home / timeout / user / interrupted, for the notice. No record (a swap by an older
     * version or by hand): the TV's own launchers that are installed but off come back, as Undo does.
     */
    @Throws(IOException::class)
    fun rollback(why: String, known: List<String>? = null) {         // known: what the swap under way turned off, if the TV kept no record
        val r = state()
        if (r?.why == KEY) dropKey()                                  // the key way: Home passes to the TV's own again
        // no record: the TV says which of its own are off. A failed read must not decide (nothing is turned on from a guess): once more
        // after 2 s, then the rollback stays undone (the record is kept, the watchdog or the person tries again) and says so
        val off = r?.off ?: known ?: try { val offNow = Pkg.twice { installedOff() }; (Setup.LAUNCHER.toList() + Setup.WIZARD).filter { it in offNow } }
            catch (e: IOException) { log("rollback not done: " + e.message); throw IOException("rollback not done: the TV did not answer (" + e.message + ")") }
        for (p in off.reversed()) sh(TvUser.enable(p))                // home screens before the wizard
        val back = backTo(r, off)
        if (back != null) sh("cmd package set-home-activity $back 2>/dev/null")
        sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
        if (r != null) save(r.with("rolledback", 0, why))
        log("rolled back: $why")
    }

    private fun installedOff(): Set<String> =
        Pkg.list(sh, "-d " + TvUser.USER, empty = true)

    /** The home screen to choose again: the one before, else the first of the TV's own that was turned off (Android TV's tvlauncher). */
    private fun backTo(r: Rec?, off: List<String>): String? {
        val all = homes()
        val prev = r?.prev ?: ""
        if (prev.contains('/') && !badHome(prev) && prev.substringBefore('/') != r?.pkg) all.firstOrNull { it == prev }?.let { return it }
        return all.firstOrNull { it.substringBefore('/') in off && it.substringBefore('/') !in Setup.WIZARD }
            ?: all.firstOrNull { !badHome(it) && it.substringBefore('/') != r?.pkg && it.substringBefore('/') !in Setup.WIZARD }   // the one before unknown: never stay on the one tried
    }

    /** A setup wizard Home turned off by [r] that is on again: some firmware turns it back on at every boot. One shell call. */
    fun wizardBack(r: Rec): Boolean {
        val wiz = r.off.filter { it.substringBefore('/') in Setup.WIZARD }   // its Home screen, or its whole app
        return wiz.isNotEmpty() && homes().any { h -> h in wiz || h.substringBefore('/') in wiz }
    }

    /** What Undo of Optimize TV leaves behind: nothing kept about a home swap. */
    fun forget() { sh("rm -f $REC") }
}

/**
 * The watchdog over a swap ([LauncherSwap.REC]): the TV app runs [check] every few seconds while a record is active. A launcher that
 * crashes twice within five minutes, a Home that resolves to nothing usable, or a confirmation nobody answered ends in [LauncherSwap.rollback],
 * so a launcher that does not work can never be the last thing a person had on their TV. [sh] is the TV's own shell (TV Setup's ADB to itself).
 */
class LauncherWatch(private val sh: (String) -> String) {
    companion object {
        const val WINDOW_SEC = 300L
        const val CRASHES = 2
        private const val GRACE = 8L                                  // the confirmation screen's own timeout comes first
        private const val STALE = 300L                                // a swap that was never finished (one pm call took 84 s on a box)

        /** The seconds at which [pkg] crashed, from `logcat -b crash -v epoch`: a Java crash names its process, a native one is ">>> pkg <<<". */
        fun crashes(logcat: String, pkg: String): List<Long> = logcat.lineSequence().mapNotNull { l ->
            val m = Regex("^\\s*(\\d{9,})\\.\\d+\\s.*?(?:Process: ([\\w.]+), PID|>>> ([\\w.]+) <<<)").find(l) ?: return@mapNotNull null
            if ((m.groupValues[2].ifEmpty { m.groupValues[3] }) == pkg) m.groupValues[1].toLong() else null
        }.toList()

        const val FRESH_MS = 6_000L                                   // two of TV Setup's looks (LauncherGuard, every 3 s) and some
        const val LONG_MS = 10_000L                                   // after a long Home: the TV's own was asked for

        /** The TV's own home screens beside [picked], from all HOME activities ("pkg/cls"): never Android's picker, the wizard, the fallback. */
        fun ownHomes(all: List<String>, picked: String): Set<String> = all.filter { it.substringBefore('/') != picked && !LauncherSwap.badHome(it) }.toSet()

        /**
         * Key way: the TV's own home screen came up by itself, so the launcher picked opens over it. Android goes there wherever
         * the role points on some TVs: at boot, on Back out of an app, when an app closes (an AOC: Back out of Wi-Fi settings
         * opened from TV Home). [fronts]: when and which activity ("pkg/cls") came to the front lately, oldest first;
         * [longAt]: the last long Home, which is the person asking for the TV's own.
         */
        fun cameHome(fronts: List<Pair<Long, String>>, homes: Set<String>, longAt: Long, now: Long): Boolean {
            val (at, comp) = fronts.lastOrNull() ?: return false
            return comp in homes && now - at in 0..FRESH_MS && (longAt == 0L || at - longAt !in 0..LONG_MS)
        }

        /** Whether [times] holds [CRASHES] crashes within [WINDOW_SEC] of each other, none before [since]. */
        fun looping(times: List<Long>, since: Long): Boolean {
            val t = times.filter { it >= since }.sorted()
            return t.size >= CRASHES && (0..t.size - CRASHES).any { t[it + CRASHES - 1] - t[it] <= WINDOW_SEC }
        }
    }

    private val swap = LauncherSwap(sh)

    /** Whether there is a record to watch (a swap that is not over). */
    fun active(): Boolean = swap.state()?.active == true

    /** One look. The reason it rolled back (crash / home / timeout / interrupted), or null: nothing to do or all well. */
    @Throws(IOException::class)
    fun check(): String? {
        val r = swap.state()?.takeIf { it.active } ?: return null
        val now = swap.tvNow()
        val key = r.why == LauncherSwap.KEY                           // Home is the key filter's: where Android sends it does not matter
        val why = when {
            r.state == "swapping" -> if (now - r.at > STALE) "interrupted" else null
            r.state == "pending" && now > r.until + GRACE -> "timeout"
            !key && LauncherSwap.badHome(swap.homeNow()) -> "home"
            !key && r.state != "swapping" && swap.wizardBack(r) -> "home"
            looping(crashes(sh("logcat -b crash -d -v epoch 2>/dev/null"), r.pkg), r.at) -> "crash"
            else -> null
        } ?: return null
        swap.rollback(why)
        // the TV took Home back (a TCL turns its wizard on again): the key filter sends it to the launcher instead, nothing to tell
        if (why == "home" && try { swap.byKey(r.pkg, "home") } catch (e: IOException) { false }) return null
        return if (why == "home" && swap.refused == LauncherSwap.KEYS) LauncherSwap.KEYS else why   // the key filter did not start: say that
    }
}
