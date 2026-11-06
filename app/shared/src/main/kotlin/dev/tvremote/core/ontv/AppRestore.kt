package dev.tvremote.core.ontv

import dev.tvremote.core.adb.Pkg
import java.io.IOException

/**
 * An app that is on the TV yet closed to the user in front (turned off in Settings or by a cleaner app, or installed for another
 * Google TV profile) answers `am start` like one that is gone (support 11223000). One place brings it back, so Open, Play a link,
 * Pin and Tailscale do not each carry a copy. Both commands change nothing on an app that was fine.
 * Old TVs: `cmd package install-existing` is Android 7+ (older: `pm install-existing`, or nothing), `--user current` may be refused
 * by an old `pm`: every command has a plainer one after it, and none can fail the shell (all output dropped).
 */
object AppRestore {
    fun command(pkg: String): String =
        "(cmd package install-existing --user current $pkg || cmd package install-existing $pkg || pm install-existing $pkg) >/dev/null 2>&1; " +
        "(pm enable --user current $pkg || pm enable $pkg) >/dev/null 2>&1"

    /** True when [pkg] is on the TV and enabled for the current user (`pm path` alone also finds a turned-off one). */
    @Throws(IOException::class)
    fun enabled(sh: (String) -> String, pkg: String): Boolean =
        Pkg.installed(sh, pkg) && pkg in Pkg.list(sh, "-e $pkg", empty = true)

    /** Runs [start]; when [ok] refuses its words, brings [pkg] back and runs it once more. Answers the last output and whether it was retried. */
    @Throws(IOException::class)
    fun run(sh: (String) -> String, pkg: String, start: String, ok: (String) -> Boolean): Pair<String, Boolean> {
        val first = sh(start)
        if (ok(first)) return first to false
        sh(command(pkg))
        return sh(start) to true
    }
}
