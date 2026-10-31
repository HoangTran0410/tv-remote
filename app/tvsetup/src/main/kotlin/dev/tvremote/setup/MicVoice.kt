package dev.tvremote.setup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import dev.tvremote.core.T
import dev.tvremote.core.ontv.Channels
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.scenes.Voice
import dev.tvremote.setup.rules.Rules
import org.json.JSONArray
import org.json.JSONObject

/**
 * The remote's mic button opens TV Remote's own voice on the TV ([MicActivity]) instead of the Assistant: off by default, the rule "mic"
 * in [Rules], so every client switches it the same way. [KeysBridge] hands the key here only while it is on and the TV has a speech
 * recognizer; otherwise the key passes untouched (the filter is the add-on's, `app/tvkeys`). What is heard is carried out through
 * [Voice.parse], the same sentences as the phone's and the page's.
 */
object MicVoice {
    /** SEARCH, ASSIST and VOICE_ASSIST: what a TV remote's mic button sends (an FPT Play box: ASSIST, by its virtual-remote.kl). In KeysProtocol.INTEREST. */
    val KEYS: Set<Int> = setOf(84, 219, 231)

    /** Held at least this long, the release of the key ends the listening (hold to talk); a shorter press listens until the person stops. */
    const val HOLD_MS = 500L

    const val PERM = "android.permission.RECORD_AUDIO"

    fun on(c: Context): Boolean = try { Rules.load(c).optBoolean("mic") } catch (e: Exception) { false }

    fun granted(c: Context): Boolean = c.packageManager.checkPermission(PERM, c.packageName) == PackageManager.PERMISSION_GRANTED

    /** Is there a recognizer to ask? Read when the rules are (never in the key path). */
    fun available(c: Context): Boolean = try { SpeechRecognizer.isRecognitionAvailable(c) } catch (e: Exception) { false }

    /** The recognizer failed for a reason that will repeat (no network, no permission): until then the key goes to the system, as before. */
    @Volatile private var brokenUntil = 0L
    fun broke() { brokenUntil = System.currentTimeMillis() + 10 * 60_000L }
    fun fixed() { brokenUntil = 0L }
    fun broken(now: Long = System.currentTimeMillis()): Boolean = now < brokenUntil

    /** What the filter does with one event of a mic key. */
    enum class Step {
        PASS,      // not ours: the system gets it
        START,     // a new press and nothing listening: open the voice screen
        STOP,      // a new press while listening: that is enough, work with what was heard
        RELEASE,   // up after a hold: the person let go, same as STOP
        EAT        // swallowed (up of a short press, repeats)
    }

    /** [ready]: the rule is on, a recognizer exists and it did not just fail. [listening]: the voice screen is up. [action] 0 down, 1 up. */
    fun step(ready: Boolean, listening: Boolean, code: Int, action: Int, repeat: Int, downTime: Long, eventTime: Long): Step {
        if (code !in KEYS || !ready) return Step.PASS
        if (action == 0) return if (repeat > 0) Step.EAT else if (listening) Step.STOP else Step.START
        return if (listening && eventTime - downTime >= HOLD_MS) Step.RELEASE else Step.EAT
    }

    /** The one-time notice that this TV has no recognizer; the key is passed on. */
    fun saidNoRecognizer(c: Context): Boolean = c.getSharedPreferences("mic", Context.MODE_PRIVATE).getBoolean("said_none", false)
    fun sayNoRecognizer(c: Context) { c.getSharedPreferences("mic", Context.MODE_PRIVATE).edit().putBoolean("said_none", true).apply() }

    /** Allowed through ADB when the rule goes on (a TV can also ask on its screen: [MicActivity]). */
    fun grant(c: Context, adb: dev.tvremote.core.adb.Adb) {
        if (!granted(c)) try { adb.shell("pm grant ${c.packageName} $PERM 2>&1") } catch (e: Exception) { }
    }

    private var names: Map<String, String> = emptyMap()
    private var namesAt = 0L

    /** package -> label of the apps with a screen of their own on the TV, kept 5 minutes (a package scan is slow on a weak box). */
    @Synchronized fun apps(c: Context): Map<String, String> {
        if (names.isNotEmpty() && System.currentTimeMillis() - namesAt < 300_000) return names
        val pm = c.packageManager
        val out = HashMap<String, String>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) try {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = ri.activityInfo.packageName
                if (p !in out) out[p] = ri.loadLabel(pm).toString()
            }
        } catch (e: Exception) { }
        names = out; namesAt = System.currentTimeMillis()
        return out
    }

    fun scenes(c: Context): Map<String, String> = try {
        val a = JSONArray(c.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("scenes", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associate { it.optString("id") to it.optString("name") }
    } catch (e: Exception) { emptyMap() }

    /** One line saying what [cmd] does, shown while it is heard and when it is done. */
    fun describe(cmd: Voice.Command, c: Context): String = when (cmd) {
        is Voice.Command.Key -> T("auto.pressed", "key" to cmd.key.lowercase().replace('_', ' ')) + if (cmd.times > 1) " × ${cmd.times}" else ""
        is Voice.Command.App -> T("automation.opening", "name" to cmd.name)
        is Voice.Command.Channel -> T("automation.channel", "name" to cmd.name)
        is Voice.Command.Close -> T("mic.do_close", "name" to cmd.name)
        is Voice.Command.Timer -> if (cmd.minutes <= 0) T("mic.do_timer_off") else T("mic.do_timer", "n" to cmd.minutes)
        is Voice.Command.Scene -> T("mic.do_scene", "name" to (scenes(c)[cmd.id] ?: cmd.id))
    }

    /** Only keys and scenes are safe to act on before the sentence ends: "mở you" is not "mở YouTube". */
    fun earlyOk(cmd: Voice.Command?): Boolean = cmd is Voice.Command.Key || cmd is Voice.Command.Scene

    /** Carries [cmd] out (off the main thread). Null: done; else the words of what could not be done. */
    fun run(c: Context, cmd: Voice.Command): String? {
        val sh: (String) -> String = { RemoteService.shell(it) ?: throw IllegalStateException("adb") }
        try {
            when (cmd) {
                is Voice.Command.App -> {
                    val i = c.packageManager.getLeanbackLaunchIntentForPackage(cmd.pkg) ?: c.packageManager.getLaunchIntentForPackage(cmd.pkg)
                    if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else Scenes.launch(sh, cmd.pkg)
                }
                is Voice.Command.Close -> {
                    if (dev.tvremote.core.ontv.Setup.mustKeep(cmd.pkg) || cmd.pkg == c.packageName) return T("mic.failed")   // the TV needs it
                    sh("am force-stop " + dev.tvremote.core.adb.Adb.q(cmd.pkg))
                }
                is Voice.Command.Channel -> {
                    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Voice.channelLink(cmd.name))).setPackage("dev.tvremote.channels").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { c.startActivity(i) } catch (e: Exception) { return T("core.fr_channels_missing") }
                }
                is Voice.Command.Timer -> Rules.change(c, JSONObject().put("do", "sleep").put("minutes", cmd.minutes))
                is Voice.Command.Scene -> {
                    val a = JSONArray(c.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("scenes", "[]"))
                    val steps = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("id") == cmd.id }?.optJSONArray("steps") ?: return null
                    Scenes.run(steps, Scenes.Shell(sh) { m -> Rules.change(c, JSONObject().put("do", "sleep").put("minutes", m)) })?.let { return it }
                }
                is Voice.Command.Key -> {
                    val code = Scenes.KEYS[cmd.key] ?: return null
                    sh((1..cmd.times).joinToString("; ") { "input keyevent $code" })
                }
            }
        } catch (e: IllegalStateException) {
            // no ADB link: the volume keys still have a way of their own
            if (cmd is Voice.Command.Key && cmd.key.startsWith("VOL_")) {
                val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                repeat(cmd.times) { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (cmd.key == "VOL_UP") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI) }
                return null
            }
            return T("mic.needs_adb")
        } catch (e: Exception) { return e.message ?: T("mic.failed") }
        return null
    }
}
