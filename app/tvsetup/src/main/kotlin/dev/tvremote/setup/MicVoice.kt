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

    /**
     * Pure: does TV Setup Voice listen this time (else the TV's own)? [offline] it already does (a network fault moved there),
     * [tvFirst] it was picked over Google's, [own] the TV has a recognizer; [noModel] it could not load its model just now: then the
     * TV's own when there is one, never "no model" for 10 minutes while Google's works (dev/flows/voice.md row 8).
     */
    fun useAddon(offline: Boolean, tvFirst: Boolean, own: Boolean, noModel: Boolean): Boolean = if (noModel) !own else offline || tvFirst || !own

    /** Is there a recognizer to ask, the TV's or TV Setup Voice? Read when the rules are (never in the key path). */
    fun available(c: Context): Boolean = own(c) || offline(c) != null

    /**
     * The TV's own recognizer (Google's on a Google TV): the one Settings names, installed and on. Not "any RecognitionService": TV Setup
     * Voice is one too, and a TV whose Google app was switched off still names it.
     */
    fun own(c: Context): Boolean = try {
        val cn = android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(c.contentResolver, "voice_recognition_service").orEmpty())
        cn != null && !cn.packageName.startsWith(VoiceProtocol.ADDON) && c.packageManager.getApplicationInfo(cn.packageName, 0).enabled && c.packageManager.getServiceInfo(cn, 0).enabled
    } catch (e: Exception) { false }

    /** TV Setup Voice, when on the TV: speech heard offline (app/tvvoice). The language picked ([lang]) when it is there, else any other. */
    fun offline(c: Context): android.content.ComponentName? = installed(c).let { has ->
        (lang(c).takeIf { it in has } ?: has.firstOrNull())?.let { android.content.ComponentName(VoiceProtocol.addon(it), VoiceProtocol.SERVICE) }
    }

    /** The languages of TV Setup Voice on the TV (each its own package, VoiceProtocol.addon), installed and on. */
    fun installed(c: Context): List<String> = VoiceProtocol.LANGS.filter { l ->
        try { c.packageManager.getApplicationInfo(VoiceProtocol.addon(l), 0).enabled } catch (e: Exception) { false }
    }

    /** The language of the add-on that listens now ([offline]), null when none is on the TV: may differ from [lang] while that one installs. */
    fun active(c: Context): String? = offline(c)?.let { VoiceProtocol.langOf(c.packageManager, it.packageName) }

    /** The language TV Setup Voice listens in: the rule "mic_lang" (picked on its screen), else the one for TV Setup's language. */
    fun lang(c: Context): String = try { Rules.load(c).optString("mic_lang") } catch (e: Exception) { "" }
        .takeIf { it in VoiceProtocol.LANGS } ?: dev.tvremote.core.ontv.VoiceAddon.lang(dev.tvremote.core.Words.lang)

    /** The rule "mic_freespeech": TV Setup Voice also decodes free speech (searches, song names); off = commands only, today's speed. */
    fun freeSpeech(c: Context): Boolean = try { Rules.load(c).optBoolean("mic_freespeech") } catch (e: Exception) { false }

    /** The rule "mic_engine" is "tv": TV Setup Voice listens first, where it is on the TV, Google's only when it is not (the user, 2026-11-03: faster). */
    fun engineTv(c: Context): Boolean = try { Rules.load(c).optString("mic_engine") == "tv" } catch (e: Exception) { false }

    /** The mic button to TV Setup Voice: it on the TV (installed when missing), listening first, the button ours ([VoiceActivity], [HomeControl]). */
    fun useTv(c: Context) {
        Rules.change(c, org.json.JSONObject().put("do", "mic_offline").put("on", true))
        Rules.change(c, org.json.JSONObject().put("do", "mic_engine").put("engine", "tv"))
        Rules.change(c, org.json.JSONObject().put("do", "mic").put("on", true))
    }

    /** The rule "mic_offline" on: TV Setup Voice installed (from the release, over our own ADB). */
    fun offlineOn(c: Context): Boolean = try { Rules.load(c).optBoolean("mic_offline") } catch (e: Exception) { false }

    /** The add-on's download, 0-100 while it runs, else -1: VoiceActivity's bar (39 MB). */
    @Volatile var offlinePct = -1
    @Volatile private var offlineJob = ""                              // "busy", or the last failure's words for the page ("" when none)

    /** For the page: "ready" (on the TV), "busy" (being installed), "failed:<why>", or "none". */
    fun offlineState(c: Context): String = when {
        offlineJob == "busy" -> "busy"
        offlineJob.isNotEmpty() && lang(c) !in installed(c) -> "failed:$offlineJob"   // the language picked failed: said, even with another one on the TV
        offline(c) != null -> "ready"                                 // (FPT 2026-11-03: English not in the release, "ready" from Vietnamese hid it)
        offlineJob.isNotEmpty() -> "failed:$offlineJob"
        else -> "none"
    }

    /** Words for a "failed:<why>" state, for a person. */
    fun why(c: Context, state: String): String = when (val why = state.removePrefix("failed:")) {
        "adb" -> T("mic.needs_adb")
        "voice:none" -> T("tvvoice.lang_none", "lang" to (dev.tvremote.i18n.Langs.NAMES[lang(c)] ?: lang(c)))   // that language's APK not in the release
        else -> why }

    /** TV Setup Voice made to match the rules, in the background: the language picked installed when on and missing (the others stay:
     *  a language is added, never swapped, the user 2026-11-03), every language removed when off (nothing of it stays). */
    fun syncOffline(c: Context) {
        if (offlineJob == "busy") return
        val want = offlineOn(c)
        val has = installed(c)
        val lang = lang(c)
        if (if (want) lang in has else has.isEmpty()) return
        val adb = dev.tvremote.setup.RemoteService.adb() ?: run { offlineJob = if (want) "adb" else ""; return }
        offlineJob = "busy"
        Thread({
            offlineJob = try {
                if (want) try { dev.tvremote.core.ontv.VoiceAddon.install(adb, c.cacheDir, dev.tvremote.core.Words.lang, progress = { offlinePct = it }, voice = lang) } finally { offlinePct = -1 }
                else dev.tvremote.core.ontv.VoiceAddon.remove(adb)
                if (want && lang !in installed(c)) "install" else ""
            } catch (e: Exception) { (e.message ?: "install").take(200) }
        }, "voice-addon").apply { isDaemon = true }.start()
    }

    /** One language off the TV (its screen's Remove): the last one = the rule "mic_offline" off; the one in use = another one picked. */
    fun removeLang(c: Context, lang: String) {
        val rest = installed(c) - lang
        if (rest.isEmpty()) { Rules.change(c, org.json.JSONObject().put("do", "mic_engine").put("engine", "auto")); Rules.change(c, org.json.JSONObject().put("do", "mic_offline").put("on", false)); return }
        if (lang == lang(c)) Rules.change(c, org.json.JSONObject().put("do", "mic_lang").put("lang", rest.first()))
        val adb = dev.tvremote.setup.RemoteService.adb() ?: run { offlineJob = "adb"; return }
        Thread({ try { dev.tvremote.core.ontv.VoiceAddon.remove(adb, lang) } catch (e: Exception) { } }, "voice-remove").apply { isDaemon = true }.start()
    }

    /** What TV Setup Voice listens for: the commands as said in its model's language, this TV's apps and scenes ([Phrases.spoken]). */
    fun words(c: Context): ArrayList<String> {
        val more = apps(c).values + scenes(c).values + sayings(c).values.flatten()   // a scene's own phrases too: offline, only these words can be heard
        val lang = offline(c)?.let { VoiceProtocol.langOf(c.packageManager, it.packageName) } ?: "vi"   // the add-on that will listen
        val key = ((Voice.phrases?.hashCode() ?: 0) * 31 + more.hashCode()) * 31 + lang.hashCode()
        spoken?.let { (k, w) -> if (k == key) return w }
        val t0 = System.currentTimeMillis()
        val w = ArrayList(Voice.phrases?.spoken(lang, more).orEmpty())
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
        syncSearch(c)                                                 // first: this one needs no ADB
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

    /**
     * A TV with no ADB cannot set the assistant, but its mic key starts WEB_SEARCH (VOICE_ASSIST), and any app with that filter is offered
     * (Kiki does the same). The alias is off in the manifest and on only while the rule is on, so a TV that never uses the option
     * never lists TV Setup in a chooser; an app may switch its own components without any permission.
     */
    fun syncSearch(c: Context) {
        val want = if (on(c)) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            val cn = android.content.ComponentName(c.packageName, SEARCH_ALIAS)
            if (c.packageManager.getComponentEnabledSetting(cn) != want) c.packageManager.setComponentEnabledSetting(cn, want, android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (e: Exception) { android.util.Log.w("MicVoice", "search alias unchanged: $e") }
    }
    private const val SEARCH_ALIAS = "dev.tvremote.setup.MicSearch"

    /** Why the mic key cannot reach TV Setup: [reason] "" (it can, or the rule is off), [GOOGLE] or [OTHER]; [pkg] / [label] the app that holds it. */
    data class Block(val reason: String, val pkg: String = "", val label: String = "")

    /**
     * The decision, pure (tested). A BLE voice remote is driven by Google's TV remote service, which streams the audio to the Google app
     * itself while that is on: nothing reaches the assistant setting or WEB_SEARCH (Skyworth Android 14, 2026-11-05). Else the mic key
     * starts WEB_SEARCH when the assistant setting is not ours, and a default another app was given for it wins over our alias.
     * [searchApp]: the app WEB_SEARCH opens without asking ("" = none, or the system's chooser). [keySeen]: the mic key has started
     * .MicAssist / .MicSearch since the rule went on, so it works here (an FPT Play 650 has the same packages as the Skyworth): the
     * packages alone cannot tell them apart, so the Google case is a warning only until a press arrives.
     */
    fun blockReason(micOn: Boolean, googleOn: Boolean, bleRemote: Boolean, assistantOurs: Boolean, searchApp: String, self: String, keySeen: Boolean = false): String = when {
        !micOn -> ""
        googleOn && bleRemote && !keySeen -> GOOGLE
        !assistantOurs && searchApp.isNotEmpty() && searchApp != self && searchApp != "android" -> OTHER
        else -> ""
    }
    /** Written on the key's start of the mic screen only (no timer), forgotten when the rule goes on, so "seen" means since then. */
    fun noteKey(c: Context) { c.getSharedPreferences("mic", Context.MODE_PRIVATE).edit().putLong("key_at", System.currentTimeMillis()).apply() }
    fun keySeen(c: Context): Boolean = c.getSharedPreferences("mic", Context.MODE_PRIVATE).getLong("key_at", 0L) > 0L
    fun forgetKey(c: Context) { c.getSharedPreferences("mic", Context.MODE_PRIVATE).edit().remove("key_at").apply() }
    const val GOOGLE = "google_holds_mic"
    const val OTHER = "other_app_holds_mic"
    private const val GOOGLE_APP = "com.google.android.katniss"
    private const val BLE_REMOTE = "com.google.android.tv.remote.service"

    /**
     * Reads [blockReason]'s facts from package manager only (no shell, no dumpsys: a few ms, measured on the emulator), and nothing at all
     * while the rule is off. Package visibility is not a problem here: TV Setup holds QUERY_ALL_PACKAGES.
     */
    fun block(c: Context): Block {
        if (!on(c)) return Block("")
        val pm = c.packageManager
        fun enabled(p: String) = try { pm.getApplicationInfo(p, 0).enabled } catch (e: Exception) { false }
        fun there(p: String) = try { pm.getApplicationInfo(p, 0); true } catch (e: Exception) { false }
        val search = try { pm.resolveActivity(Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName.orEmpty() } catch (e: Exception) { "" }
        val ours = try { android.provider.Settings.Secure.getString(c.contentResolver, ASSISTANT) == ASSIST } catch (e: Exception) { false }
        val why = blockReason(true, enabled(GOOGLE_APP), there(BLE_REMOTE), ours, search, c.packageName, keySeen(c))
        val pkg = if (why == GOOGLE) GOOGLE_APP else if (why == OTHER) search else ""
        val label = if (pkg.isEmpty()) "" else try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        return Block(why, pkg, label)
    }

    /**
     * The fix for [block]. With ADB and the Google app holding it: the app is turned off like Optimize TV does (kept in the Undo list
     * before pm is asked, so Undo turns it back on). Else the app's settings page on the TV (best effort from a service: Android 14 may
     * refuse a start from the background, so TV Setup's own screen offers it too). Returns "off", "opened" or "".
     */
    fun free(c: Context, adb: dev.tvremote.core.adb.Adb?): String {
        val b = block(c)
        if (b.reason.isEmpty()) return ""
        if (adb != null && b.reason == GOOGLE) try {
            adb.shell("grep -qx '${b.pkg}' ${dev.tvremote.core.ontv.Setup.OFF_LIST} 2>/dev/null || echo '${b.pkg}' >> ${dev.tvremote.core.ontv.Setup.OFF_LIST}")
            adb.shell(dev.tvremote.core.ontv.TvUser.disable(b.pkg))
            adb.shell("am force-stop ${b.pkg}")
            return "off"
        } catch (e: Exception) { android.util.Log.w("MicVoice", "free: $e") }
        return try {
            c.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${b.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "opened"
        } catch (e: Exception) { "" }
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

    /** Each scene's extra phrases ("say"), by scene id: what the person typed under "Câu gọi" on the scene. */
    fun sayings(c: Context): Map<String, List<String>> = try {
        Scenes.sayings(JSONArray(c.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("scenes", "[]")))
    } catch (e: Exception) { emptyMap() }

    /** The apps picked for voice (rules "voice_search", "voice_youtube"; "" = today's behaviour). */
    fun prefs(c: Context): Voice.Prefs = try {
        Rules.load(c).let { Voice.Prefs(it.optString("voice_search"), it.optString("voice_youtube")) }
    } catch (e: Exception) { Voice.Prefs() }

    /** YouTube clients on this TV, for the pickers (state "voice_tubes"): [{pkg, name, query, main}]; cheap, a handful of package lookups. */
    fun tubes(c: Context): JSONArray {
        dev.tvremote.core.scenes.VoicePhrases.start({ c.assets.open(it) }, c.filesDir)
        val pm = c.packageManager
        val have = HashMap<String, String>()
        for (a in Voice.phrases?.apps.orEmpty()) for (p in a.pkgs) if (a.tube != null) try {
            if (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null) have[p] = pm.getApplicationInfo(p, 0).loadLabel(pm).toString()
        } catch (e: Exception) { }
        val out = JSONArray()
        for (t in Voice.tubes(have)) out.put(JSONObject().put("pkg", t.pkg).put("name", t.name).put("query", t.query).put("main", t.main))
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
