package dev.tvremote.setup.home

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.Target
import dev.tvremote.setup.WelcomeState
import dev.tvremote.setup.rules.Usage
import org.json.JSONObject

/**
 * What TV Home asks of TV Setup through [HomeInfo] ("keys"): remote keys that open an app, and one app that opens each time the
 * TV is turned on. TV Home has no ADB, no key filter and is not running in standby; this app has all three. Kept here (prefs) so
 * it works after a reboot with TV Home not yet up. TV Home pushes the whole setting on every change and on every start.
 */
internal object HomeKeys {
    private const val PREFS = "homekeys"

    @Synchronized
    fun save(c: Context, raw: String): Boolean = try {
        val o = HomeConfig.clean(JSONObject(raw))                     // the same limits as on the TV Home side: one file, HomeConfig
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("conf", o.toString()).commit()
    } catch (e: Exception) { false }

    private fun load(c: Context): JSONObject = try {
        JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("conf", "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    /** The key → app map the person wants; empty while the switch is off. */
    fun keys(c: Context): Map<Int, String> = all(c).filterValues { Target.kind(it).let { k -> k == Target.Kind.APP || k == Target.Kind.ACTION } }   // apps and TV Home's actions: the key filter's rule can start nothing else (KeyFix); see [open] for the others

    /** Every target the person gave a key, shortcuts and live widgets too. */
    fun all(c: Context): Map<Int, String> = load(c).let { if (it.optBoolean("keyon")) HomeConfig.keyApps(it.optJSONObject("keyapps")) else emptyMap() }

    /**
     * Opens [t], any [Target]: an app by its launch intent; a launcher shortcut or live widget through TV Home (only the home app may
     * start another app's shortcut, LauncherApps), whose OpenActivity checks [t] is in its own settings. False: nothing to start.
     */
    fun open(c: Context, t: String): Boolean {
        val pm = c.packageManager
        val intent = if (t.isEmpty()) Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME) else when (Target.kind(t)) {   // empty: the home screen, as the Home key
            Target.Kind.APP -> pm.getLeanbackLaunchIntentForPackage(t) ?: pm.getLaunchIntentForPackage(t)
            Target.Kind.PIN, Target.Kind.TILE ->
                Intent().setClassName("dev.tvremote.home", "dev.tvremote.home.OpenActivity").putExtra("t", t).takeIf { launchable(c, "dev.tvremote.home") }
            Target.Kind.ACTION -> Intent().setClassName("dev.tvremote.home", "dev.tvremote.home.ControlCenterActivity").takeIf { launchable(c, "dev.tvremote.home") }
            null -> null
        } ?: return false
        return try { c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    }

    /** Whether [t] can be opened now: an app is installed, a shortcut or live widget needs TV Home. */
    fun can(c: Context, t: String): Boolean = if (t.isEmpty()) true else when (Target.kind(t)) {
        null -> false
        Target.Kind.APP -> c.packageManager.getLeanbackLaunchIntentForPackage(t) != null || c.packageManager.getLaunchIntentForPackage(t) != null
        else -> launchable(c, "dev.tvremote.home")
    }

    private fun launchable(c: Context, pkg: String) = try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    fun auto(c: Context): Pair<Boolean, String> = load(c).let { (it.optBoolean("autoon") || it.optBoolean("autoall")) to it.optString("autoapp") }   // "always" with no app = TV Home

    /** The "always" switch: open even when the TV woke into another app or input ([AutoOpen.decide]). Off by default. */
    fun always(c: Context): Boolean = load(c).optBoolean("autoall")

    /** The service has to run for these: the key filter's list is kept by [KeyFixWatch], waking is heard by [AutoOpenWatch]. */
    fun active(c: Context): Boolean = all(c).isNotEmpty() || auto(c).let { it.first && (it.second.isNotEmpty() || always(c)) }
}

/**
 * "Open an app when the TV is turned on". Fires once per power-on: after the boot ([BootReceiver]) and after waking from standby
 * ([AutoOpenWatch]). What it never does is in [decide], with no Android type so a test can run it. Pressing Home later does nothing
 * special: this runs on a power-on event only, so TV Home comes back and nothing starts the app again.
 */
internal object AutoOpen {
    private const val PREFS = "autoopen"
    private const val TAG = "AutoOpen"
    /** Two events inside this are one power-on (the screen comes on right after the boot, and a TV flickers its screen as it wakes). */
    const val GUARD_MS = 120_000L

    enum class Why { OPEN, OFF, MISSING, REPEAT, BUSY, SAME }

    /**
     * Whether to open [pkg] now. [front] is the app on screen when we look (empty: none known), [homes] the launchers, [ignore] screens
     * that are not an app a person chose (the system's bars, this app); [sinceLast] how long ago we last opened one in this run of the TV.
     * A TV that woke into another app (an HDMI-CEC input, a cast, the app it was left in) keeps it.
     */
    fun decide(on: Boolean, pkg: String, launchable: Boolean, front: String, homes: Set<String>, ignore: Set<String>, sinceLast: Long?,
               always: Boolean = false, homeNow: String = ""): Why = when {
        !on || (pkg.isEmpty() && !always) -> Why.OFF                  // no app and no "always": nothing chosen. "always" with no app: TV Home
        !launchable -> Why.MISSING                                    // uninstalled since it was chosen: nothing, and no message at the wrong time
        sinceLast != null && sinceLast in 0 until GUARD_MS -> Why.REPEAT
        front == pkg || (pkg.isEmpty() && front.isNotEmpty() && front == homeNow) -> Why.SAME   // [homeNow]: the launcher Home opens now (not [homes]: a Settings app may answer the Home category too)
        always && front in CAST -> Why.BUSY                           // "always" still never pulls a cast that woke the TV off the screen (a TV that woke into an input or an app is the point of it)
        !always && front.isNotEmpty() && front !in homes && front !in ignore -> Why.BUSY
        else -> Why.OPEN
    }

    /** Chromecast built-in's receiver: a cast that woke the TV is on screen at the first look. Cheap to see, so kept; an HDMI input has no package to see. */
    val CAST = setOf("com.google.android.apps.mediashell")

    private val ui by lazy { Handler(Looper.getMainLooper()) }       // lazy: a unit test of [decide] has no Android
    private var lastBoot = -1
    private var lastAt = 0L

    private fun bootCount(c: Context): Int = try { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT) } catch (e: Exception) { 0 }

    /** The TV started ("boot") or came out of standby ("wake"): a little later (the home screen must be up, and a welcome screen ends first) it looks and may open the app. */
    fun trigger(c: Context, event: String) {
        if (!HomeKeys.auto(c).first) return
        val welcome = if (WelcomeState.active(c)) WelcomeState.settings(c).optInt("seconds", 0) * 1000L + 2500 else 0L
        ui.postDelayed({ try { run(c.applicationContext, event) } catch (e: Exception) { Log.w(TAG, "no open: $e") } }, (if (event == "boot") 7000L else 1800L) + welcome)
    }

    @Synchronized
    private fun run(c: Context, event: String) {
        val (on, pkg) = HomeKeys.auto(c)
        val always = HomeKeys.always(c)
        val now = SystemClock.elapsedRealtime()
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = bootCount(c)
        if (lastBoot < 0) { lastBoot = p.getInt("last_boot", -1); lastAt = p.getLong("last_at", 0L) }
        val ago = if (lastBoot == boot) now - lastAt else null        // elapsedRealtime restarts with the TV: a new run has no "last"
        val homes = homes(c)
        val front = front(c)
        val verdict = decide(on, pkg, HomeKeys.can(c, pkg), front, homes, setOf("com.android.systemui", c.packageName), ago, always, defaultHome(c))
        Log.i(TAG, "$event: $verdict ($pkg, always: $always, on screen: '$front')")                         // adb logcat -s AutoOpen
        if (verdict != Why.OPEN || !HomeKeys.open(c, pkg)) return
        lastBoot = boot; lastAt = now
        p.edit().putInt("last_boot", boot).putLong("last_at", now).apply()
    }

    private fun defaultHome(c: Context): String = try {
        c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName ?: ""
    } catch (e: Exception) { "" }

    private fun homes(c: Context): Set<String> = try {
        c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun front(c: Context): String = Usage.front(c)
}

/** Hears the screen come on (from [RemoteService], as [WelcomeWatch] does: a manifest receiver cannot hear it). The boot is [BootReceiver]'s. */
internal class AutoOpenWatch(private val c: Context) {
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) { AutoOpen.trigger(c, "wake"); HomeKey.trigger(c, "wake") }
    }

    fun start() { try { c.registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_ON)) } catch (e: Exception) { } }
    fun stop() { try { c.unregisterReceiver(screen) } catch (e: Exception) { } }
}
