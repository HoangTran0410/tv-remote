package dev.tvremote.core.ontv
import dev.tvremote.core.adb.Adb
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * TV Setup Voice (app/tvvoice): speech heard on the TV itself, for a TV with no recognizer of its own. 25 MB, so it is on the TV only
 * while the rule "mic_offline" is on: TV Setup installs it from the latest release over its own ADB (checked against the signed
 * SHA256SUMS, like TV Channels) and removes it when switched off. One APK and one package per language ([LANGS]): vi 25 MB, en 36 MB;
 * a second language is added beside the first, never swapped for it (the user, 2026-11-03). Versioned as TV Setup, so its versions.json
 * key is TV Setup's.
 */
object VoiceAddon {
    const val PKG = "dev.tvremote.setup.voice"                        // VoiceProtocol.ADDON, the Vietnamese one (tvsetup MicVoiceTest keeps them equal)
    const val APK = "tv-voice.apk"                                    // Vietnamese
    const val APK_EN = "tv-voice-en.apk"                              // English
    /** The languages there is an add-on for: VoiceProtocol.LANGS (MicVoiceTest). */
    val LANGS = listOf("vi", "en")

    /** The add-on's package for [lang]: VoiceProtocol.addon. */
    fun pkg(lang: String) = if (lang == "en") "$PKG.en" else PKG
    /** The microphone (the shell may grant it; the add-on has no screen to ask on) and the model out of the APK now, not on the first press. */
    private fun ready(p: String) = "pm grant $p android.permission.RECORD_AUDIO >/dev/null 2>&1; am broadcast -n $p/$PKG.Warm >/dev/null 2>&1"
    private val apps = mapOf("vi" to OwnApp(PKG, APK, "tv", "voice:none", ready(PKG)), "en" to OwnApp(pkg("en"), APK_EN, "tv", "voice:none", ready(pkg("en"))))

    /** The language a TV Setup in language [ui] starts with ("vi" or "en"); others are added from the voice screen. */
    fun lang(ui: String) = if (ui == "vi") "vi" else "en"
    private fun app(lang: String) = apps.getValue(if (lang == "en") "en" else "vi")

    @Throws(IOException::class)
    fun state(sh: (String) -> String, lang: String = "vi"): JSONObject = app(lang).state(sh)
    fun refresh() { apps.values.forEach { it.refresh() } }

    /** The add-on of [voice] (picked by the person; [ui] only words the messages), installed or updated; the others stay. */
    @Throws(IOException::class)
    fun install(adb: Adb, dir: File?, ui: String, go: () -> Boolean = { true }, progress: ((Int) -> Unit)? = null, voice: String = lang(ui)): JSONObject = app(voice).install(adb, dir, ui, go, progress)

    /** [lang] null: every language. */
    fun remove(adb: Adb, lang: String? = null) { for (l in if (lang == null) LANGS else listOf(lang)) adb.shell("pm uninstall ${pkg(l)} >/dev/null 2>&1") }
}
