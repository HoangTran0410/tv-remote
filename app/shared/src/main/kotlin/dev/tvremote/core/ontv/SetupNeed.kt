package dev.tvremote.core.ontv
import dev.tvremote.update.Release
import dev.tvremote.core.adb.Pkg
import java.io.IOException
import dev.tvremote.core.Words
import dev.tvremote.core.adb.Adb

/**
 * What each feature needs of TV Setup on the TV, in one place: a phone or computer can be newer than the TV's TV Setup.
 * A client asks before a feature starts (/api/setup/need?f=, [check]); a feature that finds out midway throws [Missing].
 * Either way every client shows the same question with one button, update or install TV Setup (needSetup() in js/core.js,
 * offerSetup() in the phone app), instead of an error once the work is lost.
 * A feature that needs a newer TV Setup adds one line to [MIN], and its name to both of those.
 */
object SetupNeed {
    /** feature → the first TV Setup that has it */
    val MIN = linkedMapOf(
        "rules" to "0.1.0",                                            // sleep timer, bedtime, app limits
        "draw" to "0.1.0",                                             // ✏️ drawing over the screen
        "pic" to "0.1.0",                                              // 🖼️ a picture over it
        "volume" to "0.1.0",                                           // 🔊 volume limits
        "bedtimes" to "0.3.1",                                         // 🌙 more than one bedtime
        "notify" to "0.4.2",                                           // 🔔 a card with a message over the TV's screen
        "invite" to "0.4.2",                                           // 📲 sharing the remote by QR (an older one answers with a pass)
        "ram" to "0.4.2",                                              // 🧠 the notice when the memory is nearly full
        "ramauto" to "0.42.3",                                         // 🧹 background apps closed by itself when the memory stays full (Rules "ram" auto; the release after 0.42.2)
        "awake" to "0.7.1",                                            // 😴 the chip kept up while the TV is off (KeepAwake)
        "launcher" to "0.12.0",                                        // 🏠 the launcher gallery: the question on the TV and the watchdog (only asked about, never required: the swap works without)
        "files" to "0.8.1",                                            // 📈 its files handed over where the shell may not read them (SetupFiles)
        "welcome" to "0.12.0",                                         // 👋 a video or picture when the TV is turned on (Welcome)
        "lock" to "0.12.1",                                            // 🔒 an app opened only with a PIN (the release after 0.12.0)
        "dim" to "0.12.1",                                             // 🔅 a tint over the whole screen (DimLayer)
        "unextra" to "0.12.1",                                         // ↺ taking back the minutes granted to an app today
        "holdkeys" to "0.19.1",                                        // 🔘 Input / Settings need a 2 s hold (HoldKeys)
        "mic" to "0.21.1",                                             // 🎤 the remote's mic button opens TV Remote's voice (MicVoice; the release after 0.21.0)
        "checkout" to "0.19.1",                                        // 🧳 check-out: forget the guests' browsers (RulesReceiver "forget_web")
        "schedule" to "0.20.1",                                        // 📅 an app opened only on some days in a window of the day (Rules "sched"; the release after 0.20.0)
        "schedule_windows" to "0.21.1",                                // 📅 several windows in a schedule (Rules "sched" windows; the release after 0.21.0); one window still works on older ones
        "telegram" to dev.tvremote.home.HomeConfig.TELEGRAM_SETUP,      // 💬 the Telegram bot on the TV (Telegram.kt; the release after 0.21.0)
        "kids" to "0.21.1",                                            // 🧒 kids mode: every app asks a code, Authenticator, a parent over Telegram, a total of minutes a day (Rules "kids" / "totp" / "total"; the release after 0.21.0)
        "homekey" to "0.26.3",                                         // 🏠 Home to the launcher picked on a Chromecast (HomeKey; the release after 0.26.2)
        "keytest" to "0.30.1",                                         // ⌨️ the remote key tester (KeyTestActivity; the release after 0.30.0)
        "support" to "0.33.2",                                         // 🛟 let support look at the TV, opened from a phone, a computer or the web page (RulesReceiver "support"; the release after 0.33.1)
        "scenepin" to "0.34.3",                                        // 📌 a scene as a tile on TV Home (ScenePin, RulesReceiver "scenepin"; the release after 0.34.2)
        "mic_offline" to "0.38.1",                                     // 🎙️ speech heard on the TV without Google (TV Setup Voice, Rules "mic_offline"; the release after 0.38.0)
        "mic_freespeech" to "0.44.0",                                  // 🎙️ TV Setup Voice also decodes free speech (Rules "mic_freespeech"; the release after 0.43.0)
        "mic_engine" to "0.39.1",                                      // 🎙️ TV Setup Voice listening first, over Google's (Rules "mic_engine"; the release after 0.39.0)
        "family" to "0.38.5",                                          // 🛡️ Family mode's record kept on the TV (Rules "family"; the release after 0.38.4)
        "video" to "0.42.0",                                           // 🎬 a video or a camera in a box over the screen (VideoLayer; the release after 0.41.0)
        "speak" to "0.42.3",                                           // 🔊 a message read aloud (NotifyReceiver "speak"; the release after 0.42.2)
        "voicekey" to "0.46.1",                                        // 🎙️ a remote key that opens the voice command (Target "do:voice", KeyFix.VOICE; the release after 0.46.0)
        "idleoff" to "0.46.1",                                         // 📺 standby after hours with no key, "Still watching?" first (Rules "idle", IdleOff; the release after 0.46.0)
        "homebar" to "0.42.3",                                         // 🏠 the bar over an app on Home (HomeBar; the release after 0.42.2)
        "scenetime" to "0.45.2",                                       // 🕗 a scene that starts at a time or date by itself (SceneTimer, RulesReceiver "scenes" answers timed; the release after 0.45.1)
        "sceneon" to "0.46.1",                                         // 🎯 a scene that starts when an app opens or the TV turns on (SceneTimer.onOpen / onPower, RulesReceiver "scenes" answers events; the release after 0.46.0)
        "home_usage" to "0.14.1",                                      // 📊 TV Home's card of app use and limits (HomeInfo)
    )

    /** "setup:old:pic:2.21.0:2.20.0" or "setup:missing:draw:2.18.0:": the words every client looks for in an error. */
    class Missing(val feature: String, val have: String) :
        IOException("setup:" + (if (have.isEmpty()) "missing" else "old") + ":" + feature + ":" + (MIN[feature] ?: "") + ":" + have)

    /** The feature's name in a sentence; the same words as SETUP_NEEDS in js/core.js. */

    fun name(feature: String, lang: String): String {
        fun t(id: String) = Words.get(lang, id)
        return when (feature) {
            "rules" -> t("core.need_rules")
            "draw" -> t("setup_need.drawing_tv")
            "pic" -> t("core.need_pic")
            "volume" -> t("core.need_volume")
            "bedtimes" -> t("core.need_bedtimes")
            "notify" -> t("core.need_notify")
            "invite" -> t("core.need_invite")
            "ram" -> t("core.need_ram")
            "ramauto" -> t("core.need_ramauto")
            "idleoff" -> t("core.need_idleoff")
            "awake" -> t("core.need_awake")
            "files" -> t("core.need_files")
            "welcome" -> t("core.need_welcome")
            "launcher" -> t("core.need_launcher")
            "lock" -> t("core.need_lock")
            "schedule" -> t("core.need_schedule")
            "schedule_windows" -> t("core.need_schedule_windows")
            "dim" -> t("core.need_dim")
            "unextra" -> t("core.need_unextra")
            "home_usage" -> t("core.need_home_usage")
            "holdkeys" -> t("core.need_holdkeys")
            "mic" -> t("core.need_mic")
            "checkout" -> t("core.need_checkout")
            "telegram" -> t("core.need_telegram")
            "kids" -> t("kids.need_name")
            "homekey" -> t("core.need_homekey")
            "keytest" -> t("core.need_keytest")
            "support" -> t("core.need_support")
            "scenepin" -> t("core.need_scenepin")
            "mic_offline" -> t("core.need_mic_offline")
            "mic_freespeech" -> t("core.need_mic_freespeech")
            "mic_engine" -> t("core.need_mic_engine")
            "family" -> t("core.need_family")
            "video" -> t("core.need_video")
            "speak" -> t("core.need_speak")
            "voicekey" -> t("core.need_voicekey")
            "homebar" -> t("core.need_homebar")
            "scenetime" -> t("core.need_scenetime")
            "sceneon" -> t("core.need_sceneon")
            else -> feature
        }
    }

    /** What to say about [e] when it is a [Missing] (or its words came back from a server), else null. */

    fun say(e: Throwable, lang: String): String? {
        val m = Regex("setup:(old|missing):(\\w+):([0-9.]*):(\\S*)").find(generateSequence(e) { it.cause }.joinToString(" ") { it.message ?: "" }) ?: return null
        val (kind, f, min, have) = m.destructured
        val feature = name(f, lang)
        fun t(id: String, vararg a: Pair<String, Any?>) = Words.get(lang, id, *a)
        return if (kind == "missing") t("setup_need.needs_tv_setup_app", "feature" to feature)
        else t("setup_need.needs_tv_setup_newer", "feature" to feature, "min" to min, "have" to have)
    }

    fun ok(have: String, feature: String): Boolean {
        val min = MIN[feature] ?: return true
        return have.isNotEmpty() && Release.compare(have, min) >= 0
    }

    @Throws(IOException::class)
    fun check(adb: Adb, feature: String) {
        val have = SetupUpdate.installed(adb)
        if (!ok(have, feature)) throw Missing(feature, have)
    }

    /** The same from a shell's answer (dumpsys), for callers holding a shell rather than an [Adb]. */
    @Throws(IOException::class)
    fun have(sh: (String) -> String): String {
        fun read() = Regex("versionName=(\\S+)").find(sh("dumpsys package ${RulesClient.SETUP} 2>/dev/null | grep -m1 versionName="))?.groupValues?.get(1) ?: ""
        read().let { if (it.isNotEmpty()) return it }
        // an empty dumpsys is "not installed" only when pm path says so; a stalled system_server also answers nothing, and that must
        // never turn into "Install TV Setup": ask once more after 2 s, then say the TV did not answer
        if (!Pkg.installed(sh, RulesClient.SETUP)) return ""
        Pkg.pause(2000)
        return read().ifEmpty { throw IOException("TV Setup: the TV did not answer (no version)") }
    }
}
