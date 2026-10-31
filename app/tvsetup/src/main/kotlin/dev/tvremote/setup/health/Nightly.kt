package dev.tvremote.setup.health

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import dev.tvremote.core.WireLog
import dev.tvremote.core.adb.Adb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * Nightly maintenance, once a night between 02:00 and 05:00 while the TV is in standby and nothing plays (checked every 15
 * minutes, from RemoteService like [Reoptimize]): trims every app's cache, compiles the apps used most with their own profile
 * (`speed-profile`: they start faster in the morning) and kills the cached background processes. Each step first looks that the
 * screen is still off; a person who switches the TV on stops it at once. The result (day, bytes freed, apps compiled) is kept in
 * prefs and in stats/nightly.json, which every remote reads over ADB for the Health tab's one line (Manage.health).
 * Only for the apps' own caches and processes: nothing is disabled, cleared or removed, so there is no Undo to keep.
 */
internal class Nightly(private val c: Context, private val adb: () -> Adb?) {
    companion object {
        private const val PREFS = "nightly"
        const val FROM_HOUR = 2
        const val TO_HOUR = 5
        const val EVERY_MS = 15 * 60_000L
        const val COMPILE_MAX = 6
        const val USAGE_DAYS = 14
        @Volatile private var running: Nightly? = null

        /** Run now, ignoring the time, the screen and whether it ran tonight (RulesReceiver's "nightly": tests). False when the service is not up. */
        fun now(): Boolean = running?.let { it.async(true); true } ?: false

        /** Inside the 02:00-05:00 window, and not yet done on this [day] (yyyy-MM-dd). Pure. */
        fun due(hour: Int, day: String, lastDay: String?): Boolean = hour in FROM_HOUR until TO_HOUR && day != lastDay

        /** The most used apps of the last days: [usage] is Stats' usage.json (day -> package -> seconds). Installed, at least a minute of use, at most [n]. Pure. */
        fun pick(usage: Map<String, Map<String, Long>>, installed: Set<String>, n: Int = COMPILE_MAX): List<String> {
            val sum = HashMap<String, Long>()
            for (day in usage.keys.sortedDescending().take(USAGE_DAYS)) for ((p, s) in usage[day]!!) sum[p] = (sum[p] ?: 0L) + s
            return sum.entries.filter { it.key in installed && it.value >= 60 }
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(n).map { it.key }
        }

        /** Free bytes of the line `df /data` prints (1K blocks: filesystem, size, used, available), null when it is not one. Pure. */
        fun freeBytes(df: String): Long? =
            df.lines().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size > 3 && it[1].all(Char::isDigit) && it[3].all(Char::isDigit) }?.get(3)?.toLong()?.times(1024)

        /** Whether `dumpsys media_session` shows a session in the playing state (3), or buffering (6). Pure. */
        // Android 9 writes `state=3`, Android 10+ `state=PLAYING(3)`: read only `state=3` missed every newer TV's playback
        fun playing(media: String): Boolean = Regex("PlaybackState \\{state=(?:[A-Z_]+\\()?(3|6)[,()]").containsMatchIn(media)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    @Volatile private var stopped = false
    private val lock = Any()

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            if (due(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), day.format(Date()), prefs.getString("day", null))) async()
            ui.postDelayed(this, EVERY_MS)
        }
    }

    fun start() { running = this; ui.postDelayed(tick, 120_000L) }
    fun stop() { stopped = true; running = null; ui.removeCallbacks(tick) }

    private fun async(force: Boolean = false) { Thread({ try { run(force) } catch (e: Exception) { WireLog.all.add("·", "nightly: " + e.message) } }, "nightly").apply { isDaemon = true }.start() }

    private fun awake() = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun run(force: Boolean) = synchronized(lock) {
        val a = adb() ?: return
        fun sh(cmd: String) = a.shell(cmd)
        val today = day.format(Date())
        if (!force && (awake() || !due(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), today, prefs.getString("day", null)) || playing(sh("dumpsys media_session")))) return
        // a stop is a check before the next step; force (tests) skips only the screen, the TV is on while a person tests
        fun halt() = !force && awake()
        val before = freeBytes(sh("df /data")) ?: 0L
        var compiled = 0; var killed = false; var aborted = false
        sh("pm trim-caches 999G")
        if (halt()) aborted = true
        if (!aborted) {
            val installed = sh("pm list packages").split("\n").map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
            for (p in pick(usage(), installed)) {
                if (halt()) { aborted = true; break }
                if (!sh("cmd package compile -m speed-profile $p").contains("Failure")) compiled++
            }
        }
        if (!aborted && halt()) aborted = true
        if (!aborted) { sh("am kill-all"); killed = true }       // cached processes only: TV Setup's foreground service, the launcher in use and the keyboard are not "cached", so none is killed
        val freed = maxOf(0L, (freeBytes(sh("df /data")) ?: before) - before)
        val r = JSONObject().put("day", today).put("at", System.currentTimeMillis()).put("freed", freed).put("compiled", compiled).put("killed", killed).put("aborted", aborted)
        // an aborted night counts as done only when something was; otherwise the next 15-minute look may try again
        if (!aborted || compiled > 0 || freed > 0) prefs.edit().putString("day", today).putString("last", r.toString()).apply()
        publish(r)
        WireLog.all.add("·", "nightly: freed $freed B, compiled $compiled apps" + (if (killed) ", background apps killed" else "") + if (aborted) ", stopped (screen on)" else "")
    }

    private fun usage(): Map<String, Map<String, Long>> = try {
        val all = JSONObject(File(c.getExternalFilesDir(null), "stats/usage.json").readText())
        all.keys().asSequence().associateWith { d -> all.getJSONObject(d).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }
    } catch (e: Exception) { emptyMap() }

    /** For the remotes (Manage.health reads it with SetupFiles): next to Stats' files, which the shell can read. */
    private fun publish(r: JSONObject) {
        try {
            val dir = File(c.getExternalFilesDir(null), "stats").also { it.mkdirs() }
            val tmp = File(dir, "nightly.json.tmp")
            tmp.writeText(r.toString() + "\n")
            tmp.renameTo(File(dir, "nightly.json"))
        } catch (e: Exception) { }
    }
}
