package dev.tvremote.core.ontv
import dev.tvremote.core.adb.Adb
import dev.tvremote.home.HomeConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The project's TV apps TV Setup puts on the TV by itself once it has ADB: the site and its ads show TV Home, Channels, Games and Apps,
 * while the install guide only installs TV Setup, and nobody went looking for the rest (the user, 2026-10-25). TV Setup then
 * swaps the home to TV Home once, behind the TV's own Keep / Go back question (MainActivity.homeOnce).
 */
object Bundle {
    /** [key] / [apk]: its number in versions.json and its file in the release, for TV Setup's install without ADB (Android's installer). */
    class App(val name: String, val pkg: String, val key: String, val apk: String, val install: (Adb, File?, String, ((Int) -> Unit)?) -> JSONObject)   // the last: download percent, or null

    private val home = OwnApp(HomeConfig.PKG, Launchers.SELF_APK, "home", "home:none", HomeConfig.GRANT_WIFI)

    val apps = listOf(
        App("TV Home", HomeConfig.PKG, "home", Launchers.SELF_APK) { a, d, l, p -> home.install(a, d, l, progress = p) },
        App("TV Channels", Channels.PKG, "channels", Channels.APK) { a, d, l, p -> Channels.install(a, d, l, progress = p) },
        App("TV Games", Games.PKG, "games", Games.APK) { a, d, l, p -> Games.install(a, d, l, progress = p) },
        App("TV Apps", TvApps.PKG, "tvapps", TvApps.APK) { a, d, l, p -> TvApps.install(a, d, l, progress = p) })

    /** The ones not on the TV, in one shell call. */
    @Throws(IOException::class)
    fun missing(sh: (String) -> String): List<App> {
        val have = sh("pm list packages dev.tvremote 2>/dev/null")
        return apps.filter { !have.contains("package:${it.pkg}\n") && !have.trimEnd().endsWith("package:${it.pkg}") }
    }
}
