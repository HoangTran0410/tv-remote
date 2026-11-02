package dev.tvremote.core.ontv
import dev.tvremote.core.adb.Adb
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * TV Apps (app/tvapps), the free open-source TV apps list on the TV itself, from TV Setup and the clients: whether it is on the TV
 * and putting it there (or a newer one) from the latest release. The list it shows is the clients' TV Apps page too
 * (`/api/tvapps`), so this is only the app. See [OwnApp] for the state's shape (`soon`: the latest release has no tv-apps.apk yet).
 */
object TvApps {
    const val PKG = "dev.tvremote.apps"
    const val APK = "tv-apps.apk"
    private val app = OwnApp(PKG, APK, "tvapps", "tvapps:none")

    @Throws(IOException::class)
    fun state(sh: (String) -> String): JSONObject = app.state(sh)
    fun refresh() = app.refresh()

    @Throws(IOException::class)
    fun install(adb: Adb, dir: File?, lang: String, go: () -> Boolean = { true }, progress: ((Int) -> Unit)? = null): JSONObject = app.install(adb, dir, lang, go, progress)
}
