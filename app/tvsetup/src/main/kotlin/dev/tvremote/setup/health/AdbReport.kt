package dev.tvremote.setup.health

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.tvremote.core.Census
import dev.tvremote.setup.link.KeepAdb
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TV Setup that cannot reach the TV's own ADB, told to the census once per version and kind of failure, with what the TV says
 * about its ADB: on a box nobody here can reach (an FPT Play with port 5555 closed, the user 2026-10-25) a screenshot of a
 * banner was all there was. The same stats switch as crashes: Census.crash sends nothing when it is off.
 */
object AdbReport {
    private const val AFTER_MS = 3 * 60_000L                          // failing that long: not a TV still booting
    private const val PREFS = "adb_report"
    @Volatile private var sending = false

    /** Called on each failed try; [since] when the failures began (elapsedRealtime: the TV clock jumps at boot, "for 2632037 min"), [tries] how many in a row. */
    fun failing(c: Context, state: String, detail: String, since: Long, tries: Int, version: String) {
        if (sending || android.os.SystemClock.elapsedRealtime() - since < AFTER_MS || !Census.enabled) return
        val key = "$version $state"
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(key, false)) return
        sending = true
        Thread({
            try {
                val msg = "ADB $state for ${(android.os.SystemClock.elapsedRealtime() - since) / 60_000} min, $tries tries: " + detail.take(150)
                Census.crash("kotlin", "TV Setup ADB $state " + Build.MODEL, msg, facts(c, state, detail)) { sent ->
                    if (sent) p.edit().putBoolean(key, true).apply()
                    sending = false
                }
            } catch (e: Exception) { sending = false }
        }, "adb-report").apply { isDaemon = true }.start()
    }

    /** What the TV says about its ADB, read without ADB: Build, the adb properties, the Settings switches, and whether 5555 answers. */
    fun facts(c: Context, state: String, detail: String): String {
        val r = c.contentResolver
        fun global(k: String) = try { Settings.Global.getString(r, k) ?: "-" } catch (e: Exception) { "?" }
        val props = listOf("ro.adb.secure", "ro.debuggable", "ro.build.type", "service.adb.tcp.port", "persist.adb.tcp.port",
            "sys.usb.config", "sys.usb.state", "persist.sys.usb.config", "init.svc.adbd", "ro.product.board", "ro.hardware")
        return buildString {
            append("state=").append(state).append(" detail=").append(detail.take(200)).append('\n')
            append("build: ").append(Build.MANUFACTURER).append(" / ").append(Build.BRAND).append(" / ").append(Build.MODEL)
                .append(" / ").append(Build.DEVICE).append(" / ").append(Build.PRODUCT).append('\n')
            append("android ").append(Build.VERSION.RELEASE).append(" sdk ").append(Build.VERSION.SDK_INT).append(" ").append(Build.DISPLAY).append('\n')
            append("fingerprint=").append(Build.FINGERPRINT).append('\n')
            props.forEach { append(it).append('=').append(prop(it)).append('\n') }
            listOf("adb_enabled", "adb_wifi_enabled", "development_settings_enabled").forEach { append(it).append('=').append(global(it)).append('\n') }
            append("127.0.0.1:5555 ").append(probe(5555)).append('\n')
            append("keep_adb=").append(try { KeepAdb.can(c) } catch (e: Exception) { "?" }).append('\n')
        }
    }

    private fun prop(k: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
    } catch (e: Exception) { "?" }

    private fun probe(port: Int): String = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1500) }; "open"
    } catch (e: Exception) { e.message?.take(80) ?: e.javaClass.simpleName }
}
