package dev.tvremote.setup.keys

import android.content.Context
import dev.tvremote.core.WireLog
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.PmInstall
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.keys.KeysProtocol
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TV Setup Keys, the add-on that holds the accessibility services (`app/tvkeys`; why apart: dev/decisions/apps.md "TV Setup declares no", Play Protect). Its APK is
 * packed inside this one (assets/tvkeys.apk, same version, same key) and put on the TV by this app over its own ADB link, the way
 * the shell installs anything, so no browser or Downloader ever carries it. Installed when a feature needs it ([KeyFix.sync]: a dead
 * key or the hold rule; the key that wakes the screen asks through [RulesReceiver]), and again whenever its version is not this app's.
 */
object KeysAddon {
    const val ASSET = "tvkeys.apk"
    private const val TMP = "/data/local/tmp/tvr-keys.apk"
    private const val EVERY = 30_000L                                 // between two tries: a TV that refuses is not asked every few seconds
    private val busy = AtomicBoolean(false)
    @Volatile private var last = 0L

    private fun version(c: Context, pkg: String): Pair<String, Long>? = try {
        val i = c.packageManager.getPackageInfo(pkg, 0)
        (i.versionName ?: "") to (if (android.os.Build.VERSION.SDK_INT >= 28) i.longVersionCode else @Suppress("DEPRECATION") i.versionCode.toLong())
    } catch (e: Exception) { null }

    fun installed(c: Context): Boolean = version(c, KeysProtocol.ADDON) != null

    /** On the TV, and the version of this app (they move together: a different one is put right). */
    fun current(c: Context): Boolean = version(c, KeysProtocol.ADDON)?.first == version(c, c.packageName)?.first

    /**
     * Puts the add-on on the TV (or the version of this app in its place) in the background, then calls [then]; nothing when there is
     * no ADB link yet (the next [KeyFix.sync] after it comes up tries again), or one try is under way, or the last one was just now.
     */
    fun installThen(c: Context, then: () -> Unit) {
        val adb = RemoteService.adb() ?: return
        val now = System.currentTimeMillis()
        if (now - last < EVERY || !busy.compareAndSet(false, true)) return
        last = now
        Thread({
            try { if (ensure(c, adb)) then() } catch (e: Exception) { WireLog.all.add("·", "TV Setup Keys: " + e.message) } finally { busy.set(false) }
        }, "keys-addon").apply { isDaemon = true }.start()
    }

    /** Installs the packed APK over [adb] unless the right version is there. True when it is. */
    @Synchronized
    fun ensure(c: Context, adb: Adb): Boolean {
        if (current(c)) return true
        val apk = c.assets.open(ASSET).use { it.readBytes() }
        if (!adb.put(TMP, apk, true)) return false
        try {
            // a TV Setup put back to an older version (the update's rollback) leaves a newer add-on, which pm will not downgrade: nothing
            // of the person's is in it, so it goes and the right one comes
            val had = version(c, KeysProtocol.ADDON)
            val mine = version(c, c.packageName)
            if (had != null && mine != null && had.second > mine.second) adb.shell("pm uninstall ${KeysProtocol.ADDON} 2>&1")
            val out = adb.shell("cat $TMP | pm install -r -S ${apk.size} 2>&1", 120000)
            val said = PmInstall.fallback(out, { adb.shell(it, 120000) }, TMP, apk.size.toLong())
            if (!said.contains("Success")) { WireLog.all.add("·", "TV Setup Keys: " + said.trim().lines().lastOrNull().orEmpty()); return false }
        } finally { adb.shell("rm -f $TMP 2>&1") }
        prepare(adb)
        return current(c)
    }

    /** What the accessibility service of an app that came by the shell needs on a TV that is strict (see [KeyFix.prepare]). */
    fun prepare(adb: Adb) {
        try { adb.shell("appops set ${KeysProtocol.ADDON} ACCESS_RESTRICTED_SETTINGS allow 2>&1; appops set ${KeysProtocol.ADDON} APP_AUTO_START allow 2>&1") } catch (e: Exception) { }
    }
}
