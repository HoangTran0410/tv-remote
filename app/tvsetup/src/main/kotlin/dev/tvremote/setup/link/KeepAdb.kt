package dev.tvremote.setup.link

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.tvremote.core.adb.Adb

/**
 * Keeps the TV's ADB debugging on. Without it the sleep timer, bedtime and app limits cannot turn the TV off or close an
 * app, and phones and computers lose the picture, sound and installs: switched off by a child, a TV update or a reset of
 * Developer options, nothing would say so. With WRITE_SECURE_SETTINGS (granted once through ADB, kept across updates) this
 * app turns it back on within a second. A switch on TV Setup's screen lets a person keep it off.
 */
internal class KeepAdb(private val c: Context) {
    companion object {
        private const val PREFS = "keepadb"
        private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
        private const val WIFI = "adb_wifi_enabled"                   // Settings.Global.ADB_WIFI_ENABLED, Android 11+
        fun on(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)
        fun set(c: Context, on: Boolean) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply() }
        fun can(c: Context): Boolean = c.packageManager.checkPermission(PERM, c.packageName) == PackageManager.PERMISSION_GRANTED
        /** While there is ADB: the permission that lets this app turn ADB back on without it. */
        fun grant(c: Context, adb: Adb) {
            if (!can(c)) try { adb.shell("pm grant ${c.packageName} $PERM 2>&1") } catch (ignored: Exception) { }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val watch = object : ContentObserver(ui) {
        override fun onChange(self: Boolean) { check() }
    }

    fun start() {
        try { c.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, watch) } catch (ignored: Exception) { }
        try { c.contentResolver.registerContentObserver(Settings.Global.getUriFor(WIFI), false, watch) } catch (ignored: Exception) { }
        check()
    }

    fun stop() { try { c.contentResolver.unregisterContentObserver(watch) } catch (ignored: Exception) { } }

    private var wifiAt = 0L

    /** Off and wanted on: on again, Developer options first (some TVs hide ADB's switch, and stop adbd, with them off). */
    fun check() {
        if (!can(c)) return
        val r = c.contentResolver
        wifi(r)                                                       // its only ADB once paired that way: not behind the switch
        if (!on(c)) return
        try {
            if (Settings.Global.getInt(r, Settings.Global.ADB_ENABLED, 0) != 0) return
            Settings.Global.putInt(r, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            Settings.Global.putInt(r, Settings.Global.ADB_ENABLED, 1)
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("at", System.currentTimeMillis()).apply()   // when, for a look by ADB
        } catch (ignored: Exception) { }                              // the grant was taken back: nothing to do until ADB returns
    }

    /**
     * TV Setup paired through Wireless debugging (a TV with no port 5555) has ADB only while that switch is on, and Android turns it
     * off at every restart. On again, at most once a minute: on a network the person never allowed it on, Android turns it straight off.
     */
    private fun wifi(r: android.content.ContentResolver) {
        if (android.os.Build.VERSION.SDK_INT < 30 || dev.tvremote.core.adb.AdbTls.saved(c.filesDir, "127.0.0.1") <= 0) return
        try {
            if (Settings.Global.getInt(r, WIFI, 0) != 0 || System.currentTimeMillis() - wifiAt < 60_000L) return
            wifiAt = System.currentTimeMillis()
            Settings.Global.putInt(r, WIFI, 1)
        } catch (ignored: Exception) { }
    }
}
