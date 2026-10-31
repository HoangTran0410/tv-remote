package dev.tvremote.setup.home

import android.app.usage.UsageEvents
import android.content.Context
import android.content.Intent
import dev.tvremote.core.T
import dev.tvremote.core.ontv.LauncherWatch
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.rules.Rules
import dev.tvremote.setup.rules.Usage

/**
 * The Home key where the home screen cannot be swapped (rule "homekey" in [Rules]): on a Chromecast or Google TV Streamer the swap
 * needs the first-run setup app off, and casting from a phone then fails (LauncherSwap's "cast"). Google TV's launcherx keeps Home
 * there whatever the role names, so people put Button Mapper in to send Home to TV Home. The key filter does it instead ([KeysBridge]):
 * a short press opens the launcher the person picked, a long one is the TV's own (its panel), sent past the filter over ADB. Picking
 * the TV's own launcher again on the page clears the rule (Manage.homeSet); an uninstalled or turned off launcher lets Home pass.
 *
 * Also what a person's own key says when it opens an app outside TV Home, and the numbers held in other apps ([DIGITS]).
 */
internal object HomeKey {
    const val CODE = 3                                                // KEYCODE_HOME
    const val LONG_MS = 500L                                          // a press held this long is the TV's own long press
    val PKG = Regex("[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+")
    const val TV_HOME = "dev.tvremote.home"

    /** KEYCODE_0..9: on TV Home's screen a press opens their app (TV Home's own keys); in other apps only a hold of [HoldKeys.HOLD_MS] does. */
    val DIGITS = 7..16

    /** The launcher Home should open, when the rule names one that is still there with a home screen. */
    fun pkg(c: Context): String? = try { Rules.load(c).optString("homekey") } catch (e: Exception) { null }
        ?.takeIf { it.isNotEmpty() && intent(c, it) != null }

    fun intent(c: Context, pkg: String): Intent? = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
        .takeIf { i -> try { c.packageManager.resolveActivity(i, 0) != null } catch (e: Exception) { false } }

    /** The TV's own long press of Home: `input` injects after the filter, so it reaches the system as if the filter were not there. */
    @Volatile private var longAt = 0L                                  // the last long press: the TV's own was asked for

    fun longPress() { longAt = System.currentTimeMillis(); Thread({ try { RemoteService.adb()?.shell("input keyevent --longpress $CODE", 5000) } catch (e: Exception) { } }, "homekey-long").apply { isDaemon = true }.start() }

    /**
     * The TV started or woke on its own home screen (Google's, or any [own]: an AOC's; Android opens it, not the key): the launcher picked comes up instead,
     * a little later than the home screen and before AutoOpen's app, which then opens over it as on any TV.
     */
    fun trigger(c: Context, event: String) {                          // only over that home screen: a TV that woke into an input or an app keeps it
        if (pkg(c) == null) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread({
                val p = pkg(c) ?: return@Thread
                val front = Usage.front(c)
                android.util.Log.i("HomeKey", "$event: on screen '$front'")              // adb logcat -s HomeKey
                if (front in dev.tvremote.core.ontv.Setup.LAUNCHER || front in own(c, p).map { it.substringBefore('/') }) try { c.startActivity(intent(c, p)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return@Thread) } catch (e: Exception) { }
            }, "homekey-boot").apply { isDaemon = true }.start()
        }, if (event == "boot") 5000L else 1200L)
    }

    /** The TV's own home screens ("pkg/cls"), the one the person picked left out. */
    fun own(c: Context, picked: String): Set<String> = try {
        val all = c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName + "/" + it.activityInfo.name }
        LauncherWatch.ownHomes(all, picked)
    } catch (e: Exception) { emptySet() }

    /** One look from [LauncherGuard]: the TV's own home screen just came up by itself (Back out of an app, an app closing), the launcher picked opens over it. */
    fun landed(c: Context) {
        val p = pkg(c) ?: return
        val now = System.currentTimeMillis()
        val fronts = ArrayList<Pair<Long, String>>()
        try { Usage.events(c, now - LauncherWatch.FRESH_MS, now) { e -> if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) fronts += e.timeStamp to "${e.packageName}/${e.className}" } } catch (e: Exception) { return }
        if (!LauncherWatch.cameHome(fronts, own(c, p), longAt, now)) return
        android.util.Log.i("HomeKey", "back on '${fronts.last().second}'")   // adb logcat -s HomeKey
        try { c.startActivity(intent(c, p)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return) } catch (e: Exception) { }
    }

    /** "TV Setup opened YouTube (Red key)": the person knows the TV did not jump by itself (VTVgo on screen, a key pressed, YouTube came). */
    fun said(c: Context, code: Int, pkg: String): String {
        val app = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        return T("keys.opened", "app" to app, "key" to name(code))
    }

    private val NAMES = mapOf(183 to "home.key_red", 184 to "home.key_green", 185 to "home.key_yellow", 186 to "home.key_blue", 170 to "home.key_tv",
        178 to "home.key_input", 190 to "home.key_btn3", 191 to "home.key_btn4", 195 to "home.key_btn8", 192 to "home.key_btn5", 284 to "home.key_allapps", 176 to "home.key_settings")
    fun name(code: Int): String = NAMES[code]?.let { T(it) } ?: T("keys.digit", "n" to (code - 7))   // the names of js/home.js HOME_KEY_WORDS

    /** Whether to say [said] after a key opened its app: not over TV Home, where the person chose the key and sees the app come. */
    fun say(front: String): Boolean = front != TV_HOME
}
