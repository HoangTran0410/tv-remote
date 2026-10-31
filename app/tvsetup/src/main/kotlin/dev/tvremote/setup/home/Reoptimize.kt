package dev.tvremote.setup.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.tvremote.core.T
import dev.tvremote.core.WireLog
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.ontv.LauncherSwap
import dev.tvremote.core.ontv.Notify
import dev.tvremote.core.ontv.Setup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps Optimize TV's result after the TV's firmware updates. An update (or the brand) turns the apps Optimize TV switched off back
 * on, silently, and gives Home back to the brand's launcher. From RemoteService, like [Guard]: at its start (a boot) and once a day
 * it reads [Setup.OFF_LIST], the file Optimize TV keeps for its Undo, and when the firmware's fingerprint changed since the last
 * look turns off again what is on again (on the same firmware an app on again was turned on by the person). Only what that file names
 * (the person chose those apps), never a new app; Undo empties the file, so Undo ends this too. A package that comes back again within
 * a day of our turning it off is given up (the firmware fights back, as MOKA / Kalley do for the setup wizard) and left alone.
 * A swap to TV Home ([LauncherSwap.REC]; not the key way, dev/flows/home-screen.md) that Home no longer lands on is put back with the swap's own set-home-activity; the
 * [LauncherGuard] only rolls a broken one back, it never takes Home again.
 */
internal class Reoptimize(private val c: Context, private val adb: () -> Adb?) {
    companion object {
        private const val PREFS = "reoptimize"
        const val DAY_MS = 24 * 3600_000L
        const val GIVE_UP_AFTER = 2                          // turned off twice within a day and it came back: the firmware wins
        @Volatile private var running: Reoptimize? = null

        /** Check now, off the main thread, as if the firmware had changed (RulesReceiver's "reopt": tests). False when the service is not up. */
        fun now(): Boolean = running?.let { it.async(true); true } ?: false

        /** What to turn off again: the file's packages ([listed]) that are installed, on, not to be kept and not given up. Pure. */
        fun plan(listed: List<String>, installed: Set<String>, disabled: Set<String>, keep: (String) -> Boolean, gaveUp: Set<String>): List<String> =
            listed.distinct().filter { '/' !in it && it in installed && it !in disabled && it !in gaveUp && !keep(it) }   // "pkg/.Act" is a wizard Home: LauncherGuard's

        /** Whether [times] (ms) holds enough turn-offs within a day of [now] to stop touching the package. */
        fun fightsBack(times: List<Long>, now: Long): Boolean = times.count { now - it < DAY_MS } >= GIVE_UP_AFTER
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var stopped = false
    private val lock = Any()

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            async()
            ui.postDelayed(this, DAY_MS)
        }
    }

    fun start() { running = this; ui.postDelayed(tick, 90_000L) }   // after the ADB link is up
    fun stop() { stopped = true; running = null; ui.removeCallbacks(tick) }

    private fun async(force: Boolean = false) { Thread({ try { check(force) } catch (e: Exception) { WireLog.all.add("·", "reoptimize: " + e.message) } }, "reoptimize").apply { isDaemon = true }.start() }

    private fun history(): JSONObject = try { JSONObject(prefs.getString("history", "{}")) } catch (e: Exception) { JSONObject() }
    fun gaveUp(): Set<String> = prefs.getStringSet("gaveup", emptySet()) ?: emptySet()

    private fun check(force: Boolean) = synchronized(lock) {
        val a = adb() ?: return
        fun sh(cmd: String) = a.shell(cmd)
        fun pkgs(cmd: String) = sh(cmd).split("\n").map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
        val listed = sh("cat ${Setup.OFF_LIST} 2>/dev/null").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val fp = sh("getprop ro.build.fingerprint").trim()
        val before = prefs.getString("fp", null)
        prefs.edit().putString("fp", fp).apply()
        val why = if (before == null) "first look" else if (before != fp) "the firmware changed" else "same firmware"
        // only after a firmware change: on the same firmware an app that is on again was turned on by the person (TV Settings, the Apps tab)
        if (!force && (before == null || before == fp)) { WireLog.all.add("·", "reoptimize ($why): nothing to do"); return }
        var turned = 0
        if (listed.isNotEmpty()) {
            val hist = history(); val now = System.currentTimeMillis(); val given = gaveUp().toMutableSet()
            val back = plan(listed, pkgs("pm list packages"), pkgs("pm list packages -d"), { Setup.mustKeep(it) || it == dev.tvremote.home.HomeConfig.PKG }, given)
            for (p in back) {
                val times = (0 until (hist.optJSONArray(p)?.length() ?: 0)).map { hist.getJSONArray(p).getLong(it) }.filter { now - it < DAY_MS }
                if (fightsBack(times, now)) { given.add(p); WireLog.all.add("·", "reoptimize: gave up on $p, it keeps coming back"); continue }
                sh("pm disable-user --user 0 $p")
                sh("am force-stop $p")
                hist.put(p, JSONArray(times + now)); turned++
            }
            prefs.edit().putString("history", hist.toString()).putStringSet("gaveup", given).apply()
        }
        val home = homeBack(::sh)
        if (turned > 0 || home) {
            WireLog.all.add("·", "reoptimize ($why): $turned apps turned off again" + if (home) ", Home put back" else "")
            notice(turned, home, ::sh)
        } else WireLog.all.add("·", "reoptimize ($why): nothing to do")
    }

    /** TV Home was made the home screen and Home lands on another launcher now: choose it again. True when it did. */
    private fun homeBack(sh: (String) -> String): Boolean {
        val s = LauncherSwap(sh)
        val r = s.state()?.takeIf { it.state == "kept" && it.pkg == "dev.tvremote.home" && it.comp.contains('/') && it.why != LauncherSwap.KEY } ?: return false   // the key filter's Home survives updates
        val now = s.homeNow()
        if (now.substringBefore('/') == r.pkg || LauncherSwap.badHome(now)) return false   // already Home, or the guard's case
        sh("cmd package set-home-activity ${r.comp} 2>/dev/null")
        if (s.homeNow().substringBefore('/') == r.pkg) return true
        // a stock launcher the update turned on wins over the role (a BRAVIA's tvlauncher, Android 9): the whole swap again
        return try { s.take(r.pkg) } catch (e: java.io.IOException) { false }
    }

    private fun notice(n: Int, home: Boolean, sh: (String) -> String) {
        val text = listOfNotNull(if (n > 0) T("reopt.apps", "n" to n) else null, if (home) T("reopt.home") else null).joinToString(" ")
        try { Notify.send(sh, JSONObject().put("title", T("reopt.title")).put("text", text).put("seconds", 12)) } catch (e: Exception) { }
    }
}
