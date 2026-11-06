package dev.tvremote.core.ontv
import org.json.JSONArray
import org.json.JSONObject

/**
 * Health → Now's live figures, read every 2 s while that view is open (`/api/health/live`): CPU per core and what it is busy
 * with, memory, traffic, the open connections and each app's traffic. Only kernel files and one filtered dumpsys, cut on the
 * TV; the parsers here take what the shell printed and leave out what a TV did not tell.
 */
object LiveLoad {
    private const val SEP = "printf '\\043~\\043\\n'; "

    /** /proc/stat's cpu lines, the memory lines, the traffic per interface and each core's clock: ~2.5 KB, ~16 ms (emulator). */
    const val FAST = "grep '^cpu' /proc/stat; " + SEP +
            "grep -E '^(MemTotal|MemFree|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo; " + SEP +
            "cat /proc/net/dev; " + SEP + "cat /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq 2>/dev/null; "   // ends with "; ": CONNS / TRAFFIC follow
    /** The first reading of a TV: two counts half a second apart, so the first answer already has a CPU figure. */
    const val FIRST = "grep '^cpu' /proc/stat; sleep 0.5; "

    /** Network view only: established TCP connections per uid (/proc/net/tcp's 4th field 01, 8th the uid). */
    const val CONNS = SEP + "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '\$4==\"01\"{n[\$8]++} END{for(u in n) print u, n[u]}'; "
    /** Network view only, at most every 10 s: bytes per uid since boot. Android 10+ keeps them in a BPF map that dumpsys netstats
     *  prints (one line per uid); older kernels in xt_qtaguid (summed per uid here, tag 0x0 = the uid's total). */
    const val TRAFFIC = SEP + "if [ -r /proc/net/xt_qtaguid/stats ]; then awk 'NR>1 && \$3==\"0x0\"{r[\$4]+=\$6; t[\$4]+=\$8} END{for(u in r) print u, r[u], t[u]}' /proc/net/xt_qtaguid/stats; " +
            "else dumpsys netstats --uid 2>/dev/null | awk '/^  mAppUidStatsMap:\$/{f=1;next} /^  m[A-Za-z]+:\$/{f=0} f && \$1~/^[0-9]+\$/{print \$1, \$2, \$4}'; fi"
    /** uid → package, for the traffic list (~8 KB, read once in 10 minutes). */
    const val UIDS = "pm list packages -U 2>/dev/null"

    /** The readings of /proc/stat's cpu lines in [text] (one, or two with [FIRST]), each cpu, cpu0, cpu1 … →
     *  {total, idle + iowait, user + nice, system + irq + softirq, iowait}. */
    fun cpuLines(text: String): List<Map<String, LongArray>> {
        val out = ArrayList<LinkedHashMap<String, LongArray>>()
        for (line in text.lines()) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 8 || !f[0].startsWith("cpu")) continue
            if (f[0] == "cpu" || out.isEmpty()) out.add(LinkedHashMap())
            val n = f.drop(1).take(7).map { it.toLongOrNull() ?: 0L }
            out.last()[f[0]] = longArrayOf(n.sum(), n[3] + n[4], n[0] + n[1], n[2] + n[5] + n[6], n[4])
        }
        return out
    }

    /** The two readings' difference: {cpu, apps, system, io (percent of the whole TV), cores: [percent], or nothing for a core gone offline}. */
    fun cpu(was: Map<String, LongArray>, now: Map<String, LongArray>, o: JSONObject) {
        fun pct(a: LongArray?, b: LongArray?, i: Int): Int? {
            if (a == null || b == null) return null
            val total = b[0] - a[0]; if (total <= 0) return null
            val v = if (i == 1) total - (b[1] - a[1]) else b[i] - a[i]
            return Math.round(100.0 * v.coerceIn(0, total) / total).toInt()
        }
        val all = now["cpu"]; val before = was["cpu"]
        pct(before, all, 1)?.let { o.put("cpu", it).put("apps", pct(before, all, 2)).put("system", pct(before, all, 3)).put("io", pct(before, all, 4)) }
        val cores = JSONArray()
        for ((k, v) in now) if (k != "cpu") cores.put(pct(was[k], v, 1) ?: JSONObject.NULL)
        if (cores.length() > 0) o.put("cores", cores)
    }

    /** Each core's clock in MHz, or nothing when the TV does not say or says nonsense (the emulator: 1 kHz). */
    fun clocks(text: String): JSONArray? {
        val mhz = text.lines().mapNotNull { it.trim().toLongOrNull() }.map { it / 1000 }
        return if ((mhz.maxOrNull() ?: 0L) < 100) null else JSONArray(mhz)
    }

    /**
     * {total, avail, free, swap_total, swap_free} in bytes. In use is total - MemAvailable, the figure the full-memory notice and
     * auto free RAM go by (one number in the whole app); the page's cached part is MemAvailable - MemFree, what Android can give
     * back at once: on a TV with swap a few MB, though Cached says 180 (most of it is needed again at once).
     */
    fun mem(text: String): JSONObject {
        val o = JSONObject()
        for ((k, n) in listOf("MemTotal" to "total", "MemAvailable" to "avail", "MemFree" to "free", "SwapTotal" to "swap_total", "SwapFree" to "swap_free"))
            Meminfo.kb(text, k)?.let { o.put(n, it * 1024) }
        return o
    }

    /** uid → count, from [CONNS]. */
    fun conns(text: String): Map<Int, Int> = text.lines().mapNotNull { l ->
        val f = l.trim().split(' '); if (f.size == 2) f[0].toIntOrNull()?.let { u -> f[1].toIntOrNull()?.let { u to it } } else null
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }

    /** uid → {rx, tx}, from [TRAFFIC]. */
    fun traffic(text: String): Map<Int, LongArray> {
        val out = HashMap<Int, LongArray>()
        for (l in text.lines()) {
            val f = l.trim().split(Regex("\\s+")); if (f.size != 3) continue
            val u = f[0].toIntOrNull() ?: continue; val r = f[1].toLongOrNull() ?: continue; val t = f[2].toLongOrNull() ?: continue
            val a = out.getOrPut(u) { LongArray(2) }; a[0] += r; a[1] += t
        }
        return out
    }

    /** uid → the first package with it, from `pm list packages -U` ("package:com.x uid:10084"). */
    fun uids(text: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (m in Regex("package:(\\S+) uid:(\\d+)").findAll(text)) out.getOrPut(m.groupValues[2].toInt()) { m.groupValues[1] }
        return out
    }

    /** The apps by traffic: [{pkg ("android" for the system's uids), rx, tx, conns}], busiest first; [names] gives a package's label. */
    fun apps(traffic: Map<Int, LongArray>, conns: Map<Int, Int>, pkgs: Map<Int, String>, names: (String) -> String?): JSONArray {
        val by = LinkedHashMap<String, LongArray>()                       // pkg → rx, tx, conns
        for (u in traffic.keys + conns.keys) {
            val app = u % 100000                                           // another user's copy of the app counts as the app
            val pkg = if (app < 10000) "android" else pkgs[app] ?: continue
            val a = by.getOrPut(pkg) { LongArray(3) }
            traffic[u]?.let { a[0] += it[0]; a[1] += it[1] }
            a[2] += (conns[u] ?: 0).toLong()
        }
        return JSONArray(by.entries.filter { it.value[0] + it.value[1] > 0 || it.value[2] > 0 }.sortedByDescending { it.value[0] + it.value[1] }.take(12).map { (pkg, a) ->
            JSONObject().put("pkg", pkg).put("label", if (pkg == "android") JSONObject.NULL else names(pkg) ?: pkg).put("rx", a[0]).put("tx", a[1]).put("conns", a[2])
        })
    }
}
