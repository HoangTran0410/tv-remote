package dev.tvremote.setup.health

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.rules.Usage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The TV's own diary, for the History view of the remotes: every few minutes, how long each app has been on screen today
 * and how much memory, CPU and storage is in use. It is kept on the TV, so nothing else has to stay switched on, in a folder
 * the shell user can read, so every remote gets it over ADB:
 *
 *   stats/usage.json      { "2026-10-13": { "com.example.app": seconds, ... }, ... }      the last 90 days
 *   stats/samples.jsonl   [time, screen on, CPU %, memory used MB, memory MB, storage used MB, storage MB, "app on screen",
 *                          Wi-Fi signal dBm (null off Wi-Fi), "wifi" | "eth" | "" (no network), Wi-Fi link Mbps]   30 days
 *   stats/net.jsonl       [time, 0 lost / 1 back, "wifi" | "eth" | "", screen on]: every time the TV's network went away and
 *                          came back, which the samples five minutes apart would miss
 *   stats/power.jsonl     [time, what] for the Health tab's Sleep view: "on" / "off" (the screen), "start" (TV Setup started:
 *                          after a boot, an update, or the TV killing it), "vpn_up" / "vpn_down" (Tailscale), "ts_restart"
 *                          (TailscaleWatch started it again). Begun with the screen's last 7 days from Android's usage events.
 *
 * Reading which app is on screen needs the "usage access" permission, which the TV grants to our ADB session.
 */
internal class Stats(private val ctx: Context) {
    private val dir = File(ctx.getExternalFilesDir(null), "stats")
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** One usage event, reduced to what the count needs (so it can be tested without a device). */
    internal class Ev(val type: Int, val pkg: String, val time: Long)

    internal companion object {
        const val SCREEN_INTERACTIVE = 15
        const val SCREEN_NON_INTERACTIVE = 16

        /** Milliseconds per app from the events, and the app still open at the end (null when none was opened today). */
        fun count(events: List<Ev>, start: Long, now: Long, on: Boolean): Pair<HashMap<String, Long>, String?> {
            val total = HashMap<String, Long>()
            var cur: String? = null
            var since = start
            val close = { until: Long -> cur?.let { total[it] = (total[it] ?: 0L) + maxOf(0L, until - since) }; cur = null }
            for (e in events) when (e.type) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> if (cur != e.pkg) { close(e.time); cur = e.pkg; since = e.time }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (cur == e.pkg) close(e.time)
                SCREEN_NON_INTERACTIVE -> close(e.time)               // the TV went to standby with the app still open
            }
            val open = cur
            if (on) close(now)
            return Pair(total, open)
        }

        private val powerLock = Any()

        /** One line of power.jsonl. From any thread; TailscaleWatch calls it too. */
        fun note(ctx: Context, what: String, at: Long = System.currentTimeMillis()) {
            try {
                synchronized(powerLock) {
                    val f = File(ctx.getExternalFilesDir(null), "stats/power.jsonl")
                    f.parentFile?.mkdirs()
                    f.appendText(JSONArray().put(at / 1000).put(what).toString() + "\n")
                    if (f.length() > 120_000) f.readLines().let { l ->     // a few thousand lines: months of an ordinary TV
                        val tmp = File(f.path + ".tmp"); tmp.writeText(l.subList(l.size / 2, l.size).joinToString("\n") + "\n"); tmp.renameTo(f)
                    }
                }
            } catch (ignored: Exception) { }
        }

        /** When the screen last came on today (0 when it did not): an app on screen with no foreground event since is counted from then. */
        fun lastWake(events: List<Ev>): Long = events.lastOrNull { it.type == SCREEN_INTERACTIVE }?.time ?: 0L
    }

    /** [cpu] is the CPU load in percent, or null when it could not be read. */
    @Synchronized
    fun tick(cpu: Int?) {
        try {
            dir.mkdirs()
            val now = System.currentTimeMillis()
            val on = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val (usage, front) = today(now, on)
            if (usage.length() > 0) {
                val f = File(dir, "usage.json")
                val all = try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
                all.put(day.format(Date(now)), usage)
                val oldest = day.format(Date(now - 90L * 86400000))
                for (k in all.keys().asSequence().toList()) if (k < oldest) all.remove(k)
                write(f, all.toString())
            }
            val mem = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
            val disk = StatFs(Environment.getDataDirectory().path)
            val mb = 1024L * 1024
            val line = JSONArray().put(now / 1000).put(if (on) 1 else 0).put(cpu ?: JSONObject.NULL)
                .put((mem.totalMem - mem.availMem) / mb).put(mem.totalMem / mb)
                .put((disk.totalBytes - disk.availableBytes) / mb).put(disk.totalBytes / mb).put(if (on) front else "")
            val net = net()
            val wifi = if (net == "wifi") try { @Suppress("DEPRECATION") (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo } catch (e: Exception) { null } else null
            line.put(wifi?.rssi?.takeIf { it in -126..-1 } ?: JSONObject.NULL).put(net).put(wifi?.linkSpeed?.takeIf { it > 0 } ?: JSONObject.NULL)
            val samples = File(dir, "samples.jsonl")
            samples.appendText(line.toString() + "\n")
            if (samples.length() > 900_000) {                         // about 30 days at one line every 5 minutes
                val keep = samples.readLines().let { it.subList(it.size / 4, it.size) }
                write(samples, keep.joinToString("\n") + "\n")
            }
        } catch (ignored: Exception) {                                // a missed sample is not worth a crash
        }
    }

    /** The app last brought to the screen over the past days: what is on screen when nothing moved since midnight, or since the
     *  TV woke up (standby keeps the app it was showing). Without it a TV left on one app (an operator's launcher) has no name. */
    private fun lastFront(now: Long): String {
        var cur = ""
        var any = false
        Usage.events(ctx, now - 3 * 86400000L, now) { e ->
            any = true
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> cur = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (cur == e.packageName) cur = ""
            }
        }
        if (any) return cur
        // no events at all: a clock that once jumped ahead (a box booted before it knew the time) leaves Android's current
        // stretch of events ending in the future, and every query of it comes back empty. The totals per app still move on:
        // the app seen last is the one on screen.
        val seen = { u: android.app.usage.UsageStats -> if (Build.VERSION.SDK_INT >= 29) u.lastTimeVisible else u.lastTimeUsed }
        return (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3 * 86400000L, now)
            ?.filter { seen(it) in 1..now }?.maxByOrNull(seen)?.packageName ?: ""
    }

    /** Seconds on screen per app since midnight, and the app on screen now ("" when unknown). */
    fun today(now: Long, on: Boolean): Pair<JSONObject, String> {
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val list = ArrayList<Ev>()
        Usage.events(ctx, start, now) { e -> list.add(Ev(e.eventType, e.packageName ?: "", e.timeStamp)) }
        val (total, open) = count(list, start, now, on)
        val front = open ?: if (on) lastFront(now) else ""
        if (open == null && on && front.isNotEmpty()) total[front] = (total[front] ?: 0L) + maxOf(0L, now - maxOf(start, lastWake(list)))
        val out = JSONObject()
        for ((pkg, ms) in total) if (ms >= 1000) out.put(pkg, ms / 1000)
        return Pair(out, front)
    }

    /** The TV's own way out: "eth" before "wifi" (a cable wins when both are there), "" with neither. A VPN does not count. */
    fun net(): String {
        val cm = (ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: return ""
        var kind = ""
        @Suppress("DEPRECATION") for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "eth"
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) kind = "wifi"
        }
        return kind
    }

    // Losses shorter than this are a hand-over (Wi-Fi to cable, a roam between access points), not a cut worth showing.
    private val ui = Handler(Looper.getMainLooper())
    private var down = false
    private val check = Runnable { Thread({ netChanged() }, "netlog").apply { isDaemon = true }.start() }
    private val watch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { ui.removeCallbacks(check); ui.postDelayed(check, 1000) }
        override fun onLost(n: Network) { ui.removeCallbacks(check); ui.postDelayed(check, 10_000) }
    }

    /** Starts writing net.jsonl; the service calls it once. */
    fun watchNet() {
        try {
            (ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), watch)   // NOT_VPN is in every request by default
        } catch (ignored: Exception) {
        }
    }

    fun stopNet() {
        try { (ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(watch) } catch (ignored: Exception) { }
        ui.removeCallbacks(check)
    }

    @Synchronized
    private fun netChanged() {
        try {
            val kind = net()
            if ((kind == "") == down) return                          // nothing new: still up, or still down
            down = kind == ""
            dir.mkdirs()
            val on = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val f = File(dir, "net.jsonl")
            f.appendText(JSONArray().put(System.currentTimeMillis() / 1000).put(if (down) 0 else 1).put(kind).put(if (on) 1 else 0).toString() + "\n")
            if (f.length() > 60_000) write(f, f.readLines().let { it.subList(it.size / 2, it.size) }.joinToString("\n") + "\n")
        } catch (ignored: Exception) {
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val on = i.action == Intent.ACTION_SCREEN_ON
            note(ctx, if (on) "on" else "off")
            RemoteService.alert(if (on) "on" else "off")            // Telegram /alerts
        }
    }

    /** Starts writing power.jsonl; the service calls it once, as it starts. */
    fun watchPower() {
        Thread({
            try {
                val prefs = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("power_backfilled", false)) { backfill(); prefs.edit().putBoolean("power_backfilled", true).apply() }
            } catch (ignored: Exception) { }
            note(ctx, "start")
        }, "powerlog").apply { isDaemon = true }.start()
        try { ctx.registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }) } catch (ignored: Exception) { }
    }

    fun stopPower() { try { ctx.unregisterReceiver(screen) } catch (ignored: Exception) { } }

    /** The first power.jsonl: the screen going on and off over the last week, as Android's usage events remember it. */
    private fun backfill() {
        val now = System.currentTimeMillis()
        val out = StringBuilder()
        Usage.events(ctx, now - 7 * 86400000L, now) { e ->
            if (e.eventType == SCREEN_INTERACTIVE || e.eventType == SCREEN_NON_INTERACTIVE)
                out.append(JSONArray().put(e.timeStamp / 1000).put(if (e.eventType == SCREEN_INTERACTIVE) "on" else "off")).append('\n')
        }
        synchronized(powerLock) {                                      // ahead of what note() wrote meanwhile (a VPN coming up)
            dir.mkdirs()
            val f = File(dir, "power.jsonl")
            val had = if (f.exists()) f.readText() else ""
            if (!Regex("\"(on|off)\"]").containsMatchIn(had)) write(f, out.toString() + had)   // a TV Setup from before the pref: done already
        }
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}
