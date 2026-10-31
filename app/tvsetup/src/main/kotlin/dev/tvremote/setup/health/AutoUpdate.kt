package dev.tvremote.setup.health

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.tvremote.core.Words
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.ontv.Channels
import dev.tvremote.core.ontv.Games
import dev.tvremote.core.ontv.Home
import dev.tvremote.core.ontv.SetupUpdate
import dev.tvremote.core.ontv.TvApps
import dev.tvremote.home.HomeConfig
import dev.tvremote.setup.rules.Usage
import dev.tvremote.update.Release
import dev.tvremote.update.UpdatePlan
import java.io.File

/**
 * TV Setup keeps itself up to date, from RemoteService: it looks for a new version every few hours, downloads it, and installs
 * it only at a moment nobody would notice ([busy] says why not yet): the install closes TV Setup for some seconds, which stops
 * the web remote, the picture and the timers' checks meanwhile. What is on the TV's screen is not touched.
 * The install is [SetupUpdate]'s: a new version that does not start well is replaced by the old one, data and all, and never
 * tried again. Android 9 and older cannot put the old one back: there a version that fails is only noted, and the phone or
 * computer app, which sees TV Setup missing or broken, offers to install the next one (SetupBanner).
 * It also updates TV Home, TV Channels, TV Games and TV Apps when they are on the TV ([ownApps], every [UpdatePlan.CHECK], sooner when one
 * had to wait): those carry SelfUpdate but leave a new release to this for [UpdatePlan.GRACE] when HomeInfo "updates" says it is
 * on and has ADB, so their own Update popup is the exception; a copy from before SelfUpdate never asked at all.
 */
internal class AutoUpdate(private val c: Context, private val version: String, private val adb: () -> Adb?, private val busy: () -> String?) {
    companion object {
        private const val PREFS = "remote"
        fun on(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_update", true)
        fun set(c: Context, on: Boolean) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_update", on).apply() }
        private const val MIN = 60_000L
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var found: Release? = null
    private var checkedAt = 0L
    private var appsAt = 0L
    private var apk: File? = null
    private val dir by lazy { c.getExternalFilesDir("update") }       // shared storage: the shell, which installs it, can read it

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            Thread({
                val next = try { step() } catch (e: Exception) { 30 * MIN }   // offline, GitHub busy: later
                if (!stopped) ui.postDelayed(this, next)
            }, "auto-update").apply { isDaemon = true }.start()
        }
    }

    fun start() {
        val old = System.currentTimeMillis() - 3600_000              // left from an update that is over by now
        dir?.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        ui.postDelayed(tick, 10 * MIN)                                // not while the TV is just starting up
    }

    fun stop() { stopped = true; ui.removeCallbacks(tick) }

    /** One step towards an update; returns how long until the next is worth trying. */
    private fun step(): Long {
        if (!on(c)) return 60 * MIN
        val now = System.currentTimeMillis()
        if (now - appsAt >= UpdatePlan.CHECK) adb()?.let { a -> appsAt = if (ownApps(a)) now else now - UpdatePlan.CHECK + UpdatePlan.RETRY }   // one had to wait: again soon
        if (found == null && now - checkedAt < UpdatePlan.CHECK) return 20 * MIN
        if (now - checkedAt >= UpdatePlan.CHECK) {
            checkedAt = now
            val r = Release.newer(version, "tv", "tv-setup.apk")
            if (r?.version != found?.version) apk = null
            found = r
        }
        val r = found ?: return 20 * MIN
        val a = adb() ?: return 10 * MIN
        val s = SetupUpdate.state(a)
        if (SetupUpdate.bad(s, r.version)) return 6 * 60 * MIN        // it broke here once: wait for the next version
        if (SetupUpdate.busy(s)) return 10 * MIN
        if (SetupUpdate.failedLately(s, r.version, now)) {            // the TV refused it (storage full, a bad file...): not every half hour,
            apk?.delete(); apk = null                                 // and downloaded again next time
            return 6 * 60 * MIN                                       // after those 6 hours it tries once more (SetupUpdate.FAILED_WAIT)
        }
        val file = apk ?: File(dir ?: return 60 * MIN, "tv-setup-" + r.version + ".apk").also {
            r.download(it, null)                                      // deletes what does not match its signed checksum
            apk = it
        }
        if (!file.isFile) { apk = null; return 10 * MIN }
        busy()?.let { return 10 * MIN }
        // a phone or computer watching or controlling the TV through its own scrcpy session (ours is closed by now: see busy)
        if (a.shell("ps -A -o ARGS 2>/dev/null | grep -c '[c]om.genymobile.scrcpy.Server'").trim().let { it != "" && it != "0" }) return 10 * MIN
        SetupUpdate.start(a, file.absolutePath, r.version)
        return 30 * MIN                                               // the install closes this app; this is for a failed one
    }

    /**
     * TV Home, TV Channels and TV Games put up to date when installed and a newer one is out (each checked against the signed
     * SHA256SUMS, their data kept), so that they rarely need to ask for themselves (SelfUpdate waits for this, UpdatePlan.quiet).
     * An install closes the app, so it goes in only when nobody is in it ([UpdatePlan.mayInstall]): in standby anything, awake TV
     * Home when it is not on top, TV Channels / TV Games when not on top and not in front within the last 30 minutes either
     * (one checked as not on the screen was opened during the push and install and closed under the person: so the file is
     * downloaded first and the check made again right before the install, [free]). Awake installs also wait for [busy] (the remote
     * or a session in use). False when one had to wait.
     */
    private fun ownApps(a: Adb): Boolean {
        val sh: (String) -> String = { a.shell(it) }
        var waited = false
        fun top(pkg: String) = sh("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'").contains("$pkg/")
        fun free(pkg: String, careful: Boolean): Boolean {
            val now = System.currentTimeMillis()
            val awake = sh("dumpsys power | grep -m1 mWakefulness=").contains("Awake")
            val ok = !awake || ((!careful || busy() == null) && UpdatePlan.mayInstall(true, top(pkg), if (careful) lastUsed(pkg, now) else null, now, careful))
            if (!ok) waited = true
            return ok
        }
        Channels.refresh(); Games.refresh(); TvApps.refresh()                           // asked again now, not from the 6 h the pages keep
        for ((pkg, state, install) in listOf(
            Triple(Channels.PKG, { Channels.state(sh) }, { Channels.install(a, dir, Words.lang) { free(Channels.PKG, true) } }),
            Triple(Games.PKG, { Games.state(sh) }, { Games.install(a, dir, Words.lang) { free(Games.PKG, true) } }),
            Triple(TvApps.PKG, { TvApps.state(sh) }, { TvApps.install(a, dir, Words.lang) { free(TvApps.PKG, true) } }))) {
            try {
                val s = state()
                if (s.optBoolean("installed") && s.has("latest")) {
                    if (top(pkg)) waited = true else install()        // on screen: no download wasted; install() asks free() again once it has the file
                }
            } catch (e: Exception) { android.util.Log.w("AutoUpdate", pkg, e) }   // next time
        }
        try { if (free(HomeConfig.PKG, false)) Home.update(a, dir, Words.lang) { free(HomeConfig.PKG, false) } } catch (e: Exception) { }   // not on this TV ("home:missing"), offline: next time
        return !waited
    }

    /** The last time [pkg] came to or left the front within a day, or null when the usage log is empty (no usage access: nothing to go by). Events only; cheap. */
    private fun lastUsed(pkg: String, now: Long): Long? = try {
        var any = false
        var last = 0L
        Usage.events(c, now - 86_400_000L, now) { e ->
            any = true
            if (e.packageName == pkg && (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND)) last = e.timeStamp
        }
        if (any) last else null                                       // 0: not seen today, long ago enough
    } catch (e: Exception) { null }
}
