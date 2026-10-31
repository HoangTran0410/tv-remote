package dev.tvremote.core.manage
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.ontv.Meminfo
import dev.tvremote.core.ontv.Power
import dev.tvremote.core.ontv.Sensors
import dev.tvremote.core.ontv.SetupFiles
import dev.tvremote.core.ontv.SetupNeed
import dev.tvremote.core.web.HttpServer
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/** The Health tab: a live snapshot of the TV, power, the crashes Android noted, and the history TV Setup records. */
internal class HealthApi(core: ManageCore) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? {
        when (what) {
            "health" -> return HttpServer.Response.json(health())
            "power" -> return HttpServer.Response.json(Power.parse(link.get().shell(Power.CMD, 20000)) { try { SetupFiles.read({ sh(it) }, "stats/power.jsonl", 3000) } catch (e: SetupNeed.Missing) { "" } }.let { if (it.has("now")) it else it.put("now", System.currentTimeMillis() / 1000) })
            "crashes" -> return HttpServer.Response.json(JSONObject().put("text", sh(CRASHES).replace("\r\n", "\n").takeLast(500000)))
            "stats" -> return HttpServer.Response.json(stats(r.q("days").toIntOrNull() ?: 7))
        }
        return null
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
            slowParts = (adb.shell(HEALTH_STATIC) + "#~##~##~##~#").jsplit("#~#", -1).take(5)
            slowFor = adb; slowAt = now
        }
        return slowParts
    }

    /** The last CPU count read ([health]): which TV, when, {total, idle}. The Health tab asks every 10 s, so the next reading
     *  measures against it and needs no 0.6 s wait of its own. */
    @Volatile private var cpuWas: Triple<Any, Long, LongArray>? = null

    private fun health(): JSONObject {
        val adb = link.get()
        val was = cpuWas?.takeIf { it.first === adb && System.currentTimeMillis() - it.second < 30000 }
        val t0 = System.currentTimeMillis()
        adb.shell(":")
        val ping = System.currentTimeMillis() - t0
        val slow = slowFacts(adb)
        val live = (adb.shell(if (was != null) HEALTH_LIVE_NOW else HEALTH_LIVE) + "#~#".repeat(10)).jsplit("#~#", -1)
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
            o.put("wifi", JSONObject().put("ssid", ssid.trim().replace(Regex("^\"|\"\$"), "")).put("rssi", num(find("RSSI: (-?\\d+)", part[6]))).put("speed", num(find("Link speed: (\\d+)", part[6]))).put("freq", num(find("Frequency: (\\d+)", part[6]))))
        val ips = JSONArray()
        val m = Pattern.compile("(?m)^\\d+: (\\S+)\\s+inet ([\\d.]+)").matcher(part[7])
        while (m.find()) if (m.group(1) != "lo") ips.put(JSONObject().put("if", m.group(1)).put("ip", m.group(2)))
        o.put("ips", ips)
        val top = JSONArray()
        for (line in part[8].jsplit("\n")) {
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
        /** The separator is printed from octal codes, so it does not appear in this command's own line in the `top` output. */
        private const val HEALTH_STATIC = "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; " +
                "getprop ro.build.version.security_patch; getprop ro.board.platform; getprop ro.product.cpu.abi; cat /sys/devices/system/cpu/present; " +
                "printf '\\043~\\043\\n'; wm size; wm density; dumpsys display | grep -m1 -o 'fps=[0-9.]*'; printf '\\043~\\043\\n'; dumpsys wifi | grep -m1 mWifiInfo; " +
                "printf '\\043~\\043\\n'; ip -o -4 addr show; printf '\\043~\\043\\n'; " + Sensors.SLOW

        private const val HEALTH_LIVE = "head -1 /proc/stat; sleep 0.6; head -1 /proc/stat; printf '\\043~\\043\\n'; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; printf '\\043~\\043\\n'; df -k /data | tail -1; " +
                "printf '\\043~\\043\\n'; cat /proc/uptime; printf '\\043~\\043\\n'; top -n 1 -b -m 7 -o %CPU,%MEM,ARGS -s 1 | tail -7; " +
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
