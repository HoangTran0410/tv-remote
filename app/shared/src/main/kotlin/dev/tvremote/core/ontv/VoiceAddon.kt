package dev.tvremote.core.ontv
import dev.tvremote.core.adb.Adb
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * TV Setup Voice (app/tvvoice): speech heard on the TV itself, for a TV with no recognizer of its own. 25 MB, so it is on the TV only
 * while the rule "mic_offline" is on: TV Setup installs it from the latest release over its own ADB (checked against the signed
 * SHA256SUMS, like TV Channels) and removes it when switched off. Versioned as TV Setup, so its versions.json key is TV Setup's.
 */
object VoiceAddon {
    const val PKG = "dev.tvremote.setup.voice"                        // VoiceProtocol.ADDON (tvsetup MicVoiceTest keeps the two equal)
    const val APK = "tv-voice.apk"
    /** The microphone (the shell may grant it; the add-on has no screen to ask on) and the model out of the APK now, not on the first press. */
    private const val READY = "pm grant $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1; am broadcast -n $PKG/.Warm >/dev/null 2>&1"

    private val app = OwnApp(PKG, APK, "tv", "voice:none", READY)

    @Throws(IOException::class)
    fun state(sh: (String) -> String): JSONObject = app.state(sh)
    fun refresh() = app.refresh()

    @Throws(IOException::class)
    fun install(adb: Adb, dir: File?, lang: String, go: () -> Boolean = { true }): JSONObject = app.install(adb, dir, lang, go)

    fun remove(adb: Adb) { adb.shell("pm uninstall $PKG >/dev/null 2>&1") }
}
