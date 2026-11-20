package dev.tvremote.setup

import android.content.Context
import android.util.Log
import dev.tvremote.core.adb.Adb

/**
 * A TV whose WebView was switched off or removed for the user (by hand, by another cleaner) has no WebView at all: TV Games' web
 * games, TV Home's weather map and TV Setup's own Manage screen die on it ("No WebView installed"). The package is still on the
 * system, so ADB turns it back on. Checked once per TV Setup start, on its first ADB link (`dumpsys webviewupdate`: ~20 ms, <1 KB), and when one of our apps
 * asks (HomeInfo "webview"). Nothing on the TV to turn on: "none", and the asking app offers Play Store.
 */
object WebViewFix {
    private const val TAG = "WebViewFix"

    /** null: a WebView is in use; else the providers the system knows but are off for this user (empty: nothing to turn on). */
    fun missing(dump: String): List<String>? {
        if (!dump.contains("Current WebView package is null") && !dump.contains("Preferred WebView package: none")) return null   // just switched off: "Current" still names it for a few seconds
        return Regex("Valid package ([\\w.]+) \\(.*\\) is NOT installed/enabled").findAll(dump).map { it.groupValues[1] }.toList()
    }

    /** "fine" (nothing to do), "fixed", "none" (no provider on the TV: Play Store), "failed". */
    fun repair(adb: Adb): String {
        val off = missing(adb.shell("dumpsys webviewupdate", 3000)) ?: return "fine"
        if (off.isEmpty()) return "none"
        for (p in off) try { adb.shell("pm enable $p; cmd package install-existing $p", 5000) } catch (e: Exception) { }   // off by hand / removed for user 0: either brings it back
        val now = missing(adb.shell("dumpsys webviewupdate", 3000))
        Log.i(TAG, "turned on $off: " + (if (now == null) "fixed" else "still none"))
        return if (now == null) "fixed" else "failed"
    }

    /** For HomeInfo: on [RemoteService.withAdb]. "noadb" without ADB. */
    fun run(c: Context): String =
        RemoteService.withAdb(c) { try { repair(it) } catch (e: Exception) { "failed" } } ?: "noadb"
}
