package dev.tvremote.phone

import dev.tvremote.phone.manage.version
import dev.tvremote.phone.picker.name
import dev.tvremote.phone.ui.runs

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import dev.tvremote.update.Release
import dev.tvremote.update.SelfUpdate
import java.io.File

/**
 * The phone app updates itself without a question where Android lets it: 12+, installs allowed for this app, and this app the
 * installer of the version it runs (after the first update it made itself; a copy from the browser is the browser's). The APK is
 * fetched when found (Wi-Fi only), installed once nothing of the app is on screen or running in the background (the install
 * closes it: listening, a transfer, a scene would stop). Anything else, or Android asking after all: the Update button as before.
 */
internal object QuietUpdate {
    private const val PREFS = "quiet_update"
    private const val TAG = "QuietUpdate"

    fun can(c: Context): Boolean = why(c).isEmpty()

    /** "" when the quiet way works here, else why not (the log). */
    private fun why(c: Context): String {
        if (Build.VERSION.SDK_INT < 31) return "android"
        if (!c.packageManager.canRequestPackageInstalls()) return "not allowed"
        if (c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("asks", false)) return "asks"
        val by = try { c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName } catch (e: Exception) { null }
        return if (by == c.packageName) "" else "installed by $by"
    }

    private fun file(c: Context, v: String) = File(c.cacheDir, "quiet-$v.apk")

    /** Off the main thread: [r]'s APK, checked, kept for [install]; older ones dropped. */
    fun fetch(c: Context, r: Release) {
        why(c).let { if (it.isNotEmpty()) { Log.i(TAG, "not quiet: $it"); return } }
        val f = file(c, r.version)
        c.cacheDir.listFiles { x -> x.name.startsWith("quiet-") && x != f }?.forEach { it.delete() }
        if (f.exists()) return
        if ((c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered != false) { Log.i(TAG, "metered: later"); return }
        val part = File(c.cacheDir, "quiet.part")
        try { r.download(part, null); part.renameTo(f); Log.i(TAG, "fetched ${r.version}") }
        catch (e: Exception) { part.delete(); Log.w(TAG, "fetch ${r.version}: $e") }   // offline, a bad file: next time
    }

    /** Installs a fetched [r] when the app is out of sight with nothing running; asked a few seconds after the screen stopped. */
    fun install(c: Context, r: Release?) {
        val f = r?.let { file(c, it.version) } ?: return
        if (!f.exists() || !can(c)) { Log.i(TAG, "nothing to install"); return }
        val me = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        if (me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE) { Log.i(TAG, "busy: ${me.importance}"); return }   // on screen, PiP, a foreground service
        Log.i(TAG, "installing ${r.version}")
        Thread({
            try { SelfUpdate.commit(c, f, Intent(c, Done::class.java), false, true) } catch (e: Exception) { Log.w(TAG, "install: $e") } finally { f.delete() }
        }, "quiet-update").start()
    }

    /** Android's answer: asking after all (a maker's rule) turns the quiet way off here for good, the Update button stays. */
    class Done : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            Log.i(TAG, "status $st ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("asks", true).apply()
        }
    }
}
