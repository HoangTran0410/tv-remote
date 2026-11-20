package dev.tvremote.core.ontv
import java.io.File
import java.io.IOException

/**
 * Google Play Protect on a TV can refuse one build of our own apps: a cloud verdict on that file, not on the code (v0.35.1's
 * tv-home.apk on the FPT box, 2026-10-30, while v0.34.0's and v0.36.0's builds of the same code went in). Every try shows
 * "Harmful app blocked" on the TV, so the updates TV Setup makes by itself must not try that file again every few hours: its
 * SHA-256 is kept in [dir] and skipped until a release carries another file.
 */
object PlayProtect {
    const val REFUSED = "INSTALL_FAILED_VERIFICATION_FAILURE"         // what pm says; the page words it (core.fr_play_protect)
    private const val FILE = "play-protect-blocked.txt"

    fun blocked(dir: File?, sha256: String): Boolean =
        dir != null && sha256.isNotEmpty() && try { File(dir, FILE).takeIf { it.isFile }?.readLines()?.contains(sha256) == true } catch (e: IOException) { false }

    /** Throws when [sha256] was refused before; after an install, keeps it when [out] says Play Protect refused it. */
    @Throws(IOException::class)
    fun check(dir: File?, sha256: String, out: String? = null) {
        if (out == null) { if (blocked(dir, sha256)) throw IOException("launcher:install:$REFUSED"); return }
        if (out.contains(REFUSED) && dir != null && sha256.isNotEmpty() && !blocked(dir, sha256))
            try { File(dir, FILE).appendText(sha256 + "\n") } catch (e: IOException) { }
    }

    /**
     * What the TV's focused window is while Play Protect asks about an install ("Unsafe app blocked", "built for an older Android",
     * "Scan app?"): Finsky / Play services, an activity named for Play Protect or the verifier. One `dumpsys window` filtered on the
     * TV (a line back, not the dump).
     */
    const val FOCUS = "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus"
    private val ASKS = Regex("(com\\.android\\.vending|com\\.google\\.android\\.(finsky|gms))/\\S*(PlayProtect|Verify|Verifier)", RegexOption.IGNORE_CASE)

    const val NOTE = "play_protect"                              // the job note the page words (core.play_protect_asks)

    fun asking(focus: String): Boolean = ASKS.containsMatchIn(focus)

    /**
     * Runs [block] (an install that waits on the TV) and, while it runs, looks every [every] ms whether the TV shows Play Protect's
     * question, once [first] ms have passed (a normal install is over by then). Said once through [say], then no more asking; no more
     * either after 3 failed asks in a row, and none after [block] ends (even when it throws). A shell that cannot answer costs the
     * install nothing: the asking has its own thread and swallows its errors.
     */
    fun <T> watch(sh: (String) -> String, say: () -> Unit, first: Long = 5000, every: Long = 4000, block: () -> T): T {
        val t = Thread({
            var bad = 0
            try {
                Thread.sleep(first)
                while (bad < 3) {
                    val seen = try { asking(sh(FOCUS)).also { bad = 0 } } catch (e: IOException) { bad++; false }
                    if (seen) { say(); return@Thread }
                    Thread.sleep(every)
                }
            } catch (e: InterruptedException) { } catch (e: RuntimeException) { }
        }, "play-protect-watch").apply { isDaemon = true; start() }
        try { return block() } finally { t.interrupt() }
    }
}
