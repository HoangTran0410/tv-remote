package dev.tvremote.core.adb

import java.io.IOException

/**
 * The one check for an Android package name before it goes into a shell command. Each later segment starts with a letter or
 * underscore, as Android requires (a segment led by a digit is no package), so every screen refuses the same names.
 *
 * Also the one way to ask the package manager. [Adb.shell] answers text with stderr merged in and no exit code, so a pm that
 * could not ask system_server (stalled for 10-40 s on a weak box: "cmd: Failure calling service package: Failed transaction",
 * "Could not access the Package Manager", nothing at all) looks like an answer: the first contains "package:" (= installed), the
 * others read as "not installed" or "no packages". [installed] and [list] match only real `package:` lines and THROW when the
 * TV did not answer, so nothing (Optimize, Undo, a launcher rollback) decides from a failed read.
 */
object Pkg {
    /** For java.util.regex and grep -E alike. */
    const val PATTERN = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"
    val RE = Regex(PATTERN)
    fun valid(s: String): Boolean = RE.matches(s)

    /** What a pm prints when it could not ask system_server (the package service gone, stalled or restarting). */
    private val DEAD = Regex("Failure calling service|Can't find service|Could not access|DeadObjectException|DeadSystemException|Broken pipe")

    internal var pause: (Long) -> Unit = { Thread.sleep(it) }        // tests: no real wait

    /** True when [out] says the TV did not answer: a service failure, or an `Error:` / `Exception` line. */
    fun failed(out: String): Boolean =
        DEAD.containsMatchIn(out) || out.lines().any { l -> l.trim().let { it.startsWith("Error:") || it.startsWith("Exception") || it.startsWith("java.lang.") } }

    /** Whether [pkg] is on the TV (`pm path`: a `package:/...` line). Throws when the TV did not answer; an empty reply is "not installed". */
    @Throws(IOException::class)
    fun installed(sh: (String) -> String, pkg: String): Boolean {
        val out = sh("pm path $pkg")
        if (out.lines().any { it.trim().startsWith("package:/") }) return true
        if (failed(out)) throw IOException("pm: could not ask: " + first(out))
        return false
    }

    /**
     * The packages `pm list packages [flags]` lists. Throws when the reply is not a list: a failure line, or text without a single
     * `package:` line. An empty reply throws too, except with [empty] (`-d`, or a filter on one package: nothing disabled is a good
     * answer; a TV with no package at all is impossible).
     */
    @Throws(IOException::class)
    fun list(sh: (String) -> String, flags: String = "", empty: Boolean = false): Set<String> =
        parse(sh("pm list packages" + (if (flags.isEmpty()) "" else " $flags")), empty)

    /** [list] on a reply already in hand (several queries in one round trip). */
    @Throws(IOException::class)
    fun parse(out: String, empty: Boolean = false): Set<String> {
        val pkgs = out.lines().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
        if (pkgs.isEmpty() && (failed(out) || (out.isBlank() && !empty) || out.lines().any { it.isNotBlank() }))
            throw IOException("pm: could not ask: " + first(out))
        return pkgs
    }

    /** [read] once more after 2 s when it threw: a stall of the package service is often shorter. The second throw is the caller's. */
    @Throws(IOException::class)
    fun <T> twice(read: () -> T): T = try { read() } catch (e: IOException) { pause(2000); read() }

    private fun first(out: String): String = out.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(120) ?: "empty answer"
}
