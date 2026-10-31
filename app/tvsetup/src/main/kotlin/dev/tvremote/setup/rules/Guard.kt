package dev.tvremote.setup.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import dev.tvremote.core.T
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.Adb.Companion.q
import dev.tvremote.setup.MainActivity
import dev.tvremote.setup.ManageActivity
import dev.tvremote.setup.PinActivity
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.health.Stats

/** The marks that decide a warning, as Guard keeps them between looks. */
internal data class Marks(val bedWarnedAt: Long, val totalWarnedAt: Long, val ramQuietUntil: Long)

/**
 * Enforces [Rules] on the TV, from RemoteService. A check runs every few seconds to a minute (sooner when a deadline is close):
 * standby when the sleep timer ends or during bedtime (each after a minute's warning on screen, [WarnActivity]), and an app
 * closed once its minutes for the day are used up, and the volume turned down to the highest the hour allows, the moment it
 * goes higher (with a word on screen saying why, so nobody thinks the volume keys broke), and a notice offering to free the memory
 * when it stays fuller than the share set (once per half hour at most, so "Skip" means it). Standby, closing apps and putting the warning over another app all need
 * the TV's own ADB, which this app holds (as for everything else it does). A locked app ([Rules.locked]) is watched apart, a few
 * times a second, since its PIN must come up before anyone watches it: [PinActivity] goes over it until the PIN is given, and the
 * app stays open until another one comes to the front or the TV goes to standby.
 */
internal class Guard(private val c: Context, private val adb: () -> Adb?) {
    companion object {
        @Volatile private var running: Guard? = null
        /** The rules changed: check now rather than at the next tick. */
        fun poke() { running?.let { g -> g.ui.removeCallbacks(g.tick); g.ui.post(g.tick) } }
        /** "Turn off now" on the warning screen. */
        fun standbyNow() { running?.standby() }
        /** The TV's volume is not its own to set (sent on to a sound bar or the TV set over HDMI): no limit can hold it. */
        fun volumeFixed(c: Context): Boolean = (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isVolumeFixed
        /** The right PIN was given for [pkg] on [PinActivity]: open until another app comes to the front. */
        fun unlock(pkg: String) { running?.unlocked = pkg }
        /** [pkg] was opened with a code and nothing else came to the front since (or no Guard: nothing to ask then). */
        fun isOpen(pkg: String): Boolean = running?.let { it.unlocked == pkg } ?: true
        /** TV Setup's own screens that change the rules: with a code set they ask it, else a child turns the lock off from there. */
        val RULE_SCREENS = setOf(MainActivity::class.java.name, ManageActivity::class.java.name)
        /** "Free RAM" on the notice: the apps waiting in the background closed; the MB that came back, null without ADB. */
        fun freeNow(): Long? = running?.free()

        /** [devices]: the output's device bits; where Android hides them, [recording] asks who records the submix. Never throws. */
        internal fun toListener(devices: () -> Int, recording: () -> Boolean): Boolean = try {
            devices() and 0x8000 != 0                                 // AudioSystem.DEVICE_OUT_REMOTE_SUBMIX
        } catch (e: Throwable) {
            try { recording() } catch (e2: Throwable) { false }       // no such method on Android 5-6 either
        }

        /**
         * A mark ahead of the clock is from a clock that has since gone back (a box that booted in the wrong year): "warned a minute
         * ago" or "quiet for half an hour" would then hold for a year. Forgotten, so the warning comes again; a quiet mark can never
         * lawfully be more than its 30 minutes ahead. [lastStop] is read by both threads, so it is cleaned in one place for both.
         */
        internal fun sane(m: Marks, lastStop: MutableMap<String, Long>, now: Long): Marks {
            lastStop.entries.removeAll { it.value > now }
            return Marks(if (m.bedWarnedAt > now) 0L else m.bedWarnedAt, if (m.totalWarnedAt > now) 0L else m.totalWarnedAt,
                if (m.ramQuietUntil > now + 30 * 60_000L) 0L else m.ramQuietUntil)
        }

        /** (total, available) memory in MB, from /proc/meminfo, which any app may read. */
        fun memory(): Pair<Long, Long> {
            val m = try { java.io.File("/proc/meminfo").readText() } catch (e: Exception) { "" }
            val kb = { k: String -> dev.tvremote.core.ontv.Meminfo.kb(m, k) ?: 0L }
            return kb("MemTotal") / 1024 to kb("MemAvailable") / 1024
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val stats by lazy { Stats(c) }
    @Volatile private var stopped = false
    private var warnedSleep = 0L                                      // the sleep_at already warned about
    private var bedWarnedAt = 0L                                      // bedtime: when this turn-on was warned about, 0 when not yet
    private var totalWarnedAt = 0L                                    // the day's total used up: when the minute's grace began, 0 when not
    private val nearlyWarned = HashSet<String>()                      // "pkg day": the five-minute notice was shown
    private val lastStop = java.util.concurrent.ConcurrentHashMap<String, Long>()   // also read by the guard-lock thread (schedules)
    private val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volSaidAt = 0L
    private var ramHigh = 0                                           // checks in a row with the memory past the line
    private var ramQuietUntil = 0L                                    // the notice was shown: not again before this
    private val volume = object : BroadcastReceiver() {                // every change, from any remote: turned down at once
        override fun onReceive(ctx: Context, i: Intent) { capVolume() }
    }

    private val checking = Any()                                     // a poke during a slow check waits for it, then looks at the new rules
    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            Thread({
                val next = try { synchronized(checking) { check() } } catch (e: Exception) { 30_000L }
                ui.removeCallbacks(this)                              // a poke meanwhile: still one chain of checks
                ui.postDelayed(this, next.coerceIn(3_000L, 60_000L))
            }, "guard").apply { isDaemon = true }.start()
        }
    }

    fun start() {
        running = this
        ui.postDelayed(tick, 5_000)
        Thread({ while (!stopped) Thread.sleep(try { lockCheck() } catch (e: Exception) { android.util.Log.w("tvr-guard", "lockCheck", e); 5_000L }) }, "guard-lock").apply { isDaemon = true }.start()
        val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(volume, f, Context.RECEIVER_NOT_EXPORTED) else c.registerReceiver(volume, f)
    }

    fun stop() {
        stopped = true
        if (running === this) running = null
        ui.removeCallbacks(tick)
        try { c.unregisterReceiver(volume) } catch (e: Exception) { }
    }

    /** Louder than the range allows: down to its highest, and said on screen each time (at most every 3 s, keys repeat). */
    private fun capVolume() {
        val cap = Rules.volumeCap(Rules.load(c), System.currentTimeMillis()) ?: return
        if (audio.isVolumeFixed || toListener()) return
        val top = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val most = cap.optInt("max") * top / 100
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= most) return
        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, most, 0) } catch (e: Exception) { return }
        val now = System.currentTimeMillis()
        if (now - volSaidAt in 0 until 3_000) return                   // a clock set back leaves no mark in the future
        volSaidAt = now
        say(T("guard.volume_cap", "max" to cap.optInt("max"), "from" to cap.optString("from"), "to" to cap.optString("to")))
    }

    /**
     * The sound goes to a 🎧 listener, not the room: before Android 13 scrcpy takes the whole output (the remote submix) and the
     * speakers fall silent. The volume keys then move the submix's volume, not the speakers': capped, the TV stayed silent with
     * every key press saying "limited" (a TCL at 0 on its speakers, 30 % on the submix). The limit is for the room.
     */
    private fun toListener(): Boolean = toListener(
        { AudioManager::class.java.getMethod("getDevicesForStream", Int::class.javaPrimitiveType).invoke(audio, AudioManager.STREAM_MUSIC) as Int },   // hidden, kept for apps
        { android.os.Build.VERSION.SDK_INT >= 24
            && audio.activeRecordingConfigurations.any { it.clientAudioSource == android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX } })

    /** One look at the rules; returns how long until the next one is worth doing. */
    private fun check(): Long {
        val o = Rules.load(c)
        val now = System.currentTimeMillis()
        val on = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        var next = 60_000L
        var today: Pair<org.json.JSONObject, String>? = null           // one read of the day's usage events per check, for the total and the limits alike
        val usedToday = { today ?: stats.today(now, true).also { today = it } }
        sane(Marks(bedWarnedAt, totalWarnedAt, ramQuietUntil), lastStop, now).let { bedWarnedAt = it.bedWarnedAt; totalWarnedAt = it.totalWarnedAt; ramQuietUntil = it.ramQuietUntil }

        // ---- sleep timer
        val at = o.optLong("sleep_at")
        if (at > 0) {
            val left = at - now
            if (left <= 0) {
                Rules.clearSleep(c, at)
                if (on) { standby(); RemoteService.alert("sleep") }
            } else {
                if (left <= 61_000 && on && warnedSleep != at) { warnedSleep = at; warn("sleep", at, "") }
                next = minOf(next, if (left > 61_000) left - 60_000 else left)
            }
        }

        // ---- bedtime: a minute after the TV is turned on in the window, standby again
        if (on && Rules.inBedtime(o, now) && now >= o.optLong("snooze_until") && Rules.unlockUntil(o, now) == 0L) {   // a parent's code ("unlock") opens bedtime, limits and the total alike
            if (bedWarnedAt == 0L) { bedWarnedAt = now; warn("bed", now + 60_000, ""); RemoteService.alert("bed", "range" to (Rules.bedNow(o, now)?.let { it.optString("from") + "–" + it.optString("to") } ?: "")) }
            else if (now - bedWarnedAt >= 60_000) { bedWarnedAt = 0; standby() }
            next = minOf(next, 5_000)
        } else bedWarnedAt = 0

        // ---- volume: a range may just have begun with the sound up
        if ((o.optJSONArray("volume")?.length() ?: 0) > 0) {
            ui.post { capVolume() }
            next = minOf(next, 30_000)
        }

        // ---- memory: full at two checks in a row (half a minute apart), not a moment's peak while an app starts
        val ram = Rules.ram(o)
        if (ram.optBoolean("on")) {
            val (total, avail) = memory()
            if (on && total > 0 && (total - avail) * 100 >= total * ram.optInt("pct", 90)) {
                if (++ramHigh >= 2 && now >= ramQuietUntil) { ramQuietUntil = now + 30 * 60_000L; ramHigh = 0; warn("ram", 0, "") }
            } else ramHigh = 0
            next = minOf(next, 30_000)
        }

        // ---- the day's total on the TV (kids mode): a notice near the end, then like bedtime: a minute's grace with a place for a code, then standby
        if (on && Rules.totalMin(o, now) != null && Rules.unlockUntil(o, now) == 0L) {
            val left = Rules.totalLeftSec(o, usedToday().first, Kids.free(c, now), now) ?: 1L
            val warnMin = o.optJSONObject("total")?.optInt("warn", 5) ?: 5
            if (left <= 0) {
                if (totalWarnedAt == 0L) { totalWarnedAt = now; warn("total", now + 60_000, ""); RemoteService.alert("limit", "app" to T("kids.total_name"), "why" to "limit") }
                else if (now - totalWarnedAt >= 60_000) { totalWarnedAt = 0; standby() }
                next = minOf(next, 5_000)
            } else {
                totalWarnedAt = 0
                if (warnMin > 0 && left <= warnMin * 60L && nearlyWarned.add("total " + Rules.day(now))) say(T("kids.total_left", "n" to (left + 59) / 60))
                next = minOf(next, 20_000)                            // another app may come to the front at any time
            }
        } else totalWarnedAt = 0
        if (Rules.totpOn(o)) Clock.maybeCheck(c)                      // the Authenticator's codes follow the real time: a reading from the network, once a day

        // ---- daily app limits, for the app on screen
        val limits = o.optJSONObject("limits")
        val capped = Rules.schedApps(o).any { o.optJSONObject("sched")?.optJSONObject(it)?.optInt("max") ?: 0 > 0 }   // a schedule's minutes count like a limit's
        if (on && Rules.unlockUntil(o, now) == 0L && (limits != null && limits.length() > 0 || capped)) {
            val (used, front) = usedToday()
            val limit = Rules.budget(o, front, now) ?: 0
            if (front.isNotEmpty() && limit > 0) {
                val allowed = (limit + Rules.extra(o, front, now)) * 60L
                val left = allowed - used.optLong(front)
                val key = front + " " + Rules.day(now)
                if (left <= 0) {
                    if (now - (lastStop[front] ?: 0L) > 15_000) { lastStop[front] = now; close(front) }
                } else {
                    if (left <= 300 && nearlyWarned.add(key)) say(T("guard.min_left", "n" to (left + 59) / 60, "app" to label(front)))
                    next = minOf(next, maxOf(5_000L, left * 1000))
                }
            }
            next = minOf(next, 20_000)                                // another app may come to the front at any time
        }
        return next
    }


    // ---- app locks
    @Volatile private var unlocked = ""                               // the app the PIN was given for, until another comes to the front
    private var front = ""
    private var frontClass = ""                                       // the activity on screen: TV Setup's PIN and notice screens are free, its rule screens are not
    private var eventsTo = 0L
    private var pinAt = 0L
    private val usage by lazy { c.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager }

    /** One look at the app on screen, for the locked apps; returns how long until the next. */
    private fun lockCheck(): Long {
        val o = Rules.load(c)
        val apps = Rules.locked(o)
        val sched = Rules.schedApps(o)
        val kids = Rules.kidsMode(o)
        val code = Rules.hasCode(o)
        if (apps.isEmpty() && !kids && sched.isEmpty() && !code) { eventsTo = 0; return 3_000 }   // off: forgets the apps it followed
        val now = System.currentTimeMillis()
        lastStop.entries.removeAll { it.value > now }              
        if (pinAt > now) pinAt = 0L                                   // the clock went back (a box that booted in the wrong year): marks ahead of it held every check off
        if (!(c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
            unlocked = ""
            return 2_000
        }
        val app = frontApp(now)
        if (app in sched && !Rules.allowedNow(o, app, now) && Rules.unlockUntil(o, now) == 0L && now - (lastStop[app] ?: 0L) > 15_000) {
            lastStop[app] = now                                       // outside its window: closed here, before any PIN could be asked
            close(app, "sched")
        }
        if (app.isNotEmpty() && app != unlocked && app != c.packageName) unlocked = ""
        if (code && app == c.packageName && frontClass in RULE_SCREENS && unlocked != app && PinActivity.showing == null && now - pinAt > 2_000) {
            askPin(app, now - pinAt < 5_000)                          // MainActivity asks at once itself; this catches Manage brought back from Recents
            pinAt = now
        }
        if (apps.isEmpty() && !kids) return if (sched.isEmpty() && !code) 3_000 else 1_500   // a schedule's edge is noticed within a second and a half
        if (app != unlocked && Rules.needsPin(o, app, now, if (kids) Kids.free(c, now) else emptySet()) && PinActivity.showing == null && now - pinAt > 2_000) {
            askPin(app, now - pinAt < 5_000)                          // asked a moment ago and not shown: Android kept it back
            pinAt = now
        }
        return 700
    }

    /** The app on screen, from the usage events since the last look (the day's, the first time). */
    private fun frontApp(now: Long): String {
        if (eventsTo > now) eventsTo = 0L                             // the clock went back: the day's again, else nothing came until that hour
        Usage.events(c, if (eventsTo == 0L) now - 86_400_000L else eventsTo, now) { e ->
            when (e.eventType) {
                android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> { front = e.packageName ?: ""; frontClass = e.className ?: "" }
                android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> if (front == e.packageName) front = ""
            }
            // asked again from that moment: the same events come twice, to the same end
            eventsTo = maxOf(eventsTo, e.timeStamp)
        }
        if (eventsTo == 0L) eventsTo = now
        return front
    }

    /** The PIN screen over [pkg]: started by this app where it may draw over others (allowed through ADB), else by the shell. */
    private fun askPin(pkg: String, byShell: Boolean) {
        if (!byShell && (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c))) try {
            c.startActivity(android.content.Intent(c, PinActivity::class.java).putExtra("pkg", pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) { }
        sh("am start -n ${c.packageName}/.PinActivity --es pkg " + q(pkg))
    }

    private fun label(pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun sh(cmd: String): String? = try { adb()?.shell(cmd) } catch (e: Exception) { null }

    private fun standby() { sh("input keyevent 223") }

    /** The same as the pages' "Free up RAM" (Manage.free): what waits in the background goes, what is on screen stays. */
    private fun free(): Long? {
        val before = memory().second
        if (sh("am kill-all") == null) return null
        Thread.sleep(1500)
        return maxOf(0L, memory().second - before)
    }

    /** The app's minutes are used up: back to the home screen, the app closed, and a word on screen saying why. */
    private fun close(pkg: String, kind: String = "limit") {
        sh("input keyevent 3; am force-stop " + q(pkg))
        warn(kind, 0, pkg)
        RemoteService.alert("limit", "app" to label(pkg), "why" to (if (kind == "sched") "sched" else "limit"))
    }

    /** The warning screen over whatever is on the TV. Since Android 10 a service may not open a screen by itself; ADB's shell may. */
    private fun warn(kind: String, until: Long, pkg: String) {
        val out = sh("am start -n ${c.packageName}/.WarnActivity --es kind $kind --el until $until --es pkg " + q(pkg) + " 2>&1")
        if (out == null || out.contains("Error")) say(when (kind) {
            "sleep" -> T("guard.the_tv_turns_off")
            "bed" -> T("guard.bedtime_the_tv_turns")
            "ram" -> T("guard.the_tv_s_memory")
            "sched" -> T("guard.sched_closed", "name" to label(pkg))
            "total" -> T("kids.total_up")
            else -> T("guard.time_up", "name" to label(pkg))
        })
    }

    private fun say(text: String) { ui.post { Toast.makeText(c, text, Toast.LENGTH_LONG).show() } }
}
