package dev.tvremote.core.ontv
import dev.tvremote.core.adb.Adb
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * TV Games (app/tvgames), retro and web games played with phones as pads, from the phone or the computer: whether it is on the TV,
 * putting it there (or a newer one) from the latest release, and opening it. Playing needs no ADB and no TV Setup: the app serves
 * its own pad page (a QR on the TV), so ADB is only for installing it from here. See [OwnApp] for the state's shape (`soon`:
 * the latest release has no tv-games.apk yet).
 */
object Games {
    const val PKG = "dev.tvremote.games"
    const val APK = "tv-games.apk"
    /** The pad's "display over other apps", a Settings toggle most TVs do not have: granted here (as for TV Channels). */
    private const val ALLOW_OVERLAY = "appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1"
    private val app = OwnApp(PKG, APK, "games", "games:none", ALLOW_OVERLAY)

    @Throws(IOException::class)
    fun state(sh: (String) -> String): JSONObject = app.state(sh)
    fun refresh() = app.refresh()

    @Throws(IOException::class)
    fun install(adb: Adb, dir: File?, lang: String, go: () -> Boolean = { true }, progress: ((Int) -> Unit)? = null): JSONObject = app.install(adb, dir, lang, go, progress)

    /** TV Games writes "port token" here when its pad server runs (the pad page of the QR on the TV); the shell may read it. */
    const val PAD_FILE = "/sdcard/Android/data/$PKG/files/pad.txt"

    /** The address of the pad page on the TV at [host], or null while TV Games is not running its server. */
    @Throws(IOException::class)
    fun pad(sh: (String) -> String, host: String): String? = padUrl(sh("cat $PAD_FILE 2>/dev/null"), host)

    fun padUrl(file: String, host: String): String? {
        val f = file.trim().split(' ')
        return if (f.size == 2 && f[0].toIntOrNull() != null && f[1].length >= 16) "http://$host:${f[0]}/?k=${f[1]}" else null
    }

    /** Opens its library on the TV; false when TV Games is not there. */
    @Throws(IOException::class)
    fun open(sh: (String) -> String): Boolean = app.open(sh, "am start -n $PKG/.LibraryActivity")
}
