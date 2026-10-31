package dev.tvremote.setup.keys

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.tvremote.home.Target
import dev.tvremote.setup.InputsActivity
import dev.tvremote.setup.KeyTest
import dev.tvremote.setup.MicVoice
import dev.tvremote.setup.home.HomeKey
import dev.tvremote.setup.home.HomeKeys
import dev.tvremote.setup.keys.KeysProtocol
import org.xmlpull.v1.XmlPullParser

/**
 * Remote buttons that only the stock launcher answered (Input, YouTube, Netflix...). Android sends some keys as a "global key" to
 * one component named in the framework's global_keys.xml, in every app, and eats them there: when that component is gone (Optimize
 * TV turned Google TV's launcher off, a home swap) the button is dead everywhere. [KeysBridge] brings them back.
 *
 * This file decides, with no Android type in [plan] so a test can run it: a key is taken over only when the system maps it to a
 * component that is not there (not installed, or turned off) AND we have something to do for it. A key that still works is
 * never touched, and every other key passes. [sync] keeps the service on in the secure settings only while there is such a key
 * (or "hold to use Input" is on, [HoldKeys]), so a TV whose buttons all work pays nothing for it (every key of a filtering service waits for its answer).
 */
object KeyFix {
    /** What a key does when it is dead: [input] the TV's input picker, [app] the first of [pkgs] that is installed, [home], [settings]. */
    class Rule(val act: String, val pkgs: List<String> = emptyList(), val mine: Boolean = false)   // mine: the person gave this key its app (a toast says so)

    /** [LAUNCHER]: the home screen of pkgs[0] ([HomeKey]: Home where the swap is not possible). */
    const val INPUT = "input"; const val APP = "app"; const val HOME = "home"; const val SETTINGS = "settings"; const val LAUNCHER = "launcher"
    /** [CONTROL]: TV Home's Control Center over the app in front (the person gave the key [Target.CONTROL]). */
    const val CONTROL = "controlcenter"
    /** A key the person gave [target] (TV Home's map): an app, or TV Home's action. */
    fun userRule(target: String) = if (target == Target.CONTROL) Rule(CONTROL, listOf(target), true) else Rule(APP, listOf(target), true)

    /** By key code, as the stock launcher would answer them (cleanyourtv's list, checked on Google TV and Xiaomi remotes). */
    val RULES: Map<Int, Rule> = mapOf(
        170 to Rule(INPUT),                                           // KEYCODE_TV
        178 to Rule(INPUT),                                           // KEYCODE_TV_INPUT
        190 to Rule(APP, listOf("com.google.android.youtube.tv")),    // KEYCODE_BUTTON_3: the YouTube button of Google TV remotes
        191 to Rule(APP, listOf("com.netflix.ninja")),                // KEYCODE_BUTTON_4: Netflix
        195 to Rule(APP, listOf("com.amazon.amazonvideo.livingroom")),   // KEYCODE_BUTTON_8: Prime Video
        192 to Rule(HOME),                                            // KEYCODE_BUTTON_5: Xiaomi's PatchWall button
        284 to Rule(HOME),                                            // KEYCODE_ALL_APPS
        176 to Rule(SETTINGS))                                        // KEYCODE_SETTINGS

    /**
     * Keys that one model's remote sends differently and nobody answers (not a dead global key, so [plan]'s table does not see them):
     * a Xiaomi MiTV-MOOQ0's Input button is KEYCODE_MEDIA_RECORD (130, cleanyourtv #1368). Matched by Build.MODEL or Build.DEVICE; other TVs never.
     */
    class DeviceKey(val model: String, val device: String, val code: Int, val rule: Rule)
    val DEVICE_KEYS = listOf(DeviceKey("MiTV-MOOQ0", "croods", 130, Rule(INPUT)))

    /** [DEVICE_KEYS] and the site's list ([KeyList], fixed with no new APK; it wins on the same key) of this [model] / [device]: code → rule. */
    fun forDevice(model: String?, device: String?, listed: List<DeviceKey> = KeyList.now.keys): Map<Int, Rule> =
        (DEVICE_KEYS + listed).filter { (it.model.isNotEmpty() && it.model.equals(model, true)) || (it.device.isNotEmpty() && it.device.equals(device, true)) }
            .associate { it.code to it.rule }

    /** The key names global_keys.xml uses, for the codes of [RULES] (the file is written with names, not numbers). */
    val NAMES: Map<String, Int> = mapOf(
        "KEYCODE_TV" to 170, "KEYCODE_TV_INPUT" to 178, "KEYCODE_BUTTON_3" to 190, "KEYCODE_BUTTON_4" to 191,
        "KEYCODE_BUTTON_8" to 195, "KEYCODE_BUTTON_5" to 192, "KEYCODE_ALL_APPS" to 284, "KEYCODE_SETTINGS" to 176)

    /**
     * The rules in force: the keys of [RULES] that [mapped] (code → "package/class" the system sends it to) sends to a component
     * [alive] says is gone, and, for an app key, with its app [launchable] (the key then does nothing, as before, when it is not).
     * Returns the rule with the package that will open.
     */
    fun plan(mapped: Map<Int, String>, alive: (String) -> Boolean, launchable: (String) -> Boolean, user: Map<Int, String> = emptyMap(),
             device: Map<Int, Rule> = emptyMap(), home: String? = null): Map<Int, Rule> {
        val out = HashMap<Int, Rule>(device)
        if (home != null) out[HomeKey.CODE] = Rule(LAUNCHER, listOf(home))
        for ((code, rule) in RULES) {
            val to = mapped[code] ?: continue                         // not a global key here: the focused app gets it, nothing to fix
            if (to != NOBODY && alive(to)) continue                   // still has its handler: not ours to take
            if (rule.act == APP) {
                val pkg = rule.pkgs.firstOrNull(launchable) ?: continue
                out[code] = Rule(APP, listOf(pkg))
            } else out[code] = rule
        }
        // the person's own key → app (TV Home's "keyapps"): over a dead key's old job or an app button's, never over another key that works
        val free = offered(mapped, alive)
        for ((code, pkg) in user) if (code in COLOUR || code in free) if (launchable(pkg)) out[code] = userRule(pkg)
        return out
    }

    /** What a real press of [code] does: the plan (TV Home's map laid over the dead keys' rules, as [plan] made it); null = nothing of ours, send the key plainly. */
    fun decide(plan: Map<Int, Rule>, code: Int): Rule? = plan[code]

    /** Does what [r] says. Called from the key filter ([KeysBridge]) and for a key a client sent ([KeysBridge.press]). */
    fun run(c: Context, r: Rule) {
        fun start(i: Intent?) { if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        try {
            when (r.act) {
                INPUT -> inputs(c, ::start)
                APP -> start(c.packageManager.getLeanbackLaunchIntentForPackage(r.pkgs[0]) ?: c.packageManager.getLaunchIntentForPackage(r.pkgs[0]))
                HOME -> start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                SETTINGS -> start(Intent(Settings.ACTION_SETTINGS))
                LAUNCHER -> start(HomeKey.intent(c, r.pkgs[0]))
                CONTROL -> start(Intent().setClassName("dev.tvremote.home", "dev.tvremote.home.ControlCenterActivity"))
            }
        } catch (e: Exception) { }
    }

    /** The system's input picker; where the TV has none, ours (the TV's inputs as the TV lists them), else the Settings. */
    private fun inputs(c: Context, start: (Intent?) -> Unit) {
        try { start(Intent("com.android.tv.action.VIEW_INPUTS")); return } catch (e: Exception) { }
        val have = try { (c.getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager)?.tvInputList?.isNotEmpty() == true } catch (e: Exception) { false }
        start(if (have) Intent(c, InputsActivity::class.java) else Intent(Settings.ACTION_SETTINGS))
    }

    /** Colour keys: no app answers them in every app, so they are the person's to give. Numbers are TV Home's alone (it gets them, nobody else filters them). */
    val COLOUR = setOf(183, 184, 185, 186)                            // PROG_RED, GREEN, YELLOW, BLUE

    /** The keys of [RULES] whose global handler is gone (or none of the two, when [mapped] does not name it): the dead ones. */
    fun free(mapped: Map<Int, String>, alive: (String) -> Boolean): Set<Int> =
        RULES.keys.filter { c -> mapped[c]?.let { it == NOBODY || !alive(it) } == true }.toSet()

    /**
     * A remote's button for one app (YouTube, Netflix, Prime Video): the person may give it another app even while it works (a
     * Chromecast's remote has no colour keys, these are all it has); left alone, it keeps opening its own app.
     */
    val APP_KEYS = setOf(190, 191, 195)

    /** Settings: the person may give it TV Home's Control Center (or an app) while it works too; the Control Center keeps a way to the TV's settings. */
    const val SETTINGS_KEY = 176

    /** The keys of [RULES] TV Home may offer: the dead ones, the app buttons and Settings where this TV knows them (a global key here). */
    fun offered(mapped: Map<Int, String>, alive: (String) -> Boolean): Set<Int> = free(mapped, alive) + (APP_KEYS + SETTINGS_KEY).filter { it in mapped }

    /** What a person may give an app through TV Setup: the colour keys, the dead ones and the app buttons; every other key keeps its own way. */
    fun allowed(mapped: Map<Int, String>, alive: (String) -> Boolean): Set<Int> = COLOUR + offered(mapped, alive)

    /** global_keys.xml's `<key keyCode="KEYCODE_X" component="pkg/cls" />` lines → code to component, for the names of [NAMES] (and plain numbers). */
    fun parse(p: XmlPullParser): Map<Int, String> {
        val keys = ArrayList<Pair<String?, String?>>()
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && p.name == "key") keys.add(p.getAttributeValue(null, "keyCode") to p.getAttributeValue(null, "component"))
            e = p.next()
        }
        return byCode(keys)
    }

    /** (name or number, component) pairs → code to component, for the codes of [RULES] only (a name we do not know is none of our business). */
    fun byCode(keys: List<Pair<String?, String?>>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for ((name, comp) in keys) {
            val code = if (name == null) null else NAMES[name] ?: name.toIntOrNull()
            if (code != null && comp != null && code in RULES) out[code] = comp
        }
        return out
    }

    /**
     * KEYCODE_ALL_APPS where it is no global key (a BRAVIA on Android 9): the window manager sends it to the apps view of the system's
     * own launcher, so with that launcher off (Optimize TV, a swap) nobody answers ACTION_ALL_APPS and the key does nothing. [NOBODY]
     * stands for that answer in the map: dead, whatever [alive] says.
     */
    const val NOBODY = "-"
    fun withAllApps(mapped: Map<Int, String>, answered: Boolean): Map<Int, String> =
        if (284 in mapped || answered) mapped else mapped + (284 to NOBODY)

    /** [systemMap] with the Apps key added where it is dead outside the table: what [plan] and [free] are given on the TV. */
    fun keyMap(c: Context): Map<Int, String> = withAllApps(systemMap(),
        try { c.packageManager.queryIntentActivities(Intent(Intent.ACTION_ALL_APPS), 0).isNotEmpty() } catch (e: Exception) { true })

    /** The system's own table (overlays of the maker included, as far as the framework shows them to an app). Empty when it cannot be read. */
    fun systemMap(): Map<Int, String> = try {
        val r = Resources.getSystem()
        val id = r.getIdentifier("global_keys", "xml", "android")
        if (id == 0) emptyMap() else r.getXml(id).use { parse(it) }
    } catch (e: Exception) { emptyMap() }

    /** A component that Android would still deliver the key's broadcast to: installed, its package and itself turned on. */
    fun alive(c: Context, comp: String): Boolean {
        val cn = ComponentName.unflattenFromString(comp) ?: return true   // not a name we understand: leave the key alone
        return try {
            val i = c.packageManager.getReceiverInfo(cn, 0)           // 0: a turned off one is "not found"
            i.enabled && i.applicationInfo.enabled
        } catch (e: PackageManager.NameNotFoundException) { false } catch (e: Exception) { true }
    }

    fun launchable(c: Context, pkg: String): Boolean = if (pkg == Target.CONTROL) homeThere(c) else c.packageManager.getLeanbackLaunchIntentForPackage(pkg) != null
        || c.packageManager.getLaunchIntentForPackage(pkg) != null

    /** The Control Center is TV Home's: no key is taken for it where TV Home is not installed. */
    private fun homeThere(c: Context) = try { c.packageManager.getPackageInfo("dev.tvremote.home", 0); true } catch (e: Exception) { false }

    fun current(c: Context): Map<Int, Rule> = plan(keyMap(c), { alive(c, it) }, { launchable(c, it) }, HomeKeys.keys(c), forDevice(Build.MODEL, Build.DEVICE), HomeKey.pkg(c))

    /** The numbers the person gave an app (TV Home's map): outside TV Home a hold opens it ([HomeKey.DIGITS]). */
    fun digits(c: Context): Map<Int, String> = HomeKeys.keys(c).filter { (k, p) -> k in HomeKey.DIGITS && p != Target.CONTROL && launchable(c, p) }

    // ---- on or off -------------------------------------------------------------------------------------------------

    private const val PREFS = "keyfix"
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    /** The accessibility service lives in the add-on TV Setup Keys ([KeysAddon]), not here: this app declares none (dev/decisions/apps.md "TV Setup declares no"). */
    val SERVICE = "${KeysProtocol.ADDON}/${KeysProtocol.ADDON}.KeyFixService"

    /** Wanted unless a person switched it off on TV Setup's screen. */
    fun wanted(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)
    fun want(c: Context, on: Boolean) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply(); sync(c) }
    fun can(c: Context): Boolean = c.packageManager.checkPermission(PERM, c.packageName) == PackageManager.PERMISSION_GRANTED

    /**
     * The list with [SERVICE] put in or taken out, the others (KeyCatch while the screen is off, TalkBack) as they were. The entries
     * of the versions that kept the services inside TV Setup ([stale]) are dropped on the way: the move of a TV that was updated.
     */
    fun list(was: String?, on: Boolean): String {
        val keep = (was ?: "").split(":").filter { it.isNotEmpty() && it != "null" && !isOurs(it) && !stale(it) }
        return (if (on) keep + SERVICE else keep).joinToString(":")
    }

    /** Android rewrites the list in its short form ("dev.tvremote.setup.keys/.KeyFixService") once the service is bound. */
    fun isOurs(entry: String): Boolean = entry == SERVICE || entry == "${KeysProtocol.ADDON}/.KeyFixService"

    /** An entry of TV Setup before the move: its services were `dev.tvremote.setup/...KeyFixService` and `...KeyCatch`, which no longer exist. */
    fun stale(entry: String): Boolean = entry.startsWith("${KeysProtocol.SETUP}/") && Regex("[.](KeyFixService|KeyCatch)$").containsMatchIn(entry)

    fun has(was: String?): Boolean = was?.split(":")?.any { isOurs(it) } == true
    fun hasStale(was: String?): Boolean = was?.split(":")?.any { stale(it) } == true

    @Volatile var prepared = false

    /**
     * Android 13+ keeps an app that was not put there by a store from switching an accessibility service on (a firmware logs it as
     * "disallowed by device admin policy", as on the FPT box): the shell lifts that for this app, once, while there is ADB.
     */
    fun prepare(c: Context, adb: dev.tvremote.core.adb.Adb) {
        // APP_AUTO_START: a TCL's firmware kept the service from being bound (TclAppBoot isAllow = 'false') though the setting
        // named it; an op the TV does not know is an error that changes nothing. SYSTEM_ALERT_WINDOW: Android 14 blocks an app in
        // the background from starting an activity ("Background activity launch blocked"), and every key that opens an app or the
        // launcher starts one from here; the overlay permission is what exempts it (measured on the emulator: Home did nothing without)
        try { adb.shell("appops set ${c.packageName} ACCESS_RESTRICTED_SETTINGS allow 2>&1; appops set ${c.packageName} APP_AUTO_START allow 2>&1; appops set ${c.packageName} SYSTEM_ALERT_WINDOW allow 2>&1"); prepared = true } catch (e: Exception) { }
        KeysAddon.prepare(adb)                                        // the service is the add-on's: the same for its package, once it is installed
    }

    private var wrote = 0L

    /** Turn the service on while a dead key needs it and it is wanted, off when not; nothing when this app may not write the settings. */
    fun sync(c: Context) {
        if (!can(c)) return
        try {
            val r = c.contentResolver
            val was = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val has = has(was)
            val old = hasStale(was)                                   // updated from a TV Setup that had the services inside: move the entry
            val map = keyMap(c)
            val now0 = plan(map, { alive(c, it) }, { launchable(c, it) }, HomeKeys.keys(c), forDevice(Build.MODEL, Build.DEVICE), HomeKey.pkg(c)) + digits(c).mapValues { userRule(it.value) }
            android.util.Log.i("KeyFix", "global keys ${map.size}, to bring back ${now0.keys}, service on=$has")   // adb logcat -s KeyFix
            val need = (wanted(c) && now0.isNotEmpty()) || HoldKeys.on(c) || MicVoice.on(c)   // a dead key to bring back, "hold to use Input" (HoldKeys), or the mic button (MicVoice)
                || KeyTest.live()                                     // the key tester is open: Home and the app buttons are shown through the filter
                || HomeKey.pkg(c) != null                             // Home to the launcher picked: asked for on the page, not by the dead-key switch
            if (need && !KeysAddon.current(c)) { KeysAddon.installThen(c) { sync(c) }; return }   // the service is in the add-on: put it on the TV first (it comes back here)
            if (need == has && !old) return
            val t = System.currentTimeMillis()
            if (need && !old && t - wrote < 60000) return        // the system took it out again just now (a policy): not every few seconds
            if (need) wrote = t
            val now = list(was, need)
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, now.ifEmpty { null })
            Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, if (now.isEmpty()) 0 else 1)
        } catch (e: Exception) { android.util.Log.w("KeyFix", "no change: $e") }   // the grant was taken back: the keys are as they were before
    }
}

/** Looks again (a few seconds after the last change) when a package comes, goes or is turned off, or the accessibility list changes under us. */
internal class KeyFixWatch(private val c: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private val again = Runnable { Thread({ KeyFix.sync(c) }, "keyfix-sync").apply { isDaemon = true }.start() }
    private fun soon() { ui.removeCallbacks(again); ui.postDelayed(again, 3000) }
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { soon() }
    }
    private val list = object : ContentObserver(ui) {
        override fun onChange(self: Boolean) { soon() }               // the system took us out, or the list was rewritten: put it right
    }

    fun start() {
        try {
            c.registerReceiver(packages, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            })
            c.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, list)
        } catch (e: Exception) { }
        soon()
    }

    fun stop() {
        try { c.unregisterReceiver(packages) } catch (e: Exception) { }
        try { c.contentResolver.unregisterContentObserver(list) } catch (e: Exception) { }
        ui.removeCallbacks(again)
    }
}
