package dev.tvremote.core.manage
import dev.tvremote.core.ontv.Setup

/** The apps in the TV's memory, and which of them may be stopped (the task manager, the open apps, the AI report). */
internal object Procs {
    /**
     * The app a process belongs to, "android" for Android's own. A process is named after its app, "pkg:what" for its others;
     * some take a name of their own (com.google.android.gms.unstable, com.google.process.gapps, com.android.networkstack.process):
     * the nearest dotted parent that is an installed app ([known]), else Android's. Shown raw, these names told a person nothing.
     */
    internal fun appOf(proc: String, known: (String) -> Boolean = { true }): String {
        val p = proc.substringBefore(':')
        if (p.startsWith("com.google.process.")) return "com.google.android.gms"   // Play services' shared processes (gapps, location)
        if (p.startsWith("android.process.") || p == "system" || !p.contains('.')) return "android"
        if (known(p)) return p
        var q = p
        while (q.count { it == '.' } > 1) { q = q.substringBeforeLast('.'); if (known(q)) return q }
        return "android"
    }

    /** "Total PSS by process" of dumpsys meminfo, summed per package (":service" processes count for their app), MB, largest first. */
    internal fun memoryByApp(meminfo: String, known: (String) -> Boolean = { true }): List<Pair<String, Int>> {
        val start = meminfo.indexOf("Total PSS by process:").takeIf { it >= 0 } ?: meminfo.indexOf("Total PSS by OOM adjustment:")   // --oom: the same lines under group heads (no dot: skipped)
        if (start < 0) return emptyList()
        val by = HashMap<String, Int>()
        for (line in meminfo.substring(start).split("\n").drop(1)) {
            if (line.isBlank()) break
            val m = Regex("^\\s*([\\d,]+)K: ([^\\s(]+)").find(line) ?: continue
            if (!m.groupValues[2].contains('.')) continue          // apps only: not "system", native daemons, our own app_process
            val pkg = appOf(m.groupValues[2], known)
            by[pkg] = (by[pkg] ?: 0) + (m.groupValues[1].replace(",", "").toInt() / 1024)
        }
        return by.entries.filter { it.value >= 5 }.sortedByDescending { it.value }.take(25).map { it.key to it.value }
    }

    /** The home screen, the keyboard and the accessibility services in use, the apps the user installed and the ones with a tile, as [keepNow] reads them. */
    const val KEEP_NOW = "{ " + dev.tvremote.core.ontv.LauncherSwap.RESOLVE_HOME + "; } | tail -1; " +
        "settings get secure default_input_method; settings get secure enabled_accessibility_services; pm list packages -3 ${dev.tvremote.core.ontv.TvUser.USER}; for c in LEANBACK_LAUNCHER LAUNCHER; do " +
        "cmd package query-activities --brief ${dev.tvremote.core.ontv.TvUser.USER} -a android.intent.action.MAIN -c android.intent.category.\$c 2>/dev/null; done"

    /**
     * What may be stopped on this TV: the apps a person installed or can open from a tile (YouTube is a system app on most
     * TVs), but never its home screen, keyboard or an app whose accessibility service is on (a remote mapper, a screen reader: a
     * force-stop while Android holds its service crashed system_server on a TCL, Android 11, a soft reboot; CleanTV issue 5).
     * Everything else of the system's stays.
     */
    internal data class Keep(val home: String, val keyboard: String, val people: Set<String>, val a11y: Set<String> = emptySet())

    /** [Keep] from the output of [KEEP_NOW]. */
    internal fun keepNow(out: String): Keep {
        val lines = out.split("\n").map { it.trim() }
        val pkg = { i: Int -> lines.getOrElse(i) { "" }.substringBefore('/') }
        val a11y = lines.getOrElse(2) { "" }.split(':').filter { it.contains('/') }.map { it.substringBefore('/') }.toSet()   // "null" when none
        val people = lines.drop(3).mapNotNull { if (it.startsWith("package:")) it.substring(8) else if (it.contains('/')) it.substringBefore('/') else null }
        return Keep(pkg(0), pkg(1), people.toSet(), a11y)
    }

    /** Why [pkg] may not be stopped ("us", "home", "keyboard", "a11y", "system"), or null when it may. */
    internal fun keepWhy(pkg: String, k: Keep): String? = when {
        pkg.startsWith("dev.tvremote.setup") || pkg == "com.tailscale.ipn" -> "us"   // TV Setup (+ its keys add-on), and the way to the TV from outside; TV Home is "home" below while it is the home screen
        pkg == k.home -> "home"
        pkg == k.keyboard -> "keyboard"
        pkg in k.a11y -> "a11y"
        Setup.PROTECTED.matches(pkg) || pkg !in k.people -> "system"   // and Android's shared processes (android.process.acore)
        else -> null
    }

    /** One app in the task manager: MB in memory (all its processes), what it is doing, why it must stay (null: may be stopped). */
    internal data class Proc(val pkg: String, val mb: Int, val state: String, val keep: String?)

    /**
     * "Total PSS by OOM adjustment" of dumpsys meminfo: each app once, its processes summed, under the most active of their
     * groups: "front" (on screen), "bg" (working in the background: music, downloads, services), "home", "cached" (kept in
     * memory, doing nothing: Android lets them go first), "system" (Android itself: never stopped). Largest first.
     */
    internal fun procsOf(meminfo: String, k: Keep, known: (String) -> Boolean = { true }): List<Proc> {
        val start = meminfo.indexOf("Total PSS by OOM adjustment:")
        if (start < 0) return emptyList()
        val rank = listOf("system", "front", "home", "bg", "cached")
        val mb = HashMap<String, Int>(); val state = HashMap<String, String>()
        var group = "cached"
        for (line in meminfo.substring(start).split("\n").drop(1)) {
            if (line.isBlank()) break
            val g = if (line.contains("(pid ")) null else Regex("^\\s*[\\d,]+K: (.+)$").find(line)   // a group's head: no pid
            if (g != null) {
                val name = g.groupValues[1].trim()
                group = when {
                    name == "Native" -> "native"                  // daemons, not apps: left out
                    name == "System" || name.startsWith("Persistent") -> "system"
                    name == "Foreground" || name == "Visible" -> "front"
                    name == "Home" -> "home"
                    name == "Previous" || name.startsWith("Cached") -> "cached"
                    else -> "bg"                                  // Perceptible, A / B Services, Backup, Heavy Weight, ...
                }
                continue
            }
            val m = Regex("^\\s+([\\d,]+)K: ([^\\s(]+)").find(line) ?: continue
            if (group == "native" || !m.groupValues[2].contains('.')) continue   // "system" itself (Android's core) shows in the RAM meter
            if (m.groupValues[2].startsWith("com.google.android.webview:sandboxed")) continue   // another app's page renderer: counted nowhere rather than wrongly
            val pkg = appOf(m.groupValues[2], known)
            mb[pkg] = (mb[pkg] ?: 0) + m.groupValues[1].replace(",", "").toInt() / 1024
            val was = state[pkg]
            if (was == null || rank.indexOf(group) < rank.indexOf(was)) state[pkg] = group   // an app's busiest process says what it is doing
        }
        return mb.entries.filter { it.value >= 1 }.map { (pkg, v) ->
            val st = state[pkg] ?: "cached"
            Proc(pkg, v, st, if (st == "system" || pkg == "android") "system" else keepWhy(pkg, k))
        }.sortedByDescending { it.mb }
    }
}
