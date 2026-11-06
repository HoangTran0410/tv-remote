package dev.tvremote.core
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.remote.Remote
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * What every saved TV is doing right now, for the list of TVs (a homestay owner's look at all of them without opening each):
 * on, in standby, or not answering; its model; the app on screen. One light `adb shell` per TV per round on the link the
 * app already keeps (no scrcpy), and a TCP connect first, so a TV that is off costs a second and never slows the others.
 * A TV with no ADB (keys only, LG, Samsung, Roku, VIDAA) says "reachable" or nothing: its protocol cannot tell standby from on.
 */
object FleetStatus {
    const val ON = "on"                                               // awake: the screen is on
    const val STANDBY = "standby"                                     // answers over ADB, screen off
    const val UP = "up"                                               // answers, but nothing says on or standby (no ADB, or ADB not ready yet)
    const val OFF = "off"                                             // no answer: off, or not on this network
    const val UNKNOWN = "unknown"                                     // not read yet

    private const val SEP = "printf '\\043~\\043\\n'"
    /** wakefulness · app in front · uptime and memory · make, model, Android. dumpsys's "Failed to write" (grep -m1 closes the pipe) goes away with 2>/dev/null. */
    const val CMD_LIVE = "dumpsys power 2>/dev/null | grep -m1 mWakefulness=; $SEP; dumpsys window 2>/dev/null | grep -m1 mCurrentFocus; $SEP; " +
        "cat /proc/uptime; grep -E 'MemTotal|MemAvailable' /proc/meminfo"
    const val CMD = "$CMD_LIVE; $SEP; getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release"

    /** host -> (the ADB connection it was read on, model, Android): they cannot change on one connection, so later rounds send [CMD_LIVE]. */
    private val fixed = ConcurrentHashMap<String, Triple<Any, String, String>>()

    data class Status(val host: String, val state: String = UNKNOWN, val model: String = "", val android: String = "", val app: String = "",
                      val appName: String = "", val home: Boolean = false, val uptime: Long = 0, val memFree: Int = -1, val at: Long = 0) {
        fun json(now: Long = System.currentTimeMillis()): JSONObject = JSONObject().put("host", host).put("state", state)
            .put("age", if (at == 0L) -1 else now - at)
            .also { o ->
                if (model.isNotEmpty()) o.put("model", model)
                if (android.isNotEmpty()) o.put("android", android)
                if (app.isNotEmpty()) o.put("app", app).put("app_name", appName).put("home", home)
                if (uptime > 0) o.put("uptime", uptime)
                if (memFree >= 0) o.put("mem_free", memFree)
            }
    }

    /** Whether [port] answers within [ms]: the quick look that tells a TV that is off from one that is just busy. */
    fun reach(host: String, port: Int, ms: Int = 1500): Boolean = try { Lan.connect(host, port, ms).close(); true } catch (e: Exception) { false }

    private val FOCUS = Regex("u\\d+ ([\\w.]+)/")
    private val KNOWN = mapOf("com.google.android.youtube.tv" to "YouTube", "com.google.android.youtube.tvunplugged" to "YouTube TV",
        "com.netflix.ninja" to "Netflix", "com.amazon.amazonvideo.livingroom" to "Prime Video", "com.disney.disneyplus" to "Disney+",
        "com.google.android.apps.youtube.music" to "YouTube Music", "com.spotify.tv.android" to "Spotify", "com.plexapp.android" to "Plex",
        "org.xbmc.kodi" to "Kodi", "com.android.tv.settings" to "Settings", "com.android.vending" to "Play Store", "dev.tvremote.setup" to "TV Setup") +
        dev.tvremote.core.ontv.Bundle.apps.associate { it.pkg to it.name }   // ours: "dev.tvremote.channels" read as "Dev"
    private val NOISE = setOf("com", "org", "net", "tv", "android", "app", "apps", "google", "tvapp", "ninja", "livingroom")

    /** A TV's launcher is "home": the page says it in the person's language, so no word here. */
    fun isHome(pkg: String) = pkg.contains("launcher") && !pkg.startsWith("dev.tvremote")

    /** Well known apps by name, else the first part of the package that says something ("com.hbo.hbonow" is "Hbo"); "" for the launcher. */
    fun appName(pkg: String): String = KNOWN[pkg] ?: if (pkg.isEmpty() || isHome(pkg)) "" else pkg.split('.').firstOrNull { it !in NOISE }.orEmpty().replaceFirstChar { it.uppercase() }

    /** What [CMD] printed. Anything missing stays empty: an odd TV shows less, never an error. */
    fun parse(host: String, out: String, now: Long = System.currentTimeMillis()): Status {
        val p = out.replace("\r", "").split("#~#\n") + List(4) { "" }
        val wake = Regex("mWakefulness=(\\w+)").find(p[0])?.groupValues?.get(1)
        val state = when { wake == null -> UP; wake == "Awake" || wake == "Dreaming" -> ON; else -> STANDBY }
        val app = if (state == ON) FOCUS.find(p[1])?.groupValues?.get(1).orEmpty() else ""
        val total = Regex("MemTotal:\\s+(\\d+)").find(p[2])?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val avail = Regex("MemAvailable:\\s+(\\d+)").find(p[2])?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val head = p[3].trim().split("\n").map { it.trim() } + List(3) { "" }
        val make = head[0]; val model = head[1]
        return Status(host, state, model = (if (model.startsWith(make, true) || make.isEmpty()) model else "$make $model").trim(), android = head[2],
            app = app, appName = appName(app), home = app.isNotEmpty() && isHome(app),
            uptime = Regex("^(\\d+)").find(p[2].trim())?.groupValues?.get(1)?.toLongOrNull() ?: 0,
            memFree = if (total > 0 && avail > 0) (avail * 100 / total).toInt() else -1, at = now)
    }

    /**
     * One TV: [open] says whether a port answers (the TCP connect, ~1.5 s at most), [link] is the ADB link kept for it (null: none).
     * An Android TV whose ADB is not ready (first dial, or the TV asking the person) is "reachable" for now, never an error.
     */
    fun read(host: String, kind: String, open: (Int) -> Boolean, link: AdbLink?, shellMs: Long = 6000, now: () -> Long = System::currentTimeMillis): Status {
        if (kind != Remote.Kind.ANDROID) return Status(host, if (Remote.Kind.ports(kind).any { open(it) }) UP else OFF, at = now())
        if (dev.tvremote.core.adb.AdbPort.candidates(host).none { open(it) }) return Status(host, if (open(6466)) UP else OFF, at = now())      // keys only: ADB off, the remote link on
        val l = link ?: return Status(host, UP, at = now())
        val until = System.nanoTime() + 3_000_000_000L                // a link still dialling gets a moment (the first round after opening the list)
        var st = l.state()
        while (st == "unknown" && System.nanoTime() < until) { Thread.sleep(150); st = l.state() }
        if (st != "ready") return Status(host, UP, at = now())
        return try {
            val adb = l.get()
            val known = fixed[host]?.takeIf { it.first === adb }
            val s = parse(host, adb.shell(if (known != null) CMD_LIVE else CMD, shellMs), now())
            if (known != null) s.copy(model = known.second, android = known.third)
            else s.also { if (it.model.isNotEmpty()) fixed[host] = Triple(adb, it.model, it.android) }
        } catch (e: Exception) { Status(host, UP, at = now()) }
    }

    /**
     * The round of reading, kept between calls: a TV read less than [maxAgeMs] ago is not read again (two pages, or a page and a
     * refresh, cost one reading), a read still running is not started twice, and one slow TV never holds the others: [round]
     * returns after [waitMs] with what it has (the TV's last reading, or UNKNOWN) while the slow read finishes on its own.
     */
    class Board(private val read: (String) -> Status, private val maxAgeMs: Long = 4000, workers: Int = 8) {
        private val pool: ExecutorService = Executors.newFixedThreadPool(workers) { Thread(it, "fleet-status").apply { isDaemon = true } }
        private val last = ConcurrentHashMap<String, Status>()
        private val busy = ConcurrentHashMap<String, Future<*>>()

        fun round(hosts: List<String>, waitMs: Long = 9000): List<Status> {
            val now = System.currentTimeMillis()
            val running = hosts.distinct().mapNotNull { h ->
                val s = last[h]
                if (s != null && now - s.at < maxAgeMs) null
                // not computeIfAbsent: its java.util.function lambda is Android 7+, the phone app starts at 5 (census b1ccd0d2, Android 6)
                else synchronized(busy) {
                    busy[h] ?: pool.submit {
                        try { last[h] = try { read(h) } catch (e: Exception) { Status(h, UP, at = System.currentTimeMillis()) } }
                        finally { synchronized(busy) { busy.remove(h) } }     // after the put below, never before it
                    }.also { busy[h] = it }
                }
            }
            val until = System.nanoTime() + waitMs * 1_000_000
            for (f in running) try { f.get(maxOf(0, until - System.nanoTime()), TimeUnit.NANOSECONDS) } catch (e: Exception) { }
            return hosts.map { last[it] ?: Status(it) }
        }

        fun forget(host: String) { last.remove(host) }
        fun close() { pool.shutdownNow() }
    }
}
