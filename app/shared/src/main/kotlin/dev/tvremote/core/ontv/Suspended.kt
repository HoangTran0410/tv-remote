package dev.tvremote.core.ontv

/**
 * Apps another package has suspended ("App isn't available ... managed by Google TV"): Android shows that dialog for
 * every app the Google TV launcher (kids profile, screen time) suspended. Read from `dumpsys package`, which names the
 * suspending package: Android 9-10 print `suspended=true suspendingPackage=X` on the user line, 11+ a `Suspend params:`
 * block with one `suspendingPackage=X` line per suspender. `pm unsuspend` only lifts the shell's own suspension; clearing
 * the data of the suspender (it holds SUSPEND_APPS) lifts all of its suspensions (PackageManagerService.clearApplicationUserData).
 * Each suspension belongs to an Android user (a Google TV profile is one: a TCL G10 had 0, 10 and 11), and both commands act on
 * user 0 unless told `--user N`: [users] says which users a suspender's apps are blocked under. Cases: dev/flows/blocked-apps.md.
 */
object Suspended {
    /** One cheap read: the whole dump (over 1 MB) is filtered on the TV, so only the suspended apps (tens of bytes) come back. */
    const val CMD = "dumpsys package packages | awk '/^  Package \\[/{p=\$2} /suspended=true|suspendingPackage=/{print p \"|\" \$0}'"

    /** The suspenders that are the shell itself or the system: nothing to clear, `pm unsuspend` is what lifts theirs. */
    val SHELL = setOf("root", "com.android.shell", "android", "system")

    private val BY = Regex("suspendingPackage=(\\S+)")
    private val USER = Regex("\\bUser (\\d+):")

    /** {app: its suspenders} in the order the TV printed them; an app whose suspender the TV did not print has an empty set. */
    fun parse(out: String): Map<String, Set<String>> {
        val apps = LinkedHashMap<String, MutableSet<String>>()
        for (line in out.lineSequence()) {
            val bar = line.indexOf('|')
            if (bar < 1) continue
            val pkg = line.substring(0, bar).trim().removePrefix("[").removeSuffix("]")
            if (pkg.isEmpty()) continue
            val set = apps.getOrPut(pkg) { LinkedHashSet() }
            BY.find(line, bar)?.let { set.add(it.groupValues[1]) }
        }
        return apps
    }

    /**
     * The Android users under which [by] suspended something, from [CMD]'s [out]: Android 9-10 name the user on the suspender's own
     * line, 11+ on the `User N: ... suspended=true` line just above its `Suspend params` block. A dump without user lines: user 0.
     */
    fun users(out: String, by: String): Set<Int> {
        val users = java.util.TreeSet<Int>()
        val now = HashMap<String, Int>()                              // package -> the user line seen last for it
        for (line in out.lineSequence()) {
            val bar = line.indexOf('|')
            if (bar < 1) continue
            val pkg = line.substring(0, bar).trim()
            USER.find(line, bar)?.let { now[pkg] = it.groupValues[1].toInt() }
            if (BY.find(line, bar)?.groupValues?.get(1) == by) users.add(now[pkg] ?: 0)
        }
        return users
    }

    /** The shell commands that lift what [by] suspended under [users]: user 0 as plain as before (every Android takes it), others with `--user N`. */
    fun lift(by: String, apps: List<String>, users: Set<Int>): List<String> {
        fun u(n: Int) = if (n == 0) "" else "--user $n "
        val each = users.ifEmpty { setOf(0) }
        return if (by in SHELL) each.flatMap { n -> apps.map { "pm unsuspend ${u(n)}$it 2>&1" } } else each.map { n -> "pm clear ${u(n)}$by 2>&1" }
    }

    /** The apps [by] suspended. */
    fun of(apps: Map<String, Set<String>>, by: String): List<String> = apps.filter { by in it.value }.keys.toList()
}
