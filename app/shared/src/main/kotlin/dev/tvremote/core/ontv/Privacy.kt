package dev.tvremote.core.ontv

import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.AdbLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Privacy check: which apps on the TV hold the microphone, the camera or the location, which were seen using them, which have
 * a special access (accessibility, notification listener, device admin, drawing over apps, usage access, the voice assistant),
 * and which are known content recognition / telemetry / ads packages ([Bloat]'s lists). It says what was SEEN, never that an
 * app IS listening: a permission is not use, and the system keeps only the last time of each use.
 *
 * One shell stream per scan, run at the lowest priority ([script]): the TV's lists first, ONE `dumpsys package packages`
 * pass (not one per app: it is the heavy part, a second on the emulator), then `appops get` for the candidates, one at a time
 * with a pause, a line per app as it goes. [Reader] turns the lines into rows as they arrive; [Job] keeps the rows for the page,
 * which polls for new ones. Stop closes the stream: the shell ends at its next write.
 */
object Privacy {
    /** Runtime permissions looked at -> the chip they make. Storage is left out: nearly every app holds it, and it says little. */
    internal val PERM_TAG = linkedMapOf(
        "RECORD_AUDIO" to "mic", "CAMERA" to "cam",
        "ACCESS_FINE_LOCATION" to "loc", "ACCESS_COARSE_LOCATION" to "loc", "ACCESS_BACKGROUND_LOCATION" to "loc",
        "READ_CONTACTS" to "contacts", "READ_PHONE_STATE" to "phone", "READ_CALL_LOG" to "phone", "READ_SMS" to "phone",
        "BLUETOOTH_SCAN" to "nearby", "NEARBY_WIFI_DEVICES" to "nearby")
    private val OP_TAG = mapOf("RECORD_AUDIO" to "mic", "CAMERA" to "cam", "FINE_LOCATION" to "loc", "COARSE_LOCATION" to "loc")
    /** The order chips are shown in. */
    internal val ORDER = listOf("mic", "cam", "loc", "a11y", "notif", "acr", "track", "ads", "admin", "overlay", "usage", "assist", "contacts", "phone", "nearby", "boot")
    /** Seen using the microphone / camera / location within this many seconds counts as "recently". */
    const val RECENT = 24 * 3600
    const val REVOKED_FILE = "/data/local/tmp/tvr-privacy-revoked.txt"
    private val PKG = Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")

    /** Bloat's packages that are about watching or tracking, with the kind: acr (content recognition), track (telemetry, analytics), ads. */
    fun known(): Map<String, Pair<String, String>> = Bloat.suggest().mapNotNull { (p, label) ->
        val kind = when {
            Regex("(?i)recogni").containsMatchIn(label) -> "acr"
            Regex("(?i)telemetry|analytics|tracking|usage logger").containsMatchIn(label) -> "track"
            Regex("(?i)\\bads?\\b|advert").containsMatchIn(label) -> "ads"
            else -> null
        }
        kind?.let { p to (it to label) }
    }.toMap()

    /** The shell script of one scan; [knownPkgs] are looked for among the installed apps. */
    fun script(knownPkgs: Collection<String>): String {
        val perms = PERM_TAG.keys.joinToString("|") + "|RECEIVE_BOOT_COMPLETED"
        val k = knownPkgs.filter { PKG.matches(it) }.joinToString("|") { it.replace(".", "\\.") }.ifEmpty { "x\\.none" }
        return """
ionice -c 3 -p ${'$'}${'$'} 2>/dev/null
echo '#sys'; pm list packages -s 2>/dev/null
echo '#off'; pm list packages -d ${TvUser.USER} 2>/dev/null
KN=${'$'}(pm list packages 2>/dev/null | grep -E '^package:($k)${'$'}')
echo '#known'; echo "${'$'}KN"
A=${'$'}(settings get secure enabled_accessibility_services 2>/dev/null); echo '#a11y'; echo "${'$'}A"
L=${'$'}(settings get secure enabled_notification_listeners 2>/dev/null); echo '#notif'; echo "${'$'}L"
V=${'$'}(settings get secure assistant 2>/dev/null; settings get secure voice_interaction_service 2>/dev/null; settings get secure voice_recognition_service 2>/dev/null); echo '#assist'; echo "${'$'}V"
M=${'$'}(dumpsys device_policy 2>/dev/null | grep -A8 'Enabled Device Admins' | grep -E '^ +[A-Za-z0-9_.]+/[A-Za-z0-9_.${'$'}]+:${'$'}'); echo '#admin'; echo "${'$'}M"
O=${'$'}(appops query-op SYSTEM_ALERT_WINDOW allow 2>/dev/null); echo '#overlay'; echo "${'$'}O"
U=${'$'}(appops query-op GET_USAGE_STATS allow 2>/dev/null); echo '#usage'; echo "${'$'}U"
R=${'$'}(cat $REVOKED_FILE 2>/dev/null); echo '#revoked'; echo "${'$'}R"
D=${'$'}(dumpsys package packages 2>/dev/null | grep -E '^[A-Za-z]|^  Package \[|^ +android\.permission\.($perms): granted=true')
echo '#perms'; echo "${'$'}D"
H=${'$'}(echo "${'$'}D" | sed -nE -e '/^[A-Za-z]/{s/.*//;h;}' -e '/^  Package \[/{s/^  Package \[([^]]*)\].*/\1/;h;}' -e '/permission\.(RECORD_AUDIO|CAMERA|ACCESS_[A-Z_]*LOCATION|READ_CONTACTS|READ_PHONE_STATE|READ_CALL_LOG|READ_SMS|BLUETOOTH_SCAN|NEARBY_WIFI_DEVICES): granted/{g;p;}')
C=${'$'}(printf '%s\n' "${'$'}H" "${'$'}A" "${'$'}L" "${'$'}V" "${'$'}M" "${'$'}O" "${'$'}U" "${'$'}KN" "${'$'}R" | tr ':' '\n' | sed -E 's|/.*||;s/ .*//;s/^ +//;s/^package://' | grep -E '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+${'$'}' | sort -u)
echo "#total ${'$'}(echo "${'$'}C" | wc -l)"
n=0
for p in ${'$'}C; do n=${'$'}((n+1)); echo "A ${'$'}n ${'$'}p ${'$'}(appops get ${'$'}p 2>/dev/null | grep -E '(RECORD_AUDIO|CAMERA|FINE_LOCATION|COARSE_LOCATION):' | tr '\n' '|')"; sleep 0.05; done
echo '#done'
""".trimIndent()
    }

    fun command(knownPkgs: Collection<String>): String = "nice -n 19 sh -c " + Adb.q(script(knownPkgs))

    /** "12h49m43s190ms" -> seconds (null if it is not a time). */
    fun secs(t: String): Long? {
        val m = Regex("^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:(\\d+)s)?(?:(\\d+)ms)?$").find(t) ?: return null
        if (t.isEmpty()) return null
        val g = m.groupValues.map { it.toLongOrNull() ?: 0L }
        return g[1] * 86400 + g[2] * 3600 + g[3] * 60 + g[4]
    }

    private fun comp(l: String) = l.trim().substringBefore('/').trim()

    /** Turns the stream's lines into rows. [known]: package -> (kind, name). */
    class Reader(private val known: Map<String, Pair<String, String>> = known()) {
        var total = 0
        private var sec = ""
        private val sys = HashSet<String>(); private val off = HashSet<String>(); private val kn = HashSet<String>()
        private val a11y = HashSet<String>(); private val notif = HashSet<String>(); private val assist = HashSet<String>()
        private val admin = HashSet<String>(); private val overlay = HashSet<String>(); private val usage = HashSet<String>()
        private val perms = HashMap<String, MutableSet<String>>()
        val revoked = HashMap<String, MutableSet<String>>()
        private var cur: String? = null
        var done = false

        /** A row when the line is an app's; null for the rest. */
        fun line(raw: String): JSONObject? {
            val l = raw.trimEnd('\r')
            if (l.startsWith("#")) {
                val w = l.substring(1).trim()
                if (w.startsWith("total ")) total = w.substring(6).trim().toIntOrNull() ?: 0 else if (w == "done") done = true else { sec = w; cur = null }
                return null
            }
            if (l.startsWith("A ") && sec == "perms") {              // after the lists: the loop of apps
                val p = l.split(" ", limit = 4)
                if (p.size < 3 || !PKG.matches(p[2])) return null
                return row(p[2], p.getOrElse(3) { "" })
            }
            val t = l.trim()
            if (t.isEmpty() || t == "null") return null
            when (sec) {
                "sys" -> sys.add(t.removePrefix("package:"))
                "off" -> off.add(t.removePrefix("package:"))
                "known" -> kn.add(t.removePrefix("package:"))
                "a11y" -> t.split(':').forEach { a11y.add(comp(it)) }
                "notif" -> t.split(':').forEach { notif.add(comp(it)) }
                "assist" -> t.split(':').forEach { assist.add(comp(it)) }
                "admin" -> admin.add(comp(t))
                "overlay" -> overlay.add(t)
                "usage" -> usage.add(t)
                "revoked" -> t.split(" ").let { if (it.size == 2 && PKG.matches(it[0])) revoked.getOrPut(it[0]) { LinkedHashSet() }.add(it[1]) }
                "perms" -> {
                    if (l.startsWith("  Package [")) cur = l.substringAfter('[').substringBefore(']')
                    else if (!l.startsWith(" ")) cur = null
                    else cur?.let { c ->
                        val m = PERM.find(l) ?: return null
                        perms.getOrPut(c) { LinkedHashSet() }.add(m.groupValues[1])
                    }
                }
            }
            return null
        }

        private fun row(pkg: String, ops: String): JSONObject {
            val held = perms[pkg].orEmpty()
            val tags = HashSet<String>(); val ev = ArrayList<JSONObject>()
            for (p in held) PERM_TAG[p]?.let { tags.add(it) }
            if ("RECEIVE_BOOT_COMPLETED" in held && pkg !in sys) tags.add("boot")     // for a system app it says nothing
            val gone = revoked[pkg].orEmpty().mapNotNull { PERM_TAG[it] }.toSet()
            // last use per chip, from `appops get` ("FINE_LOCATION: allow; time=+12h49m43s190ms ago"; the "Uid mode:" lines carry no time)
            val used = HashMap<String, Long>(); val running = HashSet<String>()
            for (o in ops.split('|')) {
                val m = OPLINE.find(o.trim()) ?: continue
                val tag = OP_TAG[m.groupValues[1]] ?: continue
                if (m.groupValues[2] == "ignore" || m.groupValues[2] == "deny" || m.groupValues[2] == "errored") continue
                if (m.groupValues[3].isNotEmpty()) secs(m.groupValues[3])?.let { s -> used[tag] = minOf(used[tag] ?: Long.MAX_VALUE, s) }
                if (o.contains("(running)")) running.add(tag)
            }
            if (pkg in a11y) tags.add("a11y")
            if (pkg in notif) tags.add("notif")
            if (pkg in admin) tags.add("admin")
            if (pkg in overlay) tags.add("overlay")
            if (pkg in usage) tags.add("usage")
            if (pkg in assist) tags.add("assist")
            val k = if (pkg in kn) known[pkg] else null
            if (k != null) tags.add(k.first)
            val system = pkg in sys; val disabled = pkg in off
            val seen = HashSet<String>()                                   // the chips whose use was seen
            for (t in listOf("mic", "cam", "loc")) {
                if (t in running) { ev.add(JSONObject().put("id", "${t}_now")); seen.add(t); tags.add(t) }
                else used[t]?.let { s -> ev.add(JSONObject().put("id", "${t}_used").put("n", s)); seen.add(t); tags.add(t) }
            }
            for (t in listOf("a11y", "notif", "admin", "overlay", "usage", "assist", "boot")) if (t in tags) ev.add(JSONObject().put("id", t))
            if (k != null) ev.add(JSONObject().put("id", k.first).put("name", k.second))
            for (t in listOf("mic", "cam", "loc")) if (t in tags && t !in seen) ev.add(JSONObject().put("id", "${t}_idle"))
            // this project's own apps hold accessibility, drawing over apps and usage for what they do (keys, the drawing, the
            // notice, the limits): a group of their own with why, not "worth a close look" at the top
            val own = pkg.startsWith("dev.tvremote.")
            val grade = if (own) "own" else grade(tags, used.filterKeys { it in seen }, running, system, disabled, k?.first)
            val allow = held.filter { PERM_TAG.containsKey(it) }
            return JSONObject().put("pkg", pkg).put("grade", grade).put("sys", system).put("keep", Setup.mustKeep(pkg)).put("off", disabled).put("own", own)
                .put("tags", JSONArray(ORDER.filter { it in tags })).put("gone", JSONArray(ORDER.filter { it in gone && it !in tags }))
                .put("ev", JSONArray(ev)).put("perms", JSONArray(allow)).put("revoked", JSONArray(revoked[pkg].orEmpty().toList()))
        }
    }

    /** Takes [pkg]'s services out of the accessibility list; the list as it is after, and the switch off when it is empty. */
    fun a11yOffCmd(pkg: String): String {
        require(PKG.matches(pkg)) { "bad package name" }
        val p = pkg.replace(".", "\\.")
        return "L=$(settings get secure enabled_accessibility_services | tr ':' '\\n' | grep -v '^$p/' | grep -v '^null$' | tr '\\n' ':' | sed 's/:$//'); " +
            "if [ -z \"${'$'}L\" ]; then settings delete secure enabled_accessibility_services >/dev/null; settings put secure accessibility_enabled 0; " +
            "else settings put secure enabled_accessibility_services \"${'$'}L\"; fi; settings get secure enabled_accessibility_services"
    }

    private val PERM = Regex("^ +android\\.permission\\.(\\w+): granted=true")
    private val OPLINE = Regex("^(?:Uid mode: )?(RECORD_AUDIO|CAMERA|FINE_LOCATION|COARSE_LOCATION): (\\w+)(?:; time=\\+?([0-9dhms]+) ago)?")

    /**
     * high: seen using the microphone or camera within a day (or right now), content recognition, an accessibility service.
     * medium: holds the microphone, camera or location (a user's app; a system app only once seen using them), a notification
     * listener, a device admin, the voice assistant, drawing over apps (a user's app), known telemetry or ads, older use.
     * low: the rest: system apps that merely hold a permission, and apps that are turned off.
     */
    internal fun grade(tags: Set<String>, used: Map<String, Long>, running: Set<String>, system: Boolean, off: Boolean, known: String?): String {
        if (off) return "low"
        val recentAv = running.any { it == "mic" || it == "cam" } || used.any { (t, s) -> (t == "mic" || t == "cam") && s <= RECENT }
        if (recentAv || known == "acr" || "a11y" in tags) return "high"
        val seenAny = used.isNotEmpty() || running.isNotEmpty()
        if (seenAny || "notif" in tags || "admin" in tags || "assist" in tags || known != null) return "med"
        if (!system && ("mic" in tags || "cam" in tags || "loc" in tags || "overlay" in tags)) return "med"
        return "low"
    }

    /** The scan under way or the last one, as the page polls it. */
    class Job(private val link: AdbLink) {
        private val lock = Any()
        private val rows = ArrayList<JSONObject>()
        private var id = 0
        private var state = "none"                                      // none / running / done / stopped / error
        private var total = 0; private var at = 0L; private var took = 0L; private var msg = ""
        private var stream: Adb.Stream? = null
        private var stopped = false

        fun start(): JSONObject {
            val me: Int
            synchronized(lock) {
                if (state == "running") return snapshot(0, id)
                rows.clear(); id++; me = id; state = "running"; total = 0; msg = ""; stopped = false; at = System.currentTimeMillis(); took = 0
            }
            Thread({ run(me) }, "privacy").apply { isDaemon = true }.start()
            return snapshot(0, me)
        }

        private fun run(me: Int) {
            val t0 = System.currentTimeMillis()
            val reader = Reader()
            try {
                val s = link.get().open("shell:" + command(known().keys))
                synchronized(lock) { stream = s }
                val buf = ByteArray(8192); val acc = StringBuilder()
                var quiet = 0L
                try {
                    loop@ while (true) {
                        val n = s.read(buf, 0, buf.size, 2000)
                        if (n < 0) break
                        if (n == 0) { quiet += 2000; if (quiet > 90000) throw IOException("privacy:stalled"); continue }
                        quiet = 0
                        acc.append(String(buf, 0, n, Charsets.UTF_8))
                        var i: Int
                        while (acc.indexOf("\n").also { i = it } >= 0) {
                            val row = reader.line(acc.substring(0, i)); acc.delete(0, i + 1)
                            synchronized(lock) {
                                if (id != me) return
                                total = reader.total
                                if (row != null) rows.add(row)
                            }
                        }
                    }
                } finally { s.close() }
                synchronized(lock) { if (id == me) { state = if (stopped) "stopped" else if (reader.done) "done" else "error"; if (state == "error") msg = "incomplete"; took = System.currentTimeMillis() - t0 } }
            } catch (e: Exception) {
                synchronized(lock) { if (id == me) { state = if (stopped) "stopped" else "error"; msg = e.message ?: e.javaClass.simpleName; took = System.currentTimeMillis() - t0 } }
            } finally { synchronized(lock) { if (id == me) stream = null } }
        }

        fun stop(): JSONObject {
            val s = synchronized(lock) { stopped = true; stream }
            try { s?.close() } catch (ignored: Exception) { }
            return snapshot(0, id)
        }

        /** The rows from [since] on (of scan [scan]; another number starts over), and where the scan is. */
        fun snapshot(since: Int, scan: Int): JSONObject = synchronized(lock) {
            val from = if (scan == id) since.coerceIn(0, rows.size) else 0
            JSONObject().put("id", id).put("state", state).put("total", total).put("n", rows.size).put("at", at).put("took", took)
                .put("msg", msg).put("rows", JSONArray(rows.subList(from, rows.size).map { JSONObject(it.toString()) }))
        }

        /** The cached row of [pkg], to change in place after Revoke / Give back / Turn off. */
        fun change(pkg: String, f: (JSONObject) -> Unit): JSONObject? = synchronized(lock) {
            rows.firstOrNull { it.optString("pkg") == pkg }?.also(f)?.let { JSONObject(it.toString()) }
        }

        fun row(pkg: String): JSONObject? = change(pkg) { }
    }

    /** `pm revoke` of each permission, in one shell call; the ones that went. The system keeps some ("not a changeable permission type"). */
    fun revokeCmd(pkg: String, perms: List<String>): String =
        perms.filter { Regex("\\w+").matches(it) }.joinToString("; ") { "pm revoke $pkg android.permission.$it >/dev/null 2>&1 && echo ok:$it" }

    fun grantCmd(pkg: String, perms: List<String>): String =
        perms.filter { Regex("\\w+").matches(it) }.joinToString("; ") { "pm grant $pkg android.permission.$it >/dev/null 2>&1 && echo ok:$it" }
}
