package dev.tvremote.core.manage
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.ontv.LiveLoad
import dev.tvremote.core.ontv.Meminfo
import dev.tvremote.core.ontv.Power
import dev.tvremote.core.ontv.Sensors
import dev.tvremote.core.ontv.SetupFiles
import dev.tvremote.core.ontv.SetupNeed
import dev.tvremote.core.ontv.Suspended
import dev.tvremote.core.web.HttpServer
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/** The Health tab: a live snapshot of the TV, power, the crashes Android noted, and the history TV Setup records. */
internal class HealthApi(core: ManageCore) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? {
        when (what) {
            "health" -> return HttpServer.Response.json(health(r.q("apps") == "1"))
            "health/live" -> return HttpServer.Response.json(live(r.q("net") == "1"))
            "power" -> return HttpServer.Response.json(Power.parse(link.get().shell(Power.CMD, 20000)) { try { SetupFiles.read({ sh(it) }, "stats/power.jsonl", 3000) } catch (e: SetupNeed.Missing) { "" } }.let { if (it.has("now")) it else it.put("now", System.currentTimeMillis() / 1000) })
            "health/suspended" -> return HttpServer.Response.json(if (post) unsuspend(r.json().optString("by")) else suspended())
            "crashes" -> return HttpServer.Response.json(JSONObject().put("text", sh(CRASHES).replace("\r\n", "\n").takeLast(500000)))
            "stats" -> return HttpServer.Response.json(stats(r.q("days").toIntOrNull() ?: 7))
        }
        return null
    }

    // ---------------------------------------------------------------- suspended apps
    /** {apps: [{pkg, name, by: [{pkg, name, shell}]}]}: the apps another package locked ("managed by Google TV"). One filtered dumpsys, read when the Health tab opens, never on its 5 s tick. */
    private fun suspended(): JSONObject {
        val apps = JSONArray()
        val name = { p: String -> labels?.label(p) ?: p }
        for ((pkg, by) in Suspended.parse(sh(Suspended.CMD)))
            apps.put(JSONObject().put("pkg", pkg).put("name", name(pkg)).put("by", JSONArray(by.map { JSONObject().put("pkg", it).put("name", name(it)).put("shell", it in Suspended.SHELL) })))
        return JSONObject().put("apps", apps)
    }

    /** Lifts what [by] suspended, under each Android user it did so: the shell's own with `pm unsuspend`, any other package by clearing its data (Android then lifts all of its suspensions; it also resets that app). Then reads again. */
    private fun unsuspend(by: String): JSONObject {
        require(PKG.matcher(by).matches() || by in Suspended.SHELL) { "bad package" }
        val out = sh(Suspended.CMD)
        val mine = Suspended.of(Suspended.parse(out), by).filter { PKG.matcher(it).matches() }
        if (mine.isEmpty()) return suspended()
        for (cmd in Suspended.lift(by, mine, Suspended.users(out, by))) sh(cmd)   // under each user it locked them for (a Google TV profile)
        for (i in 0 until 4) {                                         // Android lifts them a moment after the clear returns
            if (Suspended.of(Suspended.parse(sh(Suspended.CMD)), by).isEmpty()) break
            Thread.sleep(600)
        }
        return suspended()
    }

    // ---------------------------------------------------------------- history
    /** CPU load in percent over one second, or null. */
    fun cpuNow(): Int? = try {
        val f = sh("head -1 /proc/stat; sleep 1; head -1 /proc/stat").trim().jsplit("\n")
        Meminfo.cpu(f[0], f[1])
    } catch (e: Exception) { null }

    /** Lets the TV Setup app on the TV read which app is on screen (it cannot ask for this itself); false when it could not be asked (no ADB yet): the caller tries again later. */
    fun allowUsage(pkg: String): Boolean = try { sh("appops set $pkg GET_USAGE_STATS allow"); true } catch (ignored: Exception) { false }

    /** What the TV Setup app on the TV has recorded (Stats.kt there), for the last [days] days. */
    private fun stats(days: Int): JSONObject {
        val d = days.coerceIn(1, 90)
        val from = System.currentTimeMillis() / 1000 - d * 86400L
        val read = { name: String, lines: Int -> SetupFiles.read({ sh(it) }, "stats/$name", lines) }
        val usage = try { JSONObject(read("usage.json", 1000)) } catch (e: Exception) { null }
        val samples = JSONArray()
        for (line in read("samples.jsonl", d * 300).jsplit("\n"))
            try { val a = JSONArray(line); if (a.getLong(0) >= from) samples.put(a) } catch (ignored: Exception) { }
        val net = JSONArray()                                         // the network going away and coming back (TV Setup 2.16+)
        for (line in read("net.jsonl", 400).jsplit("\n"))
            try { val a = JSONArray(line); if (a.getLong(0) >= from) net.put(a) } catch (ignored: Exception) { }
        val names = JSONObject()
        if (usage != null && labels != null)
            for (day in usage.keys()) for (pkg in usage.getJSONObject(day).keys()) if (!names.has(pkg)) labels.label(pkg)?.let { names.put(pkg, it) }
        return JSONObject().put("days", d).put("collector", usage != null || samples.length() > 0).put("usage", usage ?: JSONObject()).put("samples", samples).put("net", net).put("labels", names)
    }

    // ---------------------------------------------------------------- health
    // what a health snapshot says that hardly changes (model, screen, Wi-Fi, IPs): read once a minute, not on every 5 s tick
    private var slowAt = 0L
    private var slowFor: Any? = null
    private var slowParts: List<String> = emptyList()

    @Synchronized private fun slowFacts(adb: Adb): List<String> {
        val now = System.currentTimeMillis()
        if (slowFor !== adb || now - slowAt > 60_000 || slowParts.isEmpty()) {
            val same = slowFor === adb && slowParts.isNotEmpty() && slowParts[0].isNotBlank()   // model, Android, chip: fixed on one link
            val got = (adb.shell(if (same) HEALTH_STATIC.substring(HEALTH_PROPS.length) else HEALTH_STATIC) + "#~##~##~##~#").jsplit("#~#", -1).take(5)
            slowParts = if (same) listOf(slowParts[0]) + got.drop(1) else got
            slowFor = adb; slowAt = now
        }
        return slowParts
    }

    /** The last CPU count read ([health]): which TV, when, {total, idle}. The Health tab asks every 10 s, so the next reading
     *  measures against it and needs no 0.6 s wait of its own. */
    @Volatile private var cpuWas: Triple<Any, Long, LongArray>? = null

    /** [apps]: Health → Now → CPU is open: the load per app ([topApps]) instead of the five busiest processes. */
    private fun health(apps: Boolean = false): JSONObject {
        val adb = link.get()
        val was = cpuWas?.takeIf { it.first === adb && System.currentTimeMillis() - it.second < 30000 }
        val t0 = System.currentTimeMillis()
        adb.shell(":")
        val ping = System.currentTimeMillis() - t0
        val slow = slowFacts(adb)
        val cmd = if (was != null) HEALTH_LIVE_NOW else HEALTH_LIVE
        val live = (adb.shell(if (apps) cmd.replace(TOP, TOP_APPS) else cmd) + "#~#".repeat(10)).jsplit("#~#", -1)
        // same numbering as before: the facts that hardly change come from the cache, the rest was just read
        val part = listOf(slow[0], live[0], live[1], live[2], live[3], slow[1], slow[2], slow[3], live[4], live[5])
        val props = (part[0].trim() + "\n\n\n\n\n\n\n\n").jsplit("\n", -1)
        val o = JSONObject().put("ping_ms", ping).put("maker", props[0].trim()).put("model", props[1].trim()).put("android", props[2].trim()).put("sdk", props[3].trim())
                .put("patch", props[4].trim()).put("chip", props[5].trim()).put("abi", props[6].trim())
        val cores = find("(\\d+)\\s*\$", props[7].trim())
        o.put("cores", if (cores == null) JSONObject.NULL else cores.toInt() + 1)
        val stat = ArrayList<LongArray>().apply { if (was != null) add(was.third) }
        for (line in part[1].trim().jsplit("\n")) {
            val f = line.trim().jsplit("\\s+")
            if (f[0] != "cpu" || f.size < 6) continue
            var total = 0L
            for (i in 1 until f.size) total += f[i].toLong()
            stat.add(longArrayOf(total, f[4].toLong() + f[5].toLong()))
        }
        stat.lastOrNull()?.let { cpuWas = Triple(adb, System.currentTimeMillis(), it) }
        if (stat.size == 2 && stat[1][0] > stat[0][0]) {
            val total = stat[1][0] - stat[0][0]
            val idle = stat[1][1] - stat[0][1]
            o.put("cpu", Math.round(100.0 * (total - idle) / total))
        }
        val memTotal = Meminfo.kb(part[2], "MemTotal")
        val memFree = Meminfo.kb(part[2], "MemAvailable")
        if (memTotal != null) o.put("mem_total", memTotal * 1024)
        if (memFree != null) o.put("mem_free", memFree * 1024)
        val df = part[3].trim().jsplit("\\s+")
        if (df.size > 3 && df[1].matches(DIGITS) && df[3].matches(DIGITS)) o.put("disk_total", df[1].toLong() * 1024).put("disk_free", df[3].toLong() * 1024)
        val up = find("^\\s*([\\d.]+)", part[4])
        if (up != null) o.put("uptime", up.toDouble())
        o.put("screen", find("(\\d+x\\d+)", part[5]) ?: "").put("density", num(find("density: (\\d+)", part[5])))
        val fps = find("fps=([\\d.]+)", part[5])
        if (fps != null) o.put("fps", Math.round(fps.toDouble()))
        val ssid = find("SSID: ([^,]*),", part[6])
        if (ssid != null && ssid.trim().isNotEmpty() && !ssid.contains("unknown ssid"))
            o.put("wifi", JSONObject().put("ssid", ssid.trim().replace(Regex("^\"|\"\$"), "")).put("rssi", num(find("RSSI: (-?\\d+)", part[6]))).put("speed", num(find("Link speed: (\\d+)", part[6]))).put("freq", num(find("Frequency: (\\d+)", part[6])))
                .put("mac", find("MAC: ([0-9a-fA-F:]{17})", part[6])?.takeIf { it != "02:00:00:00:00:00" } ?: JSONObject.NULL))   // the one the router sees (a random one per network on Android 10+); 02:00… = hidden
        val ips = JSONArray()
        val m = Pattern.compile("(?m)^\\d+: (\\S+)\\s+inet ([\\d.]+)").matcher(part[7])
        while (m.find()) if (m.group(1) != "lo") ips.put(JSONObject().put("if", m.group(1)).put("ip", m.group(2)))
        o.put("ips", ips)
        val top = JSONArray()
        if (apps) o.put("apps", topApps(part[8]))
        else for (line in part[8].jsplit("\n")) {
            val t = Pattern.compile("\\s*([\\d.]+)\\s+([\\d.]+)\\s+(.+)").matcher(line)
            if (!t.matches() || t.group(3)!!.matches(Regex("(top -n|tail |sh -c).*")) || top.length() >= 5) continue
            val name = t.group(3)!!.trim().jsplit("\\s+")[0].replace(Regex("^\\[|\\]\$"), "")
                .replace(Regex("^(android|vendor\\.\\w+)\\.hardware\\."), "").replace(Regex("@[\\d.]+-service.*\$"), "")   // system services: keep the part that says what it is
            val label = if (labels != null && PKG.matcher(name).matches()) labels.label(name) else null
            top.put(JSONObject().put("cpu", t.group(1)!!.toDouble()).put("mem", t.group(2)!!.toDouble()).put("name", label ?: name))
        }
        o.put("top", top)
        val awake = find("mWakefulness=(\\w+)", part[9])
        val app = find("u\\d+ ([\\w.]+)/", part[9])
        o.put("on", if (awake == null) JSONObject.NULL else awake == "Awake")
        if (app != null) o.put("app", labels?.label(app) ?: app)
        // the computer's server reports what its remote-control session sees; here the same facts come from ADB
        o.put("remote", JSONObject().put("connected", awake != null).put("is_on", awake == "Awake").put("app", o.optString("app")))
        Sensors.thermal(live[6], live[7])?.let { o.put("thermal", it) }
        Sensors.gpu(live[8])?.let { o.put("gpu", it) }
        Sensors.netBytes(live[9])?.let { o.put("net", netRate(adb, it)) }
        Sensors.bluetooth(slow[4])?.let { o.put("bt", it) }
        nightly(adb)?.let { o.put("nightly", it) }
        return o
    }

    /** CPU → its apps: [{pkg ("-": Android's own processes), name, cpu (percent of one core, as top says), procs}], busiest first. */
    private fun topApps(out: String): JSONArray {
        val rows = out.lines().mapNotNull { l ->
            val f = l.trim().split(Regex("\\s+"))
            val c = f.getOrNull(0)?.toDoubleOrNull()
            if (f.size == 3 && c != null) Triple(f[2], c, f[1].toIntOrNull() ?: 0) else null
        }
        // a process is counted for its app (gms.unstable for Play services, Procs.appOf); one of no installed app is Android's
        val named = rows.map { (p, c, n) -> val a = if (p == "-") "android" else Procs.appOf(p) { labels?.let { l -> l.label(it) != null } ?: true }
            val pkg = if (a == "android") "-" else a; Triple(pkg, c, n) to (if (pkg == "-") null else labels?.label(pkg)) }
        val merged = named.groupBy { it.first.first }.map { (p, g) -> Triple(p, g.sumOf { it.first.second }, g.sumOf { it.first.third }) to g.first().second }
        return JSONArray(merged.sortedWith(compareByDescending<Pair<Triple<String, Double, Int>, String?>> { it.first.second }.thenByDescending { it.first.third }).map { (t, l) ->
            JSONObject().put("pkg", t.first).put("name", l ?: JSONObject.NULL).put("cpu", Math.round(t.second * 10) / 10.0).put("procs", t.third)
        })
    }

    // ---------------------------------------------------------------- live: every 2 s while Health → Now is open
    @Volatile private var liveWas: Triple<Any, Long, Map<String, LongArray>>? = null
    private var trafficAt = 0L
    private var trafficOf: Any? = null
    private var trafficIs = ""
    private var uidsAt = 0L
    private var uidsOf: Any? = null
    private var uidsIs: Map<Int, String> = emptyMap()

    /** CPU per core and what it is busy with, memory, traffic; with [net] the open connections and each app's traffic too
     *  (that one read at most every 10 s, the uid → package list once in 10 minutes). */
    private fun live(net: Boolean): JSONObject {
        val adb = link.get()
        val now = System.currentTimeMillis()
        val was = liveWas?.takeIf { it.first === adb && now - it.second < 30000 }
        val traffic = net && synchronized(this) { (trafficOf !== adb || now - trafficAt > 10_000).also { if (it) { trafficOf = adb; trafficAt = now } } }
        val cmd = (if (was == null) LiveLoad.FIRST else "") + LiveLoad.FAST + (if (net) LiveLoad.CONNS else "") + (if (traffic) LiveLoad.TRAFFIC else "")
        val p = (adb.shell(cmd) + "#~#".repeat(6)).jsplit("#~#", -1)
        val o = JSONObject()
        val reads = LiveLoad.cpuLines(p[0])
        val before = was?.third ?: reads.getOrNull(reads.size - 2)
        reads.lastOrNull()?.let { if (before != null) LiveLoad.cpu(before, it, o); liveWas = Triple(adb, System.currentTimeMillis(), it) }
        o.put("mem", LiveLoad.mem(p[1]))
        Sensors.netBytes(p[2])?.let { b -> o.put("net", netRate(adb, b).put("rx_total", b[0]).put("tx_total", b[1])) }
        LiveLoad.clocks(p[3])?.let { o.put("mhz", it) }
        if (net) {
            val conns = LiveLoad.conns(p[4])
            o.put("conns", conns.values.sum())
            val (seen, pkgs) = synchronized(this) {
                if (traffic) trafficIs = p[5]
                if (uidsOf !== adb || now - uidsAt > 600_000) { uidsIs = LiveLoad.uids(adb.shell(LiveLoad.UIDS)); uidsOf = adb; uidsAt = now }
                trafficIs to uidsIs
            }
            o.put("apps", LiveLoad.apps(LiveLoad.traffic(seen), conns, pkgs) { labels?.label(it) })
        }
        return o
    }

    private var nightlyAt = 0L
    private var nightlyOf: Any? = null
    private var nightlyIs: JSONObject? = null

    /** TV Setup's last night maintenance ({day, freed, compiled, ...}, stats/nightly.json); null when there is none or this TV Setup is older. Read once in 10 minutes: it changes once a night. */
    @Synchronized private fun nightly(adb: Adb): JSONObject? {
        val now = System.currentTimeMillis()
        if (nightlyOf !== adb || now - nightlyAt > 600_000) {
            nightlyIs = try { SetupFiles.read({ adb.shell(it) }, "stats/nightly.json", 1).trim().takeIf { it.startsWith("{") }?.let { JSONObject(it) } } catch (e: Exception) { null }
            nightlyOf = adb; nightlyAt = now
        }
        return nightlyIs
    }

    private var netLast: Triple<Any, Long, LongArray>? = null

    /** {rx, tx} in bytes per second since the last snapshot of this TV; nothing on the first (no rate yet) or after a counter reset. */
    @Synchronized private fun netRate(adb: Adb, now: LongArray): JSONObject {
        val t = System.currentTimeMillis()
        val last = netLast
        netLast = Triple(adb, t, now)
        val o = JSONObject()
        if (last == null || last.first !== adb || t - last.second < 1000 || now[0] < last.third[0] || now[1] < last.third[1]) return o
        val s = (t - last.second) / 1000.0
        return o.put("rx", Math.round((now[0] - last.third[0]) / s)).put("tx", Math.round((now[1] - last.third[1]) / s))
    }

    companion object {
        private const val TOP = "top -n 1 -b -m 7 -o %CPU,%MEM,ARGS -s 1 | tail -7"
        /** Health → Now → CPU: every process's load summed per app on the TV (~1.4 KB): "cpu processes package", "-" for the rest. */
        // the reading itself (top, its shell and awk, adbd carrying it: 20-35 % on the FPT box) is not the TV's load: left out
        private const val TOP_APPS = "top -n 1 -b -o %CPU,ARGS -s 1 | awk '\$2==\"top\"||\$2==\"sh\"||\$2==\"awk\"||\$2==\"adbd\"{next} \$1~/^[0-9.]+\$/{n=\$2; if (n~/^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+)+(:|\$)/) sub(/:.*/,\"\",n); else n=\"-\"; c[n]+=\$1; k[n]++} END{for(x in c) print c[x], k[x], x}'"
        /** The separator is printed from octal codes, so it does not appear in this command's own line in the `top` output. */
        private const val HEALTH_PROPS = "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; " +
                "getprop ro.build.version.security_patch; getprop ro.board.platform; getprop ro.product.cpu.abi; cat /sys/devices/system/cpu/present; "
        /** [HEALTH_PROPS] are read once per link: after that the command starts at its first separator (part 0 kept from the cache). */
        private const val HEALTH_STATIC = HEALTH_PROPS +
                "printf '\\043~\\043\\n'; wm size; wm density; dumpsys display | grep -m1 -o 'fps=[0-9.]*'; printf '\\043~\\043\\n'; dumpsys wifi | grep -m1 mWifiInfo; " +
                "printf '\\043~\\043\\n'; ip -o -4 addr show; printf '\\043~\\043\\n'; " + Sensors.SLOW

        private const val HEALTH_LIVE = "head -1 /proc/stat; sleep 0.6; head -1 /proc/stat; printf '\\043~\\043\\n'; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; printf '\\043~\\043\\n'; df -k /data | tail -1; " +
                "printf '\\043~\\043\\n'; cat /proc/uptime; printf '\\043~\\043\\n'; " + TOP + "; " +
                "printf '\\043~\\043\\n'; dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'; dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'; " +
                "printf '\\043~\\043\\n'; " + Sensors.LIVE
        private val HEALTH_LIVE_NOW = HEALTH_LIVE.replaceFirst("head -1 /proc/stat; sleep 0.6; ", "")   // a reading 10 s ago to count from: [cpuWas]

        /** Health → Errors: the dropbox's notes on apps that stopped, froze or crashed natively, cut on the TV to the lines the page
         *  shows (ANR thread dumps are hundreds of KB). */
        private const val CRASHES = "for t in data_app_crash system_app_crash system_server_crash data_app_anr system_app_anr system_server_anr " +
                "data_app_native_crash system_app_native_crash; do dumpsys dropbox --print \$t; done | grep -E '^(=====|[0-9]{4}-[0-9]{2}-[0-9]{2} " +
                "[0-9:]{8} |Process: |Subject: |signal [0-9]|Abort message|Caused by: |[A-Za-z_\$][A-Za-z0-9_.\$]*(Exception|Error)(: |\$)|\tat )'"

        private fun find(regex: String, input: String): String? { val m = Pattern.compile(regex).matcher(input); return if (m.find()) m.group(1) else null }

        private fun num(s: String?): Any = s?.toLong() ?: JSONObject.NULL
    }
}
