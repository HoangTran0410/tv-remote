package dev.tvremote.setup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import dev.tvremote.core.T
import dev.tvremote.core.ontv.Channels
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.scenes.Voice
import dev.tvremote.setup.rules.Rules
import dev.tvremote.setup.voice.VoiceProtocol
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

    /** Is there a recognizer to ask, the TV's or TV Setup Voice? Read when the rules are (never in the key path). */
    fun available(c: Context): Boolean = own(c) || offline(c) != null

    /**
     * The TV's own recognizer (Google's on a Google TV): the one Settings names, installed and on. Not "any RecognitionService": TV Setup
     * Voice is one too, and a TV whose Google app was switched off still names it.
     */
    fun own(c: Context): Boolean = try {
        val cn = android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(c.contentResolver, "voice_recognition_service").orEmpty())
        cn != null && cn.packageName != VoiceProtocol.ADDON && c.packageManager.getApplicationInfo(cn.packageName, 0).enabled && c.packageManager.getServiceInfo(cn, 0).enabled
    } catch (e: Exception) { false }

    /** TV Setup Voice, when on the TV: speech heard offline (app/tvvoice). */
    fun offline(c: Context): android.content.ComponentName? = try {
        c.packageManager.getApplicationInfo(VoiceProtocol.ADDON, 0).takeIf { it.enabled }?.let { android.content.ComponentName(VoiceProtocol.ADDON, VoiceProtocol.SERVICE) }
    } catch (e: Exception) { null }

    /** The rule "mic_offline" on: TV Setup Voice installed (from the release, over our own ADB). */
    fun offlineOn(c: Context): Boolean = try { Rules.load(c).optBoolean("mic_offline") } catch (e: Exception) { false }

    @Volatile private var offlineJob = ""                              // "busy", or the last failure's words for the page ("" when none)

    /** For the page: "ready" (on the TV), "busy" (being installed), "failed:<why>", or "none". */
    fun offlineState(c: Context): String = when {
        offlineJob == "busy" -> "busy"
        offline(c) != null -> "ready"
        offlineJob.isNotEmpty() -> "failed:$offlineJob"
        else -> "none"
    }

    /** TV Setup Voice made to match the rule, in the background: installed when on and missing, removed when off (nothing of it stays). */
    fun syncOffline(c: Context) {
        if (offlineJob == "busy") return
        val want = offlineOn(c)
        if (want == (offline(c) != null)) return
        val adb = dev.tvremote.setup.RemoteService.adb() ?: run { offlineJob = if (want) "adb" else ""; return }
        offlineJob = "busy"
        Thread({
            offlineJob = try {
                if (want) dev.tvremote.core.ontv.VoiceAddon.install(adb, c.cacheDir, dev.tvremote.core.Words.lang) else dev.tvremote.core.ontv.VoiceAddon.remove(adb)
                if (want && offline(c) == null) "install" else ""
            } catch (e: Exception) { (e.message ?: "install").take(200) }
        }, "voice-addon").apply { isDaemon = true }.start()
    }

    /** What TV Setup Voice listens for: the commands as said in Vietnamese (its only model), this TV's apps and scenes ([Phrases.spoken]). */
    fun words(c: Context): ArrayList<String> {
        val more = apps(c).values + scenes(c).values
        val key = (Voice.phrases?.hashCode() ?: 0) * 31 + more.hashCode()
        spoken?.let { (k, w) -> if (k == key) return w }
        val t0 = System.currentTimeMillis()
        val w = ArrayList(Voice.phrases?.spoken("vi", more).orEmpty())
        android.util.Log.i("MicVoice", "words: ${w.size} in ${System.currentTimeMillis() - t0} ms")
        spoken = key to w
        return w
    }
    /** The last list, by what it was made from: ~2 s on a TV box (FPT), so made once, off the main thread (MicActivity). */
    @Volatile private var spoken: Pair<Int, ArrayList<String>>? = null

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

    private const val ASSIST = "dev.tvremote.setup/dev.tvremote.setup.MicAssist"
    private const val VIS = "voice_interaction_service"                // Settings.Secure.VOICE_INTERACTION_SERVICE and ASSISTANT are hidden names
    private const val ASSISTANT = "assistant"

    /**
     * The TV's assistant is MicActivity while the rule is on, Google's again when it goes off. A Google TV's window manager hands the
     * mic button (SEARCH on an FPT box) to SystemUI's assistant before any key filter sees it, so [KeysBridge] alone never gets it; SystemUI
     * starts the voice interaction service when one is set, else the activity of "assistant". What was there is kept and put back.
     * Needs WRITE_SECURE_SETTINGS (granted through ADB, like KeyFix); the recognizer itself (voice_recognition_service) is not touched.
     */
    fun takeAssist(c: Context) {
        if (!dev.tvremote.setup.keys.KeyFix.can(c)) return
        val r = c.contentResolver
        val p = c.getSharedPreferences("mic", Context.MODE_PRIVATE)
        try {
            val cur = android.provider.Settings.Secure.getString(r, ASSISTANT)
            if (on(c)) {
                if (cur != ASSIST) {
                    p.edit().putString("was_assistant", cur ?: "").putString("was_vis", android.provider.Settings.Secure.getString(r, VIS) ?: "").putBoolean("took", true).apply()
                    android.provider.Settings.Secure.putString(r, ASSISTANT, ASSIST)
                    android.provider.Settings.Secure.putString(r, VIS, "")
                }
            } else if (p.getBoolean("took", false)) {
                if (cur == ASSIST) {                                  // else the person picked another assistant since: theirs stays
                    android.provider.Settings.Secure.putString(r, ASSISTANT, p.getString("was_assistant", "")!!.ifEmpty { null })
                    android.provider.Settings.Secure.putString(r, VIS, p.getString("was_vis", "")!!.ifEmpty { null })
                }
                p.edit().remove("took").remove("was_assistant").remove("was_vis").apply()
            }
        } catch (e: Exception) { android.util.Log.w("MicVoice", "assistant unchanged: $e") }
        watchAssist(c)
    }

    /** Google's assistant (or none) coming back is taken back; any other is one the person picked. */
    fun takesBack(cur: String?, was: String?): Boolean {
        val pkg = cur?.substringBefore('/').orEmpty()
        return pkg.isEmpty() || pkg == was?.substringBefore('/') || pkg in GOOGLE_ASSIST
    }
    private val GOOGLE_ASSIST = setOf("com.google.android.katniss", "com.google.android.googlequicksearchbox", "com.google.android.apps.tv.launcherx")

    @Volatile private var watch: android.database.ContentObserver? = null

    /**
     * While the rule is on, the setting is watched: Android gives the assistant back to Google by itself when its app is switched on
     * again or updated (the role is granted anew), and the mic button went to Google until the next ADB link. Taken back only when it is
     * the one we keep to put back (Google's coming back); another assistant the person picked stays theirs. Off: no observer.
     */
    private fun watchAssist(c: Context) {
        val app = c.applicationContext
        val r = app.contentResolver
        val want = on(app) && app.getSharedPreferences("mic", Context.MODE_PRIVATE).getBoolean("took", false)
        synchronized(this) {
            if (want == (watch != null)) return
            if (!want) { watch?.let { r.unregisterContentObserver(it) }; watch = null; return }
            watch = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(self: Boolean) {
                    val cur = android.provider.Settings.Secure.getString(r, ASSISTANT)
                    val was = app.getSharedPreferences("mic", Context.MODE_PRIVATE).getString("was_assistant", "")
                    if (cur != ASSIST && on(app) && takesBack(cur, was)) {
                        android.util.Log.i("MicVoice", "assistant taken back from $cur")
                        Thread({ try {
                            android.provider.Settings.Secure.putString(r, ASSISTANT, ASSIST)
                            android.provider.Settings.Secure.putString(r, VIS, "")
                        } catch (e: Exception) { android.util.Log.w("MicVoice", "assistant unchanged: $e") } }, "mic-assist").start()
                    }
                }
            }.also { r.registerContentObserver(android.provider.Settings.Secure.getUriFor(ASSISTANT), false, it) }
        }
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
    @Volatile private var scanning = false

    /**
     * package -> label of the apps with a screen of their own on the TV. A scan reads every app's label and takes ~4 s on an FPT box, so
     * it is kept in a file and redone in the background (every 5 minutes at most): the voice screen never waits for it, except the very
     * first time on a TV (nothing kept yet).
     */
    fun apps(c: Context): Map<String, String> {
        val fresh = System.currentTimeMillis() - namesAt < 300_000
        if (names.isNotEmpty() && fresh) return names
        if (names.isEmpty()) synchronized(this) { if (names.isEmpty()) names = load(c) }
        if (names.isEmpty()) return scan(c)                           // the first time: nothing else to go by
        if (!fresh && !scanning) { scanning = true; Thread({ try { scan(c) } finally { scanning = false } }, "mic-apps").apply { isDaemon = true }.start() }
        return names
    }

    /** What the voice screen reads, made ready (off the main thread): the sentences' list and the app names. Again: nothing to do. */
    fun warm(c: Context) {
        dev.tvremote.core.scenes.VoicePhrases.start({ c.assets.open(it) }, c.filesDir)
        apps(c)
        pins(c)
    }

    @Volatile private var pinList: List<Voice.PinRef> = emptyList()
    @Volatile private var pinsAt = 0L
    @Volatile private var pinsAsk: java.util.concurrent.FutureTask<Unit>? = null
    /**
     * TV Home's pinned tiles, said by name like apps. Asked again on every mic press (ADB asks TV Home, ~0.4 s, in a thread of its
     * own while the person speaks; at most every 15 s): a tile pinned a minute ago was not known for 5 minutes and "mở đàn ABC"
     * went to a YouTube search (the user, 2026-11-01). [wait]: the sentence is in, so wait for that answer, at most 1.5 s (on the main thread: it started seconds ago).
     */
    fun pins(c: Context, wait: Boolean = false): List<Voice.PinRef> {
        synchronized(this) {
            if (System.currentTimeMillis() - pinsAt > 15_000 && pinsAsk?.isDone != false) {
                pinsAt = System.currentTimeMillis()
                pinsAsk = java.util.concurrent.FutureTask {
                    val t0 = System.currentTimeMillis()
                    try { pinList = dev.tvremote.core.ontv.Home.ownPins { RemoteService.shell(it) ?: throw IllegalStateException("adb") } }
                    catch (e: Exception) { android.util.Log.i("MicVoice", "pins: $e") }
                    android.util.Log.i("MicVoice", "pins: ${pinList.size} in ${System.currentTimeMillis() - t0} ms"); Unit
                }.also { Thread(it, "mic-pins").apply { isDaemon = true }.start() }
            }
        }
        if (wait) try { pinsAsk?.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { }
        return pinList
    }

    private fun file(c: Context) = java.io.File(c.filesDir, "mic-apps.json")

    private fun load(c: Context): Map<String, String> = try {
        val o = JSONObject(file(c).readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    @Synchronized private fun scan(c: Context): Map<String, String> {
        if (names.isNotEmpty() && System.currentTimeMillis() - namesAt < 300_000) return names   // another thread just did it
        val pm = c.packageManager
        val out = HashMap<String, String>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) try {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = ri.activityInfo.packageName
                if (p !in out) out[p] = ri.loadLabel(pm).toString()
            }
        } catch (e: Exception) { }
        names = out; namesAt = System.currentTimeMillis()
        try { file(c).writeText(JSONObject(out as Map<*, *>).toString()) } catch (e: Exception) { }
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
        is Voice.Command.Search -> T("talk.search_in", "q" to cmd.query, "name" to cmd.name)
        is Voice.Command.Close -> T("mic.do_close", "name" to cmd.name)
        is Voice.Command.Timer -> if (cmd.minutes <= 0) T("mic.do_timer_off") else T("mic.do_timer", "n" to cmd.minutes)
        is Voice.Command.Scene -> T("mic.do_scene", "name" to (scenes(c)[cmd.id] ?: cmd.id))
        is Voice.Command.Pin -> T("automation.opening", "name" to cmd.name)
        is Voice.Command.Setting -> T(Voice.say(cmd).id)
        is Voice.Command.Choice -> T("talk.pick_one", "names" to Voice.names(cmd))
    }

    /** Keys, scenes and an app named in full are safe to act on before the sentence ends: "mở you" is not "mở YouTube". */
    fun earlyOk(cmd: Voice.Command?, text: String): Boolean = cmd is Voice.Command.Key || cmd is Voice.Command.Scene || Voice.whole(text, cmd)

    /**
     * A sentence that is no command, looked up instead of a dead end: the same order as [dev.tvremote.core.adb.TvShell.search] (YouTube's
     * results, else the TV's own search), started from the voice screen in front, so no ADB is needed. The words for what took it, null when nothing did.
     */
    fun search(c: Context, text: String): String? {
        val yt = youtubeApp(c)
        val has = yt != null
        val i = if (yt != null) Intent(Intent.ACTION_VIEW, android.net.Uri.parse(dev.tvremote.core.adb.TvShell.youtube(text))).setPackage(yt)
            else Intent("android.search.action.GLOBAL_SEARCH").putExtra(android.app.SearchManager.QUERY, text)
        return try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); if (has) T("remote.searching_youtube", "q" to text) else T("talk.ask", "sentence" to text) } catch (e: Exception) { null }
    }

    /** The app that opens YouTube's links and not every link (the same test as [dev.tvremote.core.adb.TvShell.search]), null: none. */
    private fun youtubeApp(c: Context): String? {
        val pm = c.packageManager
        fun who(url: String) = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE), 0).map { it.activityInfo.packageName }
        } catch (e: Exception) { emptyList() }
        val any = who(dev.tvremote.core.adb.TvShell.NOT_YOUTUBE).toSet()
        return who(dev.tvremote.core.adb.TvShell.youtube("x")).firstOrNull { it !in any }
    }

    /**
     * Volume and mute straight to the audio service, as the remote's own keys reach it (adjustSuggestedStreamVolume, the TV's volume bar,
     * and HDMI-CEC to a sound bar where the TV sends it): at once, no ADB, where `input keyevent` took ~1 s a press.
     */
    private fun volume(c: Context, cmd: Voice.Command.Key): Boolean {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val dir = when (cmd.key) {
            "VOL_UP" -> android.media.AudioManager.ADJUST_RAISE
            "VOL_DOWN" -> android.media.AudioManager.ADJUST_LOWER
            "MUTE" -> if (Build.VERSION.SDK_INT >= 23) android.media.AudioManager.ADJUST_TOGGLE_MUTE else return false
            else -> return false
        }
        repeat(cmd.times) { am.adjustSuggestedStreamVolume(dir, android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI) }
        return true
    }

    /**
     * "mở vtv3 trên fpt play": the order of [dev.tvremote.core.adb.TvShell.searchIn], started from the voice screen in front (no ADB): the list's
     * web search aimed at the app (any app when it is not installed), the app's own SEARCH screen, else the app alone and the words saying so.
     * Nothing is pressed on the results. Null: done.
     */
    private fun searchIn(c: Context, cmd: Voice.Command.Search): String? {
        val pm = c.packageManager
        val installed = try { pm.getLaunchIntentForPackage(cmd.pkg) != null || pm.getLeanbackLaunchIntentForPackage(cmd.pkg) != null } catch (e: Exception) { false }
        fun go(i: Intent) = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
        if (cmd.url != null) {
            val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(cmd.url)).addCategory(Intent.CATEGORY_BROWSABLE)
            // not there or off (an FPT box's Google YouTube): the app taking this link and not every link (TvShell.NOT_YOUTUBE), as TvShell.searchIn
            val any = try { pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(dev.tvremote.core.adb.TvShell.NOT_YOUTUBE)).addCategory(Intent.CATEGORY_BROWSABLE), 0).map { it.activityInfo.packageName }.toSet() } catch (e: Exception) { emptySet() }
            val own = if (installed) cmd.pkg else try { pm.queryIntentActivities(view, 0).map { it.activityInfo.packageName }.firstOrNull { it !in any } } catch (e: Exception) { null }
            if (go(if (own != null) view.setPackage(own) else view)) return null
        }
        val search = Intent(Intent.ACTION_SEARCH).setPackage(cmd.pkg).putExtra(android.app.SearchManager.QUERY, cmd.query)
        if (installed && pm.queryIntentActivities(search, 0).isNotEmpty() && go(search)) return null
        val open = pm.getLeanbackLaunchIntentForPackage(cmd.pkg) ?: pm.getLaunchIntentForPackage(cmd.pkg) ?: return T("auto.search_in_none", "name" to cmd.name)
        go(open)
        return T("auto.search_in_none", "name" to cmd.name)
    }

    /** Carries [cmd] out (off the main thread). Null: done; else the words of what could not be done. */
    fun run(c: Context, cmd: Voice.Command): String? {
        val sh: (String) -> String = { RemoteService.shell(it) ?: throw IllegalStateException("adb") }
        try {
            when (cmd) {
                is Voice.Command.App -> {
                    val i = c.packageManager.getLeanbackLaunchIntentForPackage(cmd.pkg) ?: c.packageManager.getLaunchIntentForPackage(cmd.pkg)
                    if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else Scenes.launch(sh, cmd.pkg)
                }
                is Voice.Command.Search -> return searchIn(c, cmd)
                is Voice.Command.Choice -> return T("talk.pick_one", "names" to Voice.names(cmd))   // the TV screen has nothing to pick with: the person says one name in full
                is Voice.Command.Setting -> {                              // the first screen this TV has, else its settings (no ADB: a screen of our own app asks)
                    val ok = (cmd.actions + android.provider.Settings.ACTION_SETTINGS).any { a ->
                        try { c.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
                    }
                    if (!ok) return T("mic.failed")
                }
                is Voice.Command.Pin -> {                                  // TV Home opens it as a press on the tile: a link pin's app is picked then (the one saved at pin time may be gone)
                    c.startActivity(Intent().setClassName("dev.tvremote.home", "dev.tvremote.home.OpenActivity").putExtra("t", cmd.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                    if (volume(c, cmd)) return null
                    val code = Scenes.KEYS[cmd.key] ?: return null
                    sh("input keyevent " + (1..cmd.times).joinToString(" ") { "$code" })   // one `input` for all: each start of it costs ~0.7 s on a TV box
                }
            }
        } catch (e: IllegalStateException) {
            return T("mic.needs_adb")
        } catch (e: Exception) { return e.message ?: T("mic.failed") }
        return null
    }
}
